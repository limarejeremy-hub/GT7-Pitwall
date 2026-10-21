package com.gt7pitwall.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiMode
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Configuration de l'IA cloud, saisie par le pilote sur son téléphone : identifiants du projet Firebase, clé du projet
 * et secret de débogage App Check. Rien n'est dans le code, le dépôt ou l'APK. La clé et le secret sont chiffrés avec
 * une clé AES-256 de l'Android Keystore ; la sauvegarde Android est désactivée pour l'application.
 */
class CloudConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mode: AiMode
        get() = prefs.getString(KEY_MODE, null)?.let { runCatching { AiMode.valueOf(it) }.getOrNull() } ?: AiMode.AUTO
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** Identifiant du projet Firebase (public, visible dans la console). */
    var projectId: String
        get() = prefs.getString(KEY_PROJECT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROJECT, value.trim()).apply()

    /** Identifiant de l'application Android dans Firebase (« 1:123…:android:abc… »). */
    var appId: String
        get() = prefs.getString(KEY_APP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_APP, value.trim()).apply()

    val hasApiKey: Boolean get() = prefs.contains(KEY_API)

    fun saveApiKey(key: String) = putSecret(KEY_API, key)

    fun apiKey(): String? = getSecret(KEY_API)

    val configured: Boolean get() = projectId.isNotBlank() && appId.isNotBlank() && hasApiKey

    /** Modèle de chaque niveau (modifiable si Google renomme un modèle ; la disponibilité se vérifie à l'appel). */
    fun modelId(b: AiBackend): String = prefs.getString("model_${b.name}", null)?.takeIf { it.isNotBlank() } ?: b.defaultModelId.orEmpty()

    fun setModelId(b: AiBackend, id: String) = prefs.edit().putString("model_${b.name}", id.trim()).apply()

    /** Plafond journalier lu par le pilote dans sa console Google (aucun par défaut : rien n'est supposé). */
    fun dailyCap(b: AiBackend): Int? = prefs.getInt("cap_${b.name}", 0).takeIf { it > 0 }

    fun setDailyCap(b: AiBackend, cap: Int?) = prefs.edit().putInt("cap_${b.name}", cap ?: 0).apply()

    /** Compteurs de requêtes réellement envoyées (sans aucun contenu des échanges). */
    var usage: String?
        get() = prefs.getString(KEY_USAGE, null)
        set(value) = prefs.edit().putString(KEY_USAGE, value).apply()

    /**
     * Secret de débogage App Check propre à ce téléphone : tiré au hasard ici, jamais dans le dépôt ni l'APK. Le pilote
     * le recopie dans la console Firebase (App Check → Gérer les jetons de débogage) pour autoriser son téléphone.
     */
    fun debugSecret(): String = getSecret(KEY_DEBUG) ?: newUuid().also { putSecret(KEY_DEBUG, it) }

    /** Nouveau secret (l'ancien est à supprimer de la console Firebase). */
    fun renewDebugSecret(): String = newUuid().also { putSecret(KEY_DEBUG, it) }

    fun clear() {
        prefs.edit().remove(KEY_PROJECT).remove(KEY_APP).remove(KEY_API).apply()
    }

    private fun newUuid(): String {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    private fun putSecret(name: String, value: String) {
        val clean = value.trim()
        require(clean.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun getSecret(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val (iv, encrypted) = stored.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
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
        const val PREFS = "cloud_ai"
        const val KEY_MODE = "mode"
        const val KEY_PROJECT = "project_id"
        const val KEY_APP = "app_id"
        const val KEY_API = "api_key_enc"
        const val KEY_DEBUG = "appcheck_debug_enc"
        const val KEY_USAGE = "usage"
        const val ALIAS = "gt7pitwall_cloud_ai"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
