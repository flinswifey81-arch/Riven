package com.shai.riven.data.provider

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ProviderProfileCapabilityEntity
import com.shai.riven.data.persistence.entity.ProviderProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderProfileServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var service: ProviderProfileService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = ProviderProfileService(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createHttpsChatProfilePreservesExactConfigurationAtRevisionOne() = runBlocking {
        val created = create(
            profileId = "primary-profile",
            displayName = "  Riven Primary  ",
            endpoint = "https://gateway.example/v1/",
            modelId = "  model/exact  ",
            credentialSlotId = "primary-slot",
            capabilities = listOf(
                ProviderCapability.STREAMING,
                ProviderCapability.TEXT_CHAT,
                ProviderCapability.IMAGE_INPUT,
            ),
            occurredAt = 11,
        )

        assertEquals("primary-profile", created.profileId)
        assertEquals("  Riven Primary  ", created.displayName)
        assertEquals("OPENAI_COMPATIBLE.v1", created.adapterId)
        assertEquals("https://gateway.example/v1/", created.endpointBaseUrl)
        assertEquals("  model/exact  ", created.modelId)
        assertEquals("primary-slot", created.credentialSlotId)
        assertTrue(created.isEnabled)
        assertEquals(1L, created.revision)
        assertEquals(11L, created.createdAt)
        assertEquals(11L, created.updatedAt)
        assertEquals(
            listOf(
                ProviderCapability.IMAGE_INPUT,
                ProviderCapability.STREAMING,
                ProviderCapability.TEXT_CHAT,
            ),
            created.capabilities.toList(),
        )
        assertEquals(1, database.providerProfileDao().profileCount())
        assertEquals(3, database.providerProfileDao().capabilityCount())
    }

    @Test
    fun imageGenerationOnlyProfileIsValidWithoutTextChat() = runBlocking {
        val created = create(
            profileId = "image-only",
            capabilities = listOf(ProviderCapability.IMAGE_GENERATION),
        )

        assertEquals(setOf(ProviderCapability.IMAGE_GENERATION), created.capabilities)
    }

    @Test
    fun invalidAndDuplicateCapabilitySetsReturnTypedFailuresWithoutRows() = runBlocking {
        val missingCore = assertCreateFailure(
            service.create(
                input(
                    profileId = "invalid-capabilities",
                    capabilities = listOf(ProviderCapability.IMAGE_INPUT),
                ),
            ),
        )
        assertEquals(
            ProviderProfileError.InvalidCapabilities(
                InvalidCapabilitiesReason.MISSING_CHAT_OR_IMAGE_GENERATION,
            ),
            missingCore,
        )

        val duplicate = assertCreateFailure(
            service.create(
                input(
                    profileId = "duplicate-capabilities",
                    capabilities = listOf(
                        ProviderCapability.TEXT_CHAT,
                        ProviderCapability.TEXT_CHAT,
                    ),
                ),
            ),
        )
        assertEquals(
            ProviderProfileError.InvalidCapabilities(InvalidCapabilitiesReason.DUPLICATE),
            duplicate,
        )
        assertEquals(0, database.providerProfileDao().profileCount())
        assertEquals(0, database.providerProfileDao().capabilityCount())
    }

    @Test
    fun updateReplacesConfigurationAndCapabilitiesAtomically() = runBlocking {
        val original = create(
            profileId = "updatable",
            modelId = "model-a",
            credentialSlotId = null,
            capabilities = listOf(ProviderCapability.TEXT_CHAT, ProviderCapability.STREAMING),
            occurredAt = 10,
        )

        val updated = assertUpdateSuccess(
            service.update(
                UpdateProviderProfileInput(
                    profileId = original.profileId,
                    expectedRevision = 1,
                    displayName = "Image specialist",
                    adapterId = "custom.image-v2",
                    endpointBaseUrl = "https://images.example/api",
                    modelId = "image-model-b",
                    credentialSlotId = "image-slot",
                    isEnabled = false,
                    capabilities = listOf(
                        ProviderCapability.IMAGE_GENERATION,
                        ProviderCapability.FILE_INPUT,
                    ),
                    occurredAt = 20,
                ),
            ),
        )

        assertEquals(2L, updated.revision)
        assertEquals(10L, updated.createdAt)
        assertEquals(20L, updated.updatedAt)
        assertEquals("image-model-b", updated.modelId)
        assertEquals("https://images.example/api", updated.endpointBaseUrl)
        assertEquals("image-slot", updated.credentialSlotId)
        assertFalse(updated.isEnabled)
        assertEquals(
            setOf(ProviderCapability.FILE_INPUT, ProviderCapability.IMAGE_GENERATION),
            updated.capabilities,
        )
        assertFalse(updated.capabilities.contains(ProviderCapability.TEXT_CHAT))
        assertFalse(updated.capabilities.contains(ProviderCapability.STREAMING))
    }

    @Test
    fun staleUpdateReturnsTypedFailureAndMutatesNothing() = runBlocking {
        create(profileId = "stale-profile", modelId = "before", occurredAt = 10)
        val beforeEntity = checkNotNull(database.providerProfileDao().profile("stale-profile"))
        val beforeCapabilities = database.providerProfileDao().capabilities("stale-profile")

        val error = assertUpdateFailure(
            service.update(
                updateInput(
                    profileId = "stale-profile",
                    expectedRevision = 0,
                    modelId = "after",
                ),
            ),
        )

        assertEquals(ProviderProfileError.StaleRevision(expected = 0, actual = 1), error)
        assertEquals(beforeEntity, database.providerProfileDao().profile("stale-profile"))
        assertEquals(beforeCapabilities, database.providerProfileDao().capabilities("stale-profile"))
    }

    @Test
    fun revisionOverflowReturnsTypedFailureAndMutatesNothing() = runBlocking {
        val profile = ProviderProfileEntity(
            profileId = "max-revision",
            displayName = "Maximum",
            adapterId = "custom",
            endpointBaseUrl = "https://example.com",
            modelId = "model",
            credentialSlotId = null,
            isEnabled = true,
            revision = Long.MAX_VALUE,
            createdAt = 1,
            updatedAt = 2,
        )
        database.providerProfileDao().insertProfile(profile)
        database.providerProfileDao().insertCapabilities(
            listOf(
                ProviderProfileCapabilityEntity(
                    profileId = profile.profileId,
                    capability = ProviderCapability.TEXT_CHAT,
                    createdAt = 1,
                ),
            ),
        )

        val error = assertUpdateFailure(
            service.update(
                updateInput(
                    profileId = profile.profileId,
                    expectedRevision = Long.MAX_VALUE,
                    modelId = "must-not-save",
                ),
            ),
        )

        assertEquals(ProviderProfileError.RevisionOverflow, error)
        assertEquals(profile, database.providerProfileDao().profile(profile.profileId))
        assertEquals(
            listOf(ProviderCapability.TEXT_CHAT),
            database.providerProfileDao().capabilities(profile.profileId),
        )
    }

    @Test
    fun capabilityReplacementFailureRollsBackProfileAndCapabilityChanges() = runBlocking {
        create(
            profileId = "rollback-profile",
            modelId = "old-model",
            capabilities = listOf(ProviderCapability.TEXT_CHAT, ProviderCapability.STREAMING),
            occurredAt = 10,
        )
        val beforeEntity = checkNotNull(database.providerProfileDao().profile("rollback-profile"))
        val beforeCapabilities = database.providerProfileDao().capabilities("rollback-profile")
        val failingService = ProviderProfileService(database) {
            error("controlled capability replacement failure")
        }

        val error = assertUpdateFailure(
            failingService.update(
                updateInput(
                    profileId = "rollback-profile",
                    expectedRevision = 1,
                    modelId = "new-model",
                    capabilities = listOf(ProviderCapability.IMAGE_GENERATION),
                ),
            ),
        )

        assertTrue(error is ProviderProfileError.StorageFailure)
        assertEquals(beforeEntity, database.providerProfileDao().profile("rollback-profile"))
        assertEquals(beforeCapabilities, database.providerProfileDao().capabilities("rollback-profile"))
    }

    @Test
    fun allProfilesUsesCaseInsensitiveDisplayNameThenProfileIdOrdering() = runBlocking {
        create(profileId = "z-id", displayName = "beta")
        create(profileId = "b-id", displayName = "Alpha")
        create(profileId = "a-id", displayName = "alpha")

        val profiles = when (val result = service.allProfiles()) {
            is ProviderProfilesReadResult.Success -> result.profiles
            is ProviderProfilesReadResult.Failure -> error("Unexpected list failure: ${result.error}")
        }

        assertEquals(listOf("a-id", "b-id", "z-id"), profiles.map { it.profileId })
    }

    @Test
    fun httpsAndLoopbackHttpEndpointsAreAcceptedWithoutRewriting() = runBlocking {
        val endpoints = listOf(
            "HTTPS://Gateway.Example/v1/path",
            "http://localhost:8080/v1",
            "http://127.0.0.1:11434/api",
            "http://[::1]:9090/root",
        )

        endpoints.forEachIndexed { index, endpoint ->
            val created = create(profileId = "endpoint-$index", endpoint = endpoint)
            assertEquals(endpoint, created.endpointBaseUrl)
        }
    }

    @Test
    fun insecureNonLoopbackHttpEndpointIsRejected() = runBlocking {
        val error = assertCreateFailure(
            service.create(input(profileId = "insecure", endpoint = "http://example.com/v1")),
        )

        assertEquals(
            ProviderProfileError.InvalidEndpoint(
                InvalidEndpointReason.INSECURE_NON_LOOPBACK_HTTP,
            ),
            error,
        )
    }

    @Test
    fun endpointUserInfoQueryAndFragmentAreRejectedWithSpecificReasons() = runBlocking {
        val cases = listOf(
            "https://user:password@example.com/v1" to InvalidEndpointReason.USER_INFO_NOT_ALLOWED,
            "https://example.com/v1?secret=no" to InvalidEndpointReason.QUERY_NOT_ALLOWED,
            "https://example.com/v1#fragment" to InvalidEndpointReason.FRAGMENT_NOT_ALLOWED,
        )

        cases.forEachIndexed { index, (endpoint, reason) ->
            val error = assertCreateFailure(
                service.create(input(profileId = "rejected-endpoint-$index", endpoint = endpoint)),
            )
            assertEquals(ProviderProfileError.InvalidEndpoint(reason), error)
        }
    }

    @Test
    fun blankMalformedAndUnsupportedEndpointsAreRejected() = runBlocking {
        val cases = listOf(
            " " to InvalidEndpointReason.BLANK,
            "https://exa mple.com" to InvalidEndpointReason.MALFORMED,
            "ftp://example.com" to InvalidEndpointReason.UNSUPPORTED_SCHEME,
            "https:///missing-host" to InvalidEndpointReason.MISSING_HOST,
        )

        cases.forEachIndexed { index, (endpoint, reason) ->
            val error = assertCreateFailure(
                service.create(input(profileId = "bad-endpoint-$index", endpoint = endpoint)),
            )
            assertEquals(ProviderProfileError.InvalidEndpoint(reason), error)
        }
    }

    @Test
    fun invalidAdapterAndCredentialSlotReturnTypedFailures() = runBlocking {
        val adapterError = assertCreateFailure(
            service.create(input(profileId = "bad-adapter", adapterId = "not safe/name")),
        )
        assertEquals(ProviderProfileError.InvalidAdapterId, adapterError)

        val slotError = assertCreateFailure(
            service.create(input(profileId = "bad-slot", credentialSlotId = "  \t")),
        )
        assertEquals(ProviderProfileError.InvalidCredentialSlotId, slotError)
    }

    @Test
    fun invalidDisplayNameAndModelReturnTypedFailures() = runBlocking {
        assertEquals(
            ProviderProfileError.InvalidDisplayName,
            assertCreateFailure(
                service.create(input(profileId = "bad-name", displayName = "\n\t")),
            ),
        )
        assertEquals(
            ProviderProfileError.InvalidModelId,
            assertCreateFailure(
                service.create(input(profileId = "bad-model", modelId = "  ")),
            ),
        )
    }

    @Test
    fun duplicateProfileIdReturnsTypedFailureWithoutChangingExistingProfile() = runBlocking {
        val original = create(profileId = "duplicate-id", modelId = "original")

        val error = assertCreateFailure(
            service.create(input(profileId = "duplicate-id", modelId = "replacement")),
        )

        assertEquals(ProviderProfileError.DuplicateProfileId("duplicate-id"), error)
        assertEquals(original, readProfile("duplicate-id"))
    }

    @Test
    fun missingProfileReadsAndUpdatesReturnTypedFailures() = runBlocking {
        val readError = when (val result = service.profile("missing-profile")) {
            is ProviderProfileReadResult.Success -> error("Expected read failure")
            is ProviderProfileReadResult.Failure -> result.error
        }
        assertEquals(ProviderProfileError.MissingProfile("missing-profile"), readError)

        val updateError = assertUpdateFailure(
            service.update(
                updateInput(
                    profileId = "missing-profile",
                    expectedRevision = 1,
                    modelId = "model",
                ),
            ),
        )
        assertEquals(ProviderProfileError.MissingProfile("missing-profile"), updateError)
    }

    @Test
    fun ordinaryReadsDoNotMutateRevisionOrTimestamps() = runBlocking {
        val created = create(profileId = "read-only", occurredAt = 42)

        repeat(3) { readProfile("read-only") }
        when (val result = service.allProfiles()) {
            is ProviderProfilesReadResult.Success -> assertEquals(1, result.profiles.size)
            is ProviderProfilesReadResult.Failure -> error("Unexpected list failure: ${result.error}")
        }

        assertEquals(created, readProfile("read-only"))
    }

    @Test
    fun profileSchemaContainsOnlyOpaqueCredentialReferenceAndNoSecretColumns() = runBlocking {
        create(profileId = "schema-check", credentialSlotId = "opaque-slot")
        val columnNames = database.openHelper.writableDatabase
            .query("PRAGMA table_info(provider_profiles)")
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
            }

        assertEquals(
            setOf(
                "profile_id",
                "display_name",
                "adapter_id",
                "endpoint_base_url",
                "model_id",
                "credential_slot_id",
                "is_enabled",
                "revision",
                "created_at",
                "updated_at",
            ),
            columnNames,
        )
        listOf("api_key", "secret", "password", "access_token", "bearer_token", "credential_value")
            .forEach { forbidden -> assertFalse(columnNames.contains(forbidden)) }
    }

    @Test
    fun profileConfigurationCreatesNoCognitiveContextOrAttachmentSideEffects() = runBlocking {
        create(profileId = "side-effects", occurredAt = 1)
        assertUpdateSuccess(
            service.update(
                updateInput(
                    profileId = "side-effects",
                    expectedRevision = 1,
                    modelId = "updated",
                ),
            ),
        )

        listOf(
            "messages",
            "experiences",
            "candidate_memories",
            "memories",
            "open_loops",
            "shai_system_instructions",
            "suppression_tombstones",
            "derived_artifacts",
            "repair_jobs",
            "attachments",
        ).forEach { table ->
            assertEquals("Unexpected side effect in $table", 0L, rowCount(table))
        }
    }

    private suspend fun create(
        profileId: String,
        displayName: String = "Primary",
        endpoint: String = "https://example.com/v1",
        adapterId: String = "OPENAI_COMPATIBLE.v1",
        modelId: String = "model-a",
        credentialSlotId: String? = null,
        capabilities: List<ProviderCapability> = listOf(ProviderCapability.TEXT_CHAT),
        occurredAt: Long = 1,
    ): ProviderProfileSnapshot = assertCreateSuccess(
        service.create(
            input(
                profileId = profileId,
                displayName = displayName,
                endpoint = endpoint,
                adapterId = adapterId,
                modelId = modelId,
                credentialSlotId = credentialSlotId,
                capabilities = capabilities,
                occurredAt = occurredAt,
            ),
        ),
    )

    private fun input(
        profileId: String,
        displayName: String = "Primary",
        endpoint: String = "https://example.com/v1",
        adapterId: String = "OPENAI_COMPATIBLE.v1",
        modelId: String = "model-a",
        credentialSlotId: String? = null,
        capabilities: List<ProviderCapability> = listOf(ProviderCapability.TEXT_CHAT),
        occurredAt: Long = 1,
    ) = CreateProviderProfileInput(
        displayName = displayName,
        adapterId = adapterId,
        endpointBaseUrl = endpoint,
        modelId = modelId,
        credentialSlotId = credentialSlotId,
        isEnabled = true,
        capabilities = capabilities,
        occurredAt = occurredAt,
        profileId = profileId,
    )

    private fun updateInput(
        profileId: String,
        expectedRevision: Long,
        modelId: String,
        capabilities: List<ProviderCapability> = listOf(ProviderCapability.TEXT_CHAT),
    ) = UpdateProviderProfileInput(
        profileId = profileId,
        expectedRevision = expectedRevision,
        displayName = "Updated",
        adapterId = "custom.updated",
        endpointBaseUrl = "https://updated.example/v2",
        modelId = modelId,
        credentialSlotId = "updated-slot",
        isEnabled = true,
        capabilities = capabilities,
        occurredAt = 30,
    )

    private suspend fun readProfile(profileId: String): ProviderProfileSnapshot =
        when (val result = service.profile(profileId)) {
            is ProviderProfileReadResult.Success -> result.profile
            is ProviderProfileReadResult.Failure -> error("Unexpected read failure: ${result.error}")
        }

    private fun assertCreateSuccess(result: CreateProviderProfileResult): ProviderProfileSnapshot =
        when (result) {
            is CreateProviderProfileResult.Success -> result.profile
            is CreateProviderProfileResult.Failure -> error("Unexpected create failure: ${result.error}")
        }

    private fun assertCreateFailure(result: CreateProviderProfileResult): ProviderProfileError =
        when (result) {
            is CreateProviderProfileResult.Success -> error("Expected create failure")
            is CreateProviderProfileResult.Failure -> result.error
        }

    private fun assertUpdateSuccess(result: UpdateProviderProfileResult): ProviderProfileSnapshot =
        when (result) {
            is UpdateProviderProfileResult.Success -> result.profile
            is UpdateProviderProfileResult.Failure -> error("Unexpected update failure: ${result.error}")
        }

    private fun assertUpdateFailure(result: UpdateProviderProfileResult): ProviderProfileError =
        when (result) {
            is UpdateProviderProfileResult.Success -> error("Expected update failure")
            is UpdateProviderProfileResult.Failure -> result.error
        }

    private fun rowCount(table: String): Long = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
