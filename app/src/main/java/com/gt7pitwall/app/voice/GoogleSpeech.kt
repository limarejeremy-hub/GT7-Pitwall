package com.gt7pitwall.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Reconnaissance vocale d'Android (Google, sur l'appareil quand c'est possible) pour une phrase : bien plus
 * fiable que le petit modèle hors ligne pour les questions libres et les relances. Une seule écoute à la fois,
 * à appeler depuis le fil principal.
 */
class GoogleSpeech(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var preferOnDevice = true

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    val listening: Boolean get() = recognizer != null

    /** Écoute une phrase ; [onResult] reçoit le texte reconnu, ou null (silence, erreur, délai). */
    fun listenOnce(onResult: (String?) -> Unit) {
        cancel()
        val onDevice = preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val sr = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            onResult(null)
            return
        }
        recognizer = sr
        var done = false
        val timeout = Runnable { finish(sr, null) { if (!done) { done = true; onResult(it) } } }
        main.postDelayed(timeout, TIMEOUT_MS)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                main.removeCallbacks(timeout)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish(sr, text) { if (!done) { done = true; onResult(it) } }
            }

            override fun onError(error: Int) {
                main.removeCallbacks(timeout)
                // Français non installé pour la reconnaissance sur l'appareil : on passe au service standard.
                if (onDevice && (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE)) {
                    preferOnDevice = false
                    finish(sr, null) {}
                    if (!done) {
                        done = true
                        listenOnce(onResult)
                    }
                    return
                }
                finish(sr, null) { if (!done) { done = true; onResult(it) } }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_SILENCE_MS)
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            main.removeCallbacks(timeout)
            finish(sr, null) { if (!done) { done = true; onResult(it) } }
        }
    }

    fun cancel() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun finish(sr: SpeechRecognizer, text: String?, deliver: (String?) -> Unit) {
        if (recognizer === sr) {
            runCatching { sr.destroy() }
            recognizer = null
        }
        deliver(text?.takeIf { it.isNotBlank() })
    }

    private companion object {
        const val TIMEOUT_MS = 12_000L
        const val END_SILENCE_MS = 1_200L
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
