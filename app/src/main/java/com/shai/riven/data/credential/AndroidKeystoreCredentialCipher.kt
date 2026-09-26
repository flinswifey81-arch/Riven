package com.shai.riven.data.credential

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedCredential(
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
)

internal interface ProviderCredentialCipher {
    fun encrypt(plaintext: ByteArray, authenticatedData: ByteArray): EncryptedCredential

    fun decrypt(
        encrypted: EncryptedCredential,
        authenticatedData: ByteArray,
    ): ByteArray
}

internal class AndroidKeystoreCredentialCipher(
    private val keyAlias: String = KEY_ALIAS,
) : ProviderCredentialCipher {
    override fun encrypt(
        plaintext: ByteArray,
        authenticatedData: ByteArray,
    ): EncryptedCredential {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(authenticatedData)
        return EncryptedCredential(
            initializationVector = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    override fun decrypt(
        encrypted: EncryptedCredential,
        authenticatedData: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, encrypted.initializationVector),
        )
        cipher.updateAAD(authenticatedData)
        return cipher.doFinal(encrypted.ciphertext)
    }

    private fun key(): SecretKey = synchronized(KEY_INITIALIZATION_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null)
        if (existing is SecretKey) return@synchronized existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(AES_KEY_SIZE_BITS)
                .build(),
        )
        generator.generateKey()
    }

    internal companion object {
        const val KEY_ALIAS = "riven.provider.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val AES_KEY_SIZE_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128

        private val KEY_INITIALIZATION_LOCK = Any()
    }
}
