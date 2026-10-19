package com.gt7pitwall.app.discord

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * Encodage Opus avec l'encodeur intégré d'Android (MediaCodec, Android 10+) : Discord transporte la voix en Opus,
 * 48 kHz, une trame toutes les 20 ms. On fournit à JDA des trames déjà encodées : aucune bibliothèque native de
 * bureau (opus-java) n'est nécessaire sur le téléphone.
 */
object OpusFrames {
    const val SAMPLE_RATE = 48_000
    const val FRAME_SAMPLES = 960 // 20 ms à 48 kHz

    /** [pcm] : échantillons 16 bits mono à 48 kHz. Renvoie une trame Opus par tranche de 20 ms. */
    fun encode(pcm: ShortArray, bitrate: Int = 64_000): List<ByteArray> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val frames = mutableListOf<ByteArray>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            // Complète la dernière tranche par du silence : l'encodeur reçoit exactement une trame par tampon.
            val padded = if (pcm.size % FRAME_SAMPLES == 0) pcm else pcm.copyOf(pcm.size + FRAME_SAMPLES - pcm.size % FRAME_SAMPLES)
            var offset = 0
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + MAX_ENCODE_MS
            while (!outputDone) {
                check(System.currentTimeMillis() < deadline) { "Encodeur Opus bloqué" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        val ptsUs = offset * 1_000_000L / SAMPLE_RATE
                        if (offset >= padded.size) {
                            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(padded, offset, FRAME_SAMPLES)
                            codec.queueInputBuffer(index, 0, FRAME_SAMPLES * 2, ptsUs, 0)
                            offset += FRAME_SAMPLES
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out >= 0) {
                    // Les tampons de configuration (en-tête OpusHead, délais) ne sont pas de l'audio.
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buffer = codec.getOutputBuffer(out)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        frames += ByteArray(info.size).also { buffer.get(it) }
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return frames
    }

    /** Durée d'une trame Opus d'après son octet TOC (RFC 6716), en 1/10 de ms ; pour vérifier les 20 ms attendues. */
    fun frameDurationTenthMs(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val single = when {
            config < 12 -> intArrayOf(100, 200, 400, 600)[config and 3]
            config < 16 -> intArrayOf(100, 200)[config and 1]
            else -> intArrayOf(25, 50, 100, 200)[config and 3]
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else return null
        }
        return single * count
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_ENCODE_MS = 15_000L
}
