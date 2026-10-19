package com.gt7pitwall.core.engineer

import kotlin.math.abs
import kotlin.math.roundToInt

/** Mise en forme des nombres et des temps, pour l'écran et pour la synthèse vocale française. */
object FrenchFormat {
    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )

    /** « 19 octobre » (le 1er : « 1er octobre »). */
    fun dayMonth(d: java.time.LocalDate): String =
        (if (d.dayOfMonth == 1) "1er" else d.dayOfMonth.toString()) + " " + MONTHS[d.monthValue - 1]

    /** "1:32.456" pour l'affichage. */
    fun lapTime(ms: Int?): String {
        if (ms == null || ms <= 0) return "--:--.---"
        val minutes = ms / 60_000
        val seconds = (ms / 1000) % 60
        val millis = ms % 1000
        return "%d:%02d.%03d".format(minutes, seconds, millis)
    }

    /** "+0.412" / "-1.030" pour l'affichage d'un écart. */
    fun delta(ms: Int): String {
        val sign = if (ms < 0) "-" else "+"
        val a = abs(ms)
        return "%s%d.%03d".format(sign, a / 1000, a % 1000)
    }

    /** "1 minute 32 secondes 4" (arrondi au dixième) pour la voix. */
    fun lapTimeSpeech(ms: Int): String {
        val tenthsTotal = (ms / 100.0).roundToInt()
        val minutes = tenthsTotal / 600
        val seconds = (tenthsTotal / 10) % 60
        val tenths = tenthsTotal % 10
        val sb = StringBuilder()
        if (minutes > 0) sb.append(minutes).append(if (minutes > 1) " minutes " else " minute ")
        sb.append(seconds).append(if (seconds > 1) " secondes" else " seconde")
        sb.append(' ').append(tenths)
        return sb.toString()
    }

    /**
     * Écart pour la voix (valeur absolue) : "2 centièmes" / "41 centièmes" sous la seconde,
     * puis "1,2 seconde" / "3,7 secondes".
     */
    fun deltaSpeech(ms: Int): String {
        val a = abs(ms)
        if (a < 995) {
            val hundredths = (a / 10.0).roundToInt()
            return when (hundredths) {
                0 -> "moins d'un centième"
                1 -> "1 centième"
                else -> "$hundredths centièmes"
            }
        }
        val tenths = (a / 100.0).roundToInt()
        val value = decimal(tenths / 10f)
        return if (tenths >= 20) "$value secondes" else "$value seconde"
    }

    /** Nombre à une décimale avec virgule française : "3,5". */
    fun decimal(v: Float): String {
        val r = (v * 10f).roundToInt()
        val whole = abs(r) / 10
        val dec = abs(r) % 10
        val sign = if (r < 0) "-" else ""
        return if (dec == 0) "$sign$whole" else "$sign$whole,$dec"
    }

    /** "3,5 tours" / "1 tour" / "0,8 tour". */
    fun laps(v: Float): String {
        val text = decimal(v)
        return if (abs(v) >= 2f) "$text tours" else "$text tour"
    }
}
