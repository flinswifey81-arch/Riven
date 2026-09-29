package com.shai.riven.data.reset

import android.content.Context
import com.shai.riven.data.archive.RivenRestorePaths
import com.shai.riven.data.archive.RIVEN_ARCHIVE_STAGING_DIRECTORY_NAME
import com.shai.riven.data.archive.writeAtomically
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.credential.FileProviderCredentialStore
import com.shai.riven.data.persistence.RivenDatabase
import java.io.File
import org.json.JSONObject

internal enum class RivenResetJournalStage {
    PENDING,
    ERASING,
    FRESH_DATABASE_CREATED,
    VERIFIED,
}

internal data class RivenResetJournalRecord(
    val version: Int = JOURNAL_VERSION,
    val stage: RivenResetJournalStage,
) {
    companion object {
        const val JOURNAL_VERSION = 1
    }
}

internal class RivenResetPaths(
    context: Context,
    val resetRoot: File = File(context.applicationContext.noBackupFilesDir, RESET_DIRECTORY),
) {
    private val appContext = context.applicationContext

    val journalFile = File(resetRoot, "reset-journal.json")
    val canonicalDatabase: File = appContext.getDatabasePath(RivenDatabase.DATABASE_NAME)
    val canonicalWal = File(canonicalDatabase.parentFile, canonicalDatabase.name + "-wal")
    val canonicalShm = File(canonicalDatabase.parentFile, canonicalDatabase.name + "-shm")
    val canonicalAttachments = FileAttachmentBlobStore.rootForContext(appContext)
    val canonicalCredentials = FileProviderCredentialStore.rootForContext(appContext)
    val archiveStaging = File(
        appContext.noBackupFilesDir,
        RIVEN_ARCHIVE_STAGING_DIRECTORY_NAME,
    )
    val restoreState = File(appContext.noBackupFilesDir, RivenRestorePaths.RESTORE_DIRECTORY)

    companion object {
        const val RESET_DIRECTORY = "riven_reset"
    }
}

internal class RivenResetJournal(
    private val file: File,
) {
    fun exists(): Boolean = file.isFile

    fun read(): RivenResetJournalRecord? = runCatching {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        require(json.keysSet() == setOf("version", "stage"))
        RivenResetJournalRecord(
            version = json.getInt("version"),
            stage = RivenResetJournalStage.valueOf(json.getString("stage")),
        ).also { require(it.version == RivenResetJournalRecord.JOURNAL_VERSION) }
    }.getOrNull()

    fun write(record: RivenResetJournalRecord) {
        val bytes = JSONObject()
            .put("version", record.version)
            .put("stage", record.stage.name)
            .toString()
            .toByteArray(Charsets.UTF_8)
        writeAtomically(file, bytes)
    }

    fun delete() {
        if (file.exists() && !file.delete()) error("Reset journal could not be removed")
        file.parentFile?.delete()
    }

    private fun JSONObject.keysSet(): Set<String> = buildSet {
        val names = keys()
        while (names.hasNext()) add(names.next())
    }
}

object RivenResetGate {
    fun isPending(context: Context): Boolean = isPending(
        context,
        File(context.applicationContext.noBackupFilesDir, RivenResetPaths.RESET_DIRECTORY),
    )

    internal fun isPending(context: Context, resetRoot: File): Boolean =
        RivenResetPaths(context, resetRoot).journalFile.isFile
}
