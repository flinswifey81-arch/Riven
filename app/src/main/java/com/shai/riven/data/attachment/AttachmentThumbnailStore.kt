package com.shai.riven.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

const val MAX_ATTACHMENT_THUMBNAIL_BYTES = 256 * 1_024
const val MAX_ATTACHMENT_THUMBNAIL_EDGE = 512

fun interface ImageThumbnailGenerator {
    fun create(originalBytes: ByteArray): ByteArray?
}

object AndroidImageThumbnailGenerator : ImageThumbnailGenerator {
    override fun create(originalBytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_ATTACHMENT_THUMBNAIL_EDGE ||
            bounds.outHeight / sample > MAX_ATTACHMENT_THUMBNAIL_EDGE
        ) {
            if (sample > Int.MAX_VALUE / 2) return null
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            originalBytes,
            0,
            originalBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) return null
                output.toByteArray().takeIf { it.size in 1..MAX_ATTACHMENT_THUMBNAIL_BYTES }
            }
        } finally {
            bitmap.recycle()
        }
    }
}

interface AttachmentThumbnailStore {
    fun write(attachmentId: String, bytes: ByteArray)
    fun read(attachmentId: String): ByteArray?
    fun delete(attachmentId: String)
}

class FileAttachmentThumbnailStore(rootDirectory: File) : AttachmentThumbnailStore {
    private val root = rootDirectory.canonicalFile.also { it.mkdirs() }

    override fun write(attachmentId: String, bytes: ByteArray) {
        require(bytes.size in 1..MAX_ATTACHMENT_THUMBNAIL_BYTES)
        val target = fileFor(attachmentId)
        val temporary = File(root, ".${target.name}.tmp-${UUID.randomUUID()}")
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.flush()
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
    }

    override fun read(attachmentId: String): ByteArray? {
        val file = fileFor(attachmentId)
        if (!file.isFile || file.length() !in 1..MAX_ATTACHMENT_THUMBNAIL_BYTES.toLong()) return null
        return file.inputStream().use { input ->
            val bytes = input.readNBytes(MAX_ATTACHMENT_THUMBNAIL_BYTES + 1)
            bytes.takeIf { it.size in 1..MAX_ATTACHMENT_THUMBNAIL_BYTES }
        }
    }

    override fun delete(attachmentId: String) {
        val file = fileFor(attachmentId)
        if (file.exists() && !file.delete()) throw IllegalStateException("Unable to delete thumbnail")
    }

    private fun fileFor(attachmentId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(attachmentId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(root, "$digest.thumb")
    }

    companion object {
        fun fromContext(context: Context) = FileAttachmentThumbnailStore(
            File(context.applicationContext.filesDir, "riven_attachment_thumbnails"),
        )
    }
}
