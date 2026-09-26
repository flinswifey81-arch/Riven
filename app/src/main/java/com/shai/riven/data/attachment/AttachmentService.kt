package com.shai.riven.data.attachment

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.GeneratedMediaProvenanceEntity
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.GeneratedMediaKind
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

class AttachmentService(
    private val database: RivenDatabase,
    private val blobStore: AttachmentBlobStore,
    private val idGenerator: AttachmentIdGenerator = AttachmentIdGenerator { UUID.randomUUID().toString() },
    private val afterFinalizationMutation: () -> Unit = {},
    private val afterStagingCleanupClaimMutation: () -> Unit = {},
) {
    private val dao = database.attachmentDao()

    suspend fun createImportedAttachment(input: ImportedAttachmentInput): AttachmentCreateResult =
        createAttachment(
            attachmentId = input.attachmentId ?: idGenerator.nextId(),
            kind = input.kind,
            mimeType = input.mimeType,
            source = AttachmentSource.SHAI_IMPORT,
            occurredAt = input.occurredAt,
            bytes = input.bytes,
            provenance = null,
            operation = AttachmentOperation.CREATE_IMPORTED,
        )

    suspend fun createGeneratedAttachment(input: GeneratedAttachmentInput): AttachmentCreateResult {
        validateGeneratedProvenance(input.provenance)?.let { error ->
            return AttachmentCreateResult.Failure(error)
        }
        return createAttachment(
            attachmentId = input.attachmentId ?: idGenerator.nextId(),
            kind = input.kind,
            mimeType = input.mimeType,
            source = AttachmentSource.RIVEN_GENERATED,
            occurredAt = input.occurredAt,
            bytes = input.bytes,
            provenance = input.provenance,
            operation = AttachmentOperation.CREATE_GENERATED,
        )
    }

    fun attachmentMetadata(attachmentId: String): AttachmentMetadataResult = try {
        val attachment = dao.attachment(attachmentId)
            ?: return AttachmentMetadataResult.Failure(AttachmentError.MissingAttachment(attachmentId))
        AttachmentMetadataResult.Success(attachment.toDomain())
    } catch (failure: Exception) {
        AttachmentMetadataResult.Failure(
            AttachmentError.StorageFailure(
                AttachmentOperation.READ_METADATA,
                failure::class.java.simpleName,
            ),
        )
    }

    fun orderedAvailableAttachmentsForMessage(messageId: String): MessageAttachmentsResult = try {
        MessageAttachmentsResult.Success(
            dao.availableAttachmentsForMessage(messageId).map { it.toDomain() },
        )
    } catch (failure: Exception) {
        MessageAttachmentsResult.Failure(
            AttachmentError.StorageFailure(
                AttachmentOperation.READ_MESSAGE_ATTACHMENTS,
                failure::class.java.simpleName,
            ),
        )
    }

    fun generatedProvenance(attachmentId: String): GeneratedMediaProvenanceResult = try {
        if (dao.attachment(attachmentId) == null) {
            GeneratedMediaProvenanceResult.Failure(AttachmentError.MissingAttachment(attachmentId))
        } else {
            GeneratedMediaProvenanceResult.Success(
                dao.generatedMediaProvenance(attachmentId)?.toDomain(),
            )
        }
    } catch (failure: Exception) {
        GeneratedMediaProvenanceResult.Failure(
            AttachmentError.StorageFailure(
                AttachmentOperation.READ_GENERATED_PROVENANCE,
                failure::class.java.simpleName,
            ),
        )
    }

    fun readAvailableBlob(attachmentId: String): AttachmentBlobReadResult {
        val attachment = try {
            dao.attachment(attachmentId)
        } catch (failure: Exception) {
            return AttachmentBlobReadResult.Failure(
                AttachmentError.StorageFailure(
                    AttachmentOperation.READ_BLOB,
                    failure::class.java.simpleName,
                ),
            )
        } ?: return AttachmentBlobReadResult.Failure(AttachmentError.MissingAttachment(attachmentId))
        if (attachment.state != AttachmentState.AVAILABLE) {
            return AttachmentBlobReadResult.Failure(
                AttachmentError.AttachmentUnavailable(attachmentId, attachment.state),
            )
        }
        return try {
            AttachmentBlobReadResult.Success(blobStore.open(attachment.storageKey).use { it.readBytes() })
        } catch (failure: Exception) {
            AttachmentBlobReadResult.Failure(
                AttachmentError.BlobReadFailure(attachmentId, failure::class.java.simpleName),
            )
        }
    }

    suspend fun cleanupStagingAttachment(
        attachmentId: String,
        occurredAt: Long,
    ): AttachmentCleanupResult = cleanupAttachment(
        attachmentId = attachmentId,
        requiredState = AttachmentState.STAGING,
        operation = AttachmentOperation.CLEANUP_STAGING,
        stagingClaimedAt = occurredAt,
    )

    suspend fun finalizePendingDeletion(attachmentId: String): AttachmentCleanupResult =
        cleanupAttachment(
            attachmentId = attachmentId,
            requiredState = AttachmentState.DELETE_PENDING,
            operation = AttachmentOperation.FINALIZE_PENDING_DELETION,
        )

    private suspend fun createAttachment(
        attachmentId: String,
        kind: com.shai.riven.data.persistence.model.AttachmentKind,
        mimeType: String,
        source: AttachmentSource,
        occurredAt: Long,
        bytes: AttachmentByteSource,
        provenance: GeneratedMediaProvenanceInput?,
        operation: AttachmentOperation,
    ): AttachmentCreateResult {
        if (!isValidMimeType(mimeType)) {
            return AttachmentCreateResult.Failure(AttachmentError.InvalidMimeType(mimeType))
        }
        val storageKey = storageKeyFor(attachmentId)
        val staging = AttachmentEntity(
            id = attachmentId,
            kind = kind,
            mimeType = mimeType,
            state = AttachmentState.STAGING,
            storageKey = storageKey,
            byteSize = null,
            contentSha256 = null,
            source = source,
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )
        try {
            database.withTransaction {
                if (dao.attachment(attachmentId) != null) {
                    abort(AttachmentError.DuplicateAttachmentId(attachmentId))
                }
                dao.insertAttachment(staging)
            }
        } catch (abort: AttachmentAbort) {
            return AttachmentCreateResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (constraint: SQLiteConstraintException) {
            return AttachmentCreateResult.Failure(
                if (dao.attachment(attachmentId) != null) {
                    AttachmentError.DuplicateAttachmentId(attachmentId)
                } else {
                    AttachmentError.StorageFailure(operation, constraint::class.java.simpleName)
                },
            )
        } catch (failure: Exception) {
            return AttachmentCreateResult.Failure(
                AttachmentError.StorageFailure(operation, failure::class.java.simpleName),
            )
        }

        val blob = try {
            blobStore.write(storageKey, bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            cleanupFailedWrite(attachmentId, storageKey)
            return AttachmentCreateResult.Failure(
                AttachmentError.BlobWriteFailure(attachmentId, failure::class.java.simpleName),
            )
        }
        if (blob.byteSize < 0 || !LOWERCASE_SHA256.matches(blob.contentSha256)) {
            return AttachmentCreateResult.Failure(
                AttachmentError.StorageFailure(operation, INVALID_BLOB_METADATA),
            )
        }

        return try {
            val available = database.withTransaction {
                val current = dao.attachment(attachmentId)
                    ?: abort(AttachmentError.MissingAttachment(attachmentId))
                if (current.state != AttachmentState.STAGING) {
                    abort(AttachmentError.AttachmentUnavailable(attachmentId, current.state))
                }
                val finalized = current.copy(
                    state = AttachmentState.AVAILABLE,
                    byteSize = blob.byteSize,
                    contentSha256 = blob.contentSha256,
                    updatedAt = occurredAt,
                )
                check(dao.updateAttachment(finalized) == 1)
                provenance?.let { dao.insertGeneratedMediaProvenance(it.toEntity(attachmentId, occurredAt)) }
                afterFinalizationMutation()
                finalized
            }
            AttachmentCreateResult.Success(available.toDomain())
        } catch (abort: AttachmentAbort) {
            AttachmentCreateResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AttachmentCreateResult.Failure(
                AttachmentError.StorageFailure(operation, failure::class.java.simpleName),
            )
        }
    }

    private suspend fun cleanupAttachment(
        attachmentId: String,
        requiredState: AttachmentState,
        operation: AttachmentOperation,
        stagingClaimedAt: Long? = null,
    ): AttachmentCleanupResult {
        val attachment = try {
            database.withTransaction {
                val current = dao.attachment(attachmentId)
                    ?: abort(AttachmentError.MissingAttachment(attachmentId))
                if (current.state != requiredState) {
                    abort(AttachmentError.AttachmentUnavailable(attachmentId, current.state))
                }
                val references = dao.messageReferenceCount(attachmentId)
                if (references != 0) {
                    abort(AttachmentError.AttachmentStillReferenced(attachmentId, references))
                }
                if (dao.derivedArtifactDependencyCount(attachmentId) != 0) {
                    abort(AttachmentError.StorageFailure(operation, REMAINING_DERIVED_DEPENDENCIES))
                }
                if (requiredState == AttachmentState.STAGING) {
                    val claimed = current.copy(
                        state = AttachmentState.DELETE_PENDING,
                        updatedAt = checkNotNull(stagingClaimedAt),
                    )
                    check(dao.updateAttachment(claimed) == 1)
                    afterStagingCleanupClaimMutation()
                    claimed
                } else {
                    current
                }
            }
        } catch (abort: AttachmentAbort) {
            return AttachmentCleanupResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return AttachmentCleanupResult.Failure(
                AttachmentError.StorageFailure(operation, failure::class.java.simpleName),
            )
        }
        try {
            blobStore.delete(attachment.storageKey)
        } catch (failure: Exception) {
            return AttachmentCleanupResult.Failure(
                AttachmentError.BlobDeleteFailure(attachmentId, failure::class.java.simpleName),
            )
        }
        return try {
            database.withTransaction {
                val current = dao.attachment(attachmentId)
                    ?: return@withTransaction
                if (current.state != AttachmentState.DELETE_PENDING) {
                    abort(AttachmentError.AttachmentUnavailable(attachmentId, current.state))
                }
                val currentReferences = dao.messageReferenceCount(attachmentId)
                if (currentReferences != 0) {
                    abort(AttachmentError.AttachmentStillReferenced(attachmentId, currentReferences))
                }
                if (dao.derivedArtifactDependencyCount(attachmentId) != 0) {
                    abort(AttachmentError.StorageFailure(operation, REMAINING_DERIVED_DEPENDENCIES))
                }
                check(
                    dao.deleteUnreferencedAttachmentInState(
                        attachmentId,
                        AttachmentState.DELETE_PENDING,
                    ) == 1,
                )
            }
            AttachmentCleanupResult.Removed(attachmentId)
        } catch (abort: AttachmentAbort) {
            AttachmentCleanupResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AttachmentCleanupResult.Failure(
                AttachmentError.StorageFailure(operation, failure::class.java.simpleName),
            )
        }
    }

    private suspend fun cleanupFailedWrite(attachmentId: String, storageKey: String) {
        try {
            blobStore.delete(storageKey)
        } catch (_: Exception) {
            return
        }
        try {
            database.withTransaction {
                dao.deleteUnreferencedAttachmentInState(attachmentId, AttachmentState.STAGING)
            }
        } catch (_: Exception) {
            // A recoverable STAGING record is safer than hiding a failed cleanup.
        }
    }

    private fun validateGeneratedProvenance(
        provenance: GeneratedMediaProvenanceInput,
    ): AttachmentError.InvalidGeneratedMediaProvenance? {
        if (provenance.generationKind == GeneratedMediaKind.RIVEN_SELFIE_STYLE) {
            if (provenance.appearanceAuthority != RIVEN_APPEARANCE_CANON_AUTHORITY) {
                return AttachmentError.InvalidGeneratedMediaProvenance(
                    "RIVEN_SELFIE_STYLE requires RIVEN_APPEARANCE_CANON",
                )
            }
            if (provenance.appearanceAuthorityFingerprint.isNullOrBlank()) {
                return AttachmentError.InvalidGeneratedMediaProvenance(
                    "RIVEN_SELFIE_STYLE requires a nonblank appearance authority fingerprint",
                )
            }
        }
        return null
    }

    private fun GeneratedMediaProvenanceInput.toEntity(
        attachmentId: String,
        generatedAt: Long,
    ) = GeneratedMediaProvenanceEntity(
        attachmentId = attachmentId,
        generationKind = generationKind,
        generatorProvider = generatorProvider,
        generatorModel = generatorModel,
        providerRequestId = providerRequestId,
        appearanceAuthority = appearanceAuthority,
        appearanceAuthorityFingerprint = appearanceAuthorityFingerprint,
        requestFingerprint = requestFingerprint,
        generatedAt = generatedAt,
    )

    private fun AttachmentEntity.toDomain() = AttachmentMetadata(
        attachmentId = id,
        kind = kind,
        mimeType = mimeType,
        state = state,
        storageKey = storageKey,
        byteSize = byteSize,
        contentSha256 = contentSha256,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun GeneratedMediaProvenanceEntity.toDomain() = GeneratedMediaProvenance(
        attachmentId = attachmentId,
        generationKind = generationKind,
        generatorProvider = generatorProvider,
        generatorModel = generatorModel,
        providerRequestId = providerRequestId,
        appearanceAuthority = appearanceAuthority,
        appearanceAuthorityFingerprint = appearanceAuthorityFingerprint,
        requestFingerprint = requestFingerprint,
        generatedAt = generatedAt,
    )

    private fun storageKeyFor(attachmentId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(attachmentId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "attachments/$digest.blob"
    }

    private fun isValidMimeType(mimeType: String): Boolean =
        mimeType == mimeType.trim() && MIME_TYPE.matches(mimeType)

    private fun abort(error: AttachmentError): Nothing = throw AttachmentAbort(error)

    private class AttachmentAbort(val error: AttachmentError) : RuntimeException()

    private companion object {
        val MIME_TYPE = Regex("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*")
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
        const val INVALID_BLOB_METADATA = "InvalidBlobMetadata"
        const val REMAINING_DERIVED_DEPENDENCIES = "RemainingDerivedAttachmentDependencies"
    }
}
