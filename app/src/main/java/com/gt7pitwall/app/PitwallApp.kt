package com.gt7pitwall.app

import android.app.Application
import com.gt7pitwall.app.ai.GeminiNano
import com.gt7pitwall.app.discord.DiscordRadio
import com.gt7pitwall.app.discord.DiscordTokenStore
import com.gt7pitwall.app.engine.PitwallEngine
import com.gt7pitwall.app.service.TelemetryService
import com.gt7pitwall.app.settings.RaceStore
import com.gt7pitwall.app.settings.SettingsStore
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.app.voice.TtsVoiceOutput
import com.gt7pitwall.app.voice.GoogleSpeech
import com.gt7pitwall.app.voice.VoiceModelManager
import com.gt7pitwall.app.voice.VoskVoiceInput

/** Point d'entrée : le moteur vit au niveau du processus (survit aux pliages/dépliages de l'écran). */
class PitwallApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var voice: TtsVoiceOutput
        private set
    lateinit var engine: PitwallEngine
        private set
    lateinit var voiceModels: VoiceModelManager
        private set
    lateinit var voiceInput: VoskVoiceInput
        private set
    val gemini = GeminiNano()

    /** Prototype radio Discord (le bot tourne sur le téléphone). */
    val discord: DiscordRadio by lazy { DiscordRadio(this, DiscordTokenStore(this)) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        voice = TtsVoiceOutput(this).also { it.speechRate = settings.speechRate }
        voiceModels = VoiceModelManager(this)
        voiceInput = VoskVoiceInput(voiceModels, voice).also {
            it.wakeWord = settings.wakeWord
            it.limitedVocabulary = settings.limitedVocabulary
            it.freeTranscription = settings.aiEnabled
            it.googleSpeech = GoogleSpeech(this)
            it.useGoogleSpeech = settings.googleSpeech
        }
        engine = PitwallEngine(voice, voiceInput, loadDatabase(), RaceStore(this)).also {
            it.voiceEnabled = settings.voiceEnabled
            it.askPositionEachLap = { settings.askPositionEachLap && voiceInput.isRunning }
            it.languageModel = gemini
        }
        if (settings.aiEnabled) gemini.enable()
    }

    /** Active ou coupe l'ingénieur IA ; l'écoute redémarre pour ajouter ou retirer la transcription libre. */
    fun setAi(enabled: Boolean) {
        settings.aiEnabled = enabled
        voiceInput.freeTranscription = enabled
        if (enabled) gemini.enable() else gemini.disable()
        voiceInput.restart()
    }

    /** Base voitures / circuits de la communauté GT7 (gt7info, MIT-0), embarquée dans l'APK. */
    private fun loadDatabase(): Gt7Database = try {
        fun read(name: String) = assets.open("gt7db/$name").bufferedReader().use { it.readText() }
        Gt7Database.parse(read("cars.csv"), read("maker.csv"), read("course.csv"))
    } catch (e: Exception) {
        Gt7Database.EMPTY
    }

    /** Active ou coupe l'écoute du mot de réveil (le micro doit déjà être autorisé). */
    fun setVoiceInput(enabled: Boolean) {
        settings.voiceInputEnabled = enabled
        if (enabled) voiceInput.start() else voiceInput.stop()
        // Le service doit déclarer l'usage du micro pour qu'il reste actif écran éteint.
        if (engine.running.value) TelemetryService.refresh(this)
    }
}
