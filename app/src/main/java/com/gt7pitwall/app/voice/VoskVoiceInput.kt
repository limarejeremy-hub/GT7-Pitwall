package com.gt7pitwall.app.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
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
 * "radio, combien d'essence ?" -> phrase transmise directement.
 * "radio" seul -> l'ingénieur répond "je t'écoute" et prend la phrase suivante.
 * Le micro est mis en pause pendant que l'ingénieur parle (pas d'écho de sa propre voix).
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
    private val tts: TtsVoiceOutput,
) : VoiceInput {

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
        useGoogleSpeech && fallback.useGoogle(it.available, phoneMicInUse, if (it.streamUsable) null else false)
    }

    @Volatile
    private var paused = false

    /**
     * Reconnaissance d'Android (Google) pour la phrase qui suit « radio » seul et pour les réponses attendues :
     * bien meilleure en français que le petit modèle hors ligne. Vosk garde la détection du mot de réveil.
     */
    var googleSpeech: GoogleSpeech? = null

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

    fun start() {
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
                ttsJob = scope.launch { tts.speaking.collectLatest(::onEngineerSpeaking) }
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
                _state.value = ListenState.ERROR
            }
        }
    }

    private var tone: ToneGenerator? = null

    private fun beep() {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_ACK, BEEP_MS.toInt())
        } catch (e: Exception) {
            // Pas de bip possible : l'écoute démarre quand même.
        }
    }

    fun stop() {
        tone?.release()
        tone = null
        phoneFallback.cancelActive()
        streamFallback.cancelActive()
        googleSpeech?.cancel()
        micReleased = false
        shadow = null
        ttsJob?.cancel()
        expiryJob?.cancel()
        pendingJob?.cancel()
        audioJob?.cancel() // la boucle ferme le micro et les reconnaisseurs
        audioJob = null
        awakeUntil = 0
        _micLabel.value = null
        _state.value = ListenState.OFF
    }

    /** Redémarre l'écoute (changement de réglage). */
    fun restart() {
        if (!isRunning) return
        stop()
        start()
    }

    /** Boucle micro : chaque bloc audio alimente les deux reconnaissances ; rien n'est écouté pendant la pause. */
    private suspend fun listen(record: PcmSource, rec: Recognizer, free: Recognizer?) {
        val buffer = ShortArray(BUFFER_SAMPLES)
        try {
            record.start()
            var recording = true
            while (scope.isActive && kotlinx.coroutines.currentCoroutineContext().isActive) {
                // Micro du casque PS5 pendant une écoute Google : le flux lui est transmis au lieu de Vosk.
                // Vosk écoute la même phrase en parallèle, pour le cas où Google échoue.
                if (micReleased && !record.isPhoneMic) {
                    val n = record.read(buffer)
                    if (n > 0) {
                        googleSpeech?.feed(buffer, n)
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
                if (n == 0 || paused) continue
                if (rec.acceptWaveForm(buffer, n)) {
                    val text = textOf(rec.result)
                    withContext(Dispatchers.Main) { onCommandText(text) }
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
            // Si l'ingénieur parle encore, l'écoute s'ouvrira à la fin de sa phrase.
            if (tts.speaking.value) awakeUntil = 1 else awake()
        }
    }

    private suspend fun onEngineerSpeaking(speaking: Boolean) {
        if (audioJob == null) return
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
            if (tts.speaking.value || audioJob == null) return@launch
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
                    if (signal == Signal.BEEP_WHEN_READY) {
                        beep()
                        _events.tryEmit(VoiceInputEvent.Listening)
                    }
                },
            ) { result -> onGoogleResult(policy, session, result) }
        }
    }

    private fun onGoogleResult(policy: SpeechFallbackPolicy, session: Int, result: GoogleResult) {
        val decision = policy.finish(session, result.outcome, result.errorCode)
        refreshFallbackInfo()
        // Écoute annulée ou remplacée entre-temps (redémarrage, l'ingénieur a repris la parole) : rien à faire ici.
        decision ?: return
        micReleased = false
        awakeUntil = 0
        if (_state.value == ListenState.AWAKE) _state.value = ListenState.WAITING_WAKE_WORD
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
                    _events.tryEmit(VoiceInputEvent.Utterance(pick.text, pick.text))
                }
                is RequestPick.Vosk -> {
                    // Même phrase, entendue par Vosk en parallèle : le pilote n'a rien à répéter.
                    policy.voskHandledRequest()
                    refreshFallbackInfo()
                    _heard.value = free.ifBlank { command }
                    log("Vosk, même phrase ($source)", free.ifBlank { command }, "$googleBy : ${result.outcome.label}" + (proof?.let { " — $it" } ?: ""))
                    _events.tryEmit(VoiceInputEvent.Utterance(pick.command, pick.free))
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

    /** Fenêtre d'écoute Vosk sans mot de réveil (après un échec de Google, ou sans Google). */
    private fun listenWithVosk() {
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
            val rest = norm.substring(match.range.last + 1).trim()
            if (rest.length >= 2) {
                awakeUntil = 0
                log("Vosk", freeText ?: text, "phrase dite d'un trait avec « $wakeWord » (Google n'est essayé qu'après « $wakeWord » seul)")
                fallback.voskHandledRequest()
                refreshFallbackInfo()
                _events.tryEmit(VoiceInputEvent.Utterance(rest, free))
            } else {
                val google = google()
                if (google != null && !phoneMicInUse) {
                    // Casque PS5 : le bip du Fold ne s'entendrait pas ; « Je t'écoute » part sur Discord, que le micro
                    // du casque ne capte pas : l'écoute commence tout de suite (le flux est mis en attente).
                    _events.tryEmit(VoiceInputEvent.Wake)
                    listenWithGoogle(google)
                } else if (google != null) {
                    // Bip court, et seulement quand Google écoute vraiment : le pilote ne parle pas dans le vide.
                    listenWithGoogle(google, signal = Signal.BEEP_WHEN_READY)
                } else {
                    awake()
                    _events.tryEmit(VoiceInputEvent.Wake)
                }
            }
            return
        }
        if (awakeUntil > 0 && SystemClock.elapsedRealtime() < awakeUntil) {
            awakeUntil = 0
            expiryJob?.cancel()
            _state.value = ListenState.WAITING_WAKE_WORD
            log("Vosk", freeText ?: text, voskReason() ?: "écoute Vosk après « $wakeWord »")
            fallback.voskHandledRequest()
            refreshFallbackInfo()
            _events.tryEmit(VoiceInputEvent.Utterance(norm, free))
        }
    }

    private fun textOf(json: String?): String =
        try {
            if (json.isNullOrBlank()) "" else JSONObject(json).optString("text")
        } catch (e: Exception) {
            ""
        }

    private companion object {
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
        const val BEEP_MS = 150L
        const val BEEP_VOLUME = 70
    }
}
