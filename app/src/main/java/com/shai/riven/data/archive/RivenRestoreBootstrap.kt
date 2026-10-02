package com.shai.riven.data.archive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.shai.riven.data.attachment.AttachmentStorageKey
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import com.shai.riven.data.reminder.platform.ReminderResetCoordinator
import java.io.File
import kotlinx.coroutines.CancellationException

internal data class RivenRestoreBootstrapHooks(
    val afterMainDatabaseMovedAside: () -> Unit = {},
    val afterReminderDatabaseMovedAside: () -> Unit = {},
    val beforeDatabaseInstall: () -> Unit = {},
    val afterMainDatabaseInstalled: () -> Unit = {},
    val afterReminderDatabaseInstalled: () -> Unit = {},
    val beforeAttachmentInstall: () -> Unit = {},
    val beforePostInstallVerification: () -> Unit = {},
    val afterRollbackAttachmentsRestored: () -> Unit = {},
)

/**
 * Deterministic recovery policy:
 * - STAGED applies only while current state is untouched; partial moves converge by rolling back.
 * - CURRENT_MOVED_ASIDE, NEW_INSTALLED, and VERIFIED keep a fully verifiable installed state.
 * - ROLLING_BACK always resumes rollback; it can never be reinterpreted as a successful install.
 * - Any unverifiable installed state rolls back when a known-good database exists.
 * - An interrupted fresh install converges toward staged state when no known-good database exists.
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
            if (record.includesReminderDatabase) {
                ReminderResetCoordinator(appContext).cancelDeliveries()
            }
            when (record.stage) {
                RivenRestoreJournalStage.STAGED -> recoverStaged(record)
                RivenRestoreJournalStage.CURRENT_MOVED_ASIDE,
                RivenRestoreJournalStage.NEW_INSTALLED,
                RivenRestoreJournalStage.VERIFIED,
                -> recoverInstalledOrRollback(record)
                RivenRestoreJournalStage.ROLLING_BACK -> rollback(
                    record,
                    RivenArchiveRestoreError.RecoveryFailure("ROLLBACK_RESUMED"),
                )
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
        if (!verifyState(
                paths.pendingDatabase,
                paths.pendingAttachmentRoot,
                record.reminderDatabaseOrNull(paths.pendingReminderDatabase),
            )
        ) {
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
            hadReminderDatabase = if (record.includesReminderDatabase) {
                paths.canonicalReminderDatabase.isFile
            } else {
                record.hadReminderDatabase
            },
            hadReminderWal = if (record.includesReminderDatabase) {
                paths.canonicalReminderWal.isFile
            } else {
                record.hadReminderWal
            },
            hadReminderShm = if (record.includesReminderDatabase) {
                paths.canonicalReminderShm.isFile
            } else {
                record.hadReminderShm
            },
        )
        try {
            journal.write(activeRecord)
            deletePath(paths.rollbackRoot)
            moveIfExists(paths.canonicalDatabase, paths.rollbackDatabase)
            hooks.afterMainDatabaseMovedAside()
            moveIfExists(paths.canonicalWal, paths.rollbackWal)
            moveIfExists(paths.canonicalShm, paths.rollbackShm)
            if (activeRecord.includesReminderDatabase) {
                moveIfExists(paths.canonicalReminderDatabase, paths.rollbackReminderDatabase)
                hooks.afterReminderDatabaseMovedAside()
                moveIfExists(paths.canonicalReminderWal, paths.rollbackReminderWal)
                moveIfExists(paths.canonicalReminderShm, paths.rollbackReminderShm)
            }
            moveIfExists(paths.canonicalAttachments, paths.rollbackAttachments)
            moveIfExists(paths.canonicalCredentials, paths.rollbackCredentials)
            journal.write(activeRecord.copy(stage = RivenRestoreJournalStage.CURRENT_MOVED_ASIDE))

            hooks.beforeDatabaseInstall()
            paths.canonicalDatabase.parentFile?.mkdirs()
            moveReplacing(paths.pendingDatabase, paths.canonicalDatabase)
            hooks.afterMainDatabaseInstalled()
            deletePath(paths.canonicalWal)
            deletePath(paths.canonicalShm)
            if (activeRecord.includesReminderDatabase) {
                paths.canonicalReminderDatabase.parentFile?.mkdirs()
                moveReplacing(paths.pendingReminderDatabase, paths.canonicalReminderDatabase)
                hooks.afterReminderDatabaseInstalled()
                deletePath(paths.canonicalReminderWal)
                deletePath(paths.canonicalReminderShm)
            }

            hooks.beforeAttachmentInstall()
            deletePath(paths.canonicalAttachments)
            moveReplacing(paths.pendingAttachmentRoot, paths.canonicalAttachments)
            deletePath(paths.canonicalCredentials)
            journal.write(activeRecord.copy(stage = RivenRestoreJournalStage.NEW_INSTALLED))

            verifying = true
            hooks.beforePostInstallVerification()
            if (!verifyState(
                    paths.canonicalDatabase,
                    paths.canonicalAttachments,
                    activeRecord.reminderDatabaseOrNull(paths.canonicalReminderDatabase),
                ) ||
                paths.canonicalCredentials.exists()
            ) {
                if (!activeRecord.hadDatabase) {
                    return RivenRestoreBootstrapResult.Failure(
                        RivenArchiveRestoreError.RecoveryFailure("FRESH_INSTALL_STATE_INVALID"),
                    )
                }
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
            if (!activeRecord.hadDatabase) {
                return RivenRestoreBootstrapResult.Failure(
                    RivenArchiveRestoreError.RecoveryFailure(
                        if (verifying) {
                            "FRESH_INSTALL_VERIFICATION_${failure.safeCauseType()}"
                        } else {
                            "FRESH_INSTALL_${failure.safeCauseType()}"
                        },
                    ),
                )
            }
            return rollback(activeRecord, trigger)
        }
    }

    private fun recoverInstalledOrRollback(
        record: RivenRestoreJournalRecord,
    ): RivenRestoreBootstrapResult {
        if (record.stage == RivenRestoreJournalStage.CURRENT_MOVED_ASIDE && !record.hadDatabase) {
            return resumeFreshInstall(record)
        }
        if (verifyState(
                paths.canonicalDatabase,
                paths.canonicalAttachments,
                record.reminderDatabaseOrNull(paths.canonicalReminderDatabase),
            ) &&
            !paths.canonicalCredentials.exists()
        ) {
            journal.write(record.copy(stage = RivenRestoreJournalStage.VERIFIED))
            finishSuccessfulRestore()
            return RivenRestoreBootstrapResult.RestoreApplied
        }
        if (!record.hadDatabase) {
            return RivenRestoreBootstrapResult.Failure(
                RivenArchiveRestoreError.RecoveryFailure("FRESH_INSTALL_STATE_INVALID"),
            )
        }
        return rollback(
            record,
            RivenArchiveRestoreError.RecoveryFailure("INSTALLED_STATE_INVALID"),
        )
    }

    private fun resumeFreshInstall(
        record: RivenRestoreJournalRecord,
    ): RivenRestoreBootstrapResult {
        if (!paths.canonicalDatabase.isFile) {
            if (!paths.pendingDatabase.isFile) {
                return RivenRestoreBootstrapResult.Failure(
                    RivenArchiveRestoreError.RecoveryFailure("FRESH_INSTALL_DATABASE_MISSING"),
                )
            }
            paths.canonicalDatabase.parentFile?.mkdirs()
            moveReplacing(paths.pendingDatabase, paths.canonicalDatabase)
        }
        deletePath(paths.canonicalWal)
        deletePath(paths.canonicalShm)
        if (record.includesReminderDatabase && !paths.canonicalReminderDatabase.isFile) {
            if (!paths.pendingReminderDatabase.isFile) {
                return RivenRestoreBootstrapResult.Failure(
                    RivenArchiveRestoreError.RecoveryFailure("FRESH_INSTALL_REMINDER_DATABASE_MISSING"),
                )
            }
            paths.canonicalReminderDatabase.parentFile?.mkdirs()
            moveReplacing(paths.pendingReminderDatabase, paths.canonicalReminderDatabase)
        }
        if (record.includesReminderDatabase) {
            deletePath(paths.canonicalReminderWal)
            deletePath(paths.canonicalReminderShm)
        }
        if (!paths.canonicalAttachments.exists() && paths.pendingAttachmentRoot.exists()) {
            moveReplacing(paths.pendingAttachmentRoot, paths.canonicalAttachments)
        }
        deletePath(paths.canonicalCredentials)
        journal.write(record.copy(stage = RivenRestoreJournalStage.NEW_INSTALLED))
        if (!verifyState(
                paths.canonicalDatabase,
                paths.canonicalAttachments,
                record.reminderDatabaseOrNull(paths.canonicalReminderDatabase),
            ) ||
            paths.canonicalCredentials.exists()
        ) {
            return RivenRestoreBootstrapResult.Failure(
                RivenArchiveRestoreError.RecoveryFailure("FRESH_INSTALL_STATE_INVALID"),
            )
        }
        journal.write(record.copy(stage = RivenRestoreJournalStage.VERIFIED))
        finishSuccessfulRestore()
        return RivenRestoreBootstrapResult.RestoreApplied
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
            journal.write(record.copy(stage = RivenRestoreJournalStage.ROLLING_BACK))
            restoreComponent(record.hadDatabase, paths.rollbackDatabase, paths.canonicalDatabase)
            restoreComponent(record.hadWal, paths.rollbackWal, paths.canonicalWal)
            restoreComponent(record.hadShm, paths.rollbackShm, paths.canonicalShm)
            if (record.includesReminderDatabase) {
                restoreComponent(
                    record.hadReminderDatabase,
                    paths.rollbackReminderDatabase,
                    paths.canonicalReminderDatabase,
                )
                restoreComponent(
                    record.hadReminderWal,
                    paths.rollbackReminderWal,
                    paths.canonicalReminderWal,
                )
                restoreComponent(
                    record.hadReminderShm,
                    paths.rollbackReminderShm,
                    paths.canonicalReminderShm,
                )
            }
            restoreComponent(record.hadAttachments, paths.rollbackAttachments, paths.canonicalAttachments)
            hooks.afterRollbackAttachmentsRestored()
            restoreComponent(record.hadCredentials, paths.rollbackCredentials, paths.canonicalCredentials)
            if (!verifyState(
                    paths.canonicalDatabase,
                    paths.canonicalAttachments,
                    paths.canonicalReminderDatabase.takeIf {
                        record.includesReminderDatabase && record.hadReminderDatabase
                    },
                )
            ) {
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

    private fun verifyState(
        databaseFile: File,
        attachmentRoot: File,
        reminderDatabaseFile: File?,
    ): Boolean = runCatching {
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
        reminderDatabaseFile?.let(::verifyReminderDatabase)
        true
    }.getOrDefault(false)

    private fun verifyReminderDatabase(databaseFile: File) {
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
            require(version == CURRENT_REMINDER_DATABASE_VERSION)
            val quick = sqlite.rawQuery("PRAGMA quick_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.count == 1 && cursor.getString(0) == "ok"
            }
            require(quick)
            val foreignKeyViolation = sqlite.rawQuery("PRAGMA foreign_key_check", null).use {
                it.moveToFirst()
            }
            require(!foreignKeyViolation)
        }
        val restored = ReminderDatabase.buildNamedForRestoreValidation(
            appContext,
            databaseFile.absolutePath,
        )
        try {
            restored.openHelper.writableDatabase
        } finally {
            restored.close()
        }
        checkpointSelfContainedDatabase(databaseFile)
    }

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
            paths.rollbackReminderDatabase.exists() ||
            paths.rollbackReminderWal.exists() ||
            paths.rollbackReminderShm.exists() ||
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

    private fun RivenRestoreJournalRecord.reminderDatabaseOrNull(file: File): File? =
        file.takeIf { includesReminderDatabase }
}
