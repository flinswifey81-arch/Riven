package com.shai.riven.data.attachment

import com.shai.riven.data.conversation.engine.ProviderImageContent
import com.shai.riven.data.conversation.engine.ProviderImageContentResolver
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentState
import java.security.MessageDigest

class AttachmentProviderImageResolver(
    private val attachments: AttachmentService,
    private val metadataDecoder: ImageMetadataDecoder = AndroidImageMetadataDecoder,
) : ProviderImageContentResolver {
    override fun resolve(attachmentId: String): ProviderImageContent? {
        val metadata = (attachments.attachmentMetadata(attachmentId) as?
            AttachmentMetadataResult.Success)?.attachment ?: return null
        if (metadata.kind != AttachmentKind.IMAGE ||
            metadata.state != AttachmentState.AVAILABLE ||
            metadata.mimeType !in SUPPORTED_IMAGE_MIME_TYPES ||
            metadata.byteSize == null || metadata.byteSize !in 1..MAX_IMPORTED_IMAGE_BYTES ||
            metadata.contentSha256.isNullOrBlank()
        ) {
            return null
        }
        val bytes = (attachments.readAvailableBlob(attachmentId) as?
            AttachmentBlobReadResult.Success)?.bytes ?: return null
        if (bytes.size.toLong() != metadata.byteSize) return null
        val actualHash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        if (actualHash != metadata.contentSha256) return null
        val decoded = metadataDecoder.decode(bytes) ?: return null
        if (decoded.mimeType != metadata.mimeType ||
            decoded.width <= 0 || decoded.height <= 0 ||
            decoded.width > MAX_IMPORTED_IMAGE_DIMENSION ||
            decoded.height > MAX_IMPORTED_IMAGE_DIMENSION ||
            decoded.width.toLong() * decoded.height.toLong() > MAX_IMPORTED_IMAGE_PIXELS
        ) {
            return null
        }
        return ProviderImageContent(
            attachmentId = attachmentId,
            mimeType = metadata.mimeType,
            bytes = bytes,
            width = decoded.width,
            height = decoded.height,
            contentSha256 = metadata.contentSha256,
        )
    }

    companion object {
        val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
