package com.gt7pitwall.app.voice

import android.os.SystemClock
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.VoiceInput
import com.gt7pitwall.core.radio.VoiceInputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

enum class ListenState { OFF, LOADING, WAITING_WAKE_WORD, AWAKE, PAUSED, ERROR }

/**
 * Radio du pilote : reconnaissance vocale française hors ligne (Vosk) avec mot de réveil.
 *
 * "radio, combien d'essence ?" -> phrase transmise directement.
 * "radio" seul -> l'ingénieur répond "je t'écoute" et prend la phrase suivante.
 * Le micro est mis en pause pendant que l'ingénieur parle (pas d'écho de sa propre voix).
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

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var ttsJob: Job? = null
    private var expiryJob: Job? = null
    private var awakeUntil = 0L

    val isRunning: Boolean get() = service != null || _state.value == ListenState.LOADING

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
                val s = SpeechService(rec, SAMPLE_RATE)
                if (!s.startListening(listener)) error("Micro déjà utilisé")
                recognizer = rec
                service = s
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
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
        recognizer?.close()
        recognizer = null
        awakeUntil = 0
        _state.value = ListenState.OFF
    }

    override fun expectReply() {
        scope.launch {
            if (service == null) return@launch
            // Si l'ingénieur parle encore, l'écoute s'ouvrira à la fin de sa phrase.
            if (tts.speaking.value) awakeUntil = 1 else awake()
        }
    }

    private suspend fun onEngineerSpeaking(speaking: Boolean) {
        val s = service ?: return
        if (speaking) {
            s.setPause(true)
            _state.value = ListenState.PAUSED
        } else {
            delay(RESUME_DELAY_MS) // laisse la fin de la phrase s'éteindre dans le casque
            s.setPause(false)
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

    private fun onText(text: String) {
        if (text.isBlank() || _state.value == ListenState.PAUSED) return
        _heard.value = text
        val norm = DriverCommandParser.normalize(text)
        val wake = DriverCommandParser.normalize(wakeWord)
        val match = Regex("(^|\\s)${Regex.escape(wake)}(\\s|$)").find(norm)
        if (wake.isNotEmpty() && match != null) {
            val rest = norm.substring(match.range.last + 1).trim()
            if (rest.length >= 2) {
                awakeUntil = 0
                _events.tryEmit(VoiceInputEvent.Utterance(rest))
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
            _events.tryEmit(VoiceInputEvent.Utterance(norm))
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = Unit

        override fun onResult(hypothesis: String?) {
            onText(textOf(hypothesis))
        }

        override fun onFinalResult(hypothesis: String?) {
            onText(textOf(hypothesis))
        }

        override fun onError(exception: Exception?) {
            _error.value = exception?.message ?: "Erreur micro"
            _state.value = ListenState.ERROR
        }

        override fun onTimeout() = Unit
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
    }
}
