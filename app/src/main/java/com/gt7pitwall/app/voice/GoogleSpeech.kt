package com.gt7pitwall.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.gt7pitwall.core.radio.GoogleEngine
import com.gt7pitwall.core.radio.GoogleOutcome
import com.gt7pitwall.core.radio.SpeechEndpointer
import com.gt7pitwall.core.radio.StreamAttempt
import com.gt7pitwall.core.radio.StreamEnginePolicy
import com.gt7pitwall.core.radio.StreamStats
import java.io.FileInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Fin d'une écoute Google : le texte s'il y en a un, sinon pourquoi. */
data class GoogleResult(
    val text: String?,
    val outcome: GoogleOutcome,
    val errorCode: Int? = null,
    /** Moteur utilisé (« Google sur l'appareil », « service Google »). */
    val engine: String? = null,
    /** Flux du casque : ce que l'essai prouve (flux lu ou non, pourquoi rien n'est sorti). */
    val note: String? = null,
    /** Flux du casque : moteur écarté ou Google coupé pour le casque après cet essai. */
    val switch: String? = null,
)

/**
 * Diagnostic de la dernière écoute Google, affiché dans l'application. Aucun son n'est enregistré ni journalisé :
 * seulement des états, des délais, un niveau sonore maximal et la phrase transcrite.
 */
data class GoogleDiag(
    val phase: String = "inactive",
    /** « sur l'appareil » ou « service Google ». */
    val engine: String? = null,
    /** « micro du Fold » ou « flux du casque PS5 ». */
    val source: String? = null,
    /** Délai entre le démarrage et « prête à écouter » (onReadyForSpeech). */
    val readyMs: Long? = null,
    /** La parole a été détectée (onBeginningOfSpeech). */
    val speechDetected: Boolean = false,
    /** Niveau sonore maximal reçu par la reconnaissance (dB, onRmsChanged) : proche de -2 = silence. */
    val maxRmsDb: Float? = null,
    val errorCode: Int? = null,
    val errorName: String? = null,
    val lastText: String? = null,
    val outcome: GoogleOutcome? = null,
    /** Flux du casque : Pitwall y a détecté une voix (null : micro du Fold). */
    val streamVoice: Boolean? = null,
    /** Flux du casque : octets envoyés, lus par la reconnaissance, délais, fermeture. */
    val stream: String? = null,
    /** Flux du casque : conclusion de l'essai (flux lu ou non, rien compris...). */
    val verdict: String? = null,
    /** Dernière bascule de moteur pour le casque. */
    val switch: String? = null,
)

/**
 * Reconnaissance vocale d'Android (Google, sur l'appareil quand c'est possible) pour une phrase : bien plus
 * fiable que le petit modèle hors ligne pour les questions libres et les relances. Une seule écoute à la fois,
 * à appeler depuis le fil principal.
 *
 * L'écoute ne commence vraiment qu'à onReadyForSpeech : [listenOnce] le signale par onReady, pour que le pilote ne
 * parle pas dans le vide. Sans « prête » dans [READY_TIMEOUT_MS], l'écoute est abandonnée (NOT_READY) : la radio
 * repasse sur Vosk au lieu de rester muette.
 */
class GoogleSpeech(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var preferOnDevice = true

    private val prefs = context.getSharedPreferences("google_stream", Context.MODE_PRIVATE)

    /**
     * Moteurs Google pour le flux du casque PS5, appris des essais et mémorisés : un moteur dont on a prouvé qu'il ne
     * lit pas le flux n'est plus essayé. Le micro du Fold n'est pas concerné.
     */
    val streamPolicy = StreamEnginePolicy().also { policy ->
        GoogleEngine.entries.forEach { e -> prefs.getString(e.name, null)?.let { policy.disable(e, it) } }
    }

    private val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Google peut être essayé sur le flux du casque : Android 13+ et au moins un moteur pas écarté. */
    val streamUsable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && streamPolicy.next(onDeviceAvailable) != null

    /** Pourquoi Google ne sert plus pour le casque (null : il est encore essayé). */
    val streamOffReason: String?
        get() = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "Android 13 requis pour donner le flux à Google"
            streamUsable -> null
            else -> streamPolicy.disabledReasons().entries.joinToString(" ; ") { (e, why) -> "${e.label} : $why" }
        }

    /** « Réessayer Google sur le casque » : les moteurs écartés sont de nouveau essayés. */
    fun resetStream() {
        streamPolicy.reset()
        prefs.edit().clear().apply()
        _diag.value = _diag.value.copy(switch = "moteurs du casque réinitialisés")
    }

    private fun saveStream() {
        val e = prefs.edit().clear()
        streamPolicy.disabledReasons().forEach { (engine, why) -> e.putString(engine.name, why) }
        e.apply()
    }

    private val _diag = MutableStateFlow(GoogleDiag())
    val diag: StateFlow<GoogleDiag> = _diag.asStateFlow()

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    val listening: Boolean get() = recognizer != null

    /**
     * Écoute une phrase. [onReady] : la reconnaissance écoute vraiment (signal au pilote). [onResult] : le texte, ou
     * la raison de l'échec (code d'erreur Android). Avec [audio], la reconnaissance lit ce flux au lieu du micro.
     */
    fun listenOnce(audio: ExternalAudio? = null, onReady: () -> Unit = {}, onResult: (GoogleResult) -> Unit) {
        cancel()
        val engine = if (audio != null) {
            streamPolicy.next(onDeviceAvailable)
        } else if (preferOnDevice && onDeviceAvailable) {
            GoogleEngine.ON_DEVICE
        } else {
            GoogleEngine.STANDARD
        }
        if (engine == null) {
            audio?.close()
            end(GoogleResult(null, GoogleOutcome.ERROR), "Google coupé pour le casque", onResult)
            return
        }
        val onDevice = engine == GoogleEngine.ON_DEVICE
        val started = SystemClock.elapsedRealtime()
        _diag.value = GoogleDiag(
            phase = "démarrage",
            engine = engine.label,
            source = if (audio != null) "flux du casque PS5" else "micro du Fold",
            lastText = _diag.value.lastText,
            switch = _diag.value.switch,
        )
        val sr = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            end(GoogleResult(null, GoogleOutcome.ERROR), "création impossible : ${e.javaClass.simpleName}", onResult)
            return
        }
        recognizer = sr
        current = audio
        var done = false
        var ready = false
        fun deliver(raw: GoogleResult, phase: String) {
            if (done) return
            done = true
            var result = raw.copy(engine = engine.label)
            if (audio != null) {
                // Mesuré avant de fermer le tube : ce qui y reste n'a pas été lu.
                val stats = audio.stats()
                val (verdict, why) = StreamAttempt.assess(result.outcome, result.errorCode, stats)
                val switch = streamPolicy.record(engine, verdict, why, onDeviceAvailable)
                if (switch != null) saveStream()
                result = result.copy(note = why, switch = switch)
                _diag.value = _diag.value.copy(
                    streamVoice = stats.voiceHeard,
                    stream = stats.summary(),
                    verdict = why,
                    switch = switch ?: _diag.value.switch,
                )
            }
            main.removeCallbacksAndMessages(sr)
            finish(sr)
            end(result, phase, onResult)
        }
        // Flux fermé par Pitwall (fin de phrase) : Google doit répondre peu après, sinon on n'attend pas son délai.
        audio?.onEnd = { label ->
            main.post {
                if (!done) {
                    _diag.value = _diag.value.copy(phase = "flux du casque fermé : $label")
                    main.postAtTime(
                        { deliver(GoogleResult(null, GoogleOutcome.TIMEOUT), "pas de réponse après la fin du flux") },
                        sr, SystemClock.uptimeMillis() + STREAM_GRACE_MS,
                    )
                }
            }
        }
        // Jamais prête : on abandonne vite, la radio repasse sur Vosk.
        main.postAtTime({ if (!ready) deliver(GoogleResult(null, GoogleOutcome.NOT_READY), "jamais prête") }, sr, SystemClock.uptimeMillis() + READY_TIMEOUT_MS)
        main.postAtTime({ deliver(GoogleResult(null, GoogleOutcome.TIMEOUT), "délai dépassé") }, sr, SystemClock.uptimeMillis() + TIMEOUT_MS)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (done || ready) return
                ready = true
                _diag.value = _diag.value.copy(phase = "écoute", readyMs = SystemClock.elapsedRealtime() - started)
                onReady()
            }

            override fun onBeginningOfSpeech() {
                _diag.value = _diag.value.copy(phase = "parole détectée", speechDetected = true)
            }

            override fun onRmsChanged(rmsdB: Float) {
                val d = _diag.value
                if (d.maxRmsDb == null || rmsdB > d.maxRmsDb) _diag.value = d.copy(maxRmsDb = rmsdB)
            }

            override fun onEndOfSpeech() {
                _diag.value = _diag.value.copy(phase = "fin de parole, transcription")
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                if (text == null) {
                    deliver(GoogleResult(null, GoogleOutcome.NO_MATCH), "résultat vide")
                } else {
                    _diag.value = _diag.value.copy(lastText = text)
                    deliver(GoogleResult(text, GoogleOutcome.RESULT), "phrase reçue")
                }
            }

            override fun onError(error: Int) {
                // Français non installé pour la reconnaissance sur l'appareil : on passe au service standard.
                if (onDevice && (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE) && !done) {
                    if (audio != null) {
                        streamPolicy.disable(GoogleEngine.ON_DEVICE, "français non installé sur l'appareil")
                        saveStream()
                    } else {
                        preferOnDevice = false
                    }
                    done = true
                    main.removeCallbacksAndMessages(sr)
                    finish(sr)
                    listenOnce(audio?.reopen(), onReady, onResult)
                    return
                }
                _diag.value = _diag.value.copy(errorCode = error, errorName = errorName(error))
                val outcome = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> GoogleOutcome.NO_MATCH
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> GoogleOutcome.SPEECH_TIMEOUT
                    else -> GoogleOutcome.ERROR
                }
                deliver(GoogleResult(null, outcome, error), "erreur ${errorName(error)}")
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
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
            deliver(GoogleResult(null, GoogleOutcome.ERROR), "démarrage refusé : ${e.javaClass.simpleName}")
        }
    }

    private fun end(result: GoogleResult, phase: String, onResult: (GoogleResult) -> Unit) {
        _diag.value = _diag.value.copy(phase = phase, outcome = result.outcome)
        onResult(result)
    }

    @Volatile
    private var current: ExternalAudio? = null

    /** Échantillons du micro du casque pour l'écoute en cours (ignorés si elle lit le micro du téléphone). */
    fun feed(samples: ShortArray, n: Int) {
        current?.write(samples, n)
    }

    /** Annule l'écoute en cours, sans résultat (la radio sait pourquoi : elle l'a demandé). */
    fun cancel() {
        current?.close()
        current = null
        recognizer?.let {
            main.removeCallbacksAndMessages(it)
            runCatching { it.cancel() }
            runCatching { it.destroy() }
            _diag.value = _diag.value.copy(phase = "annulée", outcome = GoogleOutcome.CANCELLED)
        }
        recognizer = null
    }

    private fun finish(sr: SpeechRecognizer) {
        if (recognizer === sr) {
            runCatching { sr.destroy() }
            recognizer = null
            current?.close()
            current = null
        }
    }

    companion object {
        const val TIMEOUT_MS = 12_000L
        const val READY_TIMEOUT_MS = 3_000L
        const val END_SILENCE_MS = 1_200L

        /** Après la fermeture du flux par Pitwall, délai laissé à Google pour rendre sa phrase. */
        const val STREAM_GRACE_MS = 2_500L
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /** Nom lisible des codes d'erreur de SpeechRecognizer. */
        fun errorName(code: Int): String = when (code) {
            1 -> "délai réseau (1)"
            2 -> "réseau (2)"
            3 -> "audio / micro (3)"
            4 -> "serveur (4)"
            5 -> "client (5)"
            6 -> "aucune parole (6)"
            7 -> "rien compris (7)"
            8 -> "reconnaissance occupée (8)"
            9 -> "permission micro (9)"
            10 -> "trop de requêtes (10)"
            11 -> "service déconnecté (11)"
            12 -> "langue non prise en charge (12)"
            13 -> "langue non installée (13)"
            14 -> "vérification impossible (14)"
            15 -> "pilote audio (15)"
            else -> "code $code"
        }
    }
}

/**
 * Flux audio 16 kHz mono 16 bits remis à la reconnaissance Google par un tube : les échantillons sont écrits par un fil
 * dédié, pour que la boucle d'écoute ne soit jamais bloquée si la reconnaissance lit lentement ou s'arrête.
 */
class ExternalAudio private constructor(private val pipe: Array<ParcelFileDescriptor>) {
    val readSide: ParcelFileDescriptor get() = pipe[0]
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_CHUNKS)

    /**
     * Côté lecture gardé par Pitwall, seulement pour compter ce qui reste dans le tube (jamais lu : available() sur
     * un tube donne les octets en attente). Pas propriétaire : ne ferme rien.
     */
    private val pending = FileInputStream(pipe[0].fileDescriptor)
    private val openedAt = SystemClock.elapsedRealtime()

    @Volatile
    private var offeredBytes = 0L

    @Volatile
    private var writtenBytes = 0L

    @Volatile
    private var firstByteAt = 0L

    @Volatile
    private var endLabel: String? = null

    @Volatile
    private var open = true

    /** Fin de phrase détectée : le reste de la file est écrit, puis le flux est fermé (fin de flux pour Google). */
    @Volatile
    private var ending = false
    private val endpointer = SpeechEndpointer(SAMPLE_RATE)

    /** Une voix a été détectée dans ce qui a été envoyé à Google. */
    val heardSpeech: Boolean get() = endpointer.heardSpeech

    /** Appelé (depuis la boucle d'écoute) quand Pitwall ferme le flux, avec la raison. */
    @Volatile
    var onEnd: ((String) -> Unit)? = null

    private val writer = Thread {
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
            try {
                while (open) {
                    val chunk = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: if (ending) break else continue
                    out.write(chunk)
                    if (firstByteAt == 0L) firstByteAt = SystemClock.elapsedRealtime()
                    writtenBytes += chunk.size
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
        if (!open || ending || n <= 0) return
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            bytes[2 * i] = samples[i].toInt().toByte()
            bytes[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        if (queue.offer(bytes)) offeredBytes += bytes.size
        // Google lit un flux jusqu'à sa fin : sans fin de flux, il attend jusqu'au délai et ne rend rien.
        endpointer.accept(samples, n)?.let { end ->
            ending = true
            endLabel = end.label
            onEnd?.invoke(end.label)
        }
    }

    /** Compteurs de ce flux (à prendre avant [close]). */
    fun stats(): StreamStats {
        val unread = if (open && measurable) runCatching { pending.available().toLong() }.getOrNull() else null
        return StreamStats(
            offeredBytes = offeredBytes,
            writtenBytes = writtenBytes,
            unreadBytes = unread,
            firstByteMs = firstByteAt.takeIf { it > 0 }?.let { it - openedAt },
            audioMs = offeredBytes * 1000 / (SAMPLE_RATE * 2),
            endLabel = endLabel,
            voiceHeard = endpointer.heardSpeech,
        )
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

        /**
         * Le compte des octets en attente dans un tube fonctionne sur ce téléphone (vérifié une fois sur un tube d'essai
         * de 1 000 octets) : sinon, aucune conclusion « flux lu » ou « non lu » n'est tirée.
         */
        private val measurable: Boolean by lazy {
            runCatching {
                val (r, w) = ParcelFileDescriptor.createPipe()
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(w).use { it.write(ByteArray(1_000)) }
                    FileInputStream(r.fileDescriptor).available() == 1_000
                } finally {
                    r.close()
                }
            }.getOrDefault(false)
        }
    }
}
