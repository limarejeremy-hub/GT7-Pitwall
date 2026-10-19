package com.gt7pitwall.app.discord

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Décodage de la voix reçue du salon Discord (paquets Opus de 20 ms, 48 kHz) avec le décodeur intégré d'Android,
 * puis conversion en 16 kHz mono 16 bits : le format de la reconnaissance vocale (Vosk).
 */
class OpusDecoderStream {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var channels = CHANNELS
    private var pts = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS).apply {
            // En-têtes exigés par le décodeur Opus d'Android : OpusHead, délai du codec, pré-roulement.
            setByteBuffer("csd-0", opusHead())
            setByteBuffer("csd-1", nanos(0))
            setByteBuffer("csd-2", nanos(SEEK_PREROLL_NS))
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** Un paquet Opus → échantillons 16 kHz mono (vide si le décodeur n'a encore rien rendu). */
    fun decode(packet: ByteArray): ShortArray {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index >= 0) {
            codec.getInputBuffer(index)!!.apply {
                clear()
                put(packet)
            }
            codec.queueInputBuffer(index, 0, packet.size, pts, 0)
            pts += FRAME_US
        }
        var out = ShortArray(0)
        while (true) {
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                o >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(o)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val pcm = ShortArray(info.size / 2)
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                        out += toMono16k(pcm, channels)
                    }
                    codec.releaseOutputBuffer(o, false)
                }
                else -> return out
            }
        }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val OUTPUT_RATE = 16_000
        private const val CHANNELS = 2
        private const val FRAME_US = 20_000L
        private const val TIMEOUT_US = 5_000L
        private const val SEEK_PREROLL_NS = 80_000_000L

        /** Entrelacé 48 kHz → mono 16 kHz : moyenne des canaux puis de 3 échantillons (filtre passe-bas simple). */
        fun toMono16k(interleaved: ShortArray, channels: Int): ShortArray {
            val frames = interleaved.size / channels
            val out = ShortArray(frames / 3)
            for (i in out.indices) {
                var sum = 0
                for (k in 0 until 3) {
                    val f = (i * 3 + k) * channels
                    for (c in 0 until channels) sum += interleaved[f + c]
                }
                out[i] = (sum / (3 * channels)).toShort()
            }
            return out
        }

        private fun opusHead(): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1) // version
            put(CHANNELS.toByte())
            putShort(0) // pré-saut
            putInt(SAMPLE_RATE)
            putShort(0) // gain
            put(0) // famille de canaux (mono/stéréo)
            flip()
        }

        private fun nanos(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
    }
}
