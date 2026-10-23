package com.gt7pitwall.core.radio

import kotlin.math.sqrt

/**
 * Fin de phrase dans un flux audio fourni à la reconnaissance Google (micro du casque PS5). Avec un flux, Google lit
 * jusqu'à la fin du flux au lieu de détecter lui-même le silence : sans fin, il attend jusqu'au délai et ne rend rien.
 * On ferme donc le flux quand la voix a été entendue puis suivie de [endSilenceMs] de silence, ou si aucune voix
 * n'arrive en [noSpeechMs].
 *
 * Échantillons 16 bits à [sampleRate] ; le temps est compté en échantillons reçus (le flux arrive en temps réel).
 */
class SpeechEndpointer(
    private val sampleRate: Int = 16_000,
    private val speechRms: Double = 400.0,
    private val endSilenceMs: Long = 1_200,
    private val noSpeechMs: Long = 7_000,
    /** Phrase très longue ou bruit continu : on rend la main à Google avant son délai (12 s). */
    private val maxMs: Long = 10_000,
) {
    enum class End(val label: String) {
        AFTER_SPEECH("fin de phrase détectée"),
        NO_SPEECH("aucune voix dans le flux"),
        TOO_LONG("phrase trop longue"),
    }

    private var totalSamples = 0L
    private var lastSpeechSample = -1L

    var heardSpeech = false
        private set

    var end: End? = null
        private set

    /** Bloc reçu ; renvoie la fin détectée (une seule fois), sinon null. */
    fun accept(samples: ShortArray, n: Int): End? {
        if (end != null || n <= 0) return null
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
        totalSamples += n
        if (sqrt(sum / n) >= speechRms) {
            heardSpeech = true
            lastSpeechSample = totalSamples
        }
        val nowMs = totalSamples * 1000 / sampleRate
        end = when {
            heardSpeech && (totalSamples - lastSpeechSample) * 1000 / sampleRate >= endSilenceMs -> End.AFTER_SPEECH
            !heardSpeech && nowMs >= noSpeechMs -> End.NO_SPEECH
            nowMs >= maxMs -> End.TOO_LONG
            else -> null
        }
        return end
    }
}
