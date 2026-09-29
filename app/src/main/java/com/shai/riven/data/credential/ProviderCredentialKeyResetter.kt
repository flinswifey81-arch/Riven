package com.shai.riven.data.credential

import java.security.KeyStore

fun interface ProviderCredentialKeyResetter {
    fun deleteProviderCredentialKey()
}

class AndroidKeystoreProviderCredentialKeyResetter(
    private val keyAlias: String = AndroidKeystoreCredentialCipher.KEY_ALIAS,
) : ProviderCredentialKeyResetter {
    override fun deleteProviderCredentialKey() {
        val keyStore = KeyStore.getInstance(AndroidKeystoreCredentialCipher.KEYSTORE_PROVIDER).apply {
            load(null)
        }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }
}
