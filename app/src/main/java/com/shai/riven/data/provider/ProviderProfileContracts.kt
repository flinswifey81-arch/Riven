package com.shai.riven.data.provider

enum class ProviderCapability {
    TEXT_CHAT,
    IMAGE_INPUT,
    FILE_INPUT,
    TOOL_CALLING,
    STREAMING,
    IMAGE_GENERATION,
}

data class ProviderProfileSnapshot(
    val profileId: String,
    val displayName: String,
    val adapterId: String,
    val endpointBaseUrl: String,
    val modelId: String,
    val credentialSlotId: String?,
    val isEnabled: Boolean,
    val capabilities: Set<ProviderCapability>,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

data class CreateProviderProfileInput(
    val displayName: String,
    val adapterId: String,
    val endpointBaseUrl: String,
    val modelId: String,
    val credentialSlotId: String? = null,
    val isEnabled: Boolean = true,
    val capabilities: List<ProviderCapability>,
    val occurredAt: Long,
    val profileId: String? = null,
)

data class UpdateProviderProfileInput(
    val profileId: String,
    val expectedRevision: Long,
    val displayName: String,
    val adapterId: String,
    val endpointBaseUrl: String,
    val modelId: String,
    val credentialSlotId: String?,
    val isEnabled: Boolean,
    val capabilities: List<ProviderCapability>,
    val occurredAt: Long,
)

enum class ProviderProfileOperation {
    CREATE,
    UPDATE,
    READ,
    LIST,
}

enum class InvalidEndpointReason {
    BLANK,
    TOO_LONG,
    MALFORMED,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    USER_INFO_NOT_ALLOWED,
    QUERY_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED,
    INSECURE_NON_LOOPBACK_HTTP,
}

enum class InvalidCapabilitiesReason {
    DUPLICATE,
    MISSING_CHAT_OR_IMAGE_GENERATION,
}

sealed interface ProviderProfileError {
    data class MissingProfile(val profileId: String) : ProviderProfileError
    data class DuplicateProfileId(val profileId: String) : ProviderProfileError
    data object InvalidProfileId : ProviderProfileError
    data class StaleRevision(val expected: Long, val actual: Long) : ProviderProfileError
    data object RevisionOverflow : ProviderProfileError
    data object InvalidDisplayName : ProviderProfileError
    data object InvalidAdapterId : ProviderProfileError
    data class InvalidEndpoint(val reason: InvalidEndpointReason) : ProviderProfileError
    data object InvalidModelId : ProviderProfileError
    data object InvalidCredentialSlotId : ProviderProfileError
    data class InvalidCapabilities(val reason: InvalidCapabilitiesReason) : ProviderProfileError

    data class StorageFailure(
        val operation: ProviderProfileOperation,
        val causeType: String,
    ) : ProviderProfileError
}

sealed interface CreateProviderProfileResult {
    data class Success(val profile: ProviderProfileSnapshot) : CreateProviderProfileResult
    data class Failure(val error: ProviderProfileError) : CreateProviderProfileResult
}

sealed interface UpdateProviderProfileResult {
    data class Success(val profile: ProviderProfileSnapshot) : UpdateProviderProfileResult
    data class Failure(val error: ProviderProfileError) : UpdateProviderProfileResult
}

sealed interface ProviderProfileReadResult {
    data class Success(val profile: ProviderProfileSnapshot) : ProviderProfileReadResult
    data class Failure(val error: ProviderProfileError) : ProviderProfileReadResult
}

sealed interface ProviderProfilesReadResult {
    data class Success(val profiles: List<ProviderProfileSnapshot>) : ProviderProfilesReadResult
    data class Failure(val error: ProviderProfileError) : ProviderProfilesReadResult
}

fun interface ProviderProfileIdGenerator {
    fun newProfileId(): String
}
