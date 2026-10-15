package com.abfilepro.app.assistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Android Keystore wrapper for local authentication secrets.
 *
 * The encrypted value can safely live in SharedPreferences because the AES key itself never leaves
 * AndroidKeyStore. This intentionally protects only authentication secrets; user records keep their
 * existing storage format to avoid a risky data migration in the final-hardening patch.
 */
internal object SecureLocalSecrets {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ab_file_pro_family_sync_token_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        require(iv.size in 1..255)
        val packed = ByteArray(1 + iv.size + encrypted.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, destinationOffset = 1)
        encrypted.copyInto(packed, destinationOffset = 1 + iv.size)
        PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(encoded: String): String? = runCatching {
        require(encoded.startsWith(PREFIX))
        val packed = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
        require(packed.size > 2)
        val ivSize = packed[0].toInt() and 0xFF
        require(ivSize in 8..32 && packed.size > 1 + ivSize)
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val ciphertext = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()
}
