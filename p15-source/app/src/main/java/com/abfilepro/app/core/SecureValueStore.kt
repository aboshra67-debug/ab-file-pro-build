package com.abfilepro.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Android-Keystore backed store for explicitly remembered sensitive values.
 * Ciphertext and IV only are kept in SharedPreferences; the AES key never leaves AndroidKeyStore.
 */
object SecureValueStore {
    private const val PREFS = "ab_secure_values_v1"
    private const val KEY_ALIAS = "ab_file_pro_sensitive_values_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION = "v1"

    fun get(
        context: Context,
        key: String,
        legacyPrefsName: String? = null,
        legacyKey: String = key
    ): String? {
        val securePrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = securePrefs.getString(key, null)
        if (!raw.isNullOrBlank()) {
            return runCatching { decrypt(raw) }.getOrElse {
                securePrefs.edit().remove(key).apply()
                null
            }
        }

        // One-time migration from the older plaintext preference used before V1.5.
        if (!legacyPrefsName.isNullOrBlank()) {
            val legacy = context.getSharedPreferences(legacyPrefsName, Context.MODE_PRIVATE)
            val oldValue = legacy.getString(legacyKey, null)
            if (!oldValue.isNullOrBlank()) {
                put(context, key, oldValue)
                legacy.edit().remove(legacyKey).apply()
                return oldValue
            }
        }
        return null
    }

    fun put(context: Context, key: String, value: String) {
        if (value.isBlank()) {
            remove(context, key)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = listOf(
            VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        ).joinToString(":")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, payload).apply()
    }

    fun remove(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == VERSION) { "invalid_secure_value" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
