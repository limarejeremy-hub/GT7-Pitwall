package com.gt7pitwall.app.discord

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
 * Token du bot Discord, chiffré sur le téléphone avec une clé AES-256 de l'Android Keystore (la clé ne quitte
 * jamais le matériel sécurisé). Le token n'est ni dans le code, ni dans GitHub, ni dans l'APK, ni dans les journaux :
 * il est saisi par le pilote sur son téléphone, et seul le texte chiffré est stocké.
 */
class DiscordTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val hasToken: Boolean get() = prefs.contains(KEY_TOKEN)

    fun save(token: String) {
        val clean = token.trim()
        require(clean.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(KEY_TOKEN, stored).apply()
    }

    /** Token déchiffré, ou null (absent, ou clé effacée après une restauration : il faut le ressaisir). */
    fun load(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            val (iv, encrypted) = stored.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    /** Salon vocal choisi (identifiant public, pas un secret). */
    var channelId: Long?
        get() = prefs.getLong(KEY_CHANNEL, 0L).takeIf { it != 0L }
        set(value) = prefs.edit().putLong(KEY_CHANNEL, value ?: 0L).apply()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "discord_radio"
        const val KEY_TOKEN = "token_enc"
        const val KEY_CHANNEL = "voice_channel"
        const val ALIAS = "gt7pitwall_discord_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
