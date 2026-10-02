package com.shai.riven.data.attachment

import android.graphics.BitmapFactory
import com.shai.riven.data.persistence.model.AttachmentKind
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException

const val MAX_IMPORTED_IMAGE_BYTES = 8L * 1_024L * 1_024L
const val MAX_IMPORTED_IMAGE_DIMENSION = 8_192
const val MAX_IMPORTED_IMAGE_PIXELS = 40_000_000L

data class SelectedImageInput(
    val declaredMimeType: String?,
    val declaredByteSize: Long?,
    val openStream: () -> InputStream,
    val occurredAt: Long,
)

data class ImportedImageAttachment(
    val metadata: AttachmentMetadata,
    val width: Int,
    val height: Int,
)

data class DecodedImageMetadata(
    val mimeType: String,
    val width: Int,
    val height: Int,
)

fun interface ImageMetadataDecoder {
    fun decode(bytes: ByteArray): DecodedImageMetadata?
}

object AndroidImageMetadataDecoder : ImageMetadataDecoder {
    override fun decode(bytes: ByteArray): DecodedImageMetadata? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val mimeType = options.outMimeType?.lowercase() ?: return null
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (options.outWidth / sample > VALIDATION_DECODE_EDGE ||
            options.outHeight / sample > VALIDATION_DECODE_EDGE
        ) {
            if (sample > Int.MAX_VALUE / 2) return null
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        decoded.recycle()
        return DecodedImageMetadata(mimeType, options.outWidth, options.outHeight)
    }

    private const val VALIDATION_DECODE_EDGE = 512
}

sealed interface ImageAttachmentImportResult {
    data class Success(val image: ImportedImageAttachment) : ImageAttachmentImportResult
    data class Failure(val error: ImageAttachmentImportError) : ImageAttachmentImportResult
}

sealed interface ImageAttachmentImportError {
    data object UnsupportedMimeType : ImageAttachmentImportError
    data object Oversized : ImageAttachmentImportError
    data object CorruptOrUndecodable : ImageAttachmentImportError
    data object UnsafeDimensions : ImageAttachmentImportError
    data class ReadFailure(val causeType: String) : ImageAttachmentImportError
    data class StoreFailure(val causeType: String) : ImageAttachmentImportError
}

/**
 * Copies a selected image through a bounded in-memory snapshot before it reaches durable storage.
 * The snapshot prevents a mutable content URI from changing between validation and persistence.
 */
class ImageAttachmentImportService(
    private val attachments: AttachmentService,
    private val metadataDecoder: ImageMetadataDecoder = AndroidImageMetadataDecoder,
) {
    suspend fun import(input: SelectedImageInput): ImageAttachmentImportResult {
        val declaredMime = input.declaredMimeType?.lowercase()?.substringBefore(';')?.trim()
        if (declaredMime != null && declaredMime !in SUPPORTED_MIME_TYPES) {
            return ImageAttachmentImportResult.Failure(ImageAttachmentImportError.UnsupportedMimeType)
        }
        if (input.declaredByteSize != null &&
            (input.declaredByteSize < 0L || input.declaredByteSize > MAX_IMPORTED_IMAGE_BYTES)
        ) {
            return ImageAttachmentImportResult.Failure(ImageAttachmentImportError.Oversized)
        }
        val bytes = try {
            input.openStream().use(::readBounded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ImageTooLargeException) {
            return ImageAttachmentImportResult.Failure(ImageAttachmentImportError.Oversized)
        } catch (failure: Exception) {
            return ImageAttachmentImportResult.Failure(
                ImageAttachmentImportError.ReadFailure(failure::class.java.simpleName),
            )
        }
        if (bytes.isEmpty()) {
            return ImageAttachmentImportResult.Failure(ImageAttachmentImportError.CorruptOrUndecodable)
        }
        val decoded = metadataDecoder.decode(bytes)
            ?: return ImageAttachmentImportResult.Failure(
                ImageAttachmentImportError.CorruptOrUndecodable,
            )
        val actualMime = decoded.mimeType.lowercase().let { MIME_ALIASES[it] ?: it }
        if (actualMime !in SUPPORTED_MIME_TYPES ||
            declaredMime?.let { MIME_ALIASES[it] ?: it }?.let { it != actualMime } == true
        ) {
            return ImageAttachmentImportResult.Failure(ImageAttachmentImportError.UnsupportedMimeType)
        }
        val width = decoded.width
        val height = decoded.height
        if (width <= 0 || height <= 0) {
            return ImageAttachmentImportResult.Failure(ImageAttachmentImportError.CorruptOrUndecodable)
        }
        if (width > MAX_IMPORTED_IMAGE_DIMENSION || height > MAX_IMPORTED_IMAGE_DIMENSION ||
            width.toLong() * height.toLong() > MAX_IMPORTED_IMAGE_PIXELS
        ) {
            return ImageAttachmentImportResult.Failure(ImageAttachmentImportError.UnsafeDimensions)
        }
        return when (
            val stored = attachments.createImportedAttachment(
                ImportedAttachmentInput(
                    kind = AttachmentKind.IMAGE,
                    mimeType = actualMime,
                    occurredAt = input.occurredAt,
                    bytes = AttachmentByteSource.fromBytes(bytes),
                ),
            )
        ) {
            is AttachmentCreateResult.Success -> ImageAttachmentImportResult.Success(
                ImportedImageAttachment(stored.attachment, width, height),
            )
            is AttachmentCreateResult.Failure -> ImageAttachmentImportResult.Failure(
                ImageAttachmentImportError.StoreFailure(stored.error::class.java.simpleName),
            )
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_IMPORTED_IMAGE_BYTES) throw ImageTooLargeException()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class ImageTooLargeException : IllegalArgumentException()

    private companion object {
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val MIME_ALIASES = mapOf(
            "image/jpg" to "image/jpeg",
            "image/jpeg" to "image/jpeg",
            "image/png" to "image/png",
            "image/webp" to "image/webp",
        )
    }
}
