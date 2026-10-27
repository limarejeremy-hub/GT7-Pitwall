package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.analysis.LapSample
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Efficacité de freinage à pression comparable (croisement n°2, issue #36). Même méthode que [TractionMonitor], côté
 * frein : avant la vitesse minimale de chaque virage, les paquets en freinage (frein ≥ 15 %, accélérateur relâché) sont
 * rangés par conditions comparables — même virage, tranche de pression de 15 %, tranche de vitesse de 10 km/h et de
 * charge latérale de 3 m/s², quatre roues sur l'asphalte — et on compare la **décélération obtenue** et le **blocage
 * avant** (glissement < 1) entre le début du relais et ses deux derniers tours.
 *
 * Distinction : « freiné trop tard » est une question d'**endroit** (CornerCoach) ; ici, à pression et vitesse égales,
 * une voiture qui ralentit moins ou bloque plus a **moins d'adhérence au freinage**. Ce n'est jamais une usure des freins
 * (GT7 ne transmet pas leur température), ni une preuve d'usure des pneus : un constat mesuré, avec ses limites.
 * Aucune annonce radio tant que ce n'est pas validé sur PS5 : Analyse et journal seulement.
 */
object BrakingMonitor {
    enum class Level(val label: String) {
        INSUFFICIENT("comparaison du freinage impossible pour l'instant"),
        STABLE("freinage stable à pression égale"),
        DEGRADATION_SUSPECTED("freinage moins efficace à pression égale"),
    }

    data class Key(val corner: Int, val pressureBin: Int, val speedBin: Int, val lateralBin: Int)

    private class Agg {
        var n = 0
        var decel = 0.0
        var frontSlip = 0.0
        var temp = 0.0
        fun add(s: LapSample) {
            n++
            decel += -s.longAccel!!
            frontSlip += s.slips!!.let { (it.fl + it.fr) / 2.0 }
            temp += s.tyreTemps?.toList()?.average() ?: 0.0
        }
        fun merge(o: Agg) { n += o.n; decel += o.decel; frontSlip += o.frontSlip; temp += o.temp }
        val meanDecel get() = decel / n
        val meanFront get() = frontSlip / n
        val meanTemp get() = temp / n
    }

    data class Report(
        val level: Level,
        val reasons: List<String>,
        val comparedCorners: Int = 0,
        val flaggedCorners: List<Int> = emptyList(),
        /** Variation moyenne : décélération (m/s², négatif = ralentit moins) et glissement avant (négatif = bloque plus). */
        val decelDelta: Double? = null,
        val frontSlipDelta: Double? = null,
        val surfaceKnown: Boolean = false,
    ) {
        fun text(label: (Int) -> String = { "V$it" }): String = buildString {
            append(level.label.replaceFirstChar { it.uppercase() })
            if (flaggedCorners.isNotEmpty()) append(" avant ${flaggedCorners.joinToString(", ") { label(it) }}")
            if (comparedCorners > 0) append(" ($comparedCorners virages comparés)")
            append(".")
            if (decelDelta != null && frontSlipDelta != null && level != Level.INSUFFICIENT) {
                append(" À pression égale : décélération ${signed(decelDelta, 2)} m/s², glissement avant ${signed(frontSlipDelta * 100, 1)} points.")
            }
            if (reasons.isNotEmpty()) append(" ${reasons.joinToString(" ; ").replaceFirstChar { it.uppercase() }}.")
            if (!surfaceKnown && level != Level.INSUFFICIENT) append(" Surface des roues inconnue : vibreurs non exclus.")
        }

        private fun signed(v: Double, d: Int) = (if (v >= 0) "+" else "−") + String.format(java.util.Locale.FRANCE, "%.${d}f", abs(v))
    }

    /** Fenêtres de freinage d'un tour : pour chaque virage, les paquets entre le début de sa zone et sa vitesse minimale. */
    fun profile(entries: Map<Int, List<LapSample>>): Map<Key, Pair<Int, DoubleArray>> {
        val w = HashMap<Key, Agg>()
        for ((corner, samples) in entries) for (s in samples) {
            if (s.brake < BRAKE_MIN || s.throttle > THROTTLE_OFF || s.slips == null || s.longAccel == null || s.speedMps < MIN_SPEED_MPS) continue
            if (abs(s.longAccel) > MAX_ACCEL || s.asm) continue
            val sf = s.surfaces
            if (sf != null && !sf.allTarmac) continue
            val key = Key(corner, (s.brake * 100 / 255 / 15).coerceIn(1, 6), (s.speedMps * 3.6f / 10f).toInt(), (abs(s.yawRate * s.speedMps) / 3f).toInt())
            w.getOrPut(key) { Agg() }.add(s)
        }
        return w.mapValues { (_, a) -> a.n to doubleArrayOf(a.decel, a.frontSlip, a.temp) }
    }

    /** Un tour : fenêtres et indicateur « surface inconnue ». */
    class Lap(val lap: Int, val windows: Map<Key, Pair<Int, DoubleArray>>, val surfaceKnown: Boolean)

    fun lap(lap: Int, entries: Map<Int, List<LapSample>>): Lap =
        Lap(lap, profile(entries), entries.values.flatten().none { it.surfaces == null })

    private fun agg(v: Pair<Int, DoubleArray>) = Agg().also { it.n = v.first; it.decel = v.second[0]; it.frontSlip = v.second[1]; it.temp = v.second[2] }

    /** Début du relais (deux premiers tours après le premier) contre ses deux derniers tours. */
    fun compare(laps: List<Lap>, stintStartLap: Int, excludedLaps: Set<Int>, raining: Boolean = false): Report {
        if (raining) return Report(Level.INSUFFICIENT, listOf("pluie déclarée : comparaison suspendue"))
        val usable = laps.filter { it.lap > stintStartLap && it.lap !in excludedLaps }.sortedBy { it.lap }
        if (usable.size < MIN_LAPS) return Report(Level.INSUFFICIENT, listOf("${usable.size} tour(s) propre(s) dans ce relais, il en faut $MIN_LAPS"))
        val ref = HashMap<Key, Agg>()
        usable.take(2).forEach { l -> l.windows.forEach { (k, v) -> ref.getOrPut(k) { Agg() }.merge(agg(v)) } }
        val recent = usable.takeLast(2)
        val surfaceKnown = usable.all { it.surfaceKnown }
        val tRef = ref.values.sumOf { it.temp } / ref.values.sumOf { it.n }.coerceAtLeast(1)
        val tRec = recent.flatMap { it.windows.values }.let { v -> v.sumOf { it.second[2] } / v.sumOf { it.first }.coerceAtLeast(1) }
        if (tRef > 0 && tRec > 0 && tRec < tRef - TractionMonitor.COLD_DROP_C) {
            return Report(Level.INSUFFICIENT, listOf("pneus ${(tRef - tRec).roundToInt()} °C plus froids qu'en début de relais : comparaison suspendue"))
        }
        data class Cmp(val corner: Int, val loss: Boolean, val dDecel: Double, val dFront: Double)
        val perLap = recent.map { l ->
            l.windows.mapNotNull { (k, v) ->
                val r = ref[k] ?: return@mapNotNull null
                val a = agg(v)
                if (a.n < MIN_N || r.n < MIN_N) return@mapNotNull null
                val dDecel = a.meanDecel - r.meanDecel
                val dFront = a.meanFront - r.meanFront
                Cmp(k.corner, dDecel <= -DECEL_DROP || (dFront <= -LOCK_RISE && dDecel <= 0), dDecel, dFront)
            }
        }
        val compared = perLap.map { c -> c.map { it.corner }.toSet() }.reduce { a, b -> a intersect b }
        if (compared.size < MIN_CORNERS) {
            return Report(Level.INSUFFICIENT, listOf("${compared.size} virage(s) avec des freinages comparables (même pression, vitesse), il en faut $MIN_CORNERS"),
                comparedCorners = compared.size, surfaceKnown = surfaceKnown)
        }
        fun flagged(c: List<Cmp>) = c.groupBy { it.corner }.filter { (_, l) -> l.count { it.loss } * 2 >= l.size && l.any { it.loss } }.keys
        val flags = perLap.map { flagged(it) }.reduce { a, b -> a intersect b }.intersect(compared).sorted()
        val all = perLap.flatten().filter { it.corner in compared }
        val level = if (flags.size >= MIN_CORNERS && flags.size * 3 >= compared.size) Level.DEGRADATION_SUSPECTED else Level.STABLE
        return Report(level, emptyList(), compared.size, flags, all.map { it.dDecel }.average(), all.map { it.dFront }.average(), surfaceKnown)
    }

    const val BRAKE_MIN = 38 // 15 %
    const val THROTTLE_OFF = 13 // 5 %
    const val MIN_SPEED_MPS = 8f
    const val MAX_ACCEL = 40f
    const val MIN_N = 6
    const val MIN_LAPS = 4
    const val MIN_CORNERS = 2
    /** Seuils de départ, à recalibrer sur des traces PS5 réelles. */
    const val DECEL_DROP = 0.5
    const val LOCK_RISE = 0.04
}
