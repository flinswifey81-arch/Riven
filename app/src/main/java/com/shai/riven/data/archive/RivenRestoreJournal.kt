package com.shai.riven.data.archive

import android.content.Context
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.attachment.FileAttachmentThumbnailStore
import com.shai.riven.data.attachment.RIVEN_ATTACHMENT_DIRECTORY_NAME
import com.shai.riven.data.credential.PROVIDER_CREDENTIAL_DIRECTORY_NAME
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.reminder.persistence.ReminderDatabase
import java.io.File
import org.json.JSONObject

internal enum class RivenRestoreJournalStage {
    STAGED,
    CURRENT_MOVED_ASIDE,
    NEW_INSTALLED,
    VERIFIED,
    ROLLING_BACK,
}

internal data class RivenRestoreJournalRecord(
    val version: Int = JOURNAL_VERSION,
    val stage: RivenRestoreJournalStage,
    val hadDatabase: Boolean,
    val hadWal: Boolean,
    val hadShm: Boolean,
    val hadAttachments: Boolean,
    val hadCredentials: Boolean,
    val includesReminderDatabase: Boolean = false,
    val hadReminderDatabase: Boolean = false,
    val hadReminderWal: Boolean = false,
    val hadReminderShm: Boolean = false,
) {
    companion object {
        const val JOURNAL_VERSION = 2
        const val MINIMUM_SUPPORTED_JOURNAL_VERSION = 1
    }
}

internal class RivenRestorePaths(
    context: Context,
    val restoreRoot: File = File(context.applicationContext.noBackupFilesDir, RESTORE_DIRECTORY),
) {
    private val appContext = context.applicationContext

    val pendingRoot = File(restoreRoot, "pending")
    val pendingPackage = File(pendingRoot, "package")
    val pendingDatabase = File(pendingPackage, ARCHIVE_DATABASE_PATH)
    val pendingReminderDatabase = File(pendingPackage, ARCHIVE_REMINDER_DATABASE_PATH)
    val pendingAttachmentRoot = File(pendingPackage, "attachment_root")
    val pendingManifest = File(pendingPackage, ARCHIVE_MANIFEST_PATH)

    val rollbackRoot = File(restoreRoot, "rollback")
    val rollbackDatabase = File(rollbackRoot, "database/${RivenDatabase.DATABASE_NAME}")
    val rollbackWal = File(rollbackRoot, "database/${RivenDatabase.DATABASE_NAME}-wal")
    val rollbackShm = File(rollbackRoot, "database/${RivenDatabase.DATABASE_NAME}-shm")
    val rollbackReminderDatabase = File(
        rollbackRoot,
        "database/${ReminderDatabase.DATABASE_NAME}",
    )
    val rollbackReminderWal = File(
        rollbackRoot,
        "database/${ReminderDatabase.DATABASE_NAME}-wal",
    )
    val rollbackReminderShm = File(
        rollbackRoot,
        "database/${ReminderDatabase.DATABASE_NAME}-shm",
    )
    val rollbackAttachments = File(
        rollbackRoot,
        "attachments/$RIVEN_ATTACHMENT_DIRECTORY_NAME",
    )
    val rollbackCredentials = File(
        rollbackRoot,
        "credentials/$PROVIDER_CREDENTIAL_DIRECTORY_NAME",
    )

    val journalFile = File(restoreRoot, "journal/restore-journal.json")

    val canonicalDatabase: File = appContext.getDatabasePath(RivenDatabase.DATABASE_NAME)
    val canonicalWal = File(canonicalDatabase.parentFile, canonicalDatabase.name + "-wal")
    val canonicalShm = File(canonicalDatabase.parentFile, canonicalDatabase.name + "-shm")
    val canonicalReminderDatabase: File = appContext.getDatabasePath(ReminderDatabase.DATABASE_NAME)
    val canonicalReminderWal = File(
        canonicalReminderDatabase.parentFile,
        canonicalReminderDatabase.name + "-wal",
    )
    val canonicalReminderShm = File(
        canonicalReminderDatabase.parentFile,
        canonicalReminderDatabase.name + "-shm",
    )
    val canonicalAttachments = FileAttachmentBlobStore.rootForContext(appContext)
    val canonicalAttachmentThumbnails = FileAttachmentThumbnailStore.rootForContext(appContext)
    val canonicalCredentials = File(
        appContext.noBackupFilesDir,
        PROVIDER_CREDENTIAL_DIRECTORY_NAME,
    )

    companion object {
        const val RESTORE_DIRECTORY = "riven_restore"
    }
}

internal class RivenRestoreJournal(
    private val file: File,
) {
    fun exists(): Boolean = file.isFile

    fun read(): RivenRestoreJournalRecord? = runCatching {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val sourceVersion = json.getInt("version")
        require(sourceVersion in
            RivenRestoreJournalRecord.MINIMUM_SUPPORTED_JOURNAL_VERSION..
                RivenRestoreJournalRecord.JOURNAL_VERSION
        )
        val versionOneKeys = setOf(
            "version",
            "stage",
            "hadDatabase",
            "hadWal",
            "hadShm",
            "hadAttachments",
            "hadCredentials",
        )
        val expectedKeys = if (sourceVersion == 1) {
            versionOneKeys
        } else {
            versionOneKeys + setOf(
                "includesReminderDatabase",
                "hadReminderDatabase",
                "hadReminderWal",
                "hadReminderShm",
            )
        }
        require(json.keysSet() == expectedKeys)
        RivenRestoreJournalRecord(
            version = RivenRestoreJournalRecord.JOURNAL_VERSION,
            stage = RivenRestoreJournalStage.valueOf(json.getString("stage")),
            hadDatabase = json.getBoolean("hadDatabase"),
            hadWal = json.getBoolean("hadWal"),
            hadShm = json.getBoolean("hadShm"),
            hadAttachments = json.getBoolean("hadAttachments"),
            hadCredentials = json.getBoolean("hadCredentials"),
            includesReminderDatabase = sourceVersion >= 2 &&
                json.getBoolean("includesReminderDatabase"),
            hadReminderDatabase = sourceVersion >= 2 && json.getBoolean("hadReminderDatabase"),
            hadReminderWal = sourceVersion >= 2 && json.getBoolean("hadReminderWal"),
            hadReminderShm = sourceVersion >= 2 && json.getBoolean("hadReminderShm"),
        )
    }.getOrNull()

    fun write(record: RivenRestoreJournalRecord) {
        val bytes = JSONObject()
            .put("version", RivenRestoreJournalRecord.JOURNAL_VERSION)
            .put("stage", record.stage.name)
            .put("hadDatabase", record.hadDatabase)
            .put("hadWal", record.hadWal)
            .put("hadShm", record.hadShm)
            .put("hadAttachments", record.hadAttachments)
            .put("hadCredentials", record.hadCredentials)
            .put("includesReminderDatabase", record.includesReminderDatabase)
            .put("hadReminderDatabase", record.hadReminderDatabase)
            .put("hadReminderWal", record.hadReminderWal)
            .put("hadReminderShm", record.hadReminderShm)
            .toString()
            .toByteArray(Charsets.UTF_8)
        writeAtomically(file, bytes)
    }

    fun delete() {
        if (file.exists() && !file.delete()) error("Restore journal could not be removed")
        file.parentFile?.delete()
    }

    private fun JSONObject.keysSet(): Set<String> = buildSet {
        val names = keys()
        while (names.hasNext()) add(names.next())
    }
}

object RivenRestoreGate {
    fun isPending(context: Context): Boolean {
        val paths = RivenRestorePaths(context)
        return paths.journalFile.isFile || paths.rollbackRoot.exists()
    }

    internal fun isPending(context: Context, restoreRoot: File): Boolean {
        val paths = RivenRestorePaths(context, restoreRoot)
        return paths.journalFile.isFile || paths.rollbackRoot.exists()
    }
}
