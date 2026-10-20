package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.race.StrategyDialogue.ObservationKind
import com.gt7pitwall.core.session.LapRecord
import kotlin.math.abs
import kotlin.math.sqrt

/** État de l'adhérence sur le train de pneus en cours, du plus rassurant au plus grave. */
enum class GripLevel(val label: String) {
    INSUFFICIENT("données insuffisantes"),
    STABLE("comportement stable"),
    POSSIBLE("dégradation possible"),
    PERSISTENT("dégradation persistante"),
    SEVERE("perte d'adhérence importante"),
}

/** Où se situe le problème, d'après les mesures par essieu et par pneu. */
enum class GripArea(val label: String) {
    FRONT("à l'avant"),
    REAR("à l'arrière"),
    LEFT_RIGHT("déséquilibre gauche / droite"),
    TRACTION("patinage à l'accélération"),
    BRAKING("blocages au freinage"),
}

/** Relais pneus à analyser : début (départ ou dernier changement déclaré), tours à écarter, déclarations du pilote. */
data class StintContext(
    val startLap: Int = 1,
    /** Faux si un arrêt a eu lieu sans que le pilote dise s'il a changé les pneus. */
    val certain: Boolean = true,
    /** Tours non représentatifs : départ, tours d'arrêt et de sortie des stands, ravitaillement, incident déclaré. */
    val excludedLaps: Set<Int> = emptySet(),
    /** Observations du pilote encore valables (déclarées, jamais mesurées). */
    val declared: List<ObservationKind> = emptyList(),
    val raining: Boolean = false,
)

/**
 * Diagnostic structuré. [evidence] : mesures GT7 qui l'appuient ; [declared] : ce que le pilote a dit ;
 * [alternatives] : autres causes possibles, toujours citées dès qu'une dégradation est envisagée — l'usure n'est jamais
 * affirmée, GT7 ne la transmet pas. La température n'est jamais une preuve d'adhérence.
 */
data class GripReport(
    val level: GripLevel,
    val areas: List<GripArea> = emptyList(),
    /** Écart des deux derniers tours propres sur la référence du relais (ms), null si non mesurable. */
    val paceLossMs: Int? = null,
    /** Évolution des chronos par tour de relais (ms) et son incertitude, si elle est significative. */
    val slopeMsPerLap: Double? = null,
    val slopeSe: Double? = null,
    val cleanLaps: Int = 0,
    val evidence: List<String> = emptyList(),
    val declared: List<String> = emptyList(),
    val alternatives: List<String> = emptyList(),
    /** Tour lent isolé, plus probablement une erreur ou un incident qu'une dégradation. */
    val isolatedLap: Int? = null,
) {
    val degrading: Boolean get() = level >= GripLevel.POSSIBLE

    companion object {
        val NONE = GripReport(GripLevel.INSUFFICIENT)
    }
}

object GripDiagnosis {
    fun analyze(laps: List<LapRecord>, ctx: StintContext): GripReport {
        val declared = ctx.declared.distinct().map { it.label }
        if (ctx.raining) {
            return GripReport(GripLevel.INSUFFICIENT, declared = declared, alternatives = listOf("piste mouillée : comparaison suspendue"))
        }
        val clean = laps.filter {
            it.lapNumber >= ctx.startLap && it.lapNumber > 1 && it.lapNumber !in ctx.excludedLaps && !it.refuelled &&
                (it.timeMs ?: 0) > 0 && it.tcsShare != null && it.lockups != null && it.wheelspins != null
        }.sortedBy { it.lapNumber }
        if (clean.size < MIN_LAPS) {
            return GripReport(GripLevel.INSUFFICIENT, cleanLaps = clean.size, declared = declared)
        }
        val recent = clean.takeLast(2)
        val pool = clean.dropLast(2)
        val ref = pool.sortedBy { it.timeMs }.take(2)
        val refMs = ref.map { it.timeMs!! }.average()
        val deltas = clean.map { it.timeMs!! - refMs }
        val loss = recent.map { it.timeMs!! }.average() - refMs

        fun avg(l: List<LapRecord>, f: (LapRecord) -> Double) = l.map(f).average()
        val tcsRise = avg(recent) { (it.tcsShare ?: 0f).toDouble() } - avg(ref) { (it.tcsShare ?: 0f).toDouble() }
        val lockRise = avg(recent) { (it.lockups ?: 0).toDouble() } - avg(ref) { (it.lockups ?: 0).toDouble() }
        val spinRise = avg(recent) { (it.wheelspins ?: 0).toDouble() } - avg(ref) { (it.wheelspins ?: 0).toDouble() }
        val frontRise = avg(recent) { ((it.frontLockups ?: 0) + (it.frontWheelspins ?: 0)).toDouble() } -
            avg(ref) { ((it.frontLockups ?: 0) + (it.frontWheelspins ?: 0)).toDouble() }
        val rearRise = avg(recent) { ((it.rearLockups ?: 0) + (it.rearWheelspins ?: 0)).toDouble() } -
            avg(ref) { ((it.rearLockups ?: 0) + (it.rearWheelspins ?: 0)).toDouble() }
        val slipSignal = tcsRise >= TCS_RISE || lockRise >= EVENTS_RISE || spinRise >= EVENTS_RISE
        val declaredGrip = ctx.declared.any {
            it == ObservationKind.GRIP_FRONT || it == ObservationKind.GRIP_REAR || it == ObservationKind.TYRES_WORN
        }

        // Tour lent isolé : le dernier tour seul est lent, le précédent est revenu au niveau de la référence.
        val last = deltas.last()
        val beforeLast = deltas[deltas.size - 2]
        val isolated = if (last >= ISOLATED_MS && beforeLast < NEAR_REF_MS) clean.last().lapNumber else null

        val (slope, se) = regression(clean.map { (it.lapNumber - ctx.startLap).toDouble() to it.timeMs!!.toDouble() }, refMs)
        val significant = slope != null && se != null && slope >= 2 * se && slope >= MIN_SLOPE_MS
        val lastThree = deltas.takeLast(3)

        val level = when {
            isolated != null -> GripLevel.STABLE
            loss >= SEVERE_LOSS_MS && recent.all { it.timeMs!! - refMs >= 1_000 } && (tcsRise >= 0.05 || lockRise + spinRise >= 3) ->
                GripLevel.SEVERE
            clean.size >= 5 && lastThree.all { it >= PERSISTENT_LAP_MS } && loss >= PERSISTENT_LOSS_MS && significant &&
                (slipSignal || declaredGrip) -> GripLevel.PERSISTENT
            recent.all { it.timeMs!! - refMs >= POSSIBLE_LAP_MS } && loss >= POSSIBLE_LOSS_MS -> GripLevel.POSSIBLE
            declaredGrip && loss >= POSSIBLE_LAP_MS -> GripLevel.POSSIBLE
            else -> GripLevel.STABLE
        }

        val areas = buildList {
            if (frontRise >= AXLE_RISE && frontRise >= rearRise + 1) add(GripArea.FRONT)
            if (rearRise >= AXLE_RISE && rearRise >= frontRise + 1) add(GripArea.REAR)
            if (spinRise >= EVENTS_RISE || tcsRise >= TCS_RISE) add(GripArea.TRACTION)
            if (lockRise >= EVENTS_RISE) add(GripArea.BRAKING)
            val imbalance = { l: LapRecord -> l.wheelTemps?.let { w -> ((w.fl + w.rl) - (w.fr + w.rr)) / 2.0 } }
            val recentImb = recent.mapNotNull(imbalance).takeIf { it.size == recent.size }?.average()
            val refImb = ref.mapNotNull(imbalance).takeIf { it.isNotEmpty() }?.average() ?: 0.0
            if (recentImb != null && abs(recentImb) >= IMBALANCE_C && abs(recentImb) - abs(refImb) >= IMBALANCE_RISE_C) add(GripArea.LEFT_RIGHT)
        }

        val evidence = buildList {
            if (loss >= POSSIBLE_LAP_MS) add("chronos à +${seconds(loss)} de tes meilleurs tours avec ces pneus")
            if (significant) add("dégradation de ${seconds(slope!!)} par tour sur le relais")
            if (tcsRise >= TCS_RISE) add("antipatinage plus actif (+${Math.round(tcsRise * 100)} % du tour)")
            if (spinRise >= EVENTS_RISE) add("plus de patinages (+${Math.round(spinRise)} par tour)")
            if (lockRise >= EVENTS_RISE) add("plus de blocages (+${Math.round(lockRise)} par tour)")
            if (GripArea.FRONT in areas) add("glisse surtout à l'avant")
            if (GripArea.REAR in areas) add("glisse surtout à l'arrière")
            if (GripArea.LEFT_RIGHT in areas) add("écart de température gauche / droite")
        }

        val alternatives = if (level < GripLevel.POSSIBLE && isolated == null) emptyList() else buildList {
            if (isolated != null) add("tour $isolated isolé : erreur, trafic ou incident plus probable qu'une usure")
            if (ObservationKind.TRAFFIC in ctx.declared) add("trafic signalé")
            if (ObservationKind.DAMAGE in ctx.declared) add("voiture peut-être abîmée")
            val recentTemp = recent.mapNotNull { it.avgTyreTemp }.takeIf { it.isNotEmpty() }?.average()
            val refTemp = ref.mapNotNull { it.avgTyreTemp }.takeIf { it.isNotEmpty() }?.average()
            if (recentTemp != null && refTemp != null && recentTemp < refTemp - COLD_DROP_C) add("pneus plus froids qu'en début de relais")
            if (!ctx.certain) add("âge des pneus incertain (arrêt sans réponse sur les pneus)")
            add("trafic, erreurs de pilotage ou réglages")
        }

        return GripReport(
            level = level,
            areas = if (level >= GripLevel.POSSIBLE) areas else emptyList(),
            paceLossMs = loss.toInt(),
            slopeMsPerLap = slope?.takeIf { significant },
            slopeSe = se?.takeIf { significant },
            cleanLaps = clean.size,
            evidence = evidence,
            declared = declared,
            alternatives = alternatives,
            isolatedLap = isolated,
        )
    }

    /** Pente des chronos selon l'âge des pneus, tours aberrants (plus de 7 %) exclus. */
    private fun regression(points: List<Pair<Double, Double>>, typical: Double): Pair<Double?, Double?> {
        val pts = points.filter { abs(it.second - typical) <= typical * 0.07 }
        if (pts.size < 4) return null to null
        val mx = pts.map { it.first }.average()
        val my = pts.map { it.second }.average()
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx <= 0.0) return null to null
        val b = pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
        val res = pts.sumOf { val e = it.second - (my + b * (it.first - mx)); e * e } / (pts.size - 2)
        return b to sqrt(res / sxx)
    }

    fun seconds(ms: Double): String {
        val s = abs(ms) / 1000.0
        return if (s < 10) "${Math.round(s * 10) / 10.0} s".replace('.', ',') else "${Math.round(s)} s"
    }

    const val MIN_LAPS = 4
    const val ISOLATED_MS = 1_500.0
    const val NEAR_REF_MS = 400.0
    const val POSSIBLE_LAP_MS = 300.0
    const val POSSIBLE_LOSS_MS = 400.0
    const val PERSISTENT_LAP_MS = 400.0
    const val PERSISTENT_LOSS_MS = 500.0
    const val SEVERE_LOSS_MS = 1_500.0
    const val MIN_SLOPE_MS = 80.0
    const val TCS_RISE = 0.03
    const val EVENTS_RISE = 2.0
    const val AXLE_RISE = 1.5
    const val IMBALANCE_C = 8.0
    const val IMBALANCE_RISE_C = 4.0
    const val COLD_DROP_C = 8.0
}
