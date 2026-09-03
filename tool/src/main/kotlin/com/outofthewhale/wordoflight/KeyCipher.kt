package com.outofthewhale.wordoflight

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts API keys at rest with a key held in the Android Keystore.
 *
 * API keys are never compiled into the app - they are typed in on the device
 * and belong to the person who signed up for them. Storing them as plain text
 * in preferences would leave them readable by anything that can get at the
 * app's data directory, so they are sealed with AES-256/GCM under a key that
 * lives in hardware-backed storage and cannot be exported.
 *
 * The Keystore key never leaves the device, which also means keys do not
 * survive a reinstall or a restore onto a different phone. That is the right
 * trade: a key that has become unreadable simply gets typed in again, and
 * [decrypt] returns null rather than throwing so Settings can say so.
 */
object KeyCipher {

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // The IV is generated per encryption and must travel with the payload.
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** Null when the stored value is corrupt or its Keystore key is gone. */
    fun decrypt(encoded: String): String? = try {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= IV_LENGTH) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH),
            )
            val decrypted = cipher.doFinal(combined, IV_LENGTH, combined.size - IV_LENGTH)
            decrypted.toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "wordoflight.apikeys"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val KEY_SIZE_BITS = 256
}
