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

    /** Conversation continue : secondes de silence avant le double bip et le retour au mot de réveil. */
    var conversationIdleS: Int
        get() = prefs.getInt("conversation_idle_s", 15)
        set(value) = prefs.edit().putInt("conversation_idle_s", value.coerceIn(5, 60)).apply()

    /** Messages de fin de tour détaillés (secteurs, rythme, carburant) ; sinon courts : temps, écart, position. */
    var detailedLapReports: Boolean
        get() = prefs.getBoolean("detailed_lap_reports", false)
        set(value) = prefs.edit().putBoolean("detailed_lap_reports", value).apply()

    var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value.trim().ifEmpty { DEFAULT_WAKE_WORD }).apply()

    var limitedVocabulary: Boolean
        get() = prefs.getBoolean(KEY_LIMITED_VOCAB, true)
        set(value) = prefs.edit().putBoolean(KEY_LIMITED_VOCAB, value).apply()

    /** L'ingénieur demande la position à chaque tour (réponse sans mot de réveil). */
    var askPositionEachLap: Boolean
        get() = prefs.getBoolean(KEY_ASK_POSITION, true)
        set(value) = prefs.edit().putBoolean(KEY_ASK_POSITION, value).apply()

    /** Ingénieur IA : Gemini Nano sur le téléphone (questions libres à la radio). */
    var aiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI, false)
        set(value) = prefs.edit().putBoolean(KEY_AI, value).apply()

    /** Reconnaissance d'Android (Google) pour les questions et les réponses, après « radio ». */
    var googleSpeech: Boolean
        get() = prefs.getBoolean(KEY_GOOGLE_SPEECH, true)
        set(value) = prefs.edit().putBoolean(KEY_GOOGLE_SPEECH, value).apply()

    /** Radio Discord : connexion du bot et du dernier salon au lancement de la télémétrie. */
    var discordAutoJoin: Boolean
        get() = prefs.getBoolean(KEY_DISCORD_AUTO_JOIN, true)
        set(value) = prefs.edit().putBoolean(KEY_DISCORD_AUTO_JOIN, value).apply()

    /** Radio Discord : l'ingénieur parle aussi sur le haut-parleur du Fold (sinon une seule sortie). */
    var discordMirrorPhone: Boolean
        get() = prefs.getBoolean(KEY_DISCORD_MIRROR, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCORD_MIRROR, value).apply()

    /** Radio Discord : la radio du pilote écoute le micro du casque PS5 quand le bot est dans le salon. */
    var discordMicInput: Boolean
        get() = prefs.getBoolean(KEY_DISCORD_MIC, true)
        set(value) = prefs.edit().putBoolean(KEY_DISCORD_MIC, value).apply()

    /** Expérimental : essayer Google sur le flux du casque PS5 (désactivé : Vosk seul sur le casque, fiable). */
    var googleOnHeadset: Boolean
        get() = prefs.getBoolean(KEY_GOOGLE_HEADSET, false)
        set(value) = prefs.edit().putBoolean(KEY_GOOGLE_HEADSET, value).apply()

    /** Outils de développement (banc d'essai de reconnaissance) affichés. */
    var devTools: Boolean
        get() = prefs.getBoolean(KEY_DEV_TOOLS, false)
        set(value) = prefs.edit().putBoolean(KEY_DEV_TOOLS, value).apply()

    companion object {
        private const val KEY_DISCORD_MIC = "discord_mic_input"
        private const val KEY_GOOGLE_HEADSET = "google_on_headset"
        private const val KEY_DEV_TOOLS = "dev_tools"
        private const val KEY_DISCORD_AUTO_JOIN = "discord_auto_join"
        private const val KEY_DISCORD_MIRROR = "discord_mirror_phone"
        private const val KEY_GOOGLE_SPEECH = "google_speech"
        private const val KEY_AI = "ai_enabled"
        const val DEFAULT_WAKE_WORD = "radio"
        private const val KEY_ASK_POSITION = "ask_position_each_lap"
        private const val KEY_LIMITED_VOCAB = "limited_vocabulary"
        private const val KEY_VOICE_INPUT = "voice_input_enabled"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_ADDRESS = "ps5_address"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_RATE = "speech_rate"
    }
}
