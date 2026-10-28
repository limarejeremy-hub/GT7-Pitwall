package com.gt7pitwall.app.voice

import android.os.SystemClock
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.radio.ConversationWindow
import com.gt7pitwall.core.radio.DuplexMode
import com.gt7pitwall.core.radio.DuplexPolicy
import com.gt7pitwall.core.radio.InterruptDetector
import com.gt7pitwall.core.radio.ListenWatchdog
import com.gt7pitwall.core.radio.VoiceActivity
import com.gt7pitwall.core.radio.GoogleOutcome
import com.gt7pitwall.core.radio.RadioTone
import com.gt7pitwall.core.radio.TonePlayer
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.RequestArbiter
import com.gt7pitwall.core.radio.RequestPick
import com.gt7pitwall.core.radio.SpeechFallbackPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import com.gt7pitwall.core.radio.VoiceInput
import com.gt7pitwall.core.radio.VoiceInputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

enum class ListenState { OFF, LOADING, WAITING_WAKE_WORD, AWAKE, PAUSED, ERROR }

/**
 * Radio du pilote : reconnaissance vocale française hors ligne (Vosk) avec mot de réveil.
 *
 * "radio, combien d'essence ?" -> phrase transmise directement, et la conversation reste ouverte.
 * "radio" seul -> bip court quand le micro écoute vraiment, puis conversation continue : après chaque réponse, l'écoute
 * reprend sans mot de réveil ni bip, jusqu'à « terminé » ou [conversationIdleMs] de silence (double bip).
 * Le micro est mis en pause pendant que l'ingénieur parle, sur le Fold comme sur Discord ([speaking] réunit les deux).
 *
 * Deux reconnaissances écoutent le même micro : l'une limitée au vocabulaire de la radio (fiable dans le bruit,
 * utilisée pour les commandes), l'autre libre ([freeTranscription], pour les questions posées à l'IA).
 *
 * Micro : celui du casque PS5 via le bot Discord quand [remoteMic] en fournit un, sinon celui du Fold. Avec le micro
 * PS5, la phrase après « radio » est transmise en flux à la reconnaissance Google ; si elle refuse ce flux, Vosk la
 * prend.
 */
class VoskVoiceInput(
    private val models: VoiceModelManager,
    /** L'ingénieur parle réellement (haut-parleur du Fold ou salon Discord, fin de lecture comprise). */
    private val speaking: StateFlow<Boolean>,
    /** Bips de la radio, joués là où l'ingénieur parle (casque PS5 via Discord, ou Fold). */
    private val tones: TonePlayer,
) : VoiceInput {

    /** Conversation continue ouverte par « radio » (règles testées dans le module core). */
    private val conversation = ConversationWindow()

    /** Silence (ms) après lequel la conversation se referme ; chaque phrase intelligible la prolonge. */
    var conversationIdleMs: Long
        get() = conversation.idleMs
        set(value) {
            conversation.idleMs = value.coerceIn(3_000L, 60_000L)
        }

    private val _conversationOpen = MutableStateFlow(false)
    /** Conversation ouverte (affichage discret, rien n'est dit). */
    val conversationOpen: StateFlow<Boolean> = _conversationOpen.asStateFlow()

    /**
     * Interruption (barge-in) : où le micro peut écouter pendant que l'ingénieur parle. [DuplexMode.FULL] : flux du pilote
     * séparé de la voix (Discord) ou voix au casque ; [DuplexMode.GAPS] : seulement dans les pauses entre ses phrases
     * ([inGap]) ; [DuplexMode.OFF] : micro coupé pendant la parole, comme avant.
     */
    @Volatile
    var duplexMode: () -> DuplexMode = { DuplexMode.OFF }

    /** Pause entre deux phrases de l'ingénieur (synthèse du Fold en mode [DuplexMode.GAPS]). */
    var inGap: StateFlow<Boolean>? = null

    /** Message que dit l'ingénieur (écho reconnu, alerte critique jamais coupée). */
    @Volatile
    var engineerMessage: () -> EngineerMessage? = { null }

    /** Une réponse est en préparation (moteur ou IA) : la conversation ne se referme pas. */
    @Volatile
    var isThinking: () -> Boolean = { false }

    private val voiceActivity = VoiceActivity()
    private val interrupts = InterruptDetector()

    /** Une fausse interruption a déjà été notée pour la phrase en cours de l'ingénieur. */
    private var rejectionNoted = false
    /** Sans flux séparé, la fin d'une phrase résonne encore un instant dans le micro. */
    private val gapInterrupts = InterruptDetector(echoGuardMs = GAP_ECHO_GUARD_MS)
    private var gapJob: Job? = null

    /** Le pilote vient de couper l'ingénieur : l'écoute reprend sans attendre. */
    @Volatile
    private var bargedIn = false

    /** Après une écoute pendant la parole, la reconnaissance repart d'un état propre. */
    @Volatile
    private var resetRecognizer = false

    /** Début et fin de la dernière phrase du pilote (horloge monotone), pour la mesure de latence. */
    @Volatile
    var lastSpeechStartMs: Long? = null
        private set

    /** Fin de la dernière phrase de l'ingénieur (horloge monotone). */
    private var lastEngineerEndMs = Long.MIN_VALUE / 2

    /** La phrase du pilote a commencé pendant la voix de l'ingénieur ou juste après : un écho reste possible (#45). */
    private fun nearEngineer(): Boolean {
        val start = lastSpeechStartMs ?: SystemClock.elapsedRealtime()
        return speaking.value || bargedIn || start - lastEngineerEndMs <= ECHO_TAIL_MS
    }

    @Volatile
    var lastSpeechEndMs: Long? = null
        private set

    /** Dernière fois que Vosk entendait quelqu'un parler (phrase en cours) : la conversation ne se ferme pas au milieu. */
    @Volatile
    private var lastVoiceActivityMs = 0L
    private var tickJob: Job? = null
    private var reopenJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _events = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<VoiceInputEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(ListenState.OFF)
    val state: StateFlow<ListenState> = _state.asStateFlow()

    private val _heard = MutableStateFlow("")
    /** Dernière phrase reconnue (affichée pour vérifier la reconnaissance). */
    val heard: StateFlow<String> = _heard.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    var wakeWord: String = "radio"

    /**
     * Reconnaissance limitée au vocabulaire de la radio (beaucoup plus fiable dans le bruit du jeu).
     * Si le mot de réveil choisi n'existe pas dans le modèle, il faut la désactiver.
     */
    @Volatile
    var limitedVocabulary: Boolean = true

    /** Transcription libre en parallèle, pour l'IA de la radio. */
    @Volatile
    var freeTranscription: Boolean = false

    private var model: Model? = null
    private var audioJob: Job? = null

    /** Micro du casque PS5 (bot Discord dans le salon), ou null : micro du Fold. */
    var remoteMic: () -> PcmSource? = { null }

    private val _micLabel = MutableStateFlow<String?>(null)
    /** Micro écouté en ce moment (null : écoute arrêtée). */
    val micLabel: StateFlow<String?> = _micLabel.asStateFlow()

    @Volatile
    private var phoneMicInUse = true

    /**
     * Repli sur Vosk quand Google échoue (testé dans le module core), compté séparément pour chaque micro : des échecs
     * sur le flux du casque ne suspendent pas Google sur le micro du Fold.
     */
    private val phoneFallback = SpeechFallbackPolicy()
    private val streamFallback = SpeechFallbackPolicy()
    private val fallback: SpeechFallbackPolicy get() = if (phoneMicInUse) phoneFallback else streamFallback

    private val _fallbackInfo = MutableStateFlow<String?>(null)
    /** État du repli affiché à l'écran (null : Google normal). */
    val fallbackInfo: StateFlow<String?> = _fallbackInfo.asStateFlow()

    private val _handover = MutableStateFlow<String?>(null)
    /** Dernier passage du micro du Fold à Google : libéré, et en combien de temps. */
    val handover: StateFlow<String?> = _handover.asStateFlow()

    /** Une demande reconnue (ou perdue) : par quel moteur, et pourquoi ce moteur. Texte seulement, à l'écran. */
    data class Recognition(val time: String, val text: String?, val by: String, val why: String?)

    private val _history = MutableStateFlow<List<Recognition>>(emptyList())
    /** Dernières demandes, la plus récente en premier. */
    val history: StateFlow<List<Recognition>> = _history.asStateFlow()

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    private fun log(by: String, text: String?, why: String?) {
        val entry = Recognition(clock.format(Date()), text?.take(80), by, why)
        _history.value = (listOf(entry) + _history.value).take(HISTORY_SIZE)
    }

    /** Pourquoi une demande passe par Vosk plutôt que Google (null : rien de particulier). */
    private fun voskReason(): String? {
        val g = googleSpeech
        return when {
            !useGoogleSpeech -> "Google désactivé dans les réglages"
            g == null || !g.available -> "Google indisponible sur ce téléphone"
            !phoneMicInUse && !googleOnHeadset -> "casque PS5 : Vosk (Google n'y est pas utilisé)"
            !phoneMicInUse && !g.streamUsable -> "Google coupé pour le casque (${g.streamOffReason})"
            fallback.suspended -> "Google suspendu après ${fallback.consecutiveFailures} échecs"
            fallback.voskForNextRequest -> "Google vient d'échouer"
            else -> null
        }
    }

    /** « Réessayer Google sur le casque » : moteurs écartés et échecs oubliés. */
    fun retryGoogleOnHeadset() {
        googleSpeech?.resetStream()
        streamFallback.reset()
        refreshFallbackInfo()
        log("réglage", null, "Google de nouveau essayé sur le casque PS5")
    }

    /**
     * Casque PS5 : pendant que Google écoute le flux, Vosk écoute la même phrase en parallèle (aucun conflit de micro,
     * le flux est déjà dans l'application). Si Google échoue, la phrase de Vosk est utilisée : le pilote n'a pas à
     * répéter. Rien n'est conservé après l'écoute : seulement le texte reconnu, en mémoire.
     */
    private class Shadow(val session: Int) {
        val command = StringBuilder()
        val free = StringBuilder()
        @Volatile var fed = false
        val done = CompletableDeferred<Pair<String, String>>()
    }

    @Volatile
    private var shadow: Shadow? = null

    private fun refreshFallbackInfo() {
        val g = googleSpeech
        _fallbackInfo.value = when {
            !phoneMicInUse && !googleOnHeadset -> null
            !phoneMicInUse && useGoogleSpeech && g != null && !g.streamUsable ->
                "Google coupé pour le casque PS5 : Vosk seul, sans attente. ${g.streamOffReason}. Le micro du Fold garde Google."
            fallback.suspended -> "Google suspendu après ${fallback.consecutiveFailures} échecs : Vosk pour toutes les demandes " +
                "(change le réglage Google pour réessayer)."
            fallback.voskForNextRequest -> "Google a échoué : la prochaine demande passe par Vosk."
            else -> null
        }
    }

    /**
     * Google : disponible, activé, pas d'échec en attente de repli ; micro du Fold, ou flux du casque PS5 si la
     * reconnaissance l'accepte (Android 13+).
     */
    private fun google(): GoogleSpeech? = googleSpeech?.takeIf {
        useGoogleSpeech && (phoneMicInUse || googleOnHeadset) &&
            fallback.useGoogle(it.available, phoneMicInUse, if (it.streamUsable) null else false)
    }

    /**
     * Expérimental : essayer Google sur le flux du casque PS5. Désactivé par défaut : sur le Fold, Google ne lit pas ce
     * flux (mesuré) ; le casque passe par Vosk seul, sans attente ni échec.
     */
    @Volatile
    var googleOnHeadset: Boolean = false
        set(value) {
            field = value
            streamFallback.reset()
            refreshFallbackInfo()
        }

    @Volatile
    private var paused = false

    /**
     * Reconnaissance d'Android (Google) pour la phrase qui suit « radio » seul et pour les réponses attendues :
     * bien meilleure en français que le petit modèle hors ligne. Vosk garde la détection du mot de réveil.
     */
    var googleSpeech: GoogleSpeech? = null

    /**
     * Banc d'essai de reconnaissance locale : reçoit les mêmes blocs audio que Vosk et ce que Vosk a reconnu, pour
     * comparer. Il n'agit jamais sur la radio ; pendant une série d'essai, la radio ne réagit pas aux phrases lues.
     */
    var bench: SherpaBench? = null

    @Volatile
    var useGoogleSpeech: Boolean = true
        set(value) {
            field = value
            phoneFallback.reset()
            streamFallback.reset()
            refreshFallbackInfo()
        }

    /** Confirmation, par la boucle d'écoute, que le micro du Fold est bien arrêté avant que Google le prenne. */
    @Volatile
    private var micAck: CompletableDeferred<Unit>? = null

    /** Le micro est rendu à la reconnaissance Google le temps d'une phrase. */
    @Volatile
    private var micReleased = false

    /** Dernière phrase de la reconnaissance libre, et quand elle a été reçue. */
    private var lastFree: Pair<String, Long>? = null
    private var pendingCommand: Pair<String, Long>? = null
    private var pendingJob: Job? = null
    private var ttsJob: Job? = null
    private var expiryJob: Job? = null
    private var awakeUntil = 0L

    val isRunning: Boolean get() = audioJob != null || _state.value == ListenState.LOADING

    /**
     * Le pilote a activé l'écoute : elle doit tourner, même après une erreur (issue #32 : après une bascule de micro
     * ratée, l'écoute restait arrêtée toute la course, et les redémarrages suivants étaient ignorés).
     */
    @Volatile
    private var wanted = false

    /** Micro du casque PS5 disponible et choisi : l'écoute doit le lire (bascule manquée sinon). */
    var remoteMicAvailable: () -> Boolean = { false }

    /** La voix de l'ingénieur n'a pas signalé sa fin depuis une minute : elle est réinitialisée. */
    var onStuckSpeaking: () -> Unit = {}

    /** Dernière voix du pilote reçue du salon Discord (horloge monotone, 0 : aucune), pour le diagnostic. */
    var remoteVoiceMs: () -> Long = { 0L }

    private val watchdog = ListenWatchdog()

    @Volatile private var startedMs = 0L
    @Volatile private var lastBlockMs = 0L
    @Volatile private var lastSoundMs = 0L
    @Volatile private var blocks = 0L
    @Volatile private var peak = 0
    @Volatile private var lastWakeMs = 0L
    private var pausedSinceMs = 0L
    private var lastAction: String? = null

    /** État de l'écoute pour l'Atelier : de quoi savoir pourquoi « Radio » ne répond pas, sans aucun son enregistré. */
    data class Diagnostic(
        val state: ListenState,
        val mic: String?,
        val blocksPerSecond: Float,
        val lastBlockAgoMs: Long?,
        val levelPct: Int,
        val lastSoundAgoMs: Long?,
        val pausedForMs: Long?,
        val micLentToGoogle: Boolean,
        val lastWakeAgoMs: Long?,
        val lastHeard: String,
        val remoteVoiceAgoMs: Long?,
        val restarts: Int,
        val lastAction: String?,
    )

    private val _diagnostic = MutableStateFlow<Diagnostic?>(null)
    val diagnostic: StateFlow<Diagnostic?> = _diagnostic.asStateFlow()

    init {
        // Surveillance permanente (une lecture d'état par seconde, aucun calcul audio).
        scope.launch {
            var lastBlocks = 0L
            while (true) {
                delay(WATCHDOG_MS)
                val now = SystemClock.elapsedRealtime()
                val n = blocks
                publishDiagnostic(now, (n - lastBlocks) * 1000f / WATCHDOG_MS)
                lastBlocks = n
                runCatching { watch(now) }
            }
        }
    }

    private fun publishDiagnostic(now: Long, rate: Float) {
        fun ago(t: Long) = t.takeIf { it > 0 }?.let { now - it }
        _diagnostic.value = Diagnostic(
            _state.value, _micLabel.value, rate, ago(lastBlockMs), peak * 100 / Short.MAX_VALUE, ago(lastSoundMs),
            ago(pausedSinceMs), micReleased, ago(lastWakeMs), _heard.value, ago(remoteVoiceMs()), watchdog.restarts, lastAction,
        )
        peak = 0
    }

    private fun watch(now: Long) {
        pausedSinceMs = if (paused) (pausedSinceMs.takeIf { it > 0 } ?: now) else 0L
        val action = watchdog.check(
            ListenWatchdog.Input(
                now = now,
                wanted = wanted,
                running = audioJob?.isActive == true || _state.value == ListenState.LOADING,
                error = _state.value == ListenState.ERROR,
                paused = paused,
                speaking = speaking.value,
                micReleased = micReleased,
                googleListening = googleSpeech?.listening == true,
                lastBlockMs = lastBlockMs,
                lastSoundMs = lastSoundMs,
                remoteAvailable = remoteMicAvailable(),
                usingRemote = audioJob != null && !phoneMicInUse,
                startedMs = startedMs,
            ),
        ) ?: return
        val text = "Écoute radio : ${action.reason}" + when (action) {
            is ListenWatchdog.Action.Restart -> " — écoute relancée."
            is ListenWatchdog.Action.Unpause -> " — écoute reprise."
            is ListenWatchdog.Action.ReclaimMic -> " — micro repris."
            is ListenWatchdog.Action.Report -> "."
        }
        lastAction = clock.format(Date()) + " " + text.removePrefix("Écoute radio : ")
        log("surveillance", null, text)
        _events.tryEmit(VoiceInputEvent.Diagnostic(text))
        when (action) {
            is ListenWatchdog.Action.Restart -> {
                halt()
                start()
            }
            is ListenWatchdog.Action.Unpause -> {
                onStuckSpeaking()
                paused = false
                conversation.engineerSpeaking(false, now)
                if (_state.value == ListenState.PAUSED) _state.value = ListenState.WAITING_WAKE_WORD
            }
            is ListenWatchdog.Action.ReclaimMic -> {
                shadow = null
                googleSpeech?.cancel()
                micReleased = false
                awakeUntil = 0
                if (_state.value == ListenState.AWAKE && !conversation.open) _state.value = ListenState.WAITING_WAKE_WORD
            }
            is ListenWatchdog.Action.Report -> Unit
        }
    }

    fun start() {
        wanted = true
        if (isRunning) return
        val dir = models.installedDir()
        if (dir == null) {
            _error.value = "Modèle vocal non installé"
            _state.value = ListenState.ERROR
            return
        }
        _error.value = null
        _state.value = ListenState.LOADING
        scope.launch {
            try {
                val m = model ?: withContext(Dispatchers.IO) { Model(dir.absolutePath) }.also { model = it }
                val rec = if (limitedVocabulary) {
                    Recognizer(m, SAMPLE_RATE, JSONArray(DriverCommandParser.vocabulary(wakeWord)).toString())
                } else {
                    Recognizer(m, SAMPLE_RATE)
                }
                // Sans vocabulaire limité, la reconnaissance principale est déjà libre.
                val free = if (freeTranscription && limitedVocabulary) Recognizer(m, SAMPLE_RATE) else null
                val record = remoteMic() ?: PhoneMic.open(SAMPLE_RATE.toInt(), BUFFER_SAMPLES) ?: run {
                    rec.close()
                    free?.close()
                    error("Micro indisponible")
                }
                phoneMicInUse = record.isPhoneMic
                _micLabel.value = record.label
                refreshFallbackInfo()
                paused = false
                audioJob = scope.launch(Dispatchers.IO) { listen(record, rec, free) }
                _state.value = ListenState.WAITING_WAKE_WORD
                ttsJob = scope.launch { speaking.collectLatest(::onEngineerSpeaking) }
                gapJob = scope.launch {
                    inGap?.collect { gap ->
                        val now = SystemClock.elapsedRealtime()
                        // Début de pause : fin d'une phrase (garde anti-écho) ; fin de pause : l'ingénieur reprend.
                        gapInterrupts.engineer(!gap, engineerMessage()?.text, now)
                    }
                }
                tickJob = scope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        tickConversation()
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
                _state.value = ListenState.ERROR
                _events.tryEmit(VoiceInputEvent.Diagnostic("Écoute radio : démarrage impossible (${_error.value})."))
            }
        }
    }

    /** Bip de disponibilité, une fois par conversation, quand le micro écoute vraiment. */
    private fun readyBeep() {
        if (conversation.micReady() != null) {
            scope.launch { tones.play(RadioTone.READY) }
            _events.tryEmit(VoiceInputEvent.Listening)
        }
    }

    private fun setConversation(open: Boolean) {
        _conversationOpen.value = open
    }

    /** Ferme la conversation : double bip, retour au mot de réveil. */
    private fun closeConversation(byDriver: Boolean) {
        conversation.reset()
        setConversation(false)
        reopenJob?.cancel()
        if (googleSpeech?.listening == true) {
            shadow = null
            phoneFallback.cancelActive()
            streamFallback.cancelActive()
            googleSpeech?.cancel()
            micReleased = false
        }
        awakeUntil = 0
        expiryJob?.cancel()
        if (_state.value != ListenState.PAUSED) _state.value = ListenState.WAITING_WAKE_WORD
        _events.tryEmit(VoiceInputEvent.ConversationClosed(byDriver))
        scope.launch { tones.play(RadioTone.CLOSED) }
    }

    /** Fermeture après [conversationIdleMs] de silence, jamais pendant que le pilote parle ou qu'une phrase est en cours. */
    private fun tickConversation() {
        if (!conversation.open || audioJob == null) return
        val now = SystemClock.elapsedRealtime()
        val thinking = isThinking()
        conversation.thinking(thinking, now)
        // Le pilote parle (ou sa phrase est en cours de traitement) : prolongation bornée par la fenêtre elle-même, pour que
        // le double bip vienne toujours après le silence prévu (essai PS5 : le bruit du jeu le repoussait sans fin).
        val talking = googleSpeech?.listening == true || pendingCommand != null || now - lastVoiceActivityMs < VOICE_GRACE_MS
        if (conversation.tick(now, driverTalking = talking) != null) closeConversation(byDriver = false)
    }

    /** Ouvre la conversation (« radio » dit seul, ou en tête d'une demande). */
    private fun openConversation() {
        conversation.wake(SystemClock.elapsedRealtime())
        setConversation(true)
    }

    /**
     * Après une réponse : l'écoute reprend sans mot de réveil. Bip seulement la première fois que le micro écoute dans
     * cette conversation (demande dite d'un trait avec « radio »).
     */
    private fun resumeConversation() {
        val google = google()
        if (google != null) {
            listenWithGoogle(google, startDelayMs = 0, signal = Signal.BEEP_WHEN_READY)
        } else {
            listenWithVosk()
            readyBeep()
        }
    }

    /** Une demande vient d'être transmise : si l'ingénieur ne répond pas (phrase ignorée), l'écoute reprend quand même. */
    private fun keepListeningIfSilent() {
        reopenJob?.cancel()
        reopenJob = scope.launch {
            delay(REOPEN_IF_SILENT_MS)
            if (conversation.open && !speaking.value && googleSpeech?.listening != true && audioJob != null) resumeConversation()
        }
    }

    /** Écoute coupée par le pilote (réglage) : la surveillance ne la relance pas. */
    fun stop() {
        wanted = false
        halt()
    }

    private fun halt() {
        conversation.reset()
        setConversation(false)
        tickJob?.cancel()
        reopenJob?.cancel()
        phoneFallback.cancelActive()
        streamFallback.cancelActive()
        googleSpeech?.cancel()
        micReleased = false
        shadow = null
        ttsJob?.cancel()
        gapJob?.cancel()
        expiryJob?.cancel()
        pendingJob?.cancel()
        audioJob?.cancel() // la boucle ferme le micro et les reconnaisseurs
        audioJob = null
        awakeUntil = 0
        paused = false
        startedMs = 0L
        _micLabel.value = null
        _state.value = ListenState.OFF
    }

    /** Redémarre l'écoute (changement de réglage, bascule de micro), y compris après une erreur. */
    fun restart() {
        if (!wanted) return
        halt()
        start()
    }

    /** Boucle micro : chaque bloc audio alimente les deux reconnaissances ; rien n'est écouté pendant la pause. */
    private suspend fun listen(record: PcmSource, rec: Recognizer, free: Recognizer?) {
        val buffer = ShortArray(BUFFER_SAMPLES)
        try {
            record.start()
            startedMs = SystemClock.elapsedRealtime()
            lastBlockMs = 0L
            lastSoundMs = 0L
            var recording = true
            while (scope.isActive && kotlinx.coroutines.currentCoroutineContext().isActive) {
                // Micro du casque PS5 pendant une écoute Google : le flux lui est transmis au lieu de Vosk.
                // Vosk écoute la même phrase en parallèle, pour le cas où Google échoue.
                if (micReleased && !record.isPhoneMic) {
                    val n = record.read(buffer)
                    if (n > 0) {
                        measure(buffer, n)
                        googleSpeech?.feed(buffer, n)
                        bench?.feed(buffer, n)
                        shadow?.let { s ->
                            s.fed = true
                            if (rec.acceptWaveForm(buffer, n)) s.command.append(' ').append(textOf(rec.result))
                            if (free != null && free.acceptWaveForm(buffer, n)) s.free.append(' ').append(textOf(free.result))
                        }
                    }
                    continue
                }
                // Fin de l'écoute Google : la fin de phrase en attente chez Vosk est récupérée.
                shadow?.takeIf { it.fed && !it.done.isCompleted }?.let { s ->
                    s.command.append(' ').append(textOf(rec.finalResult))
                    if (free != null) s.free.append(' ').append(textOf(free.finalResult))
                    s.done.complete(s.command.toString().trim() to s.free.toString().trim())
                }
                // Micro du Fold prêté à la reconnaissance Google : on arrête d'enregistrer, puis on reprend.
                if (micReleased) {
                    if (recording) {
                        record.pause()
                        recording = false
                    }
                    micAck?.complete(Unit)
                    delay(MIC_POLL_MS)
                    continue
                }
                if (!recording) {
                    record.start()
                    recording = true
                }
                val n = record.read(buffer)
                if (n < 0) error("Erreur micro ($n)")
                if (n == 0) continue
                measure(buffer, n)
                if (paused) {
                    // L'ingénieur parle : seul le mode d'interruption autorisé par le matériel écoute (jamais une commande).
                    if (bargeListening()) bargeFeed(buffer, n, rec)
                    continue
                }
                if (resetRecognizer) {
                    resetRecognizer = false
                    rec.reset()
                }
                bench?.feed(buffer, n)
                if (rec.acceptWaveForm(buffer, n)) {
                    val text = textOf(rec.result)
                    if (text.isNotBlank()) lastSpeechEndMs = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Main) { onCommandText(text) }
                } else if (ConversationWindow.isIntelligible(partialOf(rec.partialResult))) {
                    // Un « [unk] » du bruit du jeu n'est pas le pilote qui parle.
                    val now = SystemClock.elapsedRealtime()
                    // Le pilote est en train de parler : la conversation ne se referme pas au milieu de sa phrase.
                    if (now - lastVoiceActivityMs > VOICE_GRACE_MS) lastSpeechStartMs = now
                    if (conversation.open) lastVoiceActivityMs = now
                }
                if (free != null && free.acceptWaveForm(buffer, n)) {
                    val text = textOf(free.result)
                    withContext(Dispatchers.Main) { onFreeText(text) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _error.value = e.message ?: "Erreur micro"
                _state.value = ListenState.ERROR
            }
        } finally {
            record.release()
            rec.close()
            free?.close()
        }
    }

    /** Bloc audio reçu : horodatage et niveau crête, pour le diagnostic (rien n'est conservé). */
    private fun measure(buffer: ShortArray, n: Int) {
        val now = SystemClock.elapsedRealtime()
        lastBlockMs = now
        blocks++
        var max = 0
        for (i in 0 until n) {
            val v = kotlin.math.abs(buffer[i].toInt())
            if (v > max) max = v
        }
        if (max > 0) lastSoundMs = now
        if (max > peak) peak = max.coerceAtMost(Short.MAX_VALUE.toInt())
    }

    /**
     * Phrase de la reconnaissance radio. Si la reconnaissance libre tourne, on attend un court instant sa version
     * de la même phrase (elles ne se terminent pas exactement au même moment).
     */
    private fun onCommandText(text: String) {
        if (text.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        val free = lastFree?.takeIf { now - it.second < PAIR_WINDOW_MS }
        if (!freeTranscription || !limitedVocabulary || free != null) {
            lastFree = null
            onText(text, free?.first)
            return
        }
        pendingCommand = text to now
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(PAIR_WINDOW_MS)
            pendingCommand?.let { (t, _) ->
                pendingCommand = null
                onText(t, null)
            }
        }
    }

    private fun onFreeText(text: String) {
        if (text.isBlank()) return
        val pending = pendingCommand
        if (pending != null) {
            pendingCommand = null
            pendingJob?.cancel()
            onText(pending.first, text)
        } else {
            lastFree = text to SystemClock.elapsedRealtime()
        }
    }

    override fun expectReply() {
        scope.launch {
            if (audioJob == null) return@launch
            // Conversation ouverte : l'écoute reprend de toute façon après la question de l'ingénieur.
            if (conversation.open) return@launch
            // Si l'ingénieur parle encore, l'écoute s'ouvrira à la fin de sa phrase.
            if (speaking.value) awakeUntil = 1 else awake()
        }
    }

    /** L'ingénieur parle : le micro écoute-t-il une éventuelle interruption ? */
    private fun bargeListening(): Boolean {
        if (!speaking.value || !DuplexPolicy.interruptible(engineerMessage())) return false
        return when (duplexMode()) {
            DuplexMode.FULL -> true
            DuplexMode.GAPS -> inGap?.value == true
            DuplexMode.OFF -> false
        }
    }

    /**
     * Écoute pendant la parole de l'ingénieur : une voix établie dans le flux du pilote ET des mots qui ne sont pas les
     * siens (« attends », « stop », ou une vraie phrase) coupent l'ingénieur. Rien d'autre n'est fait de ces mots.
     */
    private suspend fun bargeFeed(buffer: ShortArray, n: Int, rec: Recognizer) {
        val now = SystemClock.elapsedRealtime()
        val mode = duplexMode()
        val detector = if (mode == DuplexMode.GAPS) gapInterrupts else interrupts
        if (voiceActivity.accept(buffer, n)) detector.voice(now)
        val text = if (rec.acceptWaveForm(buffer, n)) textOf(rec.result) else partialOf(rec.partialResult)
        if (text.isBlank()) return
        val rejectedBefore = detector.rejected
        if (!detector.words(text, now)) {
            // Fausse interruption refusée (écho probable, issue #45) : notée une fois par phrase, sans aucun son gardé.
            if (detector.rejected > rejectedBefore && !rejectionNoted) {
                rejectionNoted = true
                log("interruption refusée", text, "écho probable de l'ingénieur")
                _events.tryEmit(VoiceInputEvent.Diagnostic("FAUSSE_INTERRUPTION refusée (écho probable) : « ${text.take(60)} »"))
            }
            return
        }
        rec.reset()
        voiceActivity.reset()
        withContext(Dispatchers.Main) { onBargeIn(text, mode) }
    }

    /** Le pilote coupe l'ingénieur : la conversation s'ouvre (ou reste ouverte) et le moteur arrête la voix. */
    private fun onBargeIn(text: String, mode: DuplexMode) {
        log("interruption", text, mode.label)
        bargedIn = true
        if (!conversation.open) openConversation()
        lastSpeechStartMs = SystemClock.elapsedRealtime()
        _events.tryEmit(VoiceInputEvent.BargeIn(text, mode))
    }

    override val speechTiming: Pair<Long?, Long?> get() = lastSpeechStartMs to lastSpeechEndMs

    override fun thinking() {
        scope.launch { if (!speaking.value) tones.play(RadioTone.THINKING) }
    }

    private suspend fun onEngineerSpeaking(speaking: Boolean) {
        if (audioJob == null) return
        val now = SystemClock.elapsedRealtime()
        interrupts.engineer(speaking, engineerMessage()?.text, now)
        if (speaking) rejectionNoted = false else lastEngineerEndMs = now
        if (speaking) voiceActivity.reset() else resetRecognizer = true
        // Le temps où l'ingénieur parle ne compte pas dans la fenêtre de conversation.
        conversation.engineerSpeaking(speaking, now)
        if (speaking) reopenJob?.cancel()
        if (speaking) {
            // L'ingénieur reprend la parole pendant une écoute Google : on la relancera après lui.
            if (googleSpeech?.listening == true) {
                shadow = null
                phoneFallback.cancelActive()
                streamFallback.cancelActive()
                googleSpeech?.cancel()
                micReleased = false
                awakeUntil = 1
            }
            paused = true
            _state.value = ListenState.PAUSED
        } else {
            // Conversation ouverte : l'écoute reprend sans « radio », une fois la fin de la phrase sortie du casque.
            if (conversation.open) {
                // Après une interruption, le pilote est déjà en train de parler : l'écoute reprend aussitôt.
                val wait = when {
                    bargedIn -> 0L
                    google() != null -> GOOGLE_AFTER_SPEECH_MS
                    else -> RESUME_DELAY_MS
                }
                bargedIn = false
                if (wait > 0) delay(wait)
                if (this.speaking.value || !conversation.open) return
                paused = false
                conversation.engineerSpeaking(false, SystemClock.elapsedRealtime())
                resumeConversation()
                return
            }
            // Réponse attendue avec Google : on ouvre l'écoute presque aussitôt, pour ne pas manquer le début.
            val google = google()
            if (awakeUntil > 0 && google != null) {
                delay(GOOGLE_AFTER_SPEECH_MS)
                paused = false
                listenWithGoogle(google, startDelayMs = 0)
                return
            }
            delay(RESUME_DELAY_MS) // laisse la fin de la phrase s'éteindre dans le casque
            paused = false
            if (awakeUntil > 0) {
                awake()
            } else {
                _state.value = ListenState.WAITING_WAKE_WORD
            }
        }
    }

    private fun awake() {
        val google = google()
        if (google != null) {
            // Laisse démarrer une éventuelle phrase de l'ingénieur : l'écoute suivra sa fin.
            listenWithGoogle(google, startDelayMs = GOOGLE_START_DELAY_MS)
            return
        }
        listenWithVosk()
    }

    /** Comment le pilote sait que Google l'écoute. */
    private enum class Signal { NONE, BEEP_WHEN_READY }

    /**
     * Une phrase écoutée par Google, puis le micro revient au mot de réveil. Micro du Fold : Google ne démarre qu'après
     * la confirmation que la boucle Vosk a arrêté son enregistrement (deux enregistrements simultanés, et Android en
     * rend un muet). Le signal au pilote part quand Google est réellement prêt (onReadyForSpeech). En cas d'échec, le
     * pilote est prévenu et Vosk écoute la demande suivante.
     */
    private fun listenWithGoogle(google: GoogleSpeech, startDelayMs: Long = 0, signal: Signal = Signal.NONE) {
        val policy = fallback
        val session = policy.start()
        awakeUntil = 1
        _state.value = ListenState.AWAKE
        expiryJob?.cancel()
        scope.launch {
            if (startDelayMs > 0) delay(startDelayMs)
            // L'ingénieur parle : l'écoute sera relancée à la fin de sa phrase (onEngineerSpeaking).
            if (speaking.value || audioJob == null) return@launch
            val external = !phoneMicInUse
            if (external) {
                shadow = Shadow(session)
                micReleased = true
            } else {
                val ack = CompletableDeferred<Unit>()
                micAck = ack
                micReleased = true
                val t0 = SystemClock.elapsedRealtime()
                val released = withTimeoutOrNull(MIC_RELEASE_TIMEOUT_MS) { ack.await() } != null
                val ms = SystemClock.elapsedRealtime() - t0
                micAck = null
                _handover.value = if (released) "micro du Fold libéré en $ms ms" else "micro du Fold NON libéré après $ms ms"
            }
            google.listenOnce(
                audio = if (external) ExternalAudio.create() else null,
                onReady = {
                    // Bip de disponibilité : quand Google écoute vraiment, une fois par conversation.
                    if (signal == Signal.BEEP_WHEN_READY) readyBeep()
                },
            ) { result -> onGoogleResult(policy, session, result) }
        }
    }

    private fun onGoogleResult(policy: SpeechFallbackPolicy, session: Int, result: GoogleResult) {
        // En pleine conversation, un silence après une réponse est normal : ni échec de Google, ni message au pilote.
        val quiet = conversation.open && conversation.exchanges > 0 &&
            (result.outcome == GoogleOutcome.SPEECH_TIMEOUT || result.outcome == GoogleOutcome.NO_MATCH)
        val decision = policy.finish(session, if (quiet) GoogleOutcome.CANCELLED else result.outcome, result.errorCode)
        refreshFallbackInfo()
        // Écoute annulée ou remplacée entre-temps (redémarrage, l'ingénieur a repris la parole) : rien à faire ici,
        // sauf rendre le micro si plus aucune écoute Google ne l'utilise (sinon Vosk restait sourd).
        if (decision == null) {
            if (googleSpeech?.listening != true) micReleased = false
            return
        }
        micReleased = false
        awakeUntil = 0
        if (quiet) {
            // Le micro reste ouvert (Vosk) jusqu'à la fin de la fenêtre de conversation.
            shadow = null
            listenWithVosk()
            return
        }
        if (_state.value == ListenState.AWAKE && !conversation.open) _state.value = ListenState.WAITING_WAKE_WORD
        val parallel = shadow?.takeIf { it.session == session }
        shadow = null
        val source = if (parallel != null) "casque PS5" else "micro du Fold"
        val googleBy = "${result.engine ?: "Google"} ($source)"
        val proof = listOfNotNull(result.note, result.switch).joinToString(" — ").ifBlank { null }
        scope.launch {
            // La phrase de Vosk n'est attendue que si Google n'a rien donné d'utilisable.
            val needVosk = parallel != null && RequestArbiter.stripWake(result.text ?: "", wakeWord).isEmpty()
            val (command, free) = if (needVosk) {
                withTimeoutOrNull(SHADOW_FLUSH_MS) { parallel!!.done.await() } ?: ("" to "")
            } else {
                "" to ""
            }
            if (audioJob == null) return@launch
            when (val pick = RequestArbiter.pick(result.text, command, free, wakeWord)) {
                is RequestPick.Google -> {
                    _heard.value = result.text ?: pick.text
                    log(googleBy, result.text, proof)
                    deliverUtterance(pick.text, pick.text, pick.text, recognizer = googleBy)
                }
                is RequestPick.Vosk -> {
                    // Même phrase, entendue par Vosk en parallèle : le pilote n'a rien à répéter.
                    policy.voskHandledRequest()
                    refreshFallbackInfo()
                    _heard.value = free.ifBlank { command }
                    log("Vosk, même phrase ($source)", free.ifBlank { command }, "$googleBy : ${result.outcome.label}" + (proof?.let { " — $it" } ?: ""))
                    deliverUtterance(pick.command, pick.free, pick.free ?: pick.command, recognizer = "Vosk ($source)")
                }
                RequestPick.Repeat -> if (decision.fallbackToVosk) {
                    log("aucun", null, "$googleBy : ${result.outcome.label}" + (if (parallel != null) ", Vosk : rien entendu" else "") +
                        (proof?.let { " — $it" } ?: "") + ". Vosk écoute la demande répétée")
                    // Jamais silencieux : le pilote est prévenu, et Vosk écoute tout de suite sa demande répétée.
                    listenWithVosk()
                    decision.notice?.let { _events.tryEmit(VoiceInputEvent.Notice(it)) }
                }
            }
        }
    }

    /**
     * Phrase du pilote transmise à l'ingénieur. En conversation : « terminé » la referme ; sinon elle prolonge la fenêtre,
     * et une phrase entendue sans « radio » devant ([VoiceInputEvent.Utterance.addressed] faux) ne peut modifier la course
     * qu'après confirmation.
     */
    private fun deliverUtterance(command: String, free: String?, heard: String, addressed: Boolean = true, recognizer: String = "Vosk") {
        if (conversation.open) {
            if (ConversationWindow.isClosing(heard)) {
                closeConversation(byDriver = true)
                return
            }
            val first = conversation.addressedByWake
            conversation.utterance(heard, SystemClock.elapsedRealtime())
            _events.tryEmit(VoiceInputEvent.Utterance(command, free, nearEngineer = nearEngineer(), addressed = addressed && first, recognizer = recognizer))
            keepListeningIfSilent()
            return
        }
        _events.tryEmit(VoiceInputEvent.Utterance(command, free, nearEngineer = nearEngineer(), addressed = addressed, recognizer = recognizer))
    }

    /** Fenêtre d'écoute Vosk sans mot de réveil (conversation ouverte, après un échec de Google, ou sans Google). */
    private fun listenWithVosk() {
        if (conversation.open) {
            // La conversation décide de la fermeture (silence ou « terminé »).
            awakeUntil = Long.MAX_VALUE
            _state.value = ListenState.AWAKE
            expiryJob?.cancel()
            return
        }
        awakeUntil = SystemClock.elapsedRealtime() + AWAKE_MS
        _state.value = ListenState.AWAKE
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(AWAKE_MS)
            if (SystemClock.elapsedRealtime() >= awakeUntil && _state.value == ListenState.AWAKE) {
                awakeUntil = 0
                _state.value = ListenState.WAITING_WAKE_WORD
            }
        }
    }

    private fun onText(text: String, freeText: String?) {
        if (text.isBlank() || _state.value == ListenState.PAUSED) return
        bench?.let { b ->
            b.reference(freeText ?: text, free = freeText != null || !limitedVocabulary)
            // Série d'essai : les phrases lues ne sont pas des demandes.
            if (b.seriesActive) {
                _heard.value = freeText ?: text
                return
            }
        }
        _heard.value = freeText ?: text
        val norm = DriverCommandParser.normalize(text)
        val wake = DriverCommandParser.normalize(wakeWord)
        val wakeRegex = Regex("(^|\\s)${Regex.escape(wake)}(\\s|$)")
        // La phrase libre, sans le mot de réveil, pour l'IA.
        val free = freeText?.let { DriverCommandParser.normalize(it) }?.let { f ->
            wakeRegex.find(f)?.let { f.substring(it.range.last + 1).trim() } ?: f
        }?.takeIf { it.length >= 2 }
        val match = wakeRegex.find(norm)
        if (wake.isNotEmpty() && match != null) {
            lastWakeMs = SystemClock.elapsedRealtime()
            val rest = norm.substring(match.range.last + 1).trim()
            if (rest.length >= 2) {
                // Demande dite d'un trait : elle est transmise, et la conversation reste ouverte pour la suite.
                awakeUntil = 0
                log("Vosk", freeText ?: text, "phrase dite d'un trait avec « $wakeWord » (Google n'est essayé qu'après « $wakeWord » seul)")
                fallback.voskHandledRequest()
                refreshFallbackInfo()
                if (!conversation.open) openConversation()
                if (ConversationWindow.isClosing(rest)) {
                    closeConversation(byDriver = true)
                    return
                }
                conversation.utterance(rest, SystemClock.elapsedRealtime())
                _events.tryEmit(VoiceInputEvent.Utterance(rest, free, nearEngineer = nearEngineer(), addressed = true))
                keepListeningIfSilent()
            } else {
                // « Radio » redit alors que la conversation est déjà ouverte : le pilote doit savoir qu'il est entendu
                // (essai PS5 : « radio » semblait ne rien déclencher). Bip d'écoute, et le délai de silence repart.
                if (conversation.open) {
                    conversation.utterance("radio", SystemClock.elapsedRealtime())
                    log("Vosk", freeText ?: text, "« $wakeWord » redit : conversation déjà ouverte, à l'écoute")
                    scope.launch { tones.play(RadioTone.READY) }
                    _events.tryEmit(VoiceInputEvent.Listening)
                    return
                }
                // « Radio » seul : conversation ouverte, aucune phrase dite. Bip quand le micro écoute vraiment
                // (Google prêt, ou Vosk aussitôt), dans le casque PS5 via Discord ou sur le Fold.
                openConversation()
                _events.tryEmit(VoiceInputEvent.Wake)
                val google = google()
                if (google != null) {
                    listenWithGoogle(google, signal = Signal.BEEP_WHEN_READY)
                } else {
                    listenWithVosk()
                    readyBeep()
                }
            }
            return
        }
        // Conversation ouverte : toute phrase est pour l'ingénieur, sans « radio » (modifications à confirmer).
        if (conversation.open) {
            log("Vosk", freeText ?: text, voskReason() ?: "conversation ouverte")
            fallback.voskHandledRequest()
            refreshFallbackInfo()
            deliverUtterance(norm, free, freeText ?: text)
            return
        }
        if (awakeUntil > 0 && SystemClock.elapsedRealtime() < awakeUntil) {
            awakeUntil = 0
            expiryJob?.cancel()
            _state.value = ListenState.WAITING_WAKE_WORD
            log("Vosk", freeText ?: text, voskReason() ?: "écoute Vosk après « $wakeWord »")
            fallback.voskHandledRequest()
            refreshFallbackInfo()
            _events.tryEmit(VoiceInputEvent.Utterance(norm, free, nearEngineer = nearEngineer()))
        }
    }

    private fun partialOf(json: String?): String =
        try {
            if (json.isNullOrBlank()) "" else JSONObject(json).optString("partial")
        } catch (e: Exception) {
            ""
        }

    private fun textOf(json: String?): String =
        try {
            if (json.isNullOrBlank()) "" else JSONObject(json).optString("text")
        } catch (e: Exception) {
            ""
        }

    private companion object {
        /** Après la fin de la voix de l'ingénieur, sa fin de phrase peut encore revenir par Discord (issue #45). */
        const val ECHO_TAIL_MS = 2_000L
        const val SAMPLE_RATE = 16_000f
        const val AWAKE_MS = 8_000L
        const val RESUME_DELAY_MS = 400L
        const val BUFFER_SAMPLES = 4_000 // 0,25 s
        const val PAIR_WINDOW_MS = 1_200L
        const val MIC_POLL_MS = 50L
        const val GOOGLE_START_DELAY_MS = 500L
        const val MIC_RELEASE_TIMEOUT_MS = 1_000L
        const val SHADOW_FLUSH_MS = 1_000L
        const val HISTORY_SIZE = 8
        const val GOOGLE_AFTER_SPEECH_MS = 150L
        const val GAP_ECHO_GUARD_MS = 150L
        const val TICK_MS = 250L
        /** Phrase en cours chez Vosk : pas de fermeture avant ce délai après le dernier mot entendu. */
        const val VOICE_GRACE_MS = 1_500L
        /** Délai après une demande sans réponse parlée avant de reprendre l'écoute. */
        const val REOPEN_IF_SILENT_MS = 2_500L
        const val WATCHDOG_MS = 1_000L
    }
}
