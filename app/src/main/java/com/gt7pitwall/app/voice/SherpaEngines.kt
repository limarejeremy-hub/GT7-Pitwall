package com.gt7pitwall.app.voice

import android.os.SystemClock
import com.gt7pitwall.core.radio.UtteranceSegmenter
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** Phrase rendue par un moteur du banc d'essai : texte, temps de calcul, durée d'audio. */
internal class BenchHeard(val text: String, val computeMs: Long, val audioMs: Long)

/** Moteur sherpa-onnx du banc d'essai (un seul fil l'utilise). */
internal interface BenchEngine {
    /** Bloc reçu ; renvoie une phrase terminée, sinon null. */
    fun accept(samples: ShortArray): BenchHeard?
    fun partial(): String = ""
    fun release()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val THREADS = 2
        const val VOICE_RMS = 400.0

        fun toFloats(samples: ShortArray) = FloatArray(samples.size) { samples[it] / 32768f }
    }
}

/** Zipformer français en continu : la phrase sort quand le moteur détecte sa fin (1,2 s de silence). */
internal class ZipformerEngine(dir: File) : BenchEngine {
    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = BenchEngine.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx").path,
                    decoder = File(dir, "decoder-epoch-29-avg-9-with-averaged-model.onnx").path,
                    joiner = File(dir, "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                numThreads = BenchEngine.THREADS,
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.2f, 0f),
                rule3 = EndpointRule(false, 0f, 15f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )
    private var stream: OnlineStream = recognizer.createStream("")
    private var computeMs = 0L
    private var audioSamples = 0L
    private var voiced = false

    override fun accept(samples: ShortArray): BenchHeard? {
        stream.acceptWaveform(BenchEngine.toFloats(samples), BenchEngine.SAMPLE_RATE)
        audioSamples += samples.size
        if (UtteranceSegmenter.rms(samples) >= BenchEngine.VOICE_RMS) voiced = true
        val t0 = SystemClock.elapsedRealtime()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        computeMs += SystemClock.elapsedRealtime() - t0
        if (!recognizer.isEndpoint(stream)) return null
        val text = recognizer.getResult(stream).text
        recognizer.reset(stream)
        // Silence seul : pas une phrase (et le temps de calcul du silence n'est pas compté).
        val out = if (voiced && text.isNotBlank()) BenchHeard(text, computeMs, audioSamples * 1000 / BenchEngine.SAMPLE_RATE) else null
        computeMs = 0
        audioSamples = 0
        voiced = false
        return out
    }

    override fun partial(): String = recognizer.getResult(stream).text

    override fun release() {
        stream.release()
        recognizer.release()
    }
}

/** Whisper base en français : chaque phrase (découpée sur le silence) est transcrite une fois finie. */
internal class WhisperEngine(dir: File) : BenchEngine {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = BenchEngine.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, "base-encoder.int8.onnx").path,
                    decoder = File(dir, "base-decoder.int8.onnx").path,
                    language = "fr",
                    task = "transcribe",
                ),
                tokens = File(dir, "base-tokens.txt").path,
                numThreads = BenchEngine.THREADS,
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        ),
    )
    private val segmenter = UtteranceSegmenter(speechRms = BenchEngine.VOICE_RMS)

    override fun accept(samples: ShortArray): BenchHeard? {
        val segment = segmenter.accept(samples, samples.size) ?: return null
        val t0 = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(BenchEngine.toFloats(segment), BenchEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text
            return BenchHeard(text, SystemClock.elapsedRealtime() - t0, segment.size * 1000L / BenchEngine.SAMPLE_RATE)
        } finally {
            stream.release()
        }
    }

    override fun release() {
        recognizer.release()
    }
}
