package com.shai.riven.data.attachment

import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.GeneratedMediaKind
import java.io.ByteArrayInputStream
import java.io.InputStream

enum class AttachmentOperation {
    CREATE_IMPORTED,
    CREATE_GENERATED,
    READ_METADATA,
    READ_MESSAGE_ATTACHMENTS,
    READ_GENERATED_PROVENANCE,
    READ_BLOB,
    CLEANUP_STAGING,
    FINALIZE_PENDING_DELETION,
}

sealed interface AttachmentError {
    data class MissingAttachment(val attachmentId: String) : AttachmentError
    data class AttachmentUnavailable(
        val attachmentId: String,
        val state: AttachmentState,
    ) : AttachmentError

    data class AttachmentStillReferenced(
        val attachmentId: String,
        val messageReferenceCount: Int,
    ) : AttachmentError

    data class DuplicateAttachmentId(val attachmentId: String) : AttachmentError
    data class InvalidMimeType(val mimeType: String) : AttachmentError
    data class InvalidGeneratedMediaProvenance(val reason: String) : AttachmentError
    data class BlobWriteFailure(val attachmentId: String, val causeType: String) : AttachmentError
    data class BlobReadFailure(val attachmentId: String, val causeType: String) : AttachmentError
    data class BlobDeleteFailure(val attachmentId: String, val causeType: String) : AttachmentError
    data class StorageFailure(
        val operation: AttachmentOperation,
        val causeType: String,
    ) : AttachmentError
}

fun interface AttachmentByteSource {
    fun openStream(): InputStream

    companion object {
        fun fromBytes(bytes: ByteArray): AttachmentByteSource =
            AttachmentByteSource { ByteArrayInputStream(bytes) }
    }
}

data class ImportedAttachmentInput(
    val kind: AttachmentKind,
    val mimeType: String,
    val occurredAt: Long,
    val bytes: AttachmentByteSource,
    val attachmentId: String? = null,
)

data class GeneratedMediaProvenanceInput(
    val generationKind: GeneratedMediaKind,
    val generatorProvider: String? = null,
    val generatorModel: String? = null,
    val providerRequestId: String? = null,
    val appearanceAuthority: String? = null,
    val appearanceAuthorityFingerprint: String? = null,
    val requestFingerprint: String? = null,
)

data class GeneratedAttachmentInput(
    val kind: AttachmentKind,
    val mimeType: String,
    val provenance: GeneratedMediaProvenanceInput,
    val occurredAt: Long,
    val bytes: AttachmentByteSource,
    val attachmentId: String? = null,
)

data class AttachmentMetadata(
    val attachmentId: String,
    val kind: AttachmentKind,
    val mimeType: String,
    val state: AttachmentState,
    val storageKey: String,
    val byteSize: Long?,
    val contentSha256: String?,
    val source: AttachmentSource,
    val createdAt: Long,
    val updatedAt: Long,
)

data class GeneratedMediaProvenance(
    val attachmentId: String,
    val generationKind: GeneratedMediaKind,
    val generatorProvider: String?,
    val generatorModel: String?,
    val providerRequestId: String?,
    val appearanceAuthority: String?,
    val appearanceAuthorityFingerprint: String?,
    val requestFingerprint: String?,
    val generatedAt: Long,
)

sealed interface AttachmentCreateResult {
    data class Success(val attachment: AttachmentMetadata) : AttachmentCreateResult
    data class Failure(val error: AttachmentError) : AttachmentCreateResult
}

sealed interface AttachmentMetadataResult {
    data class Success(val attachment: AttachmentMetadata) : AttachmentMetadataResult
    data class Failure(val error: AttachmentError) : AttachmentMetadataResult
}

sealed interface MessageAttachmentsResult {
    data class Success(val attachments: List<AttachmentMetadata>) : MessageAttachmentsResult
    data class Failure(val error: AttachmentError) : MessageAttachmentsResult
}

sealed interface GeneratedMediaProvenanceResult {
    data class Success(val provenance: GeneratedMediaProvenance?) : GeneratedMediaProvenanceResult
    data class Failure(val error: AttachmentError) : GeneratedMediaProvenanceResult
}

sealed interface AttachmentBlobReadResult {
    data class Success(val bytes: ByteArray) : AttachmentBlobReadResult
    data class Failure(val error: AttachmentError) : AttachmentBlobReadResult
}

sealed interface AttachmentCleanupResult {
    data class Removed(val attachmentId: String) : AttachmentCleanupResult
    data class Failure(val error: AttachmentError) : AttachmentCleanupResult
}

fun interface AttachmentIdGenerator {
    fun nextId(): String
}

const val RIVEN_APPEARANCE_CANON_AUTHORITY = "RIVEN_APPEARANCE_CANON"
