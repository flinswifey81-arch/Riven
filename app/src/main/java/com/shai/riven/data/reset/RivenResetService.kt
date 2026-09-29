package com.shai.riven.data.reset

import android.content.Context
import com.shai.riven.data.archive.safeCauseType
import com.shai.riven.data.credential.AndroidKeystoreProviderCredentialKeyResetter
import com.shai.riven.data.credential.ClearProviderCredentialsResult as CredentialStoreClearResult
import com.shai.riven.data.credential.ProviderCredentialError
import com.shai.riven.data.credential.ProviderCredentialKeyResetter
import com.shai.riven.data.credential.ProviderCredentialStore
import java.io.File
import kotlinx.coroutines.CancellationException

class RivenResetService(
    context: Context,
    resetRoot: File = File(
        context.applicationContext.noBackupFilesDir,
        RivenResetPaths.RESET_DIRECTORY,
    ),
    restoreRoot: File = RivenStartupMutationGate.defaultRestoreRoot(context),
    private val credentialStore: ProviderCredentialStore = ProviderCredentialStore.fromContext(context),
    private val keyResetter: ProviderCredentialKeyResetter = AndroidKeystoreProviderCredentialKeyResetter(),
) {
    private val appContext = context.applicationContext
    private val resetRoot = resetRoot
    private val restoreRoot = restoreRoot
    private val journal = RivenResetJournal(RivenResetPaths(appContext, resetRoot).journalFile)

    fun stageFactoryReset(input: StageFactoryResetInput): StageFactoryResetResult {
        when (input.confirmation) {
            FactoryResetConfirmation.ERASE_ALL_LOCAL_RIVEN_STATE -> Unit
        }
        if (journal.exists()) {
            return StageFactoryResetResult.Failure(RivenResetError.ResetAlreadyPending)
        }
        return try {
            journal.write(RivenResetJournalRecord(stage = RivenResetJournalStage.PENDING))
            StageFactoryResetResult.Staged()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StageFactoryResetResult.Failure(
                RivenResetError.JournalFailure(failure.safeCauseType()),
            )
        }
    }

    fun clearProviderCredentials(): ClearProviderCredentialsResult {
        if (RivenStartupMutationGate.isPending(appContext, resetRoot, restoreRoot)) {
            return ClearProviderCredentialsResult.Failure(
                RivenResetError.StartupMutationPending,
            )
        }
        when (val cleared = credentialStore.clearAllCredentials()) {
            is CredentialStoreClearResult.Failure -> {
                val causeType = when (val error = cleared.error) {
                    is ProviderCredentialError.StorageFailure -> error.causeType
                    else -> error::class.java.simpleName
                }
                return ClearProviderCredentialsResult.Failure(
                    RivenResetError.CredentialDeleteFailure(causeType),
                )
            }
            is CredentialStoreClearResult.Success -> Unit
        }
        return try {
            keyResetter.deleteProviderCredentialKey()
            ClearProviderCredentialsResult.Cleared
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ClearProviderCredentialsResult.Failure(
                RivenResetError.CredentialKeyDeleteFailure(failure.safeCauseType()),
            )
        }
    }
}
