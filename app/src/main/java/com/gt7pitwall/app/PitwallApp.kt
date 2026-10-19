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
import com.gt7pitwall.core.radio.RoutedVoiceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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

    /** Radio Discord : le bot tourne sur le téléphone et parle dans le salon vocal de la PS5. */
    lateinit var discord: DiscordRadio
        private set

    /** Voix de l'ingénieur : Discord quand le bot est dans le salon, sinon le haut-parleur du Fold. */
    lateinit var radioOut: RoutedVoiceOutput
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        voice = TtsVoiceOutput(this).also { it.speechRate = settings.speechRate }
        discord = DiscordRadio(this, DiscordTokenStore(this)).also { it.speechRate = settings.speechRate }
        radioOut = RoutedVoiceOutput(voice, discord).also { it.mirrorOnPhone = settings.discordMirrorPhone }
        voiceModels = VoiceModelManager(this)
        voiceInput = VoskVoiceInput(voiceModels, voice).also {
            it.wakeWord = settings.wakeWord
            it.limitedVocabulary = settings.limitedVocabulary
            it.freeTranscription = settings.aiEnabled
            it.googleSpeech = GoogleSpeech(this)
            it.useGoogleSpeech = settings.googleSpeech
            it.remoteMic = { if (settings.discordMicInput) discord.openMicSource() else null }
        }
        // Le bot entre dans le salon ou en sort : l'écoute bascule entre le micro du casque PS5 et celui du Fold.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            // Bot dans le salon ET pilote présent : seul dans un salon vide, le bot ne prend pas le micro.
            discord.liveFlow.distinctUntilChanged().drop(1).collect {
                if (settings.discordMicInput) voiceInput.restart()
            }
        }
        engine = PitwallEngine(radioOut, voiceInput, loadDatabase(), RaceStore(this)).also {
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

    /** Lancement de la télémétrie : le bot rejoint le dernier salon si le réglage est actif. */
    fun onTelemetryStart() {
        if (settings.discordAutoJoin) discord.autoStart()
    }

    fun setDiscordMic(enabled: Boolean) {
        settings.discordMicInput = enabled
        voiceInput.restart()
    }

    fun setDiscordMirror(enabled: Boolean) {
        settings.discordMirrorPhone = enabled
        radioOut.mirrorOnPhone = enabled
    }

    /** Active ou coupe l'écoute du mot de réveil (le micro doit déjà être autorisé). */
    fun setVoiceInput(enabled: Boolean) {
        settings.voiceInputEnabled = enabled
        if (enabled) voiceInput.start() else voiceInput.stop()
        // Le service doit déclarer l'usage du micro pour qu'il reste actif écran éteint.
        if (engine.running.value) TelemetryService.refresh(this)
    }
}
