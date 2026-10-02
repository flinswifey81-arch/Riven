package com.shai.riven.data.runtime

import com.shai.riven.data.attachment.AttachmentThumbnailStore
import com.shai.riven.data.attachment.MAX_ATTACHMENT_THUMBNAIL_BYTES
import java.util.LinkedHashMap

internal const val MAX_RUNTIME_THUMBNAIL_CACHE_BYTES = 4 * 1_024 * 1_024

internal class BoundedImageThumbnailCache(
    private val store: AttachmentThumbnailStore?,
    private val maximumBytes: Int = MAX_RUNTIME_THUMBNAIL_CACHE_BYTES,
) {
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var totalBytes = 0

    @Synchronized
    fun read(attachmentId: String): ByteArray? {
        entries[attachmentId]?.let { return it }
        val loaded = store?.read(attachmentId)
            ?.takeIf { it.size in 1..MAX_ATTACHMENT_THUMBNAIL_BYTES }
            ?: return null
        while (entries.isNotEmpty() && totalBytes + loaded.size > maximumBytes) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
            totalBytes -= eldest.value.size
        }
        if (loaded.size > maximumBytes) return null
        entries[attachmentId] = loaded
        totalBytes += loaded.size
        return loaded
    }

    @Synchronized
    fun remove(attachmentId: String) {
        entries.remove(attachmentId)?.let { totalBytes -= it.size }
    }
}

internal class SnapshotThumbnailBudget(
    private var remainingBytes: Int = MAX_RUNTIME_THUMBNAIL_CACHE_BYTES,
) {
    fun accept(bytes: ByteArray?): ByteArray {
        if (bytes == null || bytes.size > remainingBytes) return ByteArray(0)
        remainingBytes -= bytes.size
        return bytes
    }
}
