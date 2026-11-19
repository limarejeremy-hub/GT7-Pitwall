package com.gt7pitwall.app.discord

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * V510 : encodeur Opus EN FLUX (un seul encodeur pour toute une réponse). Le prototype B reçoit l'audio par morceaux de
 * 20 ms ; encoder chaque morceau avec un encodeur neuf (comme [OpusFrames.encode]) ajouterait un claquement à chaque
 * morceau. Ici l'état de l'encodeur est gardé : la voix reste continue.
 */
class OpusStreamEncoder(bitrate: Int = 64_000) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var samples = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OpusFrames.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, OpusFrames.FRAME_SAMPLES * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** [pcm] : 48 kHz mono, de préférence par multiples de 20 ms. Renvoie les trames déjà prêtes (l'encodeur peut en garder une ou deux). */
    fun encode(pcm: ShortArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val n = OpusFrames.FRAME_SAMPLES
        val padded = if (pcm.size % n == 0) pcm else pcm.copyOf(pcm.size + n - pcm.size % n)
        var offset = 0
        while (offset < padded.size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!
                buffer.clear()
                buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(padded, offset, n)
                codec.queueInputBuffer(index, 0, n * 2, samples * 1_000_000L / OpusFrames.SAMPLE_RATE, 0)
                samples += n
                offset += n
            }
            drain(out, 0)
        }
        drain(out, DRAIN_US)
        return out
    }

    private fun drain(out: MutableList<ByteArray>, timeoutUs: Long) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, timeoutUs)
            if (i < 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val b = codec.getOutputBuffer(i)!!
                b.position(info.offset)
                b.limit(info.offset + info.size)
                out += ByteArray(info.size).also { b.get(it) }
            }
            codec.releaseOutputBuffer(i, false)
        }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val DRAIN_US = 2_000L
    }
}
