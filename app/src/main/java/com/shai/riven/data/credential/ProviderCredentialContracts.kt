package com.shai.riven.data.credential

import android.content.Context

class ProviderSecret private constructor(private val plaintext: String) {
    fun reveal(): String = plaintext

    override fun toString(): String = REDACTED_VALUE

    companion object {
        const val REDACTED_VALUE = "[REDACTED_PROVIDER_SECRET]"

        fun fromPlaintext(value: String): ProviderSecret = ProviderSecret(value)
    }
}

enum class ProviderCredentialOperation {
    PUT,
    READ,
    EXISTS,
    DELETE,
    CLEAR_ALL,
}

sealed interface ProviderCredentialError {
    data object InvalidCredentialSlotId : ProviderCredentialError
    data object InvalidSecret : ProviderCredentialError
    data class MissingCredential(val credentialSlotId: String) : ProviderCredentialError
    data class CredentialUnreadable(val credentialSlotId: String) : ProviderCredentialError

    data class StorageFailure(
        val operation: ProviderCredentialOperation,
        val causeType: String,
    ) : ProviderCredentialError
}

sealed interface PutProviderCredentialResult {
    data object Success : PutProviderCredentialResult
    data class Failure(val error: ProviderCredentialError) : PutProviderCredentialResult
}

sealed interface ReadProviderCredentialResult {
    data class Success(val secret: ProviderSecret) : ReadProviderCredentialResult
    data class Failure(val error: ProviderCredentialError) : ReadProviderCredentialResult
}

sealed interface HasProviderCredentialResult {
    data class Success(val exists: Boolean) : HasProviderCredentialResult
    data class Failure(val error: ProviderCredentialError) : HasProviderCredentialResult
}

sealed interface DeleteProviderCredentialResult {
    data object Success : DeleteProviderCredentialResult
    data class Failure(val error: ProviderCredentialError) : DeleteProviderCredentialResult
}

sealed interface ClearProviderCredentialsResult {
    data class Success(val deletedCredentialFileCount: Int) : ClearProviderCredentialsResult
    data class Failure(val error: ProviderCredentialError) : ClearProviderCredentialsResult
}

interface ProviderCredentialStore {
    fun putCredential(
        credentialSlotId: String,
        secret: ProviderSecret,
    ): PutProviderCredentialResult

    fun readCredential(credentialSlotId: String): ReadProviderCredentialResult

    fun hasCredential(credentialSlotId: String): HasProviderCredentialResult

    fun deleteCredential(credentialSlotId: String): DeleteProviderCredentialResult

    fun clearAllCredentials(): ClearProviderCredentialsResult

    companion object {
        fun fromContext(context: Context): ProviderCredentialStore = FileProviderCredentialStore(
            rootDirectory = FileProviderCredentialStore.rootForContext(context),
            cipher = AndroidKeystoreCredentialCipher(),
        )
    }
}
