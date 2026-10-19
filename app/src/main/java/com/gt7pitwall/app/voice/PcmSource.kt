package com.gt7pitwall.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Source audio de la radio du pilote : 16 kHz mono 16 bits. */
interface PcmSource {
    /** Libellé affiché (« micro du Fold », « micro du casque PS5 »). */
    val label: String

    /** Micro du téléphone : la reconnaissance Google (qui l'utilise aussi) peut prendre le relais pour une phrase. */
    val isPhoneMic: Boolean

    fun start()

    /** Lit jusqu'à [buffer].size échantillons ; bloque au plus ~0,25 s. Négatif : erreur. */
    fun read(buffer: ShortArray): Int

    /** Suspend l'enregistrement (micro prêté à Google) ; [start] le reprend. */
    fun pause()

    fun release()
}

/** Micro du Fold (AudioRecord, réglé pour la reconnaissance vocale). */
class PhoneMic private constructor(private val record: AudioRecord) : PcmSource {
    override val label = "micro du Fold"
    override val isPhoneMic = true

    override fun start() = record.startRecording()

    override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

    override fun pause() = record.stop()

    override fun release() {
        runCatching { record.stop() }
        record.release()
    }

    companion object {
        @SuppressLint("MissingPermission") // l'écoute n'est lancée qu'après l'autorisation du micro
        fun open(sampleRate: Int, bufferSamples: Int): PhoneMic? {
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, bufferSamples * 2 * 4),
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return null
            }
            return PhoneMic(r)
        }
    }
}
