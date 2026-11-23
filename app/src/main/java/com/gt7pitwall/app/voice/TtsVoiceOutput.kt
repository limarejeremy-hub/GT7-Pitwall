package com.gt7pitwall.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.radio.SpeechStyle
import com.gt7pitwall.core.radio.SpokenFrench
import com.gt7pitwall.core.radio.VoiceOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class VoiceState { INITIALIZING, READY, FRENCH_MISSING, UNAVAILABLE }

/** Voix de l'ingénieur avec le moteur Text-to-Speech intégré à Android (gratuit, hors ligne si la voix est installée). */
class TtsVoiceOutput(context: Context) : VoiceOutput, TextToSpeech.OnInitListener {

    private val _state = MutableStateFlow(VoiceState.INITIALIZING)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    /** Vrai pendant que l'ingénieur parle : la radio coupe alors le micro. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var speechRate: Float = 1.0f

    /**
     * Mode d'interruption entre les phrases (haut-parleur du Fold) : chaque phrase est suivie d'une courte pause pendant
     * laquelle le micro peut entendre « attends ». Sans ce mode, le message est dit d'un trait, comme avant.
     */
    @Volatile
    var gaps: () -> Boolean = { false }

    private val _inGap = MutableStateFlow(false)
    /** Vrai pendant une pause entre deux phrases : le micro écoute une éventuelle interruption. */
    val inGap: StateFlow<Boolean> = _inGap.asStateFlow()

    /** Message en cours de diffusion (pour reconnaître l'écho de l'ingénieur et ne pas couper une alerte). */
    @Volatile
    var current: EngineerMessage? = null
        private set

    /** Premier son réellement émis pour un message (mesure de latence). */
    @Volatile
    var onAudible: (EngineerMessage, Long) -> Unit = { _, _ -> }

    private val messages = ConcurrentHashMap<String, EngineerMessage>()

    private val ids = AtomicInteger()
    private val pending = mutableListOf<EngineerMessage>()
    private val tts = TextToSpeech(context.applicationContext, this)

    /** Décision de préemption d'après toute la file Android (V511, revue #86). */
    private val preemption = com.gt7pitwall.core.radio.TtsPreemption()

    /** V509 : moteur réellement utilisé pour la voix du haut-parleur du Fold (diagnostic). */
    fun engineReport(): com.gt7pitwall.core.radio.VoiceEngineReport {
        val v = runCatching { tts.voice }.getOrNull()
        return com.gt7pitwall.core.radio.VoiceEngineReport(
            route = "Haut-parleur du Fold",
            enginePackage = runCatching { tts.defaultEngine }.getOrNull(),
            voiceName = v?.name,
            locale = v?.locale?.toLanguageTag(),
            networkVoice = v?.isNetworkConnectionRequired,
            quality = v?.quality,
        )
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _state.value = VoiceState.UNAVAILABLE
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == null) return
                if (utteranceId.startsWith(GAP_PREFIX)) {
                    _inGap.value = true
                    return
                }
                _inGap.value = false
                messages[utteranceId]?.let { m ->
                    if (current !== m) {
                        current = m
                        onAudible(m, android.os.SystemClock.elapsedRealtime())
                    }
                }
            }

            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val result = tts.setLanguage(Locale.FRANCE)
        _state.value = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            VoiceState.FRENCH_MISSING
        } else {
            VoiceState.READY
        }
        val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
        queued.forEach(::speak)
    }

    override fun speak(message: EngineerMessage) {
        when (_state.value) {
            VoiceState.INITIALIZING -> synchronized(pending) { pending += message }
            VoiceState.UNAVAILABLE -> Unit
            // Sans voix française, on parle quand même avec la voix par défaut plutôt que se taire.
            VoiceState.READY, VoiceState.FRENCH_MISSING -> {
                // Débit et hauteur selon le message (alerte plus directe, réaction plus vive), sans jamais le retarder.
                // Voix neuronale (moteur tiers) : débit et hauteur naturels, sinon un accent apparaît (essai du 23/11).
                val sent = SpeechStyle.applied(runCatching { tts.defaultEngine }.getOrNull(), speechRate, SpeechStyle.of(message))
                tts.setSpeechRate(sent.rate)
                tts.setPitch(sent.pitch)
                // V511 (revue #86) : décision d'après toute la file (alertes placées mais pas encore commencées
                // comprises) : une alerte critique n'efface jamais une autre alerte critique ; une annonce non critique
                // arrivant entre deux alertes attend la fin de la dernière.
                val flush = when (preemption.decide(message)) {
                    com.gt7pitwall.core.radio.TtsPreemption.Action.DEFER -> return
                    com.gt7pitwall.core.radio.TtsPreemption.Action.FLUSH -> true
                    com.gt7pitwall.core.radio.TtsPreemption.Action.ADD -> false
                }
                if (flush) {
                    inFlight.clear()
                    preemption.flushed()
                }
                _speaking.value = true
                // Une alerte critique est dite d'un trait : elle ne s'interrompt pas.
                val parts = if (gaps() && !flush) SpeechStyle.sentences(message.text) else listOf(message.text)
                parts.forEachIndexed { i, part ->
                    val id = "pitwall-${ids.incrementAndGet()}"
                    inFlight += id
                    preemption.queued(id, message.priority)
                    messages[id] = message
                    val mode = if (flush && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    // #73 P1-C : texte de synthèse seulement (« P11 » → « P onze », ponctuation finale) ; écran et journal inchangés.
                    if (tts.speak(SpokenFrench.forAudio(part), mode, null, id) != TextToSpeech.SUCCESS) finished(id)
                    if (i < parts.lastIndex) {
                        val gap = "$GAP_PREFIX${ids.incrementAndGet()}"
                        inFlight += gap
                        preemption.queued(gap, message.priority)
                        if (tts.playSilentUtterance(SpeechStyle.GAP_MS, TextToSpeech.QUEUE_ADD, gap) != TextToSpeech.SUCCESS) finished(gap)
                    }
                }
                // Court silence après le message : la sortie audio reste ouverte le temps que la dernière consonne
                // (« onze », /s/, /z/) soit jouée en entier, au lieu d'être coupée par la fermeture de la sortie.
                val tail = "$TAIL_PREFIX${ids.incrementAndGet()}"
                inFlight += tail
                preemption.queued(tail, message.priority)
                if (tts.playSilentUtterance(TAIL_MS, TextToSpeech.QUEUE_ADD, tail) != TextToSpeech.SUCCESS) finished(tail)
            }
        }
    }

    override fun stop() {
        tts.stop()
        preemption.stopped()
        inFlight.clear()
        messages.clear()
        current = null
        _inGap.value = false
        _speaking.value = false
    }

    private fun finished(utteranceId: String?) {
        var released = emptyList<EngineerMessage>()
        if (utteranceId != null) {
            inFlight -= utteranceId
            messages.remove(utteranceId)
            if (utteranceId.startsWith(GAP_PREFIX)) _inGap.value = false
            released = preemption.finished(utteranceId)
        }
        // Annonces retenues pendant les alertes critiques : dites maintenant, sauf si elles sont devenues fausses.
        released.filterNot { com.gt7pitwall.core.radio.RoutedVoiceOutput.stale(it, System.currentTimeMillis()) }.forEach(::speak)
        if (inFlight.isEmpty()) {
            current = null
            _inGap.value = false
            _speaking.value = false
        }
    }

    private companion object {
        const val GAP_PREFIX = "pitwall-gap-"
        const val TAIL_PREFIX = "pitwall-tail-"
        const val TAIL_MS = 150L
    }
}
