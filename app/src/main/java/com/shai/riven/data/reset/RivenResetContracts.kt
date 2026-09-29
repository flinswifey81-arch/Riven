package com.shai.riven.data.reset

enum class FactoryResetConfirmation {
    ERASE_ALL_LOCAL_RIVEN_STATE,
}

data class StageFactoryResetInput(
    val confirmation: FactoryResetConfirmation,
)

sealed interface RivenResetError {
    data object ResetAlreadyPending : RivenResetError
    data object StartupMutationPending : RivenResetError
    data class JournalFailure(val causeType: String) : RivenResetError
    data class DatabaseDeleteFailure(val causeType: String) : RivenResetError
    data class AttachmentDeleteFailure(val causeType: String) : RivenResetError
    data class CredentialDeleteFailure(val causeType: String) : RivenResetError
    data class CredentialKeyDeleteFailure(val causeType: String) : RivenResetError
    data class ArchiveStagingDeleteFailure(val causeType: String) : RivenResetError
    data class RestoreStateDeleteFailure(val causeType: String) : RivenResetError
    data class FreshDatabaseCreationFailure(val causeType: String) : RivenResetError
    data class FreshDatabaseVerificationFailure(val causeType: String) : RivenResetError
    data class RecoveryFailure(val state: String) : RivenResetError
}

sealed interface StageFactoryResetResult {
    data class Staged(val restartRequired: Boolean = true) : StageFactoryResetResult
    data class Failure(val error: RivenResetError) : StageFactoryResetResult
}

sealed interface FactoryResetBootstrapResult {
    data object NoPendingReset : FactoryResetBootstrapResult
    data object ResetApplied : FactoryResetBootstrapResult
    data class Failure(val error: RivenResetError) : FactoryResetBootstrapResult
}

sealed interface ClearProviderCredentialsResult {
    data object Cleared : ClearProviderCredentialsResult
    data class Failure(val error: RivenResetError) : ClearProviderCredentialsResult
}
