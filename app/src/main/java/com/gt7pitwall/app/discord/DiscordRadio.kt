package com.gt7pitwall.app.discord

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.kyokobot.libdave.NativeDaveFactory
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.internal.utils.JDALogger
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

enum class DiscordState { NO_TOKEN, OFFLINE, CONNECTING, ONLINE, JOINING, IN_VOICE, ERROR }

data class VoiceChannelInfo(val id: Long, val name: String, val guild: String)

/**
 * Prototype « radio Discord » : le Fold se connecte comme bot à un serveur Discord privé, rejoint un salon vocal
 * (chiffrement de bout en bout DAVE via libdave-jvm, compilé depuis les sources pour arm64) et y diffuse la voix de
 * l'ingénieur. Sur la PS5, le pilote rejoint ce salon : la voix arrive dans le casque branché sur la DualSense.
 *
 * Le token n'est jamais journalisé ni affiché : il est lu depuis [DiscordTokenStore] au moment de la connexion.
 */
class DiscordRadio(context: Context, private val tokens: DiscordTokenStore) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val speech by lazy { SpeechPcm(appContext) }
    // Créé à la première utilisation : aucune classe de JDA n'est chargée au lancement de l'application.
    private val sender by lazy { OpusQueueSender() }
    private var jda: JDA? = null

    private val _state = MutableStateFlow(if (tokens.hasToken) DiscordState.OFFLINE else DiscordState.NO_TOKEN)
    val state: StateFlow<DiscordState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<String?>(null)
    /** Précision lisible (erreur, état de la connexion vocale, statistiques Opus). Jamais le token. */
    val detail: StateFlow<String?> = _detail.asStateFlow()

    private val _channels = MutableStateFlow<List<VoiceChannelInfo>>(emptyList())
    val channels: StateFlow<List<VoiceChannelInfo>> = _channels.asStateFlow()

    /** Bibliothèque DAVE présente dans l'APK (compilée par la CI ; absente d'une compilation locale). */
    val daveLibraryPath: File = File(appContext.applicationInfo.nativeLibraryDir, "libdave-jvm.so")

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

    fun connect() {
        scope.launch {
            lock.withLock {
                if (jda != null) return@withLock
                val token = tokens.load()
                if (token == null) {
                    _state.value = DiscordState.NO_TOKEN
                    _detail.value = "Aucun token : colle le token du bot puis « Enregistrer »."
                    return@withLock
                }
                _state.value = DiscordState.CONNECTING
                _detail.value = "Chargement du chiffrement DAVE…"
                try {
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
                } catch (e: InvalidTokenException) {
                    fail("Token refusé par Discord. Vérifie-le sur le portail développeur.", null)
                } catch (e: Throwable) {
                    fail("Connexion impossible", e)
                }
            }
        }
    }

    fun join(channelId: Long) {
        scope.launch {
            val client = jda ?: return@launch
            val channel = client.getVoiceChannelById(channelId)
            if (channel == null) {
                _detail.value = "Salon introuvable."
                return@launch
            }
            tokens.channelId = channelId
            _state.value = DiscordState.JOINING
            _detail.value = "Connexion au salon « ${channel.name} »…"
            try {
                val audio = channel.guild.audioManager
                audio.sendingHandler = sender
                audio.connectionListener = object : ConnectionListener {
                    override fun onStatusChange(status: ConnectionStatus) {
                        when {
                            status == ConnectionStatus.CONNECTED -> {
                                _state.value = DiscordState.IN_VOICE
                                _detail.value = "Dans le salon « ${channel.name} », chiffrement DAVE actif. Lance la phrase test."
                            }
                            !status.shouldReconnect() && status.name.startsWith("DISCONNECTED") -> {
                                _state.value = DiscordState.ONLINE
                                _detail.value = "Sorti du salon (${status.name})."
                            }
                            status.name.startsWith("ERROR") -> _detail.value = "Voix : ${status.name}, nouvelle tentative…"
                            else -> _detail.value = "Voix : ${status.name}"
                        }
                    }
                }
                audio.openAudioConnection(channel)
            } catch (e: Throwable) {
                fail("Impossible de rejoindre le salon (permissions « Se connecter » et « Parler » ?)", e)
            }
        }
    }

    /** Phrase de test : vérifie toute la chaîne voix → Opus → DAVE → Discord → PS5 → casque, sans GT7. */
    fun sayTest() = speak("Radio check. Ici ton ingénieur Pitwall. Si tu m'entends dans le casque de la manette, la radio Discord fonctionne.")

    fun speak(text: String) {
        scope.launch {
            if (_state.value != DiscordState.IN_VOICE) {
                _detail.value = "Rejoins d'abord un salon vocal."
                return@launch
            }
            try {
                val pcm = speech.synthesize(text)
                if (pcm == null) {
                    _detail.value = "Synthèse vocale impossible."
                    return@launch
                }
                val frames = OpusFrames.encode(pcm)
                val durations = frames.mapNotNull { OpusFrames.frameDurationTenthMs(it) }.distinct()
                sender.enqueue(frames)
                _detail.value = "Phrase envoyée : ${frames.size} trames Opus (${pcm.size * 1000L / OpusFrames.SAMPLE_RATE} ms" +
                    (if (durations == listOf(200)) ", 20 ms chacune)." else ", durées ${durations.joinToString { "${it / 10.0} ms" }}).")
            } catch (e: Throwable) {
                fail("Envoi de la voix impossible", e)
            }
        }
    }

    fun leave() {
        scope.launch {
            val client = jda ?: return@launch
            client.guilds.forEach { it.audioManager.closeAudioConnection() }
            sender.clear()
            _state.value = DiscordState.ONLINE
        }
    }

    /** Déconnexion propre : quitte le salon, ferme la session Discord. */
    fun disconnect() {
        val client = jda ?: return
        jda = null
        scope.launch {
            runCatching {
                sender.clear()
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
        // Par prudence, on ne laisse jamais passer le token dans un message d'erreur.
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
