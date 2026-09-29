package com.shai.riven.data.archive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.shai.riven.data.attachment.AttachmentStorageKey
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.model.AttachmentState
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
) {
    private val appContext = context.applicationContext
    private val paths = RivenRestorePaths(appContext, restoreRoot)
    private val journal = RivenRestoreJournal(paths.journalFile)

    fun stageRestore(input: StageRivenRestoreInput): StageRivenRestoreResult {
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
            validateManifestAndEntries(manifest, databaseEntry, extracted)

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
                    ARCHIVE_DATABASE_PATH -> limits.maximumDatabaseBytes
                    else -> limits.maximumAttachmentBytes
                }
                val destination = when (entry.name) {
                    ARCHIVE_MANIFEST_PATH -> File(working, ARCHIVE_MANIFEST_PATH)
                    ARCHIVE_DATABASE_PATH -> File(working, ARCHIVE_DATABASE_PATH)
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
                                ARCHIVE_DATABASE_PATH -> ArchiveLimit.DATABASE_BYTES
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
        extracted: Map<String, ExtractedEntry>,
    ) {
        if (manifest.archiveFormatVersion != RIVEN_ARCHIVE_FORMAT_VERSION) {
            abort(RivenArchiveRestoreError.UnsupportedArchiveVersion)
        }
        if (manifest.secretsIncluded || !LOWERCASE_SHA256.matches(manifest.databaseSha256)) {
            abort(RivenArchiveRestoreError.MalformedManifest)
        }
        if (database.sha256 != manifest.databaseSha256) {
            abort(RivenArchiveRestoreError.ArchiveIntegrityFailure("DATABASE_HASH"))
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
