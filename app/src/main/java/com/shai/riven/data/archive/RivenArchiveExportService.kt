package com.shai.riven.data.archive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException

internal const val RIVEN_ARCHIVE_STAGING_DIRECTORY_NAME = "riven_archive_staging"

class RivenArchiveExportService(
    context: Context,
    private val database: RivenDatabase,
    private val blobStore: FileAttachmentBlobStore = FileAttachmentBlobStore.fromContext(context),
    private val stagingRoot: File = File(
        context.applicationContext.noBackupFilesDir,
        RIVEN_ARCHIVE_STAGING_DIRECTORY_NAME,
    ),
    private val reminderDatabase: ReminderDatabase? = null,
) {
    private val appContext = context.applicationContext

    fun export(input: ExportRivenArchiveInput): ExportRivenArchiveResult {
        val working = File(stagingRoot, UUID.randomUUID().toString())
        var ownedReminderDatabase: ReminderDatabase? = null
        return try {
            check(working.mkdirs())
            val snapshot = File(working, "snapshot.db")
            createSnapshot(database.openHelper.writableDatabase, snapshot)
            val databaseVersion = databaseVersion(snapshot)
            val reminderSource = reminderDatabase ?: ReminderDatabase.build(appContext).also {
                ownedReminderDatabase = it
            }
            val reminderSnapshot = File(working, "reminder-snapshot.db")
            createSnapshot(reminderSource.openHelper.writableDatabase, reminderSnapshot)
            val reminderDatabaseVersion = databaseVersion(reminderSnapshot)
            val records = readAttachmentRows(snapshot).map { row -> exportAttachment(row, working) }
            val manifest = RivenArchiveManifest(
                archiveFormatVersion = RIVEN_ARCHIVE_FORMAT_VERSION,
                exportedAt = input.exportedAt,
                databaseSchemaVersion = databaseVersion,
                databaseSha256 = sha256Hex(snapshot),
                secretsIncluded = false,
                attachments = records.map { it.record },
                reminderDatabaseSchemaVersion = reminderDatabaseVersion,
                reminderDatabaseSha256 = sha256Hex(reminderSnapshot),
            )
            val archive = File(working, "riven-archive.zip")
            writeArchive(archive, manifest, snapshot, reminderSnapshot, records)
            FileInputStream(archive).use { source -> source.copyTo(input.output) }
            input.output.flush()
            ExportRivenArchiveResult.Exported(
                archiveFormatVersion = RIVEN_ARCHIVE_FORMAT_VERSION,
                databaseSchemaVersion = databaseVersion,
                attachmentCount = records.size,
                archiveByteCount = archive.length(),
            )
        } catch (abort: ExportAbort) {
            ExportRivenArchiveResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ExportRivenArchiveResult.Failure(
                RivenArchiveExportError.ExportIoFailure(failure.safeCauseType()),
            )
        } finally {
            ownedReminderDatabase?.close()
            working.deleteRecursively()
        }
    }

    private fun createSnapshot(
        source: androidx.sqlite.db.SupportSQLiteDatabase,
        target: File,
    ) {
        try {
            target.delete()
            val safePath = target.absolutePath.replace("'", "''")
            source.execSQL("VACUUM INTO '$safePath'")
            check(target.isFile && target.length() > 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ExportAbort(
                RivenArchiveExportError.ExportSnapshotFailure(failure.safeCauseType()),
            )
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
        throw ExportAbort(RivenArchiveExportError.ExportSnapshotFailure(failure.safeCauseType()))
    }

    private fun readAttachmentRows(file: File): List<SnapshotAttachment> = try {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery(
                "SELECT attachment_id, storage_key, state, byte_size, content_sha256 " +
                    "FROM attachments ORDER BY attachment_id",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SnapshotAttachment(
                                id = cursor.getString(0),
                                storageKey = cursor.getString(1),
                                state = AttachmentState.valueOf(cursor.getString(2)),
                                byteSize = if (cursor.isNull(3)) null else cursor.getLong(3),
                                contentSha256 = if (cursor.isNull(4)) null else cursor.getString(4),
                            ),
                        )
                    }
                }
            }
        }
    } catch (failure: Exception) {
        throw ExportAbort(RivenArchiveExportError.ExportSnapshotFailure(failure.safeCauseType()))
    }

    private fun exportAttachment(row: SnapshotAttachment, working: File): ExportedAttachment {
        val archivePath = RivenArchivePath.attachmentPath(row.id)
        val stagedBlob = File(working, archivePath)
        val exists = try {
            blobStore.exists(row.storageKey)
        } catch (_: Exception) {
            if (row.state == AttachmentState.AVAILABLE) {
                throw ExportAbort(RivenArchiveExportError.ExportAttachmentMismatch(row.id))
            }
            false
        }
        if (!exists) {
            if (row.state == AttachmentState.AVAILABLE) {
                throw ExportAbort(RivenArchiveExportError.ExportAttachmentMissing(row.id))
            }
            return ExportedAttachment(row.toRecord(blobPresent = false, archivePath = null), null)
        }
        val expectedSize = row.byteSize
        val expectedHash = row.contentSha256
        if (expectedSize == null || expectedSize < 0 || expectedHash == null || !LOWERCASE_SHA256.matches(expectedHash)) {
            if (row.state == AttachmentState.AVAILABLE) {
                throw ExportAbort(RivenArchiveExportError.ExportAttachmentMismatch(row.id))
            }
            return ExportedAttachment(row.toRecord(blobPresent = false, archivePath = null), null)
        }
        stagedBlob.parentFile?.mkdirs()
        val actual = try {
            blobStore.open(row.storageKey).use { source ->
                FileOutputStream(stagedBlob).use { target ->
                    copyAndHash(source, target, Long.MAX_VALUE) { error("unreachable") }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (row.state == AttachmentState.AVAILABLE) {
                throw ExportAbort(RivenArchiveExportError.ExportAttachmentMissing(row.id))
            }
            stagedBlob.delete()
            return ExportedAttachment(row.toRecord(blobPresent = false, archivePath = null), null)
        }
        if (actual.first != expectedSize || actual.second != expectedHash) {
            stagedBlob.delete()
            if (row.state == AttachmentState.AVAILABLE) {
                throw ExportAbort(RivenArchiveExportError.ExportAttachmentMismatch(row.id))
            }
            return ExportedAttachment(row.toRecord(blobPresent = false, archivePath = null), null)
        }
        return ExportedAttachment(row.toRecord(blobPresent = true, archivePath = archivePath), stagedBlob)
    }

    private fun writeArchive(
        archive: File,
        manifest: RivenArchiveManifest,
        snapshot: File,
        reminderSnapshot: File,
        attachments: List<ExportedAttachment>,
    ) {
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry(ARCHIVE_MANIFEST_PATH))
            zip.write(RivenArchiveManifestJson.encode(manifest))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ARCHIVE_DATABASE_PATH))
            FileInputStream(snapshot).use { it.copyTo(zip) }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ARCHIVE_REMINDER_DATABASE_PATH))
            FileInputStream(reminderSnapshot).use { it.copyTo(zip) }
            zip.closeEntry()

            attachments.forEach { attachment ->
                val file = attachment.stagedBlob ?: return@forEach
                zip.putNextEntry(ZipEntry(checkNotNull(attachment.record.archivePath)))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun SnapshotAttachment.toRecord(
        blobPresent: Boolean,
        archivePath: String?,
    ) = RivenArchiveAttachmentRecord(
        attachmentId = id,
        storageKey = storageKey,
        state = state,
        blobPresent = blobPresent,
        archivePath = archivePath,
        byteSize = byteSize,
        contentSha256 = contentSha256,
    )

    private data class SnapshotAttachment(
        val id: String,
        val storageKey: String,
        val state: AttachmentState,
        val byteSize: Long?,
        val contentSha256: String?,
    )

    private data class ExportedAttachment(
        val record: RivenArchiveAttachmentRecord,
        val stagedBlob: File?,
    )

    private class ExportAbort(val error: RivenArchiveExportError) : RuntimeException()

}
