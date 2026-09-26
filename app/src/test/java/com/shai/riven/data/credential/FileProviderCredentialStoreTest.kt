package com.shai.riven.data.credential

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileProviderCredentialStoreTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var cipher: TestCredentialCipher
    private lateinit var store: FileProviderCredentialStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.noBackupFilesDir, "credential-test-${UUID.randomUUID()}")
        cipher = TestCredentialCipher()
        store = FileProviderCredentialStore(root, cipher)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun credentialRoundTripPreservesExactSecretAndRedactsStringRepresentations() {
        val plaintext = "  exact secret\nwith unicode 💜 and spaces  "
        assertPutSuccess(store.putCredential("primary-slot", ProviderSecret.fromPlaintext(plaintext)))

        val result = store.readCredential("primary-slot")
        val secret = assertReadSuccess(result)

        assertEquals(plaintext, secret.reveal())
        assertEquals(ProviderSecret.REDACTED_VALUE, secret.toString())
        assertFalse(secret.toString().contains(plaintext))
        assertFalse(result.toString().contains(plaintext))
    }

    @Test
    fun encryptedCredentialFileContainsNoPlaintextBytes() {
        val plaintext = "marker-secret-that-must-never-appear-on-disk"
        assertPutSuccess(store.putCredential("disk-slot", ProviderSecret.fromPlaintext(plaintext)))

        val fileBytes = store.credentialFile("disk-slot").readBytes()
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)

        assertFalse(fileBytes.containsSubsequence(plaintextBytes))
        assertNotEquals(plaintext, String(fileBytes, StandardCharsets.UTF_8))
    }

    @Test
    fun credentialFileNameIsOpaqueSha256AndContainsNoRawSlotId() {
        val slotId = "human-readable-private-provider-slot"
        assertPutSuccess(store.putCredential(slotId, ProviderSecret.fromPlaintext("secret")))

        val file = store.credentialFile(slotId)

        assertTrue(file.isFile)
        assertFalse(file.name.contains(slotId))
        assertTrue(file.name.matches(Regex("^[0-9a-f]{64}\\.cred$")))
    }

    @Test
    fun overwriteReplacesOldSecretWithNewSecret() {
        assertPutSuccess(store.putCredential("overwrite-slot", ProviderSecret.fromPlaintext("secret-A")))
        assertPutSuccess(store.putCredential("overwrite-slot", ProviderSecret.fromPlaintext("secret-B")))

        val secret = assertReadSuccess(store.readCredential("overwrite-slot"))

        assertEquals("secret-B", secret.reveal())
        assertFalse(secret.reveal().contains("secret-A"))
    }

    @Test
    fun rewritingSamePlaintextProducesDifferentEncryptedRepresentation() {
        val secret = ProviderSecret.fromPlaintext("same plaintext")
        assertPutSuccess(store.putCredential("nonce-slot", secret))
        val first = store.credentialFile("nonce-slot").readBytes()

        assertPutSuccess(store.putCredential("nonce-slot", secret))
        val second = store.credentialFile("nonce-slot").readBytes()

        assertFalse(first.contentEquals(second))
        assertEquals("same plaintext", assertReadSuccess(store.readCredential("nonce-slot")).reveal())
    }

    @Test
    fun slotBindingPreventsCiphertextFromDecryptingUnderAnotherSlot() {
        assertPutSuccess(store.putCredential("slot-A", ProviderSecret.fromPlaintext("bound secret")))
        Files.copy(
            store.credentialFile("slot-A").toPath(),
            store.credentialFile("slot-B").toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )

        val error = assertReadFailure(store.readCredential("slot-B"))

        assertEquals(ProviderCredentialError.CredentialUnreadable("slot-B"), error)
        assertEquals("bound secret", assertReadSuccess(store.readCredential("slot-A")).reveal())
    }

    @Test
    fun missingCredentialReturnsTypedFailureAndExistenceIsFalse() {
        assertEquals(
            ProviderCredentialError.MissingCredential("missing-slot"),
            assertReadFailure(store.readCredential("missing-slot")),
        )
        assertEquals(false, assertHasSuccess(store.hasCredential("missing-slot")))
    }

    @Test
    fun corruptCredentialReturnsTypedFailureWithoutDeletingFile() {
        assertPutSuccess(store.putCredential("corrupt-slot", ProviderSecret.fromPlaintext("secret")))
        val file = store.credentialFile("corrupt-slot")
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

        val error = assertReadFailure(store.readCredential("corrupt-slot"))

        assertEquals(ProviderCredentialError.CredentialUnreadable("corrupt-slot"), error)
        assertTrue(file.isFile)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), file.readBytes())
    }

    @Test
    fun deleteCredentialIsIdempotent() {
        assertPutSuccess(store.putCredential("delete-slot", ProviderSecret.fromPlaintext("secret")))
        assertTrue(assertHasSuccess(store.hasCredential("delete-slot")))

        assertEquals(DeleteProviderCredentialResult.Success, store.deleteCredential("delete-slot"))
        assertFalse(assertHasSuccess(store.hasCredential("delete-slot")))
        assertEquals(DeleteProviderCredentialResult.Success, store.deleteCredential("delete-slot"))
    }

    @Test
    fun clearAllRemovesOnlyFilesInsideDedicatedCredentialDirectory() {
        assertPutSuccess(store.putCredential("first-slot", ProviderSecret.fromPlaintext("first")))
        assertPutSuccess(store.putCredential("second-slot", ProviderSecret.fromPlaintext("second")))
        val unrelatedNoBackupFile = File(context.noBackupFilesDir, "unrelated-${UUID.randomUUID()}.txt")
        unrelatedNoBackupFile.writeText("must survive")

        try {
            val result = store.clearAllCredentials()

            assertEquals(ClearProviderCredentialsResult.Success(2), result)
            assertFalse(assertHasSuccess(store.hasCredential("first-slot")))
            assertFalse(assertHasSuccess(store.hasCredential("second-slot")))
            assertTrue(unrelatedNoBackupFile.isFile)
            assertEquals("must survive", unrelatedNoBackupFile.readText())
        } finally {
            unrelatedNoBackupFile.delete()
        }
    }

    @Test
    fun atomicOverwriteFailureLeavesOldCredentialReadableAndNoTemporaryFile() {
        assertPutSuccess(store.putCredential("atomic-slot", ProviderSecret.fromPlaintext("old-secret")))
        val failingStore = FileProviderCredentialStore(root, cipher) { _, _ ->
            error("controlled failure before atomic replace")
        }

        val result = failingStore.putCredential(
            "atomic-slot",
            ProviderSecret.fromPlaintext("new-secret"),
        )

        assertTrue(result is PutProviderCredentialResult.Failure)
        assertEquals("old-secret", assertReadSuccess(store.readCredential("atomic-slot")).reveal())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun productionFactoryUsesDedicatedDirectoryUnderNoBackupFilesDir() {
        val productionStore = ProviderCredentialStore.fromContext(context)
        assertTrue(productionStore is FileProviderCredentialStore)
        productionStore as FileProviderCredentialStore

        assertEquals(
            context.noBackupFilesDir.canonicalFile,
            productionStore.rootDirectory.parentFile?.canonicalFile,
        )
        assertEquals(FileProviderCredentialStore.DIRECTORY_NAME, productionStore.rootDirectory.name)
        assertNotEquals(context.filesDir.canonicalFile, productionStore.rootDirectory.parentFile?.canonicalFile)
    }

    @Test
    fun invalidSlotAndBlankSecretReturnTypedFailuresWithoutCreatingFiles() {
        assertEquals(
            PutProviderCredentialResult.Failure(ProviderCredentialError.InvalidCredentialSlotId),
            store.putCredential("  ", ProviderSecret.fromPlaintext("secret")),
        )
        assertEquals(
            PutProviderCredentialResult.Failure(ProviderCredentialError.InvalidSecret),
            store.putCredential("valid-slot", ProviderSecret.fromPlaintext("\n\t")),
        )
        assertFalse(root.exists())
    }

    @Test
    fun androidKeystoreProductionCipherUsesLockedAliasAndAesGcmParameters() {
        assertEquals("riven.provider.credentials.v1", AndroidKeystoreCredentialCipher.KEY_ALIAS)
        assertEquals("AES/GCM/NoPadding", AndroidKeystoreCredentialCipher.TRANSFORMATION)
        assertEquals("AndroidKeyStore", AndroidKeystoreCredentialCipher.KEYSTORE_PROVIDER)
        assertEquals(256, AndroidKeystoreCredentialCipher.AES_KEY_SIZE_BITS)
        assertEquals(128, AndroidKeystoreCredentialCipher.GCM_TAG_LENGTH_BITS)
    }

    private fun assertPutSuccess(result: PutProviderCredentialResult) {
        if (result !is PutProviderCredentialResult.Success) error("Unexpected put failure: $result")
    }

    private fun assertReadSuccess(result: ReadProviderCredentialResult): ProviderSecret =
        when (result) {
            is ReadProviderCredentialResult.Success -> result.secret
            is ReadProviderCredentialResult.Failure -> error("Unexpected read failure: ${result.error}")
        }

    private fun assertReadFailure(result: ReadProviderCredentialResult): ProviderCredentialError =
        when (result) {
            is ReadProviderCredentialResult.Success -> error("Expected read failure")
            is ReadProviderCredentialResult.Failure -> result.error
        }

    private fun assertHasSuccess(result: HasProviderCredentialResult): Boolean = when (result) {
        is HasProviderCredentialResult.Success -> result.exists
        is HasProviderCredentialResult.Failure -> error("Unexpected existence failure: ${result.error}")
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty()) return true
        return indices.any { start ->
            start + candidate.size <= size &&
                candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }
}

internal class TestCredentialCipher : ProviderCredentialCipher {
    private val key = MessageDigest.getInstance("SHA-256")
        .digest("credential-test-key".toByteArray(StandardCharsets.UTF_8))
    private var nonceCounter = 0L

    override fun encrypt(
        plaintext: ByteArray,
        authenticatedData: ByteArray,
    ): EncryptedCredential {
        nonceCounter += 1
        val iv = ByteArray(12)
        for (index in 0 until Long.SIZE_BYTES) {
            iv[iv.lastIndex - index] = (nonceCounter ushr (index * 8)).toByte()
        }
        val encryptedBody = xorWithStream(plaintext, authenticatedData, iv)
        val tag = digest(authenticatedData + iv + plaintext).copyOf(TAG_LENGTH)
        return EncryptedCredential(iv, encryptedBody + tag)
    }

    override fun decrypt(
        encrypted: EncryptedCredential,
        authenticatedData: ByteArray,
    ): ByteArray {
        require(encrypted.ciphertext.size >= TAG_LENGTH)
        val body = encrypted.ciphertext.copyOfRange(0, encrypted.ciphertext.size - TAG_LENGTH)
        val actualTag = encrypted.ciphertext.copyOfRange(
            encrypted.ciphertext.size - TAG_LENGTH,
            encrypted.ciphertext.size,
        )
        val plaintext = xorWithStream(body, authenticatedData, encrypted.initializationVector)
        val expectedTag = digest(authenticatedData + encrypted.initializationVector + plaintext)
            .copyOf(TAG_LENGTH)
        if (!MessageDigest.isEqual(actualTag, expectedTag)) error("Authentication failed")
        return plaintext
    }

    private fun xorWithStream(
        input: ByteArray,
        authenticatedData: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        val output = ByteArray(input.size)
        var offset = 0
        var block = 0
        while (offset < input.size) {
            val stream = digest(key + authenticatedData + iv + block.toByte())
            stream.indices.forEach { index ->
                if (offset + index < input.size) {
                    output[offset + index] = (input[offset + index].toInt() xor stream[index].toInt()).toByte()
                }
            }
            offset += stream.size
            block += 1
        }
        return output
    }

    private fun digest(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private companion object {
        const val TAG_LENGTH = 16
    }
}
