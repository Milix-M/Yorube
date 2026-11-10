package com.hermes.client.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption; the key never leaves the app's Android Keystore. */
internal object SecretCipher {
    private const val ALIAS = "yorube.secrets.v1"
    private const val PREFIX = "v1:"

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "The saved encryption key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    fun encrypt(value: String, purpose: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    fun decrypt(value: String, purpose: String): String {
        if (value.isEmpty()) return ""
        require(value.startsWith(PREFIX)) { "Unsupported encrypted value" }
        val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        require(bytes.size >= 12 + 16) { "Invalid encrypted value" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}
