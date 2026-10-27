package com.gt7pitwall.core.telemetry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Validation sur PS5 du champ expérimental 0x154 ([TelemetryPacket.engineTorqueNorm]) et des autres champs ignorés
 * jusqu'ici. Elle ne fait qu'observer : rien n'est annoncé ni utilisé par la stratégie. Elle vérifie, sur la voiture du
 * pilote, les affirmations de la communauté (gt7-datalogger #150) :
 * - corrélation forte avec l'accélérateur, roues en prise (attendu ≈ +0,87 à +0,90) ;
 * - exactement 0 au point mort ou embrayage débrayé ;
 * - négatif en frein moteur (accélérateur relâché, en prise, en roulant) ;
 * - plage non bornée à ±1.
 * Coût : quelques additions par paquet, aucune mémoire qui grandit.
 */
class TorqueProbe {
    private var packets = 0L
    private var withTorque = 0L
    private var min = Float.MAX_VALUE
    private var max = -Float.MAX_VALUE
    // Corrélation accélérateur × couple, en prise et en roulant.
    private var n = 0L
    private var sx = 0.0; private var sy = 0.0; private var sxx = 0.0; private var syy = 0.0; private var sxy = 0.0
    private var neutral = 0L; private var neutralZero = 0L
    private var overrun = 0L; private var overrunNegative = 0L
    private var wheelTorqueNonZero = 0L
    private var roadPlane = 0L
    private var nonFinite = 0L

    @Synchronized
    fun onPacket(p: TelemetryPacket) {
        if (p.isPaused || p.isLoading) return
        packets++
        if (p.wheelTorque?.toList()?.any { it != 0f } == true) wheelTorqueNonZero++
        if (p.roadPlane != null) roadPlane++
        if (p.format.size >= PacketFormat.TILDE.size && p.engineTorqueNorm == null) nonFinite++
        val t = p.engineTorqueNorm ?: return
        withTorque++
        if (t < min) min = t
        if (t > max) max = t
        val rolling = p.speedMps > 5f
        val inGear = p.currentGear > 0 && p.clutchPedal < 0.1f
        if (p.currentGear == 0 || p.clutchPedal > 0.9f) {
            neutral++
            if (t == 0f) neutralZero++
        }
        if (rolling && inGear) {
            val x = p.throttle / 255.0
            n++; sx += x; sy += t; sxx += x * x; syy += t.toDouble() * t; sxy += x * t
            if (p.throttle == 0 && p.brake == 0) {
                overrun++
                if (t < 0f) overrunNegative++
            }
        }
    }

    /** Corrélation de Pearson accélérateur × 0x154, en prise et en roulant ; null sous 600 paquets (10 s). */
    @Synchronized
    fun throttleCorrelation(): Double? {
        if (n < MIN_SAMPLES) return null
        val cov = sxy / n - (sx / n) * (sy / n)
        val vx = sxx / n - (sx / n) * (sx / n)
        val vy = syy / n - (sy / n) * (sy / n)
        if (vx <= 0 || vy <= 0) return null
        return cov / sqrt(vx * vy)
    }

    @Synchronized
    fun report(): List<String> {
        if (packets == 0L) return listOf("Aucun paquet en piste.")
        if (withTorque == 0L) return listOf("0x154 absent : format ${if (nonFinite > 0) "~/C avec valeurs non finies" else "A ou B"} (il faut le format ~ ou C).")
        fun pct(a: Long, b: Long) = if (b == 0L) "—" else "${a * 100 / b} %"
        val out = mutableListOf<String>()
        out += "0x154 (couple moteur normalisé ?) : $withTorque paquets, de ${"%.2f".format(min)} à ${"%.2f".format(max)}" +
            (if (abs(max) > 1.05f || abs(min) > 1.05f) " (dépasse ±1)" else "") + "."
        out += "Corrélation avec l'accélérateur, en prise : " + (throttleCorrelation()?.let { "%+.2f".format(it) } ?: "pas assez de mesures") +
            " (communauté : +0,87 à +0,90)."
        out += "Point mort ou débrayé : ${pct(neutralZero, neutral)} à zéro sur $neutral paquets (attendu : 100 %)."
        out += "Frein moteur (pédales relâchées, en prise) : ${pct(overrunNegative, overrun)} négatifs sur $overrun paquets (communauté : 99,4 %)."
        out += "Couple par roue (0x140) non nul : $wheelTorqueNonZero paquets ; plan de la route (0x94) présent : ${pct(roadPlane, packets)}."
        if (nonFinite > 0) out += "Valeurs non finies écartées : $nonFinite."
        return out
    }

    @Synchronized
    fun reset() {
        packets = 0; withTorque = 0; min = Float.MAX_VALUE; max = -Float.MAX_VALUE
        n = 0; sx = 0.0; sy = 0.0; sxx = 0.0; syy = 0.0; sxy = 0.0
        neutral = 0; neutralZero = 0; overrun = 0; overrunNegative = 0; wheelTorqueNonZero = 0; roadPlane = 0; nonFinite = 0
    }

    companion object {
        const val MIN_SAMPLES = 600L
    }
}
