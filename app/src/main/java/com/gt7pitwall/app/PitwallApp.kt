package com.gt7pitwall.app

import android.app.Application
import com.gt7pitwall.app.ai.GeminiNano
import com.gt7pitwall.app.discord.DiscordRadio
import com.gt7pitwall.app.discord.DiscordTokenStore
import com.gt7pitwall.app.engine.PitwallEngine
import com.gt7pitwall.app.service.TelemetryService
import com.gt7pitwall.app.sport.SportScheduleRepository
import com.gt7pitwall.app.settings.RaceStore
import com.gt7pitwall.app.settings.SettingsStore
import com.gt7pitwall.app.sport.DatabaseRepository
import com.gt7pitwall.core.radio.RoutedVoiceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import com.gt7pitwall.app.voice.RadioTonePlayer
import kotlinx.coroutines.launch
import com.gt7pitwall.app.voice.TtsVoiceOutput
import com.gt7pitwall.app.voice.BenchModelManager
import com.gt7pitwall.app.voice.GoogleSpeech
import com.gt7pitwall.app.voice.SherpaBench
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

    /** Gemini dans le cloud (Firebase AI Logic), inactif tant que le projet Firebase n'est pas saisi. */
    lateinit var cloudAi: com.gt7pitwall.app.ai.CloudAi
        private set

    /** Banc d'essai de reconnaissance locale (sherpa-onnx) : compare avec Vosk, n'agit jamais sur la radio. */
    lateinit var bench: SherpaBench
        private set

    /** Radio Discord : le bot tourne sur le téléphone et parle dans le salon vocal de la PS5. */
    lateinit var discord: DiscordRadio
        private set

    /** Programme hebdomadaire des courses Sport A/B/C (flux public GTPlanet, en cache). */
    lateinit var sport: SportScheduleRepository
        private set

    /** Mise à jour automatique des voitures et circuits. */
    lateinit var database: DatabaseRepository
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
        bench = SherpaBench(this, BenchModelManager(this))
        // L'ingénieur parle réellement : sur le Fold (synthèse Android) ou dans le salon Discord (file audio du bot,
        // fin de lecture comprise). Le micro attend la fin de l'un et de l'autre.
        val speaking = combine(voice.speaking, discord.speaking) { phone, remote -> phone || remote }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Main), SharingStarted.Eagerly, false)
        voiceInput = VoskVoiceInput(voiceModels, speaking, RadioTonePlayer(discord, radioOut)).also {
            it.conversationIdleMs = settings.conversationIdleS * 1_000L
            it.wakeWord = settings.wakeWord
            it.limitedVocabulary = settings.limitedVocabulary
            // Transcription libre toujours active : la forme complète de la phrase (négation, question, futur) protège
            // les commandes, et les questions complexes en ont besoin, avec ou sans Gemini.
            it.freeTranscription = true
            it.googleOnHeadset = settings.googleOnHeadset
            it.googleSpeech = GoogleSpeech(this)
            it.useGoogleSpeech = settings.googleSpeech
            it.remoteMic = { if (settings.discordMicInput) discord.openMicSource() else null }
            it.remoteMicAvailable = { settings.discordMicInput && discord.live }
            it.remoteVoiceMs = { discord.lastPilotVoiceMs }
            // Fin de lecture perdue (issue #32) : la voix bloquée est réinitialisée, l'écoute reprend.
            it.onStuckSpeaking = {
                if (voice.speaking.value) voice.stop()
                if (discord.speaking.value) discord.stop()
            }
            it.bench = bench
            // Interruption : seulement là où le matériel le permet (voir DuplexPolicy, testé dans le module core).
            it.duplexMode = { duplexMode() }
            it.inGap = voice.inGap
            it.engineerMessage = { discord.current ?: voice.current }
            it.isThinking = { if (::engine.isInitialized) engine.thinking else false }
        }
        voice.gaps = { duplexMode() == com.gt7pitwall.core.radio.DuplexMode.GAPS }
        // Le bot entre dans le salon ou en sort : l'écoute bascule entre le micro du casque PS5 et celui du Fold.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            // Bot dans le salon ET pilote présent : seul dans un salon vide, le bot ne prend pas le micro.
            discord.liveFlow.drop(1).collect {
                // Issue #45 : une vraie déconnexion du bot se distingue d'une conversation refermée par silence.
                if (::engine.isInitialized) {
                    engine.journalRadio(if (it) "DISCORD_CONNECTE" else "DISCORD_DECONNECTE", if (it) "Bot Discord présent dans le salon" else "Bot Discord sorti du salon ou déconnecté")
                }
                if (settings.discordMicInput) voiceInput.restart()
            }
        }
        engine = PitwallEngine(radioOut, voiceInput, DatabaseRepository.load(this), RaceStore(this)).also {
            voice.onAudible = it::onAudible
            discord.onAudible = it::onAudible
            // Issue #48 : anomalies de la voix envoyée au salon, au journal (aucun son gardé).
            discord.audioMonitor.enabled = settings.audioDiagnostics
            discord.onAudioEvent = { code, text -> it.journalRadio(code, text) }
            it.voiceEnabled = settings.voiceEnabled
            it.detailedLapReports = settings.detailedLapReports
            it.voiceBusy = { speaking.value }
            it.askPositionEachLap = { settings.askPositionEachLap && voiceInput.isRunning }
            it.languageModel = gemini
        }
        cloudAi = com.gt7pitwall.app.ai.CloudAi(this)
        engine.cloudRouter = cloudAi.router
        engine.onAiTrace = cloudAi::onTrace
        if (cloudAi.mode == com.gt7pitwall.core.ai.AiMode.LOCAL) engine.languageModel = null
        if (settings.aiEnabled) gemini.enable()
        sport = SportScheduleRepository(this, engine.database)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            sport.state.collect { s -> if (s != null) engine.applySchedule(s) }
        }
        sport.start()
        // Voitures et circuits : base communautaire relue chaque jour ; nouveaux circuits reconnus dans le programme Sport.
        database = DatabaseRepository(this, engine.database) { sport.rebuildNow() }
        engine.onUnknownCar = database::unknownCar
        database.start()
        // Zero touch : retour du réseau (Wi-Fi retrouvé, mode avion coupé) : nouvelle synchronisation si besoin.
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        sport.onNetworkBack()
                        database.onNetworkBack()
                    }
                },
            )
        }
        // Mesure Black Box (Atelier) : batterie et température relevées chaque minute pendant la mesure.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                if (engine.blackBox.running) {
                    runCatching {
                        val i = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                        val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                        val temp = i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
                        if (level >= 0 && scale > 0) engine.blackBox.battery(level * 100 / scale, temp)
                    }
                }
                kotlinx.coroutines.delay(60_000)
            }
        }
        freshness = combine(sport.state, database.status, minuteTicks()) { s, _, _ ->
            val now = System.currentTimeMillis()
            com.gt7pitwall.core.sport.DataFreshness.of(
                s, java.time.LocalDate.now(com.gt7pitwall.core.sport.GtPlanetFeed.PARIS), now, database.lastSuccessMs, database.lastError,
            )
        }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, null)
    }

    /** Fraîcheur des données (programme Sport, base voitures/circuits), recalculée chaque minute. */
    lateinit var freshness: kotlinx.coroutines.flow.StateFlow<com.gt7pitwall.core.sport.DataFreshness?>

    private fun minuteTicks() = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(Unit)
            kotlinx.coroutines.delay(60_000)
        }
    }

    /** Chaque ouverture de l'écran : synchronisation non bloquante (programme de la semaine, nouvelles voitures). */
    fun syncOnOpen() {
        sport.onOpen()
        database.onOpen()
    }

    /**
     * Où le micro peut entendre une interruption : Discord (flux du pilote séparé de la voix du bot) ou Fold au casque :
     * pendant la parole ; Fold sur haut-parleur : seulement entre les phrases (le micro entendrait l'ingénieur).
     */
    fun duplexMode(): com.gt7pitwall.core.radio.DuplexMode {
        if (!settings.bargeIn) return com.gt7pitwall.core.radio.DuplexMode.OFF
        // Voix dans le casque PS5 (Discord) : pas d'interruption pendant la parole sans choix explicite (issue #45).
        return com.gt7pitwall.core.radio.DuplexPolicy.mode(
            radioOut.route, phoneSpeaker = phoneOnSpeaker(), remoteDuplexOptIn = settings.remoteBargeIn,
        )
    }

    /** La voix du Fold sort par le haut-parleur intégré (aucun casque ni écouteurs branchés ou connectés). */
    private fun phoneOnSpeaker(): Boolean {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return true
        val headset = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).none { it.type in headset }
    }

    /** Active ou coupe l'ingénieur IA (la transcription libre de Vosk reste active dans les deux cas). */
    fun setAi(enabled: Boolean) {
        settings.aiEnabled = enabled
        if (enabled) gemini.enable() else gemini.disable()
    }

    /** Modèle de l'ingénieur : cloud (Flash-Lite, Flash 3.8), Gemini Nano, ou moteur local seul. */
    fun setAiMode(mode: com.gt7pitwall.core.ai.AiMode) {
        cloudAi.setMode(mode)
        // Moteur local uniquement : aucune IA, même sur le téléphone.
        engine.languageModel = if (mode == com.gt7pitwall.core.ai.AiMode.LOCAL) null else gemini
    }

    /** Expérimental : Google sur le flux du casque PS5. */
    fun setGoogleOnHeadset(enabled: Boolean) {
        settings.googleOnHeadset = enabled
        voiceInput.googleOnHeadset = enabled
    }

    /** Outils de développement : masqués, le banc d'essai est arrêté (ni calcul ni mémoire). */
    fun setDevTools(enabled: Boolean) {
        settings.devTools = enabled
        if (!enabled) bench.stop()
    }

    /** Lancement de la télémétrie : le bot rejoint le dernier salon si le réglage est actif. */
    /** Démarrage automatique de la télémétrie à l'ouverture (issue #55) : une demande par ouverture réelle. */
    val autoConnect = com.gt7pitwall.core.telemetry.AutoConnect()

    fun onTelemetryStart() {
        if (settings.discordAutoJoin) discord.autoStart()
    }

    /** Durée de silence avant la fermeture de la conversation continue. */
    fun setConversationIdle(seconds: Int) {
        settings.conversationIdleS = seconds
        voiceInput.conversationIdleMs = settings.conversationIdleS * 1_000L
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
