package com.shai.riven.data.reset

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.archive.RivenArchiveRestoreError
import com.shai.riven.data.archive.RivenArchiveRestoreService
import com.shai.riven.data.archive.StageRivenRestoreInput
import com.shai.riven.data.archive.StageRivenRestoreResult
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.provider.ProviderCapability
import com.shai.riven.data.provider.ProviderProfileService
import com.shai.riven.data.provider.ProviderRuntimeProfileError
import com.shai.riven.data.provider.ProviderRuntimeProfileResolver
import com.shai.riven.data.provider.ResolveProviderRuntimeProfileResult
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RivenResetServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var resetRoot: File
    private lateinit var restoreRoot: File
    private lateinit var database: RivenDatabase
    private lateinit var credentialStore: FakeResetCredentialStore
    private lateinit var keyResetter: FakeProviderCredentialKeyResetter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearResetTestState(context)
        val root = temporaryFolder.newFolder("reset-service-${UUID.randomUUID()}")
        resetRoot = File(root, "reset")
        restoreRoot = File(root, "restore")
        database = RivenDatabase.buildNamedForRestoreValidation(
            context,
            context.getDatabasePath(RivenDatabase.DATABASE_NAME).absolutePath,
        )
        credentialStore = FakeResetCredentialStore()
        keyResetter = FakeProviderCredentialKeyResetter()
    }

    @After
    fun tearDown() {
        database.close()
        clearResetTestState(context)
    }

    @Test
    fun stageFactoryResetIsNondestructive() {
        seedRepresentativeState(database)
        val attachment = File(context.filesDir, "riven_attachments/attachment.blob").apply {
            parentFile?.mkdirs()
            writeText("blob")
        }
        val credential = File(context.noBackupFilesDir, "riven_provider_credentials/key.cred").apply {
            parentFile?.mkdirs()
            writeText("ciphertext")
        }
        val before = applicationTableCounts(database)

        val result = service().stageFactoryReset(confirmedInput())

        assertEquals(StageFactoryResetResult.Staged(), result)
        assertEquals(before, applicationTableCounts(database))
        assertTrue(attachment.isFile)
        assertTrue(credential.isFile)
        assertTrue(RivenResetGate.isPending(context, resetRoot))
    }

    @Test
    fun factoryResetRequiresTheSingleTypedConfirmation() {
        assertEquals(
            listOf(FactoryResetConfirmation.ERASE_ALL_LOCAL_RIVEN_STATE),
            FactoryResetConfirmation.entries,
        )
        assertFalse(StageFactoryResetInput::class.java.declaredConstructors.any { it.parameterCount == 0 })
        assertEquals(
            FactoryResetConfirmation.ERASE_ALL_LOCAL_RIVEN_STATE,
            confirmedInput().confirmation,
        )
    }

    @Test
    fun duplicateFactoryResetStageReturnsTypedFailure() {
        assertEquals(StageFactoryResetResult.Staged(), service().stageFactoryReset(confirmedInput()))

        assertEquals(
            StageFactoryResetResult.Failure(RivenResetError.ResetAlreadyPending),
            service().stageFactoryReset(confirmedInput()),
        )
    }

    @Test
    fun stagingFactoryResetCreatesNoCognitiveRecords() {
        seedRepresentativeState(database)
        val before = applicationTableCounts(database)

        assertEquals(StageFactoryResetResult.Staged(), service().stageFactoryReset(confirmedInput()))

        assertEquals(before, applicationTableCounts(database))
    }

    @Test
    fun restoreCannotBeStagedWhileFactoryResetIsPending() {
        assertEquals(StageFactoryResetResult.Staged(), service().stageFactoryReset(confirmedInput()))

        val restored = RivenArchiveRestoreService(
            context,
            restoreRoot = restoreRoot,
            resetRoot = resetRoot,
        ).stageRestore(StageRivenRestoreInput(ByteArrayInputStream(byteArrayOf()), 1))

        assertEquals(
            StageRivenRestoreResult.Failure(RivenArchiveRestoreError.FactoryResetPending),
            restored,
        )
        assertFalse(restoreRoot.exists())
    }

    @Test
    fun clearCredentialsPreservesProviderProfileAndCapabilities() {
        seedRepresentativeState(database)
        credentialStore.values["credential-slot"] = ProviderSecret.fromPlaintext("secret")
        val profile = database.providerProfileDao().profile("profile")
        val capabilities = database.providerProfileDao().capabilities("profile")

        assertEquals(ClearProviderCredentialsResult.Cleared, service().clearProviderCredentials())

        assertEquals(profile, database.providerProfileDao().profile("profile"))
        assertEquals(capabilities, database.providerProfileDao().capabilities("profile"))
        assertFalse("credential-slot" in credentialStore.values)
        assertEquals(1, keyResetter.calls)
    }

    @Test
    fun resolverReportsMissingCredentialAfterCredentialClear() = runBlocking {
        seedRepresentativeState(database)
        credentialStore.values["credential-slot"] = ProviderSecret.fromPlaintext("secret")

        assertEquals(ClearProviderCredentialsResult.Cleared, service().clearProviderCredentials())
        val resolved = ProviderRuntimeProfileResolver(
            ProviderProfileService(database),
            credentialStore,
        ).resolve("profile", setOf(ProviderCapability.TEXT_CHAT))

        assertEquals(
            ResolveProviderRuntimeProfileResult.Failure(
                ProviderRuntimeProfileError.MissingCredential("credential-slot"),
            ),
            resolved,
        )
        assertNotNull(database.providerProfileDao().profile("profile"))
    }

    @Test
    fun clearCredentialsPreservesAllOtherCanonicalState() {
        seedRepresentativeState(database)
        credentialStore.values["credential-slot"] = ProviderSecret.fromPlaintext("secret")
        val before = applicationTableCounts(database)
        val attachment = File(context.filesDir, "riven_attachments/attachment.blob").apply {
            parentFile?.mkdirs()
            writeText("blob")
        }

        assertEquals(ClearProviderCredentialsResult.Cleared, service().clearProviderCredentials())

        assertEquals(before, applicationTableCounts(database))
        assertTrue(attachment.isFile)
    }

    @Test
    fun credentialFileClearFailureDoesNotDeleteKey() {
        credentialStore.failClear = true

        val result = service().clearProviderCredentials()

        assertEquals(
            ClearProviderCredentialsResult.Failure(
                RivenResetError.CredentialDeleteFailure("ControlledCredentialFailure"),
            ),
            result,
        )
        assertEquals(0, keyResetter.calls)
    }

    @Test
    fun keyDeleteFailureIsTypedAndRetryCompletes() {
        credentialStore.values["credential-slot"] = ProviderSecret.fromPlaintext("secret")
        keyResetter.failuresRemaining = 1

        val first = service().clearProviderCredentials()

        assertTrue(first is ClearProviderCredentialsResult.Failure)
        assertTrue((first as ClearProviderCredentialsResult.Failure).error is RivenResetError.CredentialKeyDeleteFailure)
        assertTrue(credentialStore.values.isEmpty())
        assertEquals(ClearProviderCredentialsResult.Cleared, service().clearProviderCredentials())
        assertEquals(2, keyResetter.calls)
    }

    @Test
    fun clearCredentialsIsBlockedDuringResetOrRestoreMutation() {
        assertEquals(StageFactoryResetResult.Staged(), service().stageFactoryReset(confirmedInput()))
        assertEquals(
            ClearProviderCredentialsResult.Failure(RivenResetError.StartupMutationPending),
            service().clearProviderCredentials(),
        )
        assertEquals(0, credentialStore.clearCalls)
        resetRoot.deleteRecursively()
        File(restoreRoot, "rollback/sentinel").apply {
            parentFile?.mkdirs()
            writeText("pending")
        }
        assertEquals(
            ClearProviderCredentialsResult.Failure(RivenResetError.StartupMutationPending),
            service().clearProviderCredentials(),
        )
        assertEquals(0, credentialStore.clearCalls)
    }

    @Test
    fun clearCredentialsCreatesNoCognitiveSideEffects() {
        seedRepresentativeState(database)
        credentialStore.values["credential-slot"] = ProviderSecret.fromPlaintext("secret")
        val before = applicationTableCounts(database)

        assertEquals(ClearProviderCredentialsResult.Cleared, service().clearProviderCredentials())

        assertEquals(before, applicationTableCounts(database))
    }

    private fun service(): RivenResetService = RivenResetService(
        context = context,
        resetRoot = resetRoot,
        restoreRoot = restoreRoot,
        credentialStore = credentialStore,
        keyResetter = keyResetter,
    )

    private fun confirmedInput() = StageFactoryResetInput(
        FactoryResetConfirmation.ERASE_ALL_LOCAL_RIVEN_STATE,
    )
}
