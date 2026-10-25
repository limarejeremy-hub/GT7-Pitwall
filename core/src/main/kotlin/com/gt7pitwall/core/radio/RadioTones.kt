package com.gt7pitwall.core.radio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Signaux sonores de la radio (aucune parole). */
enum class RadioTone {
    /** Bip court : le micro écoute vraiment, la conversation est ouverte. */
    READY,

    /** Double bip plus grave : conversation refermée, le mot de réveil redevient nécessaire. */
    CLOSED,

    /** Petit signal très discret : la réponse demande un peu plus de temps (jamais une phrase répétitive). */
    THINKING,
}

/**
 * Signaux en PCM 16 bits mono, générés par le code (aucun fichier) : les mêmes échantillons partent sur Discord (encodés
 * en Opus comme la voix, donc audibles dans le casque PS5) ou sur le haut-parleur du Fold. Volume modéré, attaque et
 * extinction douces pour éviter les clics.
 */
object RadioTones {
    data class Beep(val hz: Double, val ms: Int)

    /** Une note brève et aiguë. */
    val READY_PATTERN = listOf(Beep(1_175.0, 90))

    /** Deux notes plus graves, séparées d'un court silence. */
    val CLOSED_PATTERN = listOf(Beep(660.0, 70), Beep(0.0, 70), Beep(523.0, 90))

    /** Une note grave, brève et douce. */
    val THINKING_PATTERN = listOf(Beep(440.0, 60))

    const val AMPLITUDE = 0.22
    private const val FADE_MS = 8

    fun pattern(tone: RadioTone): List<Beep> = when (tone) {
        RadioTone.READY -> READY_PATTERN
        RadioTone.CLOSED -> CLOSED_PATTERN
        RadioTone.THINKING -> THINKING_PATTERN
    }

    fun pcm(tone: RadioTone, sampleRate: Int): ShortArray {
        val parts = pattern(tone).map { b -> beep(b, sampleRate) }
        val out = ShortArray(parts.sumOf { it.size })
        var i = 0
        for (p in parts) {
            p.copyInto(out, i)
            i += p.size
        }
        return out
    }

    fun durationMs(tone: RadioTone): Int = pattern(tone).sumOf { it.ms }

    private fun beep(b: Beep, rate: Int): ShortArray {
        val n = rate * b.ms / 1000
        if (b.hz <= 0.0) return ShortArray(n)
        val fade = rate * FADE_MS / 1000
        return ShortArray(n) { i ->
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (sin(2 * PI * b.hz * i / rate) * env * AMPLITUDE * Short.MAX_VALUE).roundToInt().toShort()
        }
    }
}

/** Joue un signal de la radio là où l'ingénieur parle (Discord ou Fold) et revient quand il a été diffusé. */
fun interface TonePlayer {
    suspend fun play(tone: RadioTone)
}
