package com.gt7pitwall.app.discord

import android.content.Context
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.radio.RemoteVoice
import com.gt7pitwall.core.radio.SpeechQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.kyokobot.libdave.NativeDaveFactory
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.audio.AudioReceiveHandler
import net.dv8tion.jda.api.audio.OpusPacket
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.internal.utils.JDALogger
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

enum class DiscordState { NO_TOKEN, OFFLINE, CONNECTING, ONLINE, JOINING, IN_VOICE, ERROR }

data class VoiceChannelInfo(val id: Long, val name: String, val guild: String)

/** Test du micro PS5 : ce que le bot reçoit du salon et ce que la reconnaissance en comprend. */
data class MicReport(
    val packets: Int = 0,
    val speaker: String? = null,
    /** Niveau de la voix reçue, 0 à 100. */
    val level: Int = 0,
    val heard: List<String> = emptyList(),
    val partial: String? = null,
    val note: String? = null,
)

/**
 * Radio Discord : le Fold se connecte comme bot à un serveur Discord privé, rejoint un salon vocal (chiffrement de bout
 * en bout DAVE via libdave-jvm, compilé depuis les sources pour arm64) et y diffuse la voix de l'ingénieur. Sur la PS5,
 * le pilote rejoint ce salon : la voix arrive dans le casque branché sur la DualSense.
 *
 * Sortie vocale [RemoteVoice] : file dans l'ordre, alerte critique prioritaire ; un message non diffusé (connexion
 * perdue) est rendu à l'appelant, qui le dit sur le haut-parleur du Fold. Le salon est surveillé et rejoint à nouveau
 * après une coupure.
 *
 * Le token n'est jamais journalisé ni affiché : il est lu depuis [DiscordTokenStore] au moment de la connexion.
 */
class DiscordRadio(context: Context, private val tokens: DiscordTokenStore) : RemoteVoice {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val speech by lazy { SpeechPcm(appContext) }
    // Créé à la première utilisation : aucune classe de JDA n'est chargée au lancement de l'application.
    private val sender by lazy { OpusQueueSender() }
    private var jda: JDA? = null

    private class Queued(val message: EngineerMessage, val onFailed: (EngineerMessage) -> Unit)

    private val queue = SpeechQueue<Queued> { it.message.priority }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val phrases = PhraseCache(PHRASE_CACHE_SIZE)

    /** Salon que le bot doit occuper (null : le pilote l'a quitté ou n'en a pas choisi). */
    @Volatile
    private var wantedChannel: Long? = null
    private var watchdog: Job? = null

    private val _state = MutableStateFlow(if (tokens.hasToken) DiscordState.OFFLINE else DiscordState.NO_TOKEN)
    val state: StateFlow<DiscordState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<String?>(null)
    /** Précision lisible (erreur, état de la connexion vocale, statistiques Opus). Jamais le token. */
    val detail: StateFlow<String?> = _detail.asStateFlow()

    private val _mic = MutableStateFlow<MicReport?>(null)
    /** Test du micro PS5 en cours (null : arrêté). */
    val mic: StateFlow<MicReport?> = _mic.asStateFlow()
    private var micJob: Job? = null
    @Volatile
    private var micPackets: Channel<Pair<Long, ByteArray>>? = null
    private val receiver by lazy { PsMicReceiver { user, opus -> micPackets?.trySend(user to opus) } }

    private val _channels = MutableStateFlow<List<VoiceChannelInfo>>(emptyList())
    val channels: StateFlow<List<VoiceChannelInfo>> = _channels.asStateFlow()

    /** Bibliothèque DAVE présente dans l'APK (compilée par la CI ; absente d'une compilation locale). */
    val daveLibraryPath: File = File(appContext.applicationInfo.nativeLibraryDir, "libdave-jvm.so")

    /** Vitesse de la voix, la même que sur le haut-parleur (la synthèse n'est créée qu'à la première phrase). */
    @Volatile
    var speechRate: Float = 1.05f

    /** Salon mémorisé : rejoint automatiquement à la connexion du bot. */
    val savedChannel: Long? get() = tokens.channelId

    override val live: Boolean get() = _state.value == DiscordState.IN_VOICE

    init {
        scope.launch { speakLoop() }
    }

    fun saveToken(token: String) {
        tokens.save(token)
        if (_state.value == DiscordState.NO_TOKEN) _state.value = DiscordState.OFFLINE
        _detail.value = "Token enregistré, chiffré sur le téléphone."
    }

    fun forgetToken() {
        disconnect()
        tokens.clear()
        _state.value = DiscordState.NO_TOKEN
        _detail.value = "Token effacé."
    }

    /** Connecte le bot ; avec [joinSaved], rejoint aussitôt le dernier salon utilisé. */
    fun connect(joinSaved: Boolean = false) {
        scope.launch {
            lock.withLock {
                if (jda == null && !openSession()) return@withLock
                val saved = tokens.channelId
                if (joinSaved && saved != null && _state.value == DiscordState.ONLINE) joinNow(saved)
            }
        }
    }

    /** Lancement de la télémétrie : bot connecté et salon rejoint si un token et un salon sont mémorisés. */
    fun autoStart() {
        if (!tokens.hasToken || tokens.channelId == null) return
        if (_state.value == DiscordState.IN_VOICE || _state.value == DiscordState.JOINING) return
        connect(joinSaved = true)
    }

    private fun openSession(): Boolean {
        val token = tokens.load()
        if (token == null) {
            _state.value = DiscordState.NO_TOKEN
            _detail.value = "Aucun token : colle le token du bot puis « Enregistrer »."
            return false
        }
        _state.value = DiscordState.CONNECTING
        _detail.value = "Chargement du chiffrement DAVE…"
        return try {
            loadDave()
            _detail.value = "Connexion à Discord…"
            // Pas de journal de repli de JDA : rien ne doit pouvoir écrire d'informations de connexion.
            JDALogger.setFallbackLoggerEnabled(false)
            val client = JDABuilder.createLight(token, listOf(GatewayIntent.GUILD_VOICE_STATES))
                .enableCache(CacheFlag.VOICE_STATE)
                .setAudioModuleConfig(AudioModuleConfig().withDaveSessionFactory(LDJDADaveSessionFactory(NativeDaveFactory())))
                .build()
            client.awaitReady()
            jda = client
            val list = client.voiceChannels.map { VoiceChannelInfo(it.idLong, it.name, it.guild.name) }
            _channels.value = list
            _state.value = DiscordState.ONLINE
            _detail.value = when {
                list.isEmpty() -> "Connecté, mais le bot n'est dans aucun serveur avec un salon vocal : invite-le dans ton serveur."
                else -> "Connecté en tant que ${client.selfUser.name}. Choisis le salon vocal."
            }
            true
        } catch (e: InvalidTokenException) {
            fail("Token refusé par Discord. Vérifie-le sur le portail développeur.", null)
            false
        } catch (e: Throwable) {
            fail("Connexion impossible", e)
            false
        }
    }

    fun join(channelId: Long) {
        scope.launch { lock.withLock { joinNow(channelId) } }
    }

    private fun joinNow(channelId: Long) {
        val client = jda ?: return
        val channel = client.getVoiceChannelById(channelId)
        if (channel == null) {
            _detail.value = "Salon introuvable (supprimé, ou bot retiré du serveur ?)."
            return
        }
        tokens.channelId = channelId
        wantedChannel = channelId
        _state.value = DiscordState.JOINING
        _detail.value = "Connexion au salon « ${channel.name} »…"
        try {
            val audio = channel.guild.audioManager
            audio.sendingHandler = sender
            audio.isAutoReconnect = true
            audio.connectionListener = object : ConnectionListener {
                override fun onStatusChange(status: ConnectionStatus) = onVoiceStatus(channel.name, status)
            }
            audio.openAudioConnection(channel)
            startWatchdog()
        } catch (e: Throwable) {
            fail("Impossible de rejoindre le salon (permissions « Se connecter » et « Parler » ?)", e)
        }
    }

    private fun onVoiceStatus(channelName: String, status: ConnectionStatus) {
        when {
            status == ConnectionStatus.CONNECTED -> {
                _state.value = DiscordState.IN_VOICE
                _detail.value = "Dans le salon « $channelName », chiffrement DAVE actif : l'ingénieur parle sur Discord."
            }
            wantedChannel == null -> {
                if (status.name.startsWith("DISCONNECTED") || status == ConnectionStatus.NOT_CONNECTED) {
                    if (jda != null) _state.value = DiscordState.ONLINE
                    _detail.value = "Sorti du salon. L'ingénieur parle sur le haut-parleur du Fold."
                }
            }
            _state.value == DiscordState.IN_VOICE -> {
                // Coupure : le haut-parleur du Fold prend le relais pendant la reconnexion.
                _state.value = DiscordState.JOINING
                failPending()
                _detail.value = "Reconnexion au salon « $channelName » (${status.name}) : voix sur le Fold en attendant."
            }
            else -> _detail.value = "Salon « $channelName » : ${status.name}"
        }
    }

    /** Toutes les 5 s : si le salon voulu n'est plus occupé et que JDA ne le rejoint pas seul, on le rejoint. */
    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            var downSince = 0L
            while (isActive) {
                delay(WATCHDOG_MS)
                val client = jda ?: break
                val wanted = wantedChannel ?: continue
                val channel = client.getVoiceChannelById(wanted) ?: continue
                val audio = channel.guild.audioManager
                val status = audio.connectionStatus
                if (status == ConnectionStatus.CONNECTED) {
                    downSince = 0L
                    if (_state.value != DiscordState.IN_VOICE) onVoiceStatus(channel.name, status)
                    continue
                }
                val now = System.currentTimeMillis()
                if (downSince == 0L) downSince = now
                if (now - downSince >= REJOIN_AFTER_MS && !status.name.startsWith("CONNECTING")) {
                    downSince = now
                    lock.withLock { if (wantedChannel == wanted) joinNow(wanted) }
                }
            }
        }
    }

    /**
     * Prototype « micro PS5 » : le bot écoute le salon (voix déchiffrée par JDA et DAVE), décode l'Opus avec Android et
     * transcrit avec Vosk ([modelDir], le modèle de la radio vocale). Rien n'est encore transmis à l'ingénieur.
     */
    fun startMicTest(modelDir: File?) {
        val client = jda ?: return
        val channel = wantedChannel?.let { client.getVoiceChannelById(it) }
        if (channel == null || !live) {
            _mic.value = MicReport(note = "Rejoins d'abord le salon vocal.")
            return
        }
        if (micJob?.isActive == true) return
        val packets = Channel<Pair<Long, ByteArray>>(MIC_BUFFER, BufferOverflow.DROP_OLDEST)
        micPackets = packets
        _mic.value = MicReport(note = if (modelDir == null) MIC_NO_MODEL else "Chargement de la reconnaissance…")
        micJob = scope.launch(Dispatchers.Default) {
            try {
                channel.guild.audioManager.receivingHandler = receiver
                runMicTest(client, packets, modelDir)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val why = generateSequence(e) { t -> t.cause?.takeIf { c -> c !== t } }.take(3)
                    .joinToString(" ← ") { t -> t.javaClass.simpleName + (t.message?.let { m -> " ($m)" } ?: "") }
                _mic.value = (_mic.value ?: MicReport()).copy(note = "Erreur : $why")
            }
        }
    }

    fun stopMicTest() {
        micJob?.cancel()
        micJob = null
        micPackets = null
        _mic.value = null
        runCatching { jda?.guilds?.forEach { it.audioManager.receivingHandler = null } }
    }

    private suspend fun runMicTest(client: JDA, packets: Channel<Pair<Long, ByteArray>>, modelDir: File?) {
        val decoder = OpusDecoderStream()
        val model = modelDir?.let { Model(it.absolutePath) }
        val recognizer = model?.let { Recognizer(it, OpusDecoderStream.OUTPUT_RATE.toFloat()) }
        var report = MicReport(note = if (recognizer == null) MIC_NO_MODEL else "Parle dans le micro du casque…")
        _mic.value = report
        var silenceFedMs = SILENCE_TAIL_MS
        var lastPublish = 0L
        try {
            while (true) {
                val item = withTimeoutOrNull(SILENCE_STEP_MS) { packets.receive() }
                if (item == null) {
                    // Discord n'envoie rien quand tu te tais : du silence permet à Vosk de finir la phrase.
                    if (recognizer != null && silenceFedMs < SILENCE_TAIL_MS) {
                        val silence = ShortArray((OpusDecoderStream.OUTPUT_RATE * SILENCE_STEP_MS / 1000).toInt())
                        report = recognize(recognizer, silence, report)
                        silenceFedMs += SILENCE_STEP_MS
                    }
                    report = report.copy(level = 0)
                    _mic.value = report
                    continue
                }
                silenceFedMs = 0
                val (user, opus) = item
                val pcm = decoder.decode(opus)
                report = report.copy(
                    packets = report.packets + 1,
                    speaker = report.speaker ?: (client.getUserById(user)?.name ?: "utilisateur $user"),
                    level = level(pcm),
                )
                if (recognizer != null && pcm.isNotEmpty()) report = recognize(recognizer, pcm, report)
                val now = System.currentTimeMillis()
                if (now - lastPublish >= PUBLISH_MS) {
                    lastPublish = now
                    _mic.value = report
                }
            }
        } finally {
            decoder.release()
            recognizer?.close()
            model?.close()
        }
    }

    private fun recognize(recognizer: Recognizer, pcm: ShortArray, report: MicReport): MicReport =
        if (recognizer.acceptWaveForm(pcm, pcm.size)) {
            val text = JSONObject(recognizer.result).optString("text").trim()
            if (text.isEmpty()) report.copy(partial = null) else report.copy(heard = (listOf(text) + report.heard).take(5), partial = null)
        } else {
            report.copy(partial = JSONObject(recognizer.partialResult).optString("partial").trim().ifEmpty { null })
        }

    private fun level(pcm: ShortArray): Int {
        if (pcm.isEmpty()) return 0
        var sum = 0.0
        for (v in pcm) sum += v.toDouble() * v
        val rms = kotlin.math.sqrt(sum / pcm.size)
        return (rms / 50).toInt().coerceIn(0, 100)
    }

    /** Phrase de test : vérifie toute la chaîne voix → Opus → DAVE → Discord → PS5 → casque, sans GT7. */
    fun sayTest() {
        if (!live) {
            _detail.value = "Rejoins d'abord un salon vocal."
            return
        }
        speak(
            EngineerMessage(
                "Radio check. Ici ton ingénieur Pitwall. Si tu m'entends dans le casque de la manette, la radio Discord fonctionne.",
                Priority.HIGH,
                MessageCategory.RADIO,
            ),
        ) { _detail.value = "Phrase test non diffusée (connexion au salon perdue)." }
    }

    override fun speak(message: EngineerMessage, onFailed: (EngineerMessage) -> Unit) {
        if (!live) {
            onFailed(message)
            return
        }
        val dropped = queue.offer(Queued(message, onFailed))
        // Alerte critique : la phrase en cours est coupée, les messages en attente sont abandonnés (comme sur le Fold).
        if (dropped != null) sender.clear()
        wake.trySend(Unit)
    }

    override fun stop() {
        queue.drain()
        sender.clear()
    }

    /** Diffuse la file une phrase après l'autre : synthèse → Opus → attente de la fin de la lecture. */
    private suspend fun speakLoop() {
        while (true) {
            wake.receive()
            while (true) {
                val generation = queue.generation
                val item = queue.poll() ?: break
                if (!live) {
                    item.onFailed(item.message)
                    continue
                }
                val frames = try {
                    framesFor(item.message.text)
                } catch (e: Throwable) {
                    null
                }
                if (frames == null) {
                    item.onFailed(item.message)
                    continue
                }
                // Une alerte critique est arrivée pendant la synthèse : cette phrase est abandonnée.
                if (queue.generation != generation && item.message.priority != Priority.CRITICAL) continue
                if (!live) {
                    item.onFailed(item.message)
                    continue
                }
                sender.enqueue(frames)
                val started = System.currentTimeMillis()
                val maxMs = frames.size * FRAME_MS + PLAYBACK_MARGIN_MS
                while (sender.canProvide() && queue.generation == generation && live &&
                    System.currentTimeMillis() - started < maxMs
                ) {
                    delay(FRAME_MS)
                }
            }
        }
    }

    /** Trames Opus d'une phrase ; les phrases courtes et répétées (« Je t'écoute. ») sont gardées en mémoire. */
    private suspend fun framesFor(text: String): List<ByteArray>? {
        val key = "$speechRate|$text"
        phrases[key]?.let { return it }
        speech.speechRate = speechRate
        val pcm = speech.synthesize(text) ?: return null
        val frames = OpusFrames.encode(pcm)
        if (frames.isEmpty()) return null
        if (text.length <= CACHEABLE_CHARS) phrases[key] = frames
        return frames
    }

    /** Messages en attente rendus à l'appelant (haut-parleur du Fold) : la connexion au salon est perdue. */
    private fun failPending() {
        sender.clear()
        queue.drain().forEach { it.onFailed(it.message) }
    }

    fun leave() {
        stopMicTest()
        scope.launch {
            lock.withLock {
                wantedChannel = null
                val client = jda ?: return@withLock
                failPending()
                client.guilds.forEach { it.audioManager.closeAudioConnection() }
                _state.value = DiscordState.ONLINE
                _detail.value = "Sorti du salon. L'ingénieur parle sur le haut-parleur du Fold."
            }
        }
    }

    /** Déconnexion propre : quitte le salon, ferme la session Discord. */
    fun disconnect() {
        stopMicTest()
        wantedChannel = null
        watchdog?.cancel()
        val client = jda ?: return
        jda = null
        scope.launch {
            runCatching {
                failPending()
                client.guilds.forEach { it.audioManager.closeAudioConnection() }
                client.shutdown()
                if (!client.awaitShutdown(SHUTDOWN_S, TimeUnit.SECONDS)) client.shutdownNow()
            }
            _channels.value = emptyList()
            _state.value = if (tokens.hasToken) DiscordState.OFFLINE else DiscordState.NO_TOKEN
            _detail.value = "Déconnecté."
        }
    }

    /**
     * libdave-jvm cherche sa bibliothèque native par une propriété système : on lui donne celle de l'APK
     * (extraite par Android dans nativeLibraryDir), au lieu d'une extraction depuis les ressources Java.
     */
    private fun loadDave() {
        check(daveLibraryPath.exists()) {
            "Bibliothèque DAVE absente de cet APK (libdave-jvm.so). Utilise l'APK compilé par GitHub."
        }
        System.setProperty("lava.native.dave-jvm.path", daveLibraryPath.absolutePath)
        NativeDaveFactory.ensureAvailable()
    }

    private fun fail(message: String, e: Throwable?) {
        val token = tokens.load()
        // Type et message de chaque cause : de quoi diagnostiquer depuis une capture d'écran.
        val why = e?.let {
            generateSequence(it) { t -> t.cause?.takeIf { c -> c !== t } }.take(4)
                .joinToString(" ← ") { t -> t.javaClass.simpleName + (t.message?.let { m -> " ($m)" } ?: "") }
                .let { m -> if (token != null) m.replace(token, "•••") else m }
        }
        _state.value = DiscordState.ERROR
        _detail.value = if (why != null) "$message : $why" else message
    }

    private companion object {
        const val SHUTDOWN_S = 5L
        const val FRAME_MS = 20L
        const val PLAYBACK_MARGIN_MS = 2_000L
        const val WATCHDOG_MS = 5_000L
        const val REJOIN_AFTER_MS = 20_000L
        const val PHRASE_CACHE_SIZE = 40
        const val CACHEABLE_CHARS = 60
        const val MIC_BUFFER = 500
        const val SILENCE_STEP_MS = 200L
        const val SILENCE_TAIL_MS = 1_600L
        const val PUBLISH_MS = 150L
        const val MIC_NO_MODEL = "Pas de modèle vocal : télécharge-le dans la carte « Radio vocale » pour la transcription. " +
            "Les paquets et le niveau s'affichent quand même."
    }
}

/** Reçoit la voix du salon déjà déchiffrée (transport + DAVE), en Opus : décodée ensuite par Android. */
class PsMicReceiver(private val onPacket: (userId: Long, opus: ByteArray) -> Unit) : AudioReceiveHandler {
    override fun canReceiveEncoded(): Boolean = true

    override fun handleEncodedAudio(packet: OpusPacket) = onPacket(packet.userId, packet.opusAudio)
}

/** Petit cache LRU de phrases encodées. */
private class PhraseCache(private val capacity: Int) {
    private val map = object : LinkedHashMap<String, List<ByteArray>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ByteArray>>?) = size > capacity
    }

    @Synchronized
    operator fun get(key: String): List<ByteArray>? = map[key]

    @Synchronized
    operator fun set(key: String, value: List<ByteArray>) {
        map[key] = value
    }
}

/** File de trames Opus 20 ms lue par JDA toutes les 20 ms (audio déjà encodé : pas d'opus-java). */
class OpusQueueSender : AudioSendHandler {
    private val queue = ConcurrentLinkedQueue<ByteArray>()

    fun enqueue(frames: List<ByteArray>) {
        queue.addAll(frames)
    }

    fun clear() = queue.clear()

    override fun canProvide(): Boolean = queue.isNotEmpty()

    override fun provide20MsAudio(): ByteBuffer? = queue.poll()?.let { ByteBuffer.wrap(it) }

    override fun isOpus(): Boolean = true
}
