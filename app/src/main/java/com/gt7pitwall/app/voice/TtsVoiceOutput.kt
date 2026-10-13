package com.gt7pitwall.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.radio.VoiceOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class VoiceState { INITIALIZING, READY, FRENCH_MISSING, UNAVAILABLE }

/** Voix de l'ingénieur avec le moteur Text-to-Speech intégré à Android (gratuit, hors ligne si la voix est installée). */
class TtsVoiceOutput(context: Context) : VoiceOutput, TextToSpeech.OnInitListener {

    private val _state = MutableStateFlow(VoiceState.INITIALIZING)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    @Volatile
    var speechRate: Float = 1.0f

    private val ids = AtomicInteger()
    private val pending = mutableListOf<EngineerMessage>()
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _state.value = VoiceState.UNAVAILABLE
            return
        }
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
                tts.setSpeechRate(speechRate)
                val mode = if (message.priority == Priority.CRITICAL) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts.speak(message.text, mode, null, "pitwall-${ids.incrementAndGet()}")
            }
        }
    }

    override fun stop() {
        tts.stop()
    }
}
