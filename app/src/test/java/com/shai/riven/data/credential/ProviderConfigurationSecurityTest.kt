package com.shai.riven.data.credential

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.context.RivenContextCollectionResult
import com.shai.riven.data.context.RivenContextSourceRegistry
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.provider.CreateProviderProfileInput
import com.shai.riven.data.provider.CreateProviderProfileResult
import com.shai.riven.data.provider.ProviderCapability
import com.shai.riven.data.provider.ProviderProfileService
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderConfigurationSecurityTest {
    private lateinit var database: RivenDatabase
    private lateinit var credentialRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        credentialRoot = File(context.noBackupFilesDir, "security-test-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        database.close()
        credentialRoot.deleteRecursively()
    }

    @Test
    fun credentialPlaintextNeverEntersOwnedDatabaseContextOrResultSurfaces() = runBlocking {
        val secretText = "SECURITY_MARKER_${UUID.randomUUID()}"
        val profileService = ProviderProfileService(database)
        val profileResult = profileService.create(
            CreateProviderProfileInput(
                displayName = "Secure profile",
                adapterId = "custom.secure",
                endpointBaseUrl = "https://secure.example/v1",
                modelId = "secure-model",
                credentialSlotId = "opaque-secure-slot",
                isEnabled = true,
                capabilities = listOf(ProviderCapability.TEXT_CHAT),
                occurredAt = 1,
                profileId = "secure-profile",
            ),
        )
        assertTrue(profileResult is CreateProviderProfileResult.Success)

        val credentialStore = FileProviderCredentialStore(
            rootDirectory = credentialRoot,
            cipher = TestCredentialCipher(),
        )
        val putResult = credentialStore.putCredential(
            "opaque-secure-slot",
            ProviderSecret.fromPlaintext(secretText),
        )
        assertTrue(putResult is PutProviderCredentialResult.Success)
        val readResult = credentialStore.readCredential("opaque-secure-slot")
        assertTrue(readResult is ReadProviderCredentialResult.Success)

        listOf(
            "provider_profiles",
            "provider_profile_capabilities",
            "messages",
            "experiences",
            "memories",
            "shai_system_instructions",
            "attachments",
        ).forEach { table -> assertNoTextValueContains(table, secretText) }

        val contextResult = RivenContextSourceRegistry(emptyList()).collect(now = 2)
        assertTrue(contextResult is RivenContextCollectionResult.Success)
        contextResult as RivenContextCollectionResult.Success
        assertTrue(contextResult.snapshot.fragments.isEmpty())

        assertFalse(profileResult.toString().contains(secretText))
        assertFalse(putResult.toString().contains(secretText))
        assertFalse(readResult.toString().contains(secretText))
        assertFalse(contextResult.toString().contains(secretText))
        credentialRoot.listFiles().orEmpty().forEach { file ->
            assertFalse(file.readBytes().containsSubsequence(secretText.toByteArray(StandardCharsets.UTF_8)))
        }
    }

    private fun assertNoTextValueContains(table: String, forbidden: String) {
        val textColumns = database.openHelper.writableDatabase
            .query("PRAGMA table_info(`$table`)")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)
                        val type = cursor.getString(2)
                        if (type.equals("TEXT", ignoreCase = true)) add(name)
                    }
                }
            }
        if (textColumns.isEmpty()) return
        val projection = textColumns.joinToString(",") { column -> "`$column`" }
        database.openHelper.writableDatabase.query("SELECT $projection FROM `$table`").use { cursor ->
            while (cursor.moveToNext()) {
                textColumns.indices.forEach { index ->
                    if (!cursor.isNull(index)) {
                        assertFalse(
                            "Credential plaintext leaked into $table.${textColumns[index]}",
                            cursor.getString(index).contains(forbidden),
                        )
                    }
                }
            }
        }
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty()) return true
        return indices.any { start ->
            start + candidate.size <= size &&
                candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }
}
