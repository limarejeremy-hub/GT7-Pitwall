package com.gt7pitwall.app.sport

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Clé personnelle pour l'API facultative GT GridStats.
 * Stockée chiffrée avec AndroidKeyStore, jamais ajoutée à l'APK ni dans les logs.
 * Ne pas l'inclure dans une URL, ni dans les exports de journal.
 */
class SportApiCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("sport_api_private", Context.MODE_PRIVATE)

    fun configured(): Boolean = prefs.contains("cipher") && prefs.contains("iv")

    fun read(): String? {
        if (!configured()) return null
        return runCatching {
        val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString("cipher", ""), Base64.NO_WRAP)
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: return@runCatching null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun save(value: String): Boolean {
        val clean = value.trim()
        if (clean.isBlank() || clean.length > 1024 || '\n' in clean || '\r' in clean) return false
        return runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreate())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()
        }.getOrDefault(false)
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun loadOrCreate(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    companion object {
        private const val ALIAS = "gt7_pitwall_gridstats_token"
    }
}
