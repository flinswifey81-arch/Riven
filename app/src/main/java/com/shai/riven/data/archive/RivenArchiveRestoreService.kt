package com.shai.riven.data.archive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.shai.riven.data.attachment.AttachmentStorageKey
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.reset.RivenResetGate
import com.shai.riven.data.reset.RivenResetPaths
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException

class RivenArchiveRestoreService(
    context: Context,
    private val limits: RivenArchiveLimits = RivenArchiveLimits(),
    restoreRoot: File = File(
        context.applicationContext.noBackupFilesDir,
        RivenRestorePaths.RESTORE_DIRECTORY,
    ),
    resetRoot: File = File(
        context.applicationContext.noBackupFilesDir,
        RivenResetPaths.RESET_DIRECTORY,
    ),
) {
    private val appContext = context.applicationContext
    private val paths = RivenRestorePaths(appContext, restoreRoot)
    private val resetRootPath = resetRoot
    private val journal = RivenRestoreJournal(paths.journalFile)

    fun stageRestore(input: StageRivenRestoreInput): StageRivenRestoreResult {
        if (RivenResetGate.isPending(appContext, resetRootPath)) {
            return StageRivenRestoreResult.Failure(RivenArchiveRestoreError.FactoryResetPending)
        }
        if (journal.exists() || paths.rollbackRoot.exists()) {
            return StageRivenRestoreResult.Failure(RivenArchiveRestoreError.RestoreAlreadyPending)
        }
        val working = File(paths.pendingRoot, ".stage-${UUID.randomUUID()}")
        return try {
            check(!paths.pendingRoot.exists() || paths.pendingRoot.deleteRecursively())
            check(working.mkdirs())
            val extracted = try {
                extractArchive(input.input, working)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ZipException) {
                abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("ZIP_STRUCTURE"))
            } catch (_: EOFException) {
                abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("ZIP_STRUCTURE"))
            }
            val manifestEntry = extracted[ARCHIVE_MANIFEST_PATH]
                ?: abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("MISSING_MANIFEST"))
            val databaseEntry = extracted[ARCHIVE_DATABASE_PATH]
                ?: abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("MISSING_DATABASE"))
            val manifest = RivenArchiveManifestJson.decode(manifestEntry.file.readBytes())
                ?: abort(RivenArchiveRestoreError.MalformedManifest)
            val reminderDatabaseEntry = extracted[ARCHIVE_REMINDER_DATABASE_PATH]
            validateManifestAndEntries(manifest, databaseEntry, reminderDatabaseEntry, extracted)

            val sourceVersion = databaseVersion(databaseEntry.file)
            if (sourceVersion != manifest.databaseSchemaVersion) {
                abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("DATABASE_VERSION"))
            }
            if (sourceVersion > CURRENT_RIVEN_DATABASE_VERSION) {
                abort(
                    RivenArchiveRestoreError.DatabaseTooNew(
                        sourceVersion,
                        CURRENT_RIVEN_DATABASE_VERSION,
                    ),
                )
            }
            if (sourceVersion < 1) {
                abort(RivenArchiveRestoreError.DatabaseMigrationFailure("UnsupportedDatabaseVersion"))
            }
            verifySqlite(databaseEntry.file)
            val attachments = migrateAndPrepareDatabase(databaseEntry.file, input.occurredAt)
            val resultingVersion = databaseVersion(databaseEntry.file)
            if (resultingVersion != CURRENT_RIVEN_DATABASE_VERSION) {
                abort(RivenArchiveRestoreError.DatabaseMigrationFailure("UnexpectedResultVersion"))
            }
            verifySqlite(databaseEntry.file)
            if (manifest.archiveFormatVersion >= 2) {
                val reminderEntry = reminderDatabaseEntry
                    ?: abort(
                        RivenArchiveRestoreError.ArchiveIntegrityFailure(
                            "MISSING_REMINDER_DATABASE",
                        ),
                    )
                prepareReminderDatabase(
                    file = reminderEntry.file,
                    expectedVersion = checkNotNull(manifest.reminderDatabaseSchemaVersion),
                    occurredAt = input.occurredAt,
                )
            }
            materializeAndValidateAttachments(
                manifest = manifest,
                databaseAttachments = attachments,
                extracted = extracted,
                destinationRoot = File(working, "attachment_root"),
            )

            val manifestFile = File(working, ARCHIVE_MANIFEST_PATH)
            check(manifestFile.isFile)
            val archiveBlobRoot = File(working, "archive_blobs")
            check(!archiveBlobRoot.exists() || archiveBlobRoot.deleteRecursively())
            val packageDirectory = File(paths.pendingRoot, "package")
            check(!packageDirectory.exists() || packageDirectory.deleteRecursively())
            moveReplacing(working, packageDirectory)
            journal.write(
                RivenRestoreJournalRecord(
                    stage = RivenRestoreJournalStage.STAGED,
                    hadDatabase = paths.canonicalDatabase.isFile,
                    hadWal = paths.canonicalWal.isFile,
                    hadShm = paths.canonicalShm.isFile,
                    hadAttachments = paths.canonicalAttachments.exists(),
                    hadCredentials = paths.canonicalCredentials.exists(),
                    includesReminderDatabase = manifest.archiveFormatVersion >= 2,
                    hadReminderDatabase = paths.canonicalReminderDatabase.isFile,
                    hadReminderWal = paths.canonicalReminderWal.isFile,
                    hadReminderShm = paths.canonicalReminderShm.isFile,
                ),
            )
            StageRivenRestoreResult.RestoreStaged(
                archiveFormatVersion = manifest.archiveFormatVersion,
                sourceDatabaseVersion = sourceVersion,
                resultingDatabaseVersion = resultingVersion,
                attachmentCount = attachments.size,
            )
        } catch (abort: RestoreAbort) {
            working.deleteRecursively()
            if (!journal.exists()) paths.pendingPackage.deleteRecursively()
            StageRivenRestoreResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            working.deleteRecursively()
            throw cancelled
        } catch (failure: Exception) {
            working.deleteRecursively()
            if (!journal.exists()) paths.pendingPackage.deleteRecursively()
            StageRivenRestoreResult.Failure(
                RivenArchiveRestoreError.RestoreStagingFailure(failure.safeCauseType()),
            )
        }
    }

    private fun extractArchive(input: InputStream, working: File): Map<String, ExtractedEntry> {
        val extracted = linkedMapOf<String, ExtractedEntry>()
        var total = 0L
        val nonClosing = object : FilterInputStream(input) {
            override fun close() = Unit
        }
        ZipInputStream(nonClosing).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !RivenArchivePath.isSafe(entry.name)) {
                    abort(RivenArchiveRestoreError.UnsafeArchivePath)
                }
                if (!RivenArchivePath.isAllowlistedEntry(entry.name)) {
                    abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("UNEXPECTED_ENTRY"))
                }
                if (extracted.containsKey(entry.name)) {
                    abort(RivenArchiveRestoreError.DuplicateArchiveEntry)
                }
                if (extracted.size + 1 > limits.maximumEntryCount) {
                    abort(RivenArchiveRestoreError.ArchiveLimitExceeded(ArchiveLimit.ENTRY_COUNT))
                }
                val perEntryLimit = when (entry.name) {
                    ARCHIVE_MANIFEST_PATH -> limits.maximumManifestBytes
                    ARCHIVE_DATABASE_PATH, ARCHIVE_REMINDER_DATABASE_PATH -> limits.maximumDatabaseBytes
                    else -> limits.maximumAttachmentBytes
                }
                val destination = when (entry.name) {
                    ARCHIVE_MANIFEST_PATH -> File(working, ARCHIVE_MANIFEST_PATH)
                    ARCHIVE_DATABASE_PATH -> File(working, ARCHIVE_DATABASE_PATH)
                    ARCHIVE_REMINDER_DATABASE_PATH -> File(working, ARCHIVE_REMINDER_DATABASE_PATH)
                    else -> File(working, "archive_blobs/${entry.name.removePrefix(ARCHIVE_ATTACHMENT_PREFIX)}")
                }
                destination.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var entryBytes = 0L
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read == -1) break
                        entryBytes += read
                        total += read
                        if (entryBytes > perEntryLimit) {
                            val limit = when (entry.name) {
                                ARCHIVE_MANIFEST_PATH -> ArchiveLimit.MANIFEST_BYTES
                                ARCHIVE_DATABASE_PATH, ARCHIVE_REMINDER_DATABASE_PATH -> ArchiveLimit.DATABASE_BYTES
                                else -> ArchiveLimit.ATTACHMENT_BYTES
                            }
                            abort(RivenArchiveRestoreError.ArchiveLimitExceeded(limit))
                        }
                        if (total > limits.maximumTotalExtractedBytes) {
                            abort(
                                RivenArchiveRestoreError.ArchiveLimitExceeded(
                                    ArchiveLimit.TOTAL_EXTRACTED_BYTES,
                                ),
                            )
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    output.fd.sync()
                }
                extracted[entry.name] = ExtractedEntry(
                    file = destination,
                    byteSize = entryBytes,
                    sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                )
                zip.closeEntry()
            }
        }
        return extracted
    }

    private fun validateManifestAndEntries(
        manifest: RivenArchiveManifest,
        database: ExtractedEntry,
        reminderDatabase: ExtractedEntry?,
        extracted: Map<String, ExtractedEntry>,
    ) {
        if (manifest.archiveFormatVersion !in
            MINIMUM_SUPPORTED_RIVEN_ARCHIVE_FORMAT_VERSION..RIVEN_ARCHIVE_FORMAT_VERSION
        ) {
            abort(RivenArchiveRestoreError.UnsupportedArchiveVersion)
        }
        if (manifest.secretsIncluded || !LOWERCASE_SHA256.matches(manifest.databaseSha256)) {
            abort(RivenArchiveRestoreError.MalformedManifest)
        }
        if (database.sha256 != manifest.databaseSha256) {
            abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("DATABASE_HASH"))
        }
        val expectedCoreEntries = mutableSetOf(ARCHIVE_MANIFEST_PATH, ARCHIVE_DATABASE_PATH)
        if (manifest.archiveFormatVersion >= 2) {
            val reminderVersion = manifest.reminderDatabaseSchemaVersion
                ?: abort(RivenArchiveRestoreError.MalformedManifest)
            val reminderHash = manifest.reminderDatabaseSha256
                ?: abort(RivenArchiveRestoreError.MalformedManifest)
            if (reminderVersion < 1 || !LOWERCASE_SHA256.matches(reminderHash)) {
                abort(RivenArchiveRestoreError.MalformedManifest)
            }
            val reminder = reminderDatabase
                ?: abort(
                    RivenArchiveRestoreError.ArchiveIntegrityFailure(
                        "MISSING_REMINDER_DATABASE",
                    ),
                )
            if (reminder.sha256 != reminderHash) {
                abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("REMINDER_DATABASE_HASH"))
            }
            expectedCoreEntries += ARCHIVE_REMINDER_DATABASE_PATH
        } else if (manifest.reminderDatabaseSchemaVersion != null ||
            manifest.reminderDatabaseSha256 != null
        ) {
            abort(RivenArchiveRestoreError.MalformedManifest)
        }
        val actualCoreEntries = extracted.keys
            .filterNot { it.startsWith(ARCHIVE_ATTACHMENT_PREFIX) }
            .toSet()
        if (actualCoreEntries != expectedCoreEntries) {
            abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("DATABASE_SET"))
        }
        val ids = mutableSetOf<String>()
        val declaredPaths = mutableSetOf<String>()
        manifest.attachments.forEach { record ->
            if (record.attachmentId.isBlank() || !ids.add(record.attachmentId)) {
                abort(RivenArchiveRestoreError.MalformedManifest)
            }
            if (!AttachmentStorageKey.isSafe(record.storageKey)) {
                abort(RivenArchiveRestoreError.AttachmentIntegrityFailure(record.attachmentId))
            }
            if (record.byteSize != null && record.byteSize < 0) {
                abort(RivenArchiveRestoreError.MalformedManifest)
            }
            if (record.contentSha256 != null && !LOWERCASE_SHA256.matches(record.contentSha256)) {
                abort(RivenArchiveRestoreError.MalformedManifest)
            }
            if (record.blobPresent) {
                val archivePath = record.archivePath
                    ?: abort(RivenArchiveRestoreError.MalformedManifest)
                if (archivePath != RivenArchivePath.attachmentPath(record.attachmentId) ||
                    !RivenArchivePath.isSafe(archivePath) ||
                    !declaredPaths.add(archivePath)
                ) {
                    abort(RivenArchiveRestoreError.MalformedManifest)
                }
                val blob = extracted[archivePath]
                    ?: abort(RivenArchiveRestoreError.AttachmentIntegrityFailure(record.attachmentId))
                val expectedSize = record.byteSize
                    ?: abort(RivenArchiveRestoreError.MalformedManifest)
                val expectedHash = record.contentSha256
                    ?: abort(RivenArchiveRestoreError.MalformedManifest)
                if (blob.byteSize != expectedSize || blob.sha256 != expectedHash) {
                    abort(RivenArchiveRestoreError.AttachmentIntegrityFailure(record.attachmentId))
                }
            } else if (record.archivePath != null) {
                abort(RivenArchiveRestoreError.MalformedManifest)
            }
        }
        val actualBlobPaths = extracted.keys.filter { it.startsWith(ARCHIVE_ATTACHMENT_PREFIX) }.toSet()
        if (actualBlobPaths != declaredPaths) {
            abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("ATTACHMENT_SET"))
        }
    }

    private fun databaseVersion(file: File): Int = try {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }
    } catch (failure: Exception) {
        abort(RivenArchiveRestoreError.DatabaseIntegrityFailure("USER_VERSION"))
    }

    private fun verifySqlite(file: File) {
        try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                val quickCheck = sqlite.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    cursor.moveToFirst() && cursor.count == 1 && cursor.getString(0) == "ok"
                }
                if (!quickCheck) {
                    abort(RivenArchiveRestoreError.DatabaseIntegrityFailure("QUICK_CHECK"))
                }
                val foreignKeyViolation = sqlite.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    cursor.moveToFirst()
                }
                if (foreignKeyViolation) {
                    abort(RivenArchiveRestoreError.DatabaseIntegrityFailure("FOREIGN_KEY_CHECK"))
                }
            }
        } catch (abort: RestoreAbort) {
            throw abort
        } catch (_: Exception) {
            abort(RivenArchiveRestoreError.DatabaseIntegrityFailure("SQLITE_OPEN"))
        }
    }

    private fun migrateAndPrepareDatabase(
        file: File,
        occurredAt: Long,
    ): List<AttachmentEntity> {
        val restored = try {
            RivenDatabase.buildNamedForRestoreValidation(appContext, file.absolutePath)
        } catch (failure: Exception) {
            abort(RivenArchiveRestoreError.DatabaseMigrationFailure(failure.safeCauseType()))
        }
        val attachments = try {
            val sqlite = restored.openHelper.writableDatabase
            sqlite.execSQL(
                "UPDATE derived_artifacts " +
                    "SET state = 'REBUILD_PENDING', artifact_hash = NULL, invalidated_at = ?",
                arrayOf(occurredAt),
            )
            // WorkManager execution state is intentionally not portable. Requeue only jobs that
            // were RUNNING in the archived database without counting restore as another attempt.
            sqlite.execSQL(
                "UPDATE repair_jobs " +
                    "SET state = 'PENDING', updated_at = ?, last_error_code = 'RESTORE_REQUEUED' " +
                    "WHERE state = 'RUNNING'",
                arrayOf(occurredAt),
            )
            sqlite.execSQL(
                "UPDATE automatic_memory_jobs " +
                    "SET state = 'PENDING', updated_at = ?, last_error_code = 'RESTORE_REQUEUED' " +
                    "WHERE state = 'RUNNING'",
                arrayOf(occurredAt),
            )
            // Provider calls are never replayed from an archive. Any nonterminal attempt is
            // converted to a durable interrupted result without entering the repair-job queue.
            sqlite.execSQL(
                "UPDATE messages SET delivery_state = 'CANCELLED', updated_at = ?, " +
                    "error_code = 'INTERRUPTED' WHERE message_id IN (" +
                    "SELECT assistant_message_id FROM conversation_runs " +
                    "WHERE active_conversation_id IS NOT NULL)",
                arrayOf(occurredAt),
            )
            sqlite.execSQL(
                "UPDATE conversation_runs SET state = 'INTERRUPTED', " +
                    "active_conversation_id = NULL, error_code = 'INTERRUPTED', " +
                    "updated_at = ?, finished_at = ? WHERE active_conversation_id IS NOT NULL",
                arrayOf(occurredAt, occurredAt),
            )
            restored.attachmentDao().allAttachments()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            abort(RivenArchiveRestoreError.DatabaseMigrationFailure(failure.safeCauseType()))
        } finally {
            restored.close()
        }
        try {
            checkpointSelfContainedDatabase(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            abort(RivenArchiveRestoreError.DatabaseMigrationFailure(failure.safeCauseType()))
        }
        return attachments
    }

    private fun prepareReminderDatabase(
        file: File,
        expectedVersion: Int,
        occurredAt: Long,
    ) {
        val sourceVersion = databaseVersion(file)
        if (sourceVersion != expectedVersion) {
            abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("REMINDER_DATABASE_VERSION"))
        }
        if (sourceVersion > CURRENT_REMINDER_DATABASE_VERSION) {
            abort(
                RivenArchiveRestoreError.DatabaseTooNew(
                    sourceVersion,
                    CURRENT_REMINDER_DATABASE_VERSION,
                ),
            )
        }
        if (sourceVersion < 1) {
            abort(RivenArchiveRestoreError.DatabaseMigrationFailure("UnsupportedReminderDatabaseVersion"))
        }
        verifySqlite(file)
        val restored = try {
            ReminderDatabase.buildNamedForRestoreValidation(appContext, file.absolutePath)
        } catch (failure: Exception) {
            abort(RivenArchiveRestoreError.DatabaseMigrationFailure(failure.safeCauseType()))
        }
        try {
            val sqlite = restored.openHelper.writableDatabase
            // A portable archive cannot resume a notification or audio session. Terminalizing
            // in-flight deliveries prevents a restored alarm from ringing or nagging again.
            sqlite.execSQL(
                "UPDATE local_reminders SET status = 'DISMISSED', delivery_token = NULL, " +
                    "ring_until_at = NULL, finished_at = ?, updated_at = ?, " +
                    "last_failure_code = NULL, last_failure_detail = NULL " +
                    "WHERE status IN ('DELIVERING', 'RINGING', 'DELIVERED')",
                arrayOf(occurredAt, occurredAt),
            )
            // Scheduled, snoozed, and failed rows are recreated by startup recovery through the
            // normal AlarmManager and notification permission checks.
            sqlite.execSQL(
                "UPDATE local_reminders SET delivery_token = NULL, ring_until_at = NULL " +
                    "WHERE status IN ('SCHEDULED', 'SNOOZED', 'FAILED')",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            abort(RivenArchiveRestoreError.DatabaseMigrationFailure(failure.safeCauseType()))
        } finally {
            restored.close()
        }
        try {
            checkpointSelfContainedDatabase(file)
            verifySqlite(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            abort(RivenArchiveRestoreError.DatabaseMigrationFailure(failure.safeCauseType()))
        }
    }

    private fun materializeAndValidateAttachments(
        manifest: RivenArchiveManifest,
        databaseAttachments: List<AttachmentEntity>,
        extracted: Map<String, ExtractedEntry>,
        destinationRoot: File,
    ) {
        destinationRoot.mkdirs()
        val records = manifest.attachments.associateBy { it.attachmentId }
        if (records.size != manifest.attachments.size || records.keys != databaseAttachments.map { it.id }.toSet()) {
            abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("ATTACHMENT_DATABASE_SET"))
        }
        databaseAttachments.forEach { attachment ->
            val record = records.getValue(attachment.id)
            if (record.storageKey != attachment.storageKey ||
                record.state != attachment.state ||
                record.byteSize != attachment.byteSize ||
                record.contentSha256 != attachment.contentSha256
            ) {
                abort(RivenArchiveRestoreError.AttachmentIntegrityFailure(attachment.id))
            }
            if (!AttachmentStorageKey.isSafe(attachment.storageKey)) {
                abort(RivenArchiveRestoreError.AttachmentIntegrityFailure(attachment.id))
            }
            if (attachment.state == AttachmentState.AVAILABLE) {
                if (!record.blobPresent || attachment.byteSize == null || attachment.byteSize < 0 ||
                    attachment.contentSha256 == null ||
                    !LOWERCASE_SHA256.matches(attachment.contentSha256)
                ) {
                    abort(RivenArchiveRestoreError.AttachmentIntegrityFailure(attachment.id))
                }
            }
            if (record.blobPresent) {
                if (attachment.byteSize == null || attachment.contentSha256 == null) {
                    abort(RivenArchiveRestoreError.AttachmentIntegrityFailure(attachment.id))
                }
                val source = extracted.getValue(checkNotNull(record.archivePath)).file
                val destination = try {
                    AttachmentStorageKey.resolve(destinationRoot, attachment.storageKey)
                } catch (_: Exception) {
                    abort(RivenArchiveRestoreError.AttachmentIntegrityFailure(attachment.id))
                }
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = false)
                if (destination.length() != attachment.byteSize ||
                    sha256Hex(destination) != attachment.contentSha256
                ) {
                    abort(RivenArchiveRestoreError.AttachmentIntegrityFailure(attachment.id))
                }
            }
        }
    }

    private fun abort(error: RivenArchiveRestoreError): Nothing = throw RestoreAbort(error)

    private data class ExtractedEntry(
        val file: File,
        val byteSize: Long,
        val sha256: String,
    )

    private class RestoreAbort(val error: RivenArchiveRestoreError) : RuntimeException()
}
