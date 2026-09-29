package com.shai.riven.data.archive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.shai.riven.data.attachment.AttachmentStorageKey
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.model.AttachmentState
import java.io.File
import kotlinx.coroutines.CancellationException

internal data class RivenRestoreBootstrapHooks(
    val beforeDatabaseInstall: () -> Unit = {},
    val beforeAttachmentInstall: () -> Unit = {},
    val beforePostInstallVerification: () -> Unit = {},
)

/**
 * Deterministic recovery policy:
 * - STAGED applies only while current state is untouched; partial moves converge by rolling back.
 * - CURRENT_MOVED_ASIDE, NEW_INSTALLED, and VERIFIED keep a fully verifiable installed state.
 * - Any unverifiable installed state rolls back when a known-good database exists.
 * - Missing journal/known-good material is fatal and leaves the restore gate in place.
 */
class RivenRestoreBootstrap(
    context: Context,
    restoreRoot: File = File(
        context.applicationContext.noBackupFilesDir,
        RivenRestorePaths.RESTORE_DIRECTORY,
    ),
) {
    private val appContext = context.applicationContext
    private val paths = RivenRestorePaths(appContext, restoreRoot)
    private val journal = RivenRestoreJournal(paths.journalFile)
    internal var hooks: RivenRestoreBootstrapHooks = RivenRestoreBootstrapHooks()

    fun recoverAndApply(): RivenRestoreBootstrapResult {
        if (!journal.exists()) {
            return if (paths.rollbackRoot.exists()) {
                RivenRestoreBootstrapResult.Failure(
                    RivenArchiveRestoreError.RecoveryFailure("MISSING_JOURNAL"),
                )
            } else {
                RivenRestoreBootstrapResult.NoPendingRestore
            }
        }
        val record = journal.read()
            ?: return RivenRestoreBootstrapResult.Failure(
                RivenArchiveRestoreError.RecoveryFailure("MALFORMED_JOURNAL"),
            )
        return try {
            when (record.stage) {
                RivenRestoreJournalStage.STAGED -> recoverStaged(record)
                RivenRestoreJournalStage.CURRENT_MOVED_ASIDE,
                RivenRestoreJournalStage.NEW_INSTALLED,
                RivenRestoreJournalStage.VERIFIED,
                -> recoverInstalledOrRollback(record)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RivenRestoreBootstrapResult.Failure(
                RivenArchiveRestoreError.RecoveryFailure(failure.safeCauseType()),
            )
        }
    }

    private fun recoverStaged(record: RivenRestoreJournalRecord): RivenRestoreBootstrapResult {
        if (rollbackMaterialExists()) {
            return rollback(
                record,
                RivenArchiveRestoreError.RecoveryFailure("PARTIAL_MOVE_FROM_STAGED"),
            )
        }
        if (!verifyState(paths.pendingDatabase, paths.pendingAttachmentRoot)) {
            return RivenRestoreBootstrapResult.Failure(
                RivenArchiveRestoreError.RecoveryFailure("INVALID_STAGED_STATE"),
            )
        }
        return applyStaged(record)
    }

    private fun applyStaged(record: RivenRestoreJournalRecord): RivenRestoreBootstrapResult {
        var verifying = false
        var verified = false
        val activeRecord = record.copy(
            hadDatabase = paths.canonicalDatabase.isFile,
            hadWal = paths.canonicalWal.isFile,
            hadShm = paths.canonicalShm.isFile,
            hadAttachments = paths.canonicalAttachments.exists(),
            hadCredentials = paths.canonicalCredentials.exists(),
        )
        try {
            journal.write(activeRecord)
            deletePath(paths.rollbackRoot)
            moveIfExists(paths.canonicalDatabase, paths.rollbackDatabase)
            moveIfExists(paths.canonicalWal, paths.rollbackWal)
            moveIfExists(paths.canonicalShm, paths.rollbackShm)
            moveIfExists(paths.canonicalAttachments, paths.rollbackAttachments)
            moveIfExists(paths.canonicalCredentials, paths.rollbackCredentials)
            journal.write(activeRecord.copy(stage = RivenRestoreJournalStage.CURRENT_MOVED_ASIDE))

            hooks.beforeDatabaseInstall()
            paths.canonicalDatabase.parentFile?.mkdirs()
            moveReplacing(paths.pendingDatabase, paths.canonicalDatabase)
            deletePath(paths.canonicalWal)
            deletePath(paths.canonicalShm)

            hooks.beforeAttachmentInstall()
            deletePath(paths.canonicalAttachments)
            moveReplacing(paths.pendingAttachmentRoot, paths.canonicalAttachments)
            deletePath(paths.canonicalCredentials)
            journal.write(activeRecord.copy(stage = RivenRestoreJournalStage.NEW_INSTALLED))

            verifying = true
            hooks.beforePostInstallVerification()
            if (!verifyState(paths.canonicalDatabase, paths.canonicalAttachments) ||
                paths.canonicalCredentials.exists()
            ) {
                return rollback(
                    activeRecord,
                    RivenArchiveRestoreError.RestoreVerificationFailure("POST_INSTALL"),
                )
            }
            journal.write(activeRecord.copy(stage = RivenRestoreJournalStage.VERIFIED))
            verified = true
            finishSuccessfulRestore()
            return RivenRestoreBootstrapResult.RestoreApplied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (verified) {
                return RivenRestoreBootstrapResult.Failure(
                    RivenArchiveRestoreError.RecoveryFailure("VERIFIED_CLEANUP"),
                )
            }
            val trigger = if (verifying) {
                RivenArchiveRestoreError.RestoreVerificationFailure(failure.safeCauseType())
            } else {
                RivenArchiveRestoreError.RestoreInstallFailure(failure.safeCauseType())
            }
            return rollback(activeRecord, trigger)
        }
    }

    private fun recoverInstalledOrRollback(
        record: RivenRestoreJournalRecord,
    ): RivenRestoreBootstrapResult {
        if (verifyState(paths.canonicalDatabase, paths.canonicalAttachments) &&
            !paths.canonicalCredentials.exists()
        ) {
            journal.write(record.copy(stage = RivenRestoreJournalStage.VERIFIED))
            finishSuccessfulRestore()
            return RivenRestoreBootstrapResult.RestoreApplied
        }
        return rollback(
            record,
            RivenArchiveRestoreError.RecoveryFailure("INSTALLED_STATE_INVALID"),
        )
    }

    private fun rollback(
        record: RivenRestoreJournalRecord,
        trigger: RivenArchiveRestoreError,
    ): RivenRestoreBootstrapResult {
        if (!record.hadDatabase) {
            return RivenRestoreBootstrapResult.Failure(
                RivenArchiveRestoreError.RecoveryFailure("NO_KNOWN_GOOD_DATABASE"),
            )
        }
        return try {
            restoreComponent(record.hadDatabase, paths.rollbackDatabase, paths.canonicalDatabase)
            restoreComponent(record.hadWal, paths.rollbackWal, paths.canonicalWal)
            restoreComponent(record.hadShm, paths.rollbackShm, paths.canonicalShm)
            restoreComponent(record.hadAttachments, paths.rollbackAttachments, paths.canonicalAttachments)
            restoreComponent(record.hadCredentials, paths.rollbackCredentials, paths.canonicalCredentials)
            if (!verifyState(paths.canonicalDatabase, paths.canonicalAttachments)) {
                return RivenRestoreBootstrapResult.Failure(
                    RivenArchiveRestoreError.RollbackFailure("ROLLBACK_VERIFICATION"),
                )
            }
            deletePath(paths.rollbackRoot)
            deletePath(paths.pendingRoot)
            journal.delete()
            RivenRestoreBootstrapResult.PreviousStateRestored(trigger)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RivenRestoreBootstrapResult.Failure(
                RivenArchiveRestoreError.RollbackFailure(failure.safeCauseType()),
            )
        }
    }

    private fun restoreComponent(hadOriginal: Boolean, rollback: File, canonical: File) {
        if (!hadOriginal) {
            deletePath(canonical)
            return
        }
        if (rollback.exists()) {
            deletePath(canonical)
            moveReplacing(rollback, canonical)
        } else if (!canonical.exists()) {
            error("Known-good restore component is missing")
        }
    }

    private fun verifyState(databaseFile: File, attachmentRoot: File): Boolean = runCatching {
        require(databaseFile.isFile)
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            val version = sqlite.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            require(version == CURRENT_RIVEN_DATABASE_VERSION)
            val quick = sqlite.rawQuery("PRAGMA quick_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.count == 1 && cursor.getString(0) == "ok"
            }
            require(quick)
            val foreignKeyViolation = sqlite.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }
            require(!foreignKeyViolation)
        }
        val restored = RivenDatabase.buildNamedForRestoreValidation(appContext, databaseFile.absolutePath)
        val attachments = try {
            restored.openHelper.writableDatabase
            restored.attachmentDao().allAttachments()
        } finally {
            restored.close()
        }
        checkpointSelfContainedDatabase(databaseFile)
        verifyAttachments(attachments, attachmentRoot)
        true
    }.getOrDefault(false)

    private fun verifyAttachments(
        attachments: List<AttachmentEntity>,
        root: File,
    ) {
        attachments.forEach { attachment ->
            val file = AttachmentStorageKey.resolve(root, attachment.storageKey)
            if (attachment.state == AttachmentState.AVAILABLE) {
                require(file.isFile)
                require(attachment.byteSize != null && attachment.byteSize >= 0)
                require(attachment.contentSha256 != null && LOWERCASE_SHA256.matches(attachment.contentSha256))
                require(file.length() == attachment.byteSize)
                require(sha256Hex(file) == attachment.contentSha256)
            } else if (file.isFile && attachment.byteSize != null && attachment.contentSha256 != null) {
                require(file.length() == attachment.byteSize)
                require(sha256Hex(file) == attachment.contentSha256)
            }
        }
    }

    private fun moveIfExists(source: File, target: File) {
        if (source.exists()) moveReplacing(source, target)
    }

    private fun rollbackMaterialExists(): Boolean =
        paths.rollbackDatabase.exists() ||
            paths.rollbackWal.exists() ||
            paths.rollbackShm.exists() ||
            paths.rollbackAttachments.exists() ||
            paths.rollbackCredentials.exists()

    private fun finishSuccessfulRestore() {
        deletePath(paths.rollbackRoot)
        deletePath(paths.pendingRoot)
        journal.delete()
    }

    private fun deletePath(file: File) {
        check(!file.exists() || file.deleteRecursively())
    }
}
