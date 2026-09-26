package com.shai.riven.data.provider

import com.shai.riven.data.credential.ProviderCredentialError
import com.shai.riven.data.credential.ProviderCredentialStore
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.credential.ReadProviderCredentialResult

data class ResolvedProviderRuntimeProfile(
    val profile: ProviderProfileSnapshot,
    val credential: ProviderSecret?,
)

enum class ProviderRuntimeStorageArea {
    PROFILE,
    CREDENTIAL,
}

sealed interface ProviderRuntimeProfileError {
    data class MissingProfile(val profileId: String) : ProviderRuntimeProfileError
    data class ProfileDisabled(val profileId: String) : ProviderRuntimeProfileError

    data class MissingCapability(
        val profileId: String,
        val capabilities: Set<ProviderCapability>,
    ) : ProviderRuntimeProfileError

    data class MissingCredential(val credentialSlotId: String) : ProviderRuntimeProfileError
    data class CredentialUnreadable(val credentialSlotId: String) : ProviderRuntimeProfileError

    data class StorageFailure(
        val area: ProviderRuntimeStorageArea,
        val causeType: String,
    ) : ProviderRuntimeProfileError
}

sealed interface ResolveProviderRuntimeProfileResult {
    data class Success(
        val runtimeProfile: ResolvedProviderRuntimeProfile,
    ) : ResolveProviderRuntimeProfileResult

    data class Failure(
        val error: ProviderRuntimeProfileError,
    ) : ResolveProviderRuntimeProfileResult
}

class ProviderRuntimeProfileResolver(
    private val profileService: ProviderProfileService,
    private val credentialStore: ProviderCredentialStore,
) {
    suspend fun resolve(
        profileId: String,
        requiredCapabilities: Set<ProviderCapability>,
    ): ResolveProviderRuntimeProfileResult {
        val profile = when (val result = profileService.profile(profileId)) {
            is ProviderProfileReadResult.Success -> result.profile
            is ProviderProfileReadResult.Failure -> {
                return ResolveProviderRuntimeProfileResult.Failure(result.error.toRuntimeError(profileId))
            }
        }
        if (!profile.isEnabled) {
            return ResolveProviderRuntimeProfileResult.Failure(
                ProviderRuntimeProfileError.ProfileDisabled(profile.profileId),
            )
        }
        val missingCapabilities = requiredCapabilities
            .filterNot(profile.capabilities::contains)
            .sortedBy(ProviderCapability::name)
            .toCollection(linkedSetOf())
        if (missingCapabilities.isNotEmpty()) {
            return ResolveProviderRuntimeProfileResult.Failure(
                ProviderRuntimeProfileError.MissingCapability(
                    profileId = profile.profileId,
                    capabilities = missingCapabilities,
                ),
            )
        }

        val credentialSlotId = profile.credentialSlotId
        if (credentialSlotId == null) {
            return ResolveProviderRuntimeProfileResult.Success(
                ResolvedProviderRuntimeProfile(profile = profile, credential = null),
            )
        }
        return when (val result = credentialStore.readCredential(credentialSlotId)) {
            is ReadProviderCredentialResult.Success -> ResolveProviderRuntimeProfileResult.Success(
                ResolvedProviderRuntimeProfile(profile = profile, credential = result.secret),
            )

            is ReadProviderCredentialResult.Failure -> ResolveProviderRuntimeProfileResult.Failure(
                result.error.toRuntimeError(credentialSlotId),
            )
        }
    }

    private fun ProviderProfileError.toRuntimeError(profileId: String): ProviderRuntimeProfileError =
        when (this) {
            is ProviderProfileError.MissingProfile,
            ProviderProfileError.InvalidProfileId,
            -> ProviderRuntimeProfileError.MissingProfile(profileId)

            is ProviderProfileError.StorageFailure -> ProviderRuntimeProfileError.StorageFailure(
                area = ProviderRuntimeStorageArea.PROFILE,
                causeType = causeType,
            )

            else -> ProviderRuntimeProfileError.StorageFailure(
                area = ProviderRuntimeStorageArea.PROFILE,
                causeType = this::class.java.simpleName,
            )
        }

    private fun ProviderCredentialError.toRuntimeError(
        credentialSlotId: String,
    ): ProviderRuntimeProfileError = when (this) {
        is ProviderCredentialError.MissingCredential ->
            ProviderRuntimeProfileError.MissingCredential(credentialSlotId)

        is ProviderCredentialError.CredentialUnreadable ->
            ProviderRuntimeProfileError.CredentialUnreadable(credentialSlotId)

        is ProviderCredentialError.StorageFailure -> ProviderRuntimeProfileError.StorageFailure(
            area = ProviderRuntimeStorageArea.CREDENTIAL,
            causeType = causeType,
        )

        ProviderCredentialError.InvalidCredentialSlotId,
        ProviderCredentialError.InvalidSecret,
        -> ProviderRuntimeProfileError.StorageFailure(
            area = ProviderRuntimeStorageArea.CREDENTIAL,
            causeType = this::class.java.simpleName,
        )
    }
}
