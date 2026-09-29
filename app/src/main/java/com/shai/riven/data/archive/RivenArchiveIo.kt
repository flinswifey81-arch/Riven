package com.shai.riven.data.archive

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object RivenArchivePath {
    fun isSafe(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
        if ('\\' in path || ':' in path) return false
        val components = path.split('/')
        return components.none { it.isBlank() || it == "." || it == ".." }
    }

    fun isAllowlistedEntry(path: String): Boolean = when {
        path == ARCHIVE_MANIFEST_PATH -> true
        path == ARCHIVE_DATABASE_PATH -> true
        path.startsWith(ARCHIVE_ATTACHMENT_PREFIX) -> {
            val name = path.removePrefix(ARCHIVE_ATTACHMENT_PREFIX)
            name.matches(Regex("[0-9a-f]{64}\\.blob"))
        }
        else -> false
    }

    fun attachmentPath(attachmentId: String): String =
        ARCHIVE_ATTACHMENT_PREFIX + sha256Hex(attachmentId.toByteArray(Charsets.UTF_8)) + ".blob"
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun sha256Hex(file: File): String = FileInputStream(file).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun copyAndHash(
    input: InputStream,
    output: OutputStream,
    maximumBytes: Long,
    onLimit: () -> Nothing,
): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var count = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        count += read
        if (count > maximumBytes) onLimit()
        output.write(buffer, 0, read)
        digest.update(buffer, 0, read)
    }
    return count to digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun moveReplacing(source: File, target: File) {
    target.parentFile?.mkdirs()
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun writeAtomically(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, ".${target.name}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        moveReplacing(temporary, target)
    } finally {
        temporary.delete()
    }
}

internal fun Throwable.safeCauseType(): String = this::class.java.simpleName.ifBlank { "Failure" }

internal fun checkpointSelfContainedDatabase(file: File) {
    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
        sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            while (cursor.moveToNext()) {
                // Consuming the result completes the checkpoint operation.
            }
        }
        val mode = sqlite.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        check(mode.equals("delete", ignoreCase = true))
    }
    listOf(File(file.path + "-wal"), File(file.path + "-shm")).forEach { sidecar ->
        check(!sidecar.exists() || sidecar.delete())
    }
}
