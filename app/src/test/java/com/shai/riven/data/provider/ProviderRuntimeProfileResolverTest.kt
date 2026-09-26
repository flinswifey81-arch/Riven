package com.shai.riven.data.provider

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.credential.ClearProviderCredentialsResult
import com.shai.riven.data.credential.DeleteProviderCredentialResult
import com.shai.riven.data.credential.HasProviderCredentialResult
import com.shai.riven.data.credential.ProviderCredentialError
import com.shai.riven.data.credential.ProviderCredentialStore
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.credential.PutProviderCredentialResult
import com.shai.riven.data.credential.ReadProviderCredentialResult
import com.shai.riven.data.persistence.RivenDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderRuntimeProfileResolverTest {
    private lateinit var database: RivenDatabase
    private lateinit var profileService: ProviderProfileService
    private lateinit var credentialStore: FakeProviderCredentialStore
    private lateinit var resolver: ProviderRuntimeProfileResolver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        profileService = ProviderProfileService(database)
        credentialStore = FakeProviderCredentialStore()
        resolver = ProviderRuntimeProfileResolver(profileService, credentialStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun enabledProfileWithoutCredentialRequirementResolves() = runBlocking {
        createProfile(profileId = "no-credential", credentialSlotId = null)

        val resolved = assertSuccess(
            resolver.resolve("no-credential", setOf(ProviderCapability.TEXT_CHAT)),
        )

        assertEquals("no-credential", resolved.profile.profileId)
        assertNull(resolved.credential)
        assertEquals(0, credentialStore.readCount)
    }

    @Test
    fun disabledProfileReturnsTypedFailureWithoutReadingCredential() = runBlocking {
        createProfile(
            profileId = "disabled",
            credentialSlotId = "disabled-slot",
            isEnabled = false,
        )

        val error = assertFailure(
            resolver.resolve("disabled", setOf(ProviderCapability.TEXT_CHAT)),
        )

        assertEquals(ProviderRuntimeProfileError.ProfileDisabled("disabled"), error)
        assertEquals(0, credentialStore.readCount)
    }

    @Test
    fun missingCapabilitiesAreReportedDeterministicallyWithoutReadingCredential() = runBlocking {
        createProfile(
            profileId = "missing-capability",
            credentialSlotId = "unused-slot",
            capabilities = listOf(ProviderCapability.TEXT_CHAT),
        )

        val error = assertFailure(
            resolver.resolve(
                "missing-capability",
                setOf(ProviderCapability.TOOL_CALLING, ProviderCapability.IMAGE_INPUT),
            ),
        )

        assertEquals(
            ProviderRuntimeProfileError.MissingCapability(
                profileId = "missing-capability",
                capabilities = linkedSetOf(
                    ProviderCapability.IMAGE_INPUT,
                    ProviderCapability.TOOL_CALLING,
                ),
            ),
            error,
        )
        assertEquals(0, credentialStore.readCount)
    }

    @Test
    fun requiredCredentialPresentReturnsRedactedSecretWrapper() = runBlocking {
        createProfile(profileId = "credential-present", credentialSlotId = "runtime-slot")
        credentialStore.values["runtime-slot"] = ProviderSecret.fromPlaintext("runtime-secret")

        val result = resolver.resolve(
            "credential-present",
            setOf(ProviderCapability.TEXT_CHAT),
        )
        val resolved = assertSuccess(result)

        assertEquals("runtime-secret", resolved.credential?.reveal())
        assertFalse(resolved.toString().contains("runtime-secret"))
        assertFalse(result.toString().contains("runtime-secret"))
        assertTrue(resolved.toString().contains(ProviderSecret.REDACTED_VALUE))
    }

    @Test
    fun requiredCredentialMissingReturnsTypedFailureAndPreservesProfile() = runBlocking {
        createProfile(profileId = "credential-missing", credentialSlotId = "restored-slot")

        val error = assertFailure(
            resolver.resolve("credential-missing", setOf(ProviderCapability.TEXT_CHAT)),
        )

        assertEquals(ProviderRuntimeProfileError.MissingCredential("restored-slot"), error)
        assertTrue(readProfile("credential-missing").isEnabled)
        assertEquals("restored-slot", readProfile("credential-missing").credentialSlotId)
    }

    @Test
    fun corruptCredentialReturnsTypedUnreadableFailure() = runBlocking {
        createProfile(profileId = "credential-corrupt", credentialSlotId = "corrupt-slot")
        credentialStore.unreadableSlots += "corrupt-slot"

        val error = assertFailure(
            resolver.resolve("credential-corrupt", setOf(ProviderCapability.TEXT_CHAT)),
        )

        assertEquals(ProviderRuntimeProfileError.CredentialUnreadable("corrupt-slot"), error)
    }

    @Test
    fun capabilityResolutionIsIndependentOfAdapterIdAndDisplayName() = runBlocking {
        createProfile(
            profileId = "vendor-neutral",
            displayName = "Name mentions Anthropic but has no routing meaning",
            adapterId = "totally.custom-protocol",
            credentialSlotId = null,
            capabilities = listOf(
                ProviderCapability.IMAGE_GENERATION,
                ProviderCapability.IMAGE_INPUT,
            ),
        )

        val resolved = assertSuccess(
            resolver.resolve("vendor-neutral", setOf(ProviderCapability.IMAGE_INPUT)),
        )

        assertEquals("totally.custom-protocol", resolved.profile.adapterId)
        assertTrue(resolved.profile.capabilities.contains(ProviderCapability.IMAGE_INPUT))
    }

    @Test
    fun missingProfileReturnsTypedFailure() = runBlocking {
        assertEquals(
            ProviderRuntimeProfileError.MissingProfile("absent"),
            assertFailure(resolver.resolve("absent", emptySet())),
        )
    }

    private suspend fun createProfile(
        profileId: String,
        displayName: String = "Profile",
        adapterId: String = "custom",
        credentialSlotId: String?,
        isEnabled: Boolean = true,
        capabilities: List<ProviderCapability> = listOf(ProviderCapability.TEXT_CHAT),
    ) {
        val result = profileService.create(
            CreateProviderProfileInput(
                displayName = displayName,
                adapterId = adapterId,
                endpointBaseUrl = "https://example.com/v1",
                modelId = "model",
                credentialSlotId = credentialSlotId,
                isEnabled = isEnabled,
                capabilities = capabilities,
                occurredAt = 1,
                profileId = profileId,
            ),
        )
        if (result !is CreateProviderProfileResult.Success) error("Unexpected create failure: $result")
    }

    private suspend fun readProfile(profileId: String): ProviderProfileSnapshot =
        when (val result = profileService.profile(profileId)) {
            is ProviderProfileReadResult.Success -> result.profile
            is ProviderProfileReadResult.Failure -> error("Unexpected read failure: ${result.error}")
        }

    private fun assertSuccess(
        result: ResolveProviderRuntimeProfileResult,
    ): ResolvedProviderRuntimeProfile = when (result) {
        is ResolveProviderRuntimeProfileResult.Success -> result.runtimeProfile
        is ResolveProviderRuntimeProfileResult.Failure -> error("Unexpected resolver failure: ${result.error}")
    }

    private fun assertFailure(
        result: ResolveProviderRuntimeProfileResult,
    ): ProviderRuntimeProfileError = when (result) {
        is ResolveProviderRuntimeProfileResult.Success -> error("Expected resolver failure")
        is ResolveProviderRuntimeProfileResult.Failure -> result.error
    }
}

private class FakeProviderCredentialStore : ProviderCredentialStore {
    val values = mutableMapOf<String, ProviderSecret>()
    val unreadableSlots = mutableSetOf<String>()
    var readCount = 0

    override fun putCredential(
        credentialSlotId: String,
        secret: ProviderSecret,
    ): PutProviderCredentialResult {
        values[credentialSlotId] = secret
        return PutProviderCredentialResult.Success
    }

    override fun readCredential(credentialSlotId: String): ReadProviderCredentialResult {
        readCount += 1
        if (credentialSlotId in unreadableSlots) {
            return ReadProviderCredentialResult.Failure(
                ProviderCredentialError.CredentialUnreadable(credentialSlotId),
            )
        }
        return values[credentialSlotId]
            ?.let(ReadProviderCredentialResult::Success)
            ?: ReadProviderCredentialResult.Failure(
                ProviderCredentialError.MissingCredential(credentialSlotId),
            )
    }

    override fun hasCredential(credentialSlotId: String): HasProviderCredentialResult =
        HasProviderCredentialResult.Success(credentialSlotId in values)

    override fun deleteCredential(credentialSlotId: String): DeleteProviderCredentialResult {
        values.remove(credentialSlotId)
        return DeleteProviderCredentialResult.Success
    }

    override fun clearAllCredentials(): ClearProviderCredentialsResult {
        val count = values.size
        values.clear()
        return ClearProviderCredentialsResult.Success(count)
    }
}
