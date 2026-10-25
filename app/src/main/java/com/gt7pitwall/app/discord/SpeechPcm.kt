package com.gt7pitwall.app.discord

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/**
 * Synthèse vocale Android vers des échantillons audio (au lieu du haut-parleur) : la même voix française que sur le
 * Fold, convertie en 48 kHz mono 16 bits pour l'encodeur Opus de Discord.
 */
class SpeechPcm(context: Context) {
    private val cacheDir = context.cacheDir
    private val ready = CompletableDeferred<Boolean>()
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.also { engine ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)

            @Deprecated("Remplacée par onError(String, Int)")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
        })
    }

    @Volatile
    var speechRate: Float = 1.05f

    private fun finish(id: String?, ok: Boolean) {
        synchronized(pending) { pending.remove(id) }?.complete(ok)
    }

    /** Phrase → échantillons 48 kHz mono, ou null si la synthèse échoue. */
    suspend fun synthesize(text: String): ShortArray? {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) return null
        tts.language = Locale.FRANCE
        tts.setSpeechRate(speechRate)
        val id = UUID.randomUUID().toString()
        val file = File(cacheDir, "discord-$id.wav")
        val done = CompletableDeferred<Boolean>()
        synchronized(pending) { pending[id] = done }
        try {
            if (tts.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS) return null
            if (withTimeoutOrNull(SYNTH_TIMEOUT_MS) { done.await() } != true) return null
            val (rate, samples) = readWav(file.readBytes()) ?: return null
            return resample(samples, rate, OpusFrames.SAMPLE_RATE)
        } finally {
            synchronized(pending) { pending.remove(id) }
            file.delete()
        }
    }

    fun shutdown() = tts.shutdown()

    companion object {
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val SYNTH_TIMEOUT_MS = 15_000L

        /** WAV PCM 16 bits (sortie de synthesizeToFile) → (fréquence, échantillons mono). */
        fun readWav(bytes: ByteArray): Pair<Int, ShortArray>? {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
            var pos = 12
            var rate = 0
            var channels = 1
            var bits = 16
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = b.getInt(pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        channels = b.getShort(body + 2).toInt()
                        rate = b.getInt(body + 4)
                        bits = b.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        if (bits != 16 || rate <= 0) return null
                        val end = minOf(bytes.size, body + if (size > 0) size else bytes.size)
                        val frames = (end - body) / (2 * channels)
                        val out = ShortArray(frames) { i -> b.getShort(body + i * 2 * channels) } // 1er canal
                        return rate to out
                    }
                }
                pos = body + size + (size and 1)
            }
            return null
        }

        /** Rééchantillonnage linéaire (la voix de synthèse est souvent en 22,05 ou 24 kHz). */
        fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
            if (from == to || input.isEmpty()) return input
            val outSize = (input.size.toLong() * to / from).toInt()
            return ShortArray(outSize) { i ->
                val src = i.toDouble() * from / to
                val i0 = src.toInt().coerceAtMost(input.size - 1)
                val i1 = (i0 + 1).coerceAtMost(input.size - 1)
                val f = src - i0
                (input[i0] * (1 - f) + input[i1] * f).toInt().toShort()
            }
        }
    }
}
