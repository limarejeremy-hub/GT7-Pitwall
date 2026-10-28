package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.analysis.CornerPass
import com.gt7pitwall.core.session.LapRecord
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Comportement en virage (croisement n°3, issue #36) : le rapport « lacet mesuré / lacet attendu d'après l'angle des
 * roues » ([CornerPass.rotationRatio], format C) au milieu de chaque virage, début contre fin de relais. Une rotation qui
 * baisse dans la plupart des virages = sous-virage en hausse (l'avant décroche davantage) ; qui monte = survirage en
 * hausse. Constat sur l'équilibre de la voiture, pas sur l'usure, et jamais sur une seule sortie. Les passages sur
 * vibreur (plus de 20 % de la zone) ou hors piste sont écartés.
 */
object BalanceTrend {
    enum class Verdict(val label: String) {
        UNKNOWN("équilibre non comparable"),
        STABLE("équilibre stable en virage"),
        MORE_UNDERSTEER("sous-virage en hausse (l'avant décroche davantage)"),
        MORE_OVERSTEER("survirage en hausse (l'arrière décroche davantage)"),
    }

    data class Report(val verdict: Verdict, val corners: Int = 0, val shift: Double? = null, val reason: String? = null) {
        val text: String get() = verdict.label.replaceFirstChar { it.uppercase() } +
            (shift?.takeIf { verdict != Verdict.UNKNOWN }?.let { " : rotation ${if (it >= 0) "+" else "−"}${(abs(it) * 100).roundToInt()} % sur $corners virages" } ?: "") +
            (reason?.let { " ($it)" } ?: "") + "."
    }

    const val SHIFT = 0.08
    const val MAX_KERB = 0.2f

    fun analyze(passes: List<CornerPass>, stintStartLap: Int, excludedLaps: Set<Int> = emptySet()): Report {
        val usable = passes.filter {
            it.lap > stintStartLap && it.lap !in excludedLaps && it.rotationRatio != null && it.offTrack != true && (it.kerbShare ?: 0f) <= MAX_KERB
        }
        if (passes.none { it.rotationRatio != null }) return Report(Verdict.UNKNOWN, reason = "angle des roues absent : il faut le format C")
        val laps = usable.map { it.lap }.distinct().sorted()
        if (laps.size < 4) return Report(Verdict.UNKNOWN, reason = "${laps.size} tour(s) comparables dans ce relais, il en faut 4")
        val early = laps.take(2).toSet()
        val late = laps.takeLast(2).toSet()
        fun med(xs: List<Float>) = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2].toDouble() }
        val shifts = usable.groupBy { it.corner }.mapNotNull { (_, ps) ->
            val e = med(ps.filter { it.lap in early }.mapNotNull { it.rotationRatio }) ?: return@mapNotNull null
            val l = med(ps.filter { it.lap in late }.mapNotNull { it.rotationRatio }) ?: return@mapNotNull null
            l - e
        }
        if (shifts.size < 2) return Report(Verdict.UNKNOWN, shifts.size, reason = "moins de 2 virages comparables")
        val down = shifts.count { it <= -SHIFT }
        val up = shifts.count { it >= SHIFT }
        val mean = shifts.average()
        val verdict = when {
            down * 2 > shifts.size -> Verdict.MORE_UNDERSTEER
            up * 2 > shifts.size -> Verdict.MORE_OVERSTEER
            else -> Verdict.STABLE
        }
        return Report(verdict, shifts.size, mean)
    }
}

/**
 * Stress thermique (croisement n°4, issue #36) : pneu le plus chaud par rapport aux trois autres, son évolution dans le
 * relais, recoupés avec les blocages avant et les patinages arrière mesurés par tour. Une température haute n'est jamais
 * une usure : c'est l'indication de la roue la plus sollicitée. Le sens gauche/droite de la charge latérale n'est pas
 * vérifié en jeu : aucune conclusion n'en dépend.
 */
object ThermalStress {
    private val names = listOf("avant gauche", "avant droit", "arrière gauche", "arrière droit")

    data class Report(
        val wheel: String?,
        /** Écart du pneu le plus chaud à la moyenne des trois autres, fin de relais (°C). */
        val marginC: Double? = null,
        /** Évolution de cet écart depuis le début du relais (°C). */
        val marginTrendC: Double? = null,
        val frontLockTrend: Int? = null,
        val rearSpinTrend: Int? = null,
        val reason: String? = null,
    ) {
        val text: String get() = if (wheel == null) "Stress thermique non mesurable${reason?.let { " ($it)" } ?: ""}." else buildString {
            append("Pneu le plus sollicité : $wheel, ${marginC!!.roundToInt()} °C au-dessus des trois autres")
            marginTrendC?.let { append(", ${if (it >= 0) "+" else "−"}${abs(it).roundToInt()} °C depuis le début du relais") }
            if ((frontLockTrend ?: 0) > 0) append(" ; blocages avant en hausse (+$frontLockTrend)")
            if ((rearSpinTrend ?: 0) > 0) append(" ; patinages arrière en hausse (+$rearSpinTrend)")
            append(". Température élevée ≠ usure.")
        }
    }

    fun analyze(laps: List<LapRecord>, stintStartLap: Int, excludedLaps: Set<Int> = emptySet()): Report {
        val usable = laps.filter { it.lapNumber > stintStartLap && it.lapNumber !in excludedLaps && it.wheelTemps != null && !it.refuelled && !it.pitLane }
        if (usable.size < 3) return Report(null, reason = "${usable.size} tour(s) suivis en entier dans ce relais, il en faut 3")
        fun margins(l: LapRecord): DoubleArray {
            val t = l.wheelTemps!!.toList().map { it.toDouble() }
            return DoubleArray(4) { i -> t[i] - (t.sum() - t[i]) / 3 }
        }
        val late = usable.takeLast(2).map(::margins)
        val early = usable.take(2).map(::margins)
        val lateMean = DoubleArray(4) { i -> late.map { it[i] }.average() }
        val hottest = lateMean.indices.maxBy { lateMean[it] }
        if (lateMean[hottest] < MIN_MARGIN_C) return Report(null, reason = "écarts de température inférieurs à ${MIN_MARGIN_C.roundToInt()} °C : pneus équilibrés")
        val earlyMean = early.map { it[hottest] }.average()
        fun trend(sel: (LapRecord) -> Int?): Int? {
            val e = usable.take(2).mapNotNull(sel)
            val l = usable.takeLast(2).mapNotNull(sel)
            return if (e.size < 2 || l.size < 2) null else l.sum() - e.sum()
        }
        return Report(names[hottest], lateMean[hottest], lateMean[hottest] - earlyMean, trend { it.frontLockups }, trend { it.rearWheelspins })
    }

    const val MIN_MARGIN_C = 4.0
}
