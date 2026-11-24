package com.kike.musicplayer.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (passwords, API tokens) with AES-GCM keys kept in the Android Keystore. */
object Secrets {
    const val ALIAS_SERVER_PASSWORD = "musicminchas-server-password"
    const val ALIAS_LISTENBRAINZ_TOKEN = "musicminchas-listenbrainz-token"
    const val ALIAS_LASTFM_SECRET = "musicminchas-lastfm-secret"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12

    private fun key(alias: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    /** IV followed by the ciphertext, Base64. */
    fun encrypt(text: String, alias: String = ALIAS_SERVER_PASSWORD): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(alias)) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(stored: String, alias: String = ALIAS_SERVER_PASSWORD): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(GCM_TAG_BITS, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }.onFailure { Log.w("Secrets", "Saved secret for $alias can't be decrypted", it) }.getOrNull()
}
