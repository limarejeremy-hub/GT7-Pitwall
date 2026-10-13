package com.gt7pitwall.app.settings

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pitwall", Context.MODE_PRIVATE)

    /** Adresse IP de la PS5. Vide = recherche automatique. */
    var ps5Address: String
        get() = prefs.getString(KEY_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADDRESS, value.trim()).apply()

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    var speechRate: Float
        get() = prefs.getFloat(KEY_RATE, 1.05f)
        set(value) = prefs.edit().putFloat(KEY_RATE, value).apply()

    /** Écoute du mot de réveil activée. */
    var voiceInputEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_INPUT, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_INPUT, value).apply()

    var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value.trim().ifEmpty { DEFAULT_WAKE_WORD }).apply()

    var limitedVocabulary: Boolean
        get() = prefs.getBoolean(KEY_LIMITED_VOCAB, true)
        set(value) = prefs.edit().putBoolean(KEY_LIMITED_VOCAB, value).apply()

    companion object {
        const val DEFAULT_WAKE_WORD = "radio"
        private const val KEY_LIMITED_VOCAB = "limited_vocabulary"
        private const val KEY_VOICE_INPUT = "voice_input_enabled"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_ADDRESS = "ps5_address"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_RATE = "speech_rate"
    }
}
