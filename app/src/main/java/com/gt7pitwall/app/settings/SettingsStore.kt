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

    private companion object {
        const val KEY_ADDRESS = "ps5_address"
        const val KEY_VOICE = "voice_enabled"
        const val KEY_RATE = "speech_rate"
    }
}
