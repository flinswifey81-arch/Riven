package com.shai.riven.data.provider

import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ProviderProfileCapabilityEntity
import com.shai.riven.data.persistence.entity.ProviderProfileEntity
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID
import kotlinx.coroutines.CancellationException

class ProviderProfileService(
    private val database: RivenDatabase,
    private val idGenerator: ProviderProfileIdGenerator = ProviderProfileIdGenerator {
        UUID.randomUUID().toString()
    },
    private val afterCapabilityReplacement: () -> Unit = {},
) {
    private val dao = database.providerProfileDao()

    suspend fun create(input: CreateProviderProfileInput): CreateProviderProfileResult =
        executeCreate {
            val profileId = input.profileId ?: idGenerator.newProfileId()
            validateProfileId(profileId)
            validateFields(
                displayName = input.displayName,
                adapterId = input.adapterId,
                endpointBaseUrl = input.endpointBaseUrl,
                modelId = input.modelId,
                credentialSlotId = input.credentialSlotId,
            )
            val capabilities = validateCapabilities(input.capabilities)
            if (dao.profile(profileId) != null) {
                abort(ProviderProfileError.DuplicateProfileId(profileId))
            }

            val entity = ProviderProfileEntity(
                profileId = profileId,
                displayName = input.displayName,
                adapterId = input.adapterId,
                endpointBaseUrl = input.endpointBaseUrl,
                modelId = input.modelId,
                credentialSlotId = input.credentialSlotId,
                isEnabled = input.isEnabled,
                revision = FIRST_REVISION,
                createdAt = input.occurredAt,
                updatedAt = input.occurredAt,
            )
            dao.insertProfile(entity)
            dao.insertCapabilities(capabilities.toEntities(profileId, input.occurredAt))
            CreateProviderProfileResult.Success(entity.toSnapshot(capabilities))
        }

    suspend fun update(input: UpdateProviderProfileInput): UpdateProviderProfileResult =
        executeUpdate {
            validateProfileId(input.profileId)
            validateFields(
                displayName = input.displayName,
                adapterId = input.adapterId,
                endpointBaseUrl = input.endpointBaseUrl,
                modelId = input.modelId,
                credentialSlotId = input.credentialSlotId,
            )
            val capabilities = validateCapabilities(input.capabilities)
            val current = dao.profile(input.profileId)
                ?: abort(ProviderProfileError.MissingProfile(input.profileId))
            if (input.expectedRevision != current.revision) {
                abort(
                    ProviderProfileError.StaleRevision(
                        expected = input.expectedRevision,
                        actual = current.revision,
                    ),
                )
            }
            if (current.revision == Long.MAX_VALUE) {
                abort(ProviderProfileError.RevisionOverflow)
            }

            val updated = current.copy(
                displayName = input.displayName,
                adapterId = input.adapterId,
                endpointBaseUrl = input.endpointBaseUrl,
                modelId = input.modelId,
                credentialSlotId = input.credentialSlotId,
                isEnabled = input.isEnabled,
                revision = current.revision + 1,
                updatedAt = input.occurredAt,
            )
            check(dao.updateProfile(updated) == 1)
            dao.deleteCapabilities(input.profileId)
            dao.insertCapabilities(capabilities.toEntities(input.profileId, input.occurredAt))
            afterCapabilityReplacement()
            UpdateProviderProfileResult.Success(updated.toSnapshot(capabilities))
        }

    suspend fun profile(profileId: String): ProviderProfileReadResult = try {
        validateProfileId(profileId)
        database.withTransaction {
            val profile = dao.profile(profileId)
                ?: abort(ProviderProfileError.MissingProfile(profileId))
            ProviderProfileReadResult.Success(
                profile.toSnapshot(dao.capabilities(profileId).toDeterministicSet()),
            )
        }
    } catch (abort: ProfileAbort) {
        ProviderProfileReadResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ProviderProfileReadResult.Failure(failure.toStorageFailure(ProviderProfileOperation.READ))
    }

    suspend fun allProfiles(): ProviderProfilesReadResult = try {
        database.withTransaction {
            ProviderProfilesReadResult.Success(
                dao.allProfiles().map { profile ->
                    profile.toSnapshot(dao.capabilities(profile.profileId).toDeterministicSet())
                },
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ProviderProfilesReadResult.Failure(failure.toStorageFailure(ProviderProfileOperation.LIST))
    }

    private suspend fun executeCreate(
        block: suspend () -> CreateProviderProfileResult,
    ): CreateProviderProfileResult = try {
        database.withTransaction { block() }
    } catch (abort: ProfileAbort) {
        CreateProviderProfileResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        CreateProviderProfileResult.Failure(failure.toStorageFailure(ProviderProfileOperation.CREATE))
    }

    private suspend fun executeUpdate(
        block: suspend () -> UpdateProviderProfileResult,
    ): UpdateProviderProfileResult = try {
        database.withTransaction { block() }
    } catch (abort: ProfileAbort) {
        UpdateProviderProfileResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        UpdateProviderProfileResult.Failure(failure.toStorageFailure(ProviderProfileOperation.UPDATE))
    }

    private fun validateProfileId(value: String) {
        if (value.isBlank() || value.length > MAX_PROFILE_ID_LENGTH) {
            abort(ProviderProfileError.InvalidProfileId)
        }
    }

    private fun validateFields(
        displayName: String,
        adapterId: String,
        endpointBaseUrl: String,
        modelId: String,
        credentialSlotId: String?,
    ) {
        if (displayName.isBlank() || displayName.length > MAX_DISPLAY_NAME_LENGTH) {
            abort(ProviderProfileError.InvalidDisplayName)
        }
        if (!ADAPTER_ID_PATTERN.matches(adapterId) || adapterId.length > MAX_ADAPTER_ID_LENGTH) {
            abort(ProviderProfileError.InvalidAdapterId)
        }
        validateEndpoint(endpointBaseUrl)
        if (modelId.isBlank() || modelId.length > MAX_MODEL_ID_LENGTH) {
            abort(ProviderProfileError.InvalidModelId)
        }
        if (
            credentialSlotId != null &&
            (credentialSlotId.isBlank() || credentialSlotId.length > MAX_CREDENTIAL_SLOT_ID_LENGTH)
        ) {
            abort(ProviderProfileError.InvalidCredentialSlotId)
        }
    }

    private fun validateEndpoint(value: String) {
        if (value.isBlank()) abort(ProviderProfileError.InvalidEndpoint(InvalidEndpointReason.BLANK))
        if (value.length > MAX_ENDPOINT_LENGTH) {
            abort(ProviderProfileError.InvalidEndpoint(InvalidEndpointReason.TOO_LONG))
        }
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            abort(ProviderProfileError.InvalidEndpoint(InvalidEndpointReason.MALFORMED))
        }
        if (uri.isOpaque) abort(ProviderProfileError.InvalidEndpoint(InvalidEndpointReason.MALFORMED))
        val scheme = uri.scheme?.lowercase()
        if (scheme != HTTPS_SCHEME && scheme != HTTP_SCHEME) {
            abort(ProviderProfileError.InvalidEndpoint(InvalidEndpointReason.UNSUPPORTED_SCHEME))
        }
        val host = uri.host
            ?: abort(ProviderProfileError.InvalidEndpoint(InvalidEndpointReason.MISSING_HOST))
        if (uri.rawUserInfo != null) {
            abort(ProviderProfileError.InvalidEndpoint(InvalidEndpointReason.USER_INFO_NOT_ALLOWED))
        }
        if (uri.rawQuery != null) {
            abort(ProviderProfileError.InvalidEndpoint(InvalidEndpointReason.QUERY_NOT_ALLOWED))
        }
        if (uri.rawFragment != null) {
            abort(ProviderProfileError.InvalidEndpoint(InvalidEndpointReason.FRAGMENT_NOT_ALLOWED))
        }
        if (scheme == HTTP_SCHEME && host.normalizedHost() !in LOOPBACK_HOSTS) {
            abort(
                ProviderProfileError.InvalidEndpoint(
                    InvalidEndpointReason.INSECURE_NON_LOOPBACK_HTTP,
                ),
            )
        }
    }

    private fun validateCapabilities(values: List<ProviderCapability>): Set<ProviderCapability> {
        val capabilities = values.toDeterministicSet()
        if (capabilities.size != values.size) {
            abort(
                ProviderProfileError.InvalidCapabilities(InvalidCapabilitiesReason.DUPLICATE),
            )
        }
        if (
            ProviderCapability.TEXT_CHAT !in capabilities &&
            ProviderCapability.IMAGE_GENERATION !in capabilities
        ) {
            abort(
                ProviderProfileError.InvalidCapabilities(
                    InvalidCapabilitiesReason.MISSING_CHAT_OR_IMAGE_GENERATION,
                ),
            )
        }
        return capabilities
    }

    private fun Set<ProviderCapability>.toEntities(
        profileId: String,
        occurredAt: Long,
    ): List<ProviderProfileCapabilityEntity> = map { capability ->
        ProviderProfileCapabilityEntity(
            profileId = profileId,
            capability = capability,
            createdAt = occurredAt,
        )
    }

    private fun List<ProviderCapability>.toDeterministicSet(): Set<ProviderCapability> =
        sortedBy(ProviderCapability::name).toCollection(linkedSetOf())

    private fun ProviderProfileEntity.toSnapshot(
        capabilities: Set<ProviderCapability>,
    ) = ProviderProfileSnapshot(
        profileId = profileId,
        displayName = displayName,
        adapterId = adapterId,
        endpointBaseUrl = endpointBaseUrl,
        modelId = modelId,
        credentialSlotId = credentialSlotId,
        isEnabled = isEnabled,
        capabilities = capabilities,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun Exception.toStorageFailure(operation: ProviderProfileOperation) =
        ProviderProfileError.StorageFailure(
            operation = operation,
            causeType = this::class.java.simpleName,
        )

    private fun String.normalizedHost(): String =
        lowercase().removePrefix("[").removeSuffix("]")

    private fun abort(error: ProviderProfileError): Nothing = throw ProfileAbort(error)

    private class ProfileAbort(val error: ProviderProfileError) : RuntimeException()

    private companion object {
        const val FIRST_REVISION = 1L
        const val MAX_PROFILE_ID_LENGTH = 200
        const val MAX_DISPLAY_NAME_LENGTH = 200
        const val MAX_ADAPTER_ID_LENGTH = 128
        const val MAX_ENDPOINT_LENGTH = 2_048
        const val MAX_MODEL_ID_LENGTH = 512
        const val MAX_CREDENTIAL_SLOT_ID_LENGTH = 200
        const val HTTPS_SCHEME = "https"
        const val HTTP_SCHEME = "http"

        val ADAPTER_ID_PATTERN = Regex("^[A-Za-z0-9_.-]+$")
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
