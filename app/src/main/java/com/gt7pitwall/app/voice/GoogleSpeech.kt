package com.gt7pitwall.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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

    /**
     * Flux audio de l'application (micro du casque PS5) accepté par la reconnaissance : inconnu tant qu'aucun essai
     * n'a eu lieu, faux après un refus (on n'insiste plus, la radio garde Vosk).
     */
    @Volatile
    var externalAudioSupported: Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null else false
        private set

    /**
     * Écoute une phrase ; [onResult] reçoit le texte reconnu, ou null (silence, erreur, délai). Avec [audio], la
     * reconnaissance lit ce flux au lieu du micro du téléphone.
     */
    fun listenOnce(audio: ExternalAudio? = null, onResult: (String?) -> Unit) {
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
        current = audio
        var done = false
        val timeout = Runnable { finish(sr, null) { if (!done) { done = true; onResult(it) } } }
        main.postDelayed(timeout, TIMEOUT_MS)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                main.removeCallbacks(timeout)
                if (audio != null) externalAudioSupported = true
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
                        listenOnce(audio?.reopen(), onResult)
                    }
                    return
                }
                // Flux audio refusé (reconnaissance qui ne sait lire que le micro) : on ne le proposera plus.
                if (audio != null && externalAudioSupported == null &&
                    error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    externalAudioSupported = false
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
            if (audio != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio.readSide)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, ExternalAudio.SAMPLE_RATE)
            }
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            main.removeCallbacks(timeout)
            finish(sr, null) { if (!done) { done = true; onResult(it) } }
        }
    }

    @Volatile
    private var current: ExternalAudio? = null

    /** Échantillons du micro du casque pour l'écoute en cours (ignorés si elle lit le micro du téléphone). */
    fun feed(samples: ShortArray, n: Int) {
        current?.write(samples, n)
    }

    fun cancel() {
        current?.close()
        current = null
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
            current?.close()
            current = null
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

/**
 * Flux audio 16 kHz mono 16 bits remis à la reconnaissance Google par un tube : les échantillons sont écrits par un fil
 * dédié, pour que la boucle d'écoute ne soit jamais bloquée si la reconnaissance lit lentement ou s'arrête.
 */
class ExternalAudio private constructor(private val pipe: Array<ParcelFileDescriptor>) {
    val readSide: ParcelFileDescriptor get() = pipe[0]
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_CHUNKS)

    @Volatile
    private var open = true

    private val writer = Thread {
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
            try {
                while (open) {
                    val chunk = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    out.write(chunk)
                }
            } catch (e: Exception) {
                // Lecteur fermé (fin de la reconnaissance) : on s'arrête.
            }
        }
    }.apply {
        isDaemon = true
        name = "pitwall-google-audio"
        start()
    }

    fun write(samples: ShortArray, n: Int) {
        if (!open || n <= 0) return
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            bytes[2 * i] = samples[i].toInt().toByte()
            bytes[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        queue.offer(bytes)
    }

    /** Nouveau flux (nouvel essai de reconnaissance) ; celui-ci est fermé. */
    fun reopen(): ExternalAudio {
        close()
        return create()
    }

    fun close() {
        if (!open) return
        open = false
        queue.clear()
        runCatching { pipe[0].close() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MAX_CHUNKS = 200
        private const val POLL_MS = 100L

        fun create(): ExternalAudio = ExternalAudio(ParcelFileDescriptor.createPipe())
    }
}
