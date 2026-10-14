package com.gt7pitwall.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.gt7pitwall.core.radio.DriverCommandParser
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

    @Volatile
    private var paused = false

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
                val record = openMicrophone() ?: run {
                    rec.close()
                    free?.close()
                    error("Micro indisponible")
                }
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

    fun stop() {
        ttsJob?.cancel()
        expiryJob?.cancel()
        pendingJob?.cancel()
        audioJob?.cancel() // la boucle ferme le micro et les reconnaisseurs
        audioJob = null
        awakeUntil = 0
        _state.value = ListenState.OFF
    }

    /** Redémarre l'écoute (changement de réglage). */
    fun restart() {
        if (!isRunning) return
        stop()
        start()
    }

    @SuppressLint("MissingPermission") // l'écoute n'est lancée qu'après l'autorisation du micro
    private fun openMicrophone(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE.toInt(),
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, BUFFER_SAMPLES * 2 * 4),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return null
        }
        return r
    }

    /** Boucle micro : chaque bloc audio alimente les deux reconnaissances ; rien n'est écouté pendant la pause. */
    private suspend fun listen(record: AudioRecord, rec: Recognizer, free: Recognizer?) {
        val buffer = ShortArray(BUFFER_SAMPLES)
        try {
            record.startRecording()
            while (scope.isActive && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val n = record.read(buffer, 0, buffer.size)
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
            runCatching { record.stop() }
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
            paused = true
            _state.value = ListenState.PAUSED
        } else {
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
                _events.tryEmit(VoiceInputEvent.Utterance(rest, free))
            } else {
                awake()
                _events.tryEmit(VoiceInputEvent.Wake)
            }
            return
        }
        if (awakeUntil > 0 && SystemClock.elapsedRealtime() < awakeUntil) {
            awakeUntil = 0
            expiryJob?.cancel()
            _state.value = ListenState.WAITING_WAKE_WORD
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
    }
}
