package com.shai.riven.data.attachment

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class AttachmentBlobWriteResult(
    val byteSize: Long,
    val contentSha256: String,
)

interface AttachmentBlobStore {
    fun write(storageKey: String, source: AttachmentByteSource): AttachmentBlobWriteResult
    fun exists(storageKey: String): Boolean
    fun open(storageKey: String): InputStream
    fun delete(storageKey: String)
}

class FileAttachmentBlobStore(
    rootDirectory: File,
) : AttachmentBlobStore {
    private val root = rootDirectory.canonicalFile.also { it.mkdirs() }

    override fun write(storageKey: String, source: AttachmentByteSource): AttachmentBlobWriteResult {
        val target = resolve(storageKey)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp-${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        try {
            source.openStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        byteCount += read
                    }
                    output.fd.sync()
                }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
        return AttachmentBlobWriteResult(
            byteSize = byteCount,
            contentSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    override fun exists(storageKey: String): Boolean = resolve(storageKey).isFile

    override fun open(storageKey: String): InputStream = FileInputStream(resolve(storageKey))

    override fun delete(storageKey: String) {
        val target = resolve(storageKey)
        if (!target.exists()) return
        if (!target.delete()) {
            throw IllegalStateException("Unable to delete attachment blob")
        }
    }

    private fun resolve(storageKey: String): File {
        require(storageKey.isNotBlank()) { "Storage key is blank" }
        val candidate = File(storageKey)
        require(!candidate.isAbsolute) { "Storage key must be relative" }
        val resolved = File(root, storageKey).canonicalFile
        val rootPrefix = root.path + File.separator
        require(resolved.path.startsWith(rootPrefix)) { "Storage key escapes attachment root" }
        return resolved
    }

    companion object {
        fun fromContext(context: Context): FileAttachmentBlobStore =
            FileAttachmentBlobStore(File(context.filesDir, "riven_attachments"))
    }
}
