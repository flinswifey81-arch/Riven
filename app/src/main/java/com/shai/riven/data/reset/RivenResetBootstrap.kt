package com.shai.riven.data.reset

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.shai.riven.data.archive.CURRENT_RIVEN_DATABASE_VERSION
import com.shai.riven.data.archive.safeCauseType
import com.shai.riven.data.credential.AndroidKeystoreProviderCredentialKeyResetter
import com.shai.riven.data.credential.ProviderCredentialKeyResetter
import com.shai.riven.data.persistence.RivenDatabase
import java.io.File
import kotlinx.coroutines.CancellationException

internal data class RivenResetBootstrapHooks(
    val afterDatabaseDeleted: () -> Unit = {},
    val afterAttachmentsDeleted: () -> Unit = {},
    val afterCredentialFilesDeleted: () -> Unit = {},
    val afterCredentialKeyDeleted: () -> Unit = {},
    val afterFreshDatabaseCreated: () -> Unit = {},
    val afterVerifiedJournalWritten: () -> Unit = {},
)

class RivenResetBootstrap(
    context: Context,
    resetRoot: File = File(
        context.applicationContext.noBackupFilesDir,
        RivenResetPaths.RESET_DIRECTORY,
    ),
    private val keyResetter: ProviderCredentialKeyResetter = AndroidKeystoreProviderCredentialKeyResetter(),
) {
    private val appContext = context.applicationContext
    private val paths = RivenResetPaths(appContext, resetRoot)
    private val journal = RivenResetJournal(paths.journalFile)
    internal var hooks = RivenResetBootstrapHooks()

    fun recoverAndApply(): FactoryResetBootstrapResult {
        if (!journal.exists()) return FactoryResetBootstrapResult.NoPendingReset
        val record = journal.read()
            ?: return FactoryResetBootstrapResult.Failure(
                RivenResetError.RecoveryFailure("MALFORMED_JOURNAL"),
            )
        return try {
            when (record.stage) {
                RivenResetJournalStage.PENDING,
                RivenResetJournalStage.ERASING,
                -> eraseAndRecreate(record)
                RivenResetJournalStage.FRESH_DATABASE_CREATED -> {
                    if (RivenEmptyDatabaseVerifier.verify(appContext, paths.canonicalDatabase)) {
                        verifyAndFinish(record)
                    } else {
                        eraseAndRecreate(record)
                    }
                }
                RivenResetJournalStage.VERIFIED -> {
                    if (RivenEmptyDatabaseVerifier.verify(appContext, paths.canonicalDatabase)) {
                        finishVerified()
                    } else {
                        eraseAndRecreate(record)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            FactoryResetBootstrapResult.Failure(
                RivenResetError.RecoveryFailure(failure.safeCauseType()),
            )
        }
    }

    private fun eraseAndRecreate(
        record: RivenResetJournalRecord,
    ): FactoryResetBootstrapResult {
        writeStage(record, RivenResetJournalStage.ERASING)?.let(::failure)?.let { return it }

        runStep(RivenResetError::DatabaseDeleteFailure) {
            deleteFile(paths.canonicalDatabase)
            deleteFile(paths.canonicalWal)
            deleteFile(paths.canonicalShm)
            hooks.afterDatabaseDeleted()
        }?.let(::failure)?.let { return it }

        runStep(RivenResetError::AttachmentDeleteFailure) {
            deleteDirectory(paths.canonicalAttachments)
            hooks.afterAttachmentsDeleted()
        }?.let(::failure)?.let { return it }

        runStep(RivenResetError::CredentialDeleteFailure) {
            deleteDirectory(paths.canonicalCredentials)
            hooks.afterCredentialFilesDeleted()
        }?.let(::failure)?.let { return it }

        runStep(RivenResetError::CredentialKeyDeleteFailure) {
            keyResetter.deleteProviderCredentialKey()
            hooks.afterCredentialKeyDeleted()
        }?.let(::failure)?.let { return it }

        runStep(RivenResetError::ArchiveStagingDeleteFailure) {
            deleteDirectory(paths.archiveStaging)
        }?.let(::failure)?.let { return it }

        runStep(RivenResetError::RestoreStateDeleteFailure) {
            deleteDirectory(paths.restoreState)
        }?.let(::failure)?.let { return it }

        runStep(RivenResetError::FreshDatabaseCreationFailure) {
            val fresh = RivenDatabase.build(appContext)
            try {
                fresh.openHelper.writableDatabase
            } finally {
                fresh.close()
            }
        }?.let(::failure)?.let { return it }

        writeStage(record, RivenResetJournalStage.FRESH_DATABASE_CREATED)
            ?.let(::failure)
            ?.let { return it }
        runStep(RivenResetError::FreshDatabaseCreationFailure) {
            hooks.afterFreshDatabaseCreated()
        }?.let(::failure)?.let { return it }

        if (!RivenEmptyDatabaseVerifier.verify(appContext, paths.canonicalDatabase)) {
            return failure(RivenResetError.FreshDatabaseVerificationFailure("NOT_EMPTY_OR_INVALID"))
        }
        return verifyAndFinish(record)
    }

    private fun verifyAndFinish(
        record: RivenResetJournalRecord,
    ): FactoryResetBootstrapResult {
        writeStage(record, RivenResetJournalStage.VERIFIED)?.let(::failure)?.let { return it }
        runStep(RivenResetError::JournalFailure) {
            hooks.afterVerifiedJournalWritten()
        }?.let(::failure)?.let { return it }
        return finishVerified()
    }

    private fun finishVerified(): FactoryResetBootstrapResult = try {
        journal.delete()
        FactoryResetBootstrapResult.ResetApplied
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Exception) {
        failure(RivenResetError.JournalFailure(cause.safeCauseType()))
    }

    private fun writeStage(
        record: RivenResetJournalRecord,
        stage: RivenResetJournalStage,
    ): RivenResetError? = runStep(RivenResetError::JournalFailure) {
        journal.write(record.copy(stage = stage))
    }

    private fun runStep(
        error: (String) -> RivenResetError,
        operation: () -> Unit,
    ): RivenResetError? = try {
        operation()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        error(failure.safeCauseType())
    }

    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) error("Owned reset file could not be deleted")
    }

    private fun deleteDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            error("Owned reset directory could not be deleted")
        }
    }

    private fun failure(error: RivenResetError): FactoryResetBootstrapResult =
        FactoryResetBootstrapResult.Failure(error)
}

internal object RivenEmptyDatabaseVerifier {
    private const val EXPECTED_APPLICATION_TABLE_COUNT = 37

    fun verify(context: Context, databaseFile: File): Boolean = runCatching {
        require(databaseFile.isFile)
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            val version = sqlite.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            require(version == CURRENT_RIVEN_DATABASE_VERSION)
            val tables = sqlite.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (name != "android_metadata" &&
                            name != "room_master_table" &&
                            !name.startsWith("sqlite_")
                        ) {
                            add(name)
                        }
                    }
                }
            }
            require(tables.size == EXPECTED_APPLICATION_TABLE_COUNT)
            tables.forEach { table ->
                require(table.matches(Regex("[A-Za-z0-9_]+")))
                val count = sqlite.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
                require(count == 0L)
            }
        }
        true
    }.getOrDefault(false)
}
