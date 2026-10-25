package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.race.Comparability
import com.gt7pitwall.core.race.Compatibility
import com.gt7pitwall.core.race.HistoryRelation
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.HistoryModel
import com.gt7pitwall.core.strategy.Learned
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreService
import kotlin.math.abs
import kotlin.math.sqrt

/** Un tour propre d'un relais passé : session, début du relais, gomme déclarée (null : inconnue), âge des pneus. */
class StintLap(val session: String, val stintStart: Int, val compound: TyreCompound?, val age: Int, val lap: LapEntry)

/** Tours propres des sessions comparables ; [excluded] : sessions incompatibles écartées ; [gains] : gain des pneus neufs par gomme. */
class StintPool(val laps: List<StintLap>, val related: Set<String>, val excluded: Int, val gains: List<Triple<String, TyreCompound?, Double>>)

/**
 * Ce que Pitwall apprend des relais pneus passés, indépendamment du carburant. Seuls comptent les relais comparables :
 * même épreuve et même type de session (un entraînement seulement s'il reproduit exactement l'épreuve), même voiture,
 * circuit et multiplicateur d'usure, et même gomme si elle est déclarée. Un relais dont l'âge des pneus est incertain
 * (arrêt sans réponse sur les pneus) est écarté. Tout ici est une estimation tirée de mesures passées : jamais une
 * mesure de la course en cours, jamais un pourcentage d'usure.
 */
data class TyreModel(
    /** Évolution des chronos par tour d'âge des pneus (ms), un échantillon par relais. */
    val slopeMsPerLap: Learned?,
    /** Tours avant une perte nette (+0,7 s sur le début du relais, deux tours de suite), sur les relais où elle est apparue. */
    val performantLaps: Learned?,
    /** Relais assez longs restés sans perte nette (la durée de vie n'y est pas connue, seulement « au moins »). */
    val stintsWithoutLoss: Int,
    /** Gain des pneus neufs : trois tours avant le changement contre trois tours après (ms). */
    val freshGainMs: Learned?,
    /** Glisse qui augmente surtout à l'avant ou à l'arrière au fil des relais, si c'est net. */
    val axle: GripArea?,
    val stints: Int,
    val sessions: Int,
    val sessionsExcluded: Int,
    /** Relais d'entraînement ou de type non précisé retenus (confiance limitée à « provisoire »). */
    val relatedUsed: Int,
    /** Gomme des relais retenus ; null : gomme non déclarée (confiance limitée). */
    val compound: TyreCompound?,
) {
    /** Explication courte de la base : « 4 relais, 3 courses, provisoire ». */
    fun basis(): String = "$stints relais sur $sessions course${if (sessions > 1) "s" else ""}" +
        (compound?.let { ", ${it.spoken}" } ?: ", gomme non déclarée") +
        if (relatedUsed > 0) ", dont $relatedUsed entraînement(s)" else ""

    companion object {
        val EMPTY = TyreModel(null, null, 0, null, null, 0, 0, 0, 0, null)

        private val INCIDENT = Regex("(?i)accident|accroch|t[eê]te.?[aà].?queue|sortie de piste|touch[eé]|crash|p[eé]nalit")

        private class Stint(val session: String, val laps: List<Pair<Int, LapEntry>>)

        /**
         * Sessions comparables (même épreuve, voiture, circuit, multiplicateurs) et leurs tours propres : ni premier tour,
         * ni ravitaillement, ni tour d'entrée ou de sortie des stands, ni sortie de piste mesurée, ni incident déclaré, ni
         * chrono à plus de 7 % de la médiane (trafic, erreur). Seuls les tours dont l'âge des pneus est certain comptent.
         */
        fun pool(context: SessionContext, sessions: Collection<SessionRecord>, excludeId: String? = null): StintPool {
            val pool = sessions.filter { it.id != excludeId }
            val same = pool.filter { HistoryRelation.of(context.profile.id, it.context.profile.id) == HistoryRelation.SAME }
            val related = pool.filter { HistoryRelation.of(context.profile.id, it.context.profile.id) == HistoryRelation.RELATED }
            val usable = same.filter { Compatibility.compare(context, it.context).first != Comparability.INCOMPATIBLE } +
                related.filter { Compatibility.compare(context, it.context).first == Comparability.SAME_EVENT }
            val excluded = same.size + related.size - usable.size
            val rows = mutableListOf<StintLap>()
            val gains = mutableListOf<Triple<String, TyreCompound?, Double>>()
            for (s in usable) {
                val stopLaps = s.stops.map { it.lap }.toSet()
                val incidents = s.notes.filter { INCIDENT.containsMatchIn(it.text) }.map { it.lap }.toSet()
                val typical = s.laps.mapNotNull { it.timeMs?.toDouble() }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
                fun clean(l: LapEntry) = l.lap > 1 && !l.refuelled && !l.pitLane && (l.offTrackEvents ?: 0) == 0 &&
                    l.lap !in stopLaps && (l.lap - 1) !in stopLaps &&
                    l.lap !in incidents && (l.timeMs ?: 0) > 0 && typical != null && abs(l.timeMs!! - typical) <= typical * 0.07
                fun compoundAt(l: LapEntry) = l.tyreCompound ?: if (s.tyres.none { it.lap <= l.lap }) s.startTyre else s.tyres.last { it.lap <= l.lap }.compound
                for (l in s.laps) {
                    if (!clean(l)) continue
                    val age = HistoryModel.tyreAge(s, l) ?: continue
                    rows += StintLap(s.id, l.lap - age, compoundAt(l), age, l)
                }
                // Gain des pneus neufs à chaque changement déclaré : trois tours avant contre trois tours après.
                for (stop in s.stops.filter { it.tyres == TyreService.CHANGED }) {
                    val before = s.laps.filter { it.lap < stop.lap && clean(it) }.takeLast(3).mapNotNull { it.timeMs }
                    val afterLaps = s.laps.filter { it.lap > stop.lap + 1 && clean(it) }.take(3)
                    val after = afterLaps.mapNotNull { it.timeMs }
                    if (before.size == 3 && after.size == 3) gains += Triple(s.id, afterLaps.firstOrNull()?.let(::compoundAt), before.average() - after.average())
                }
            }
            return StintPool(rows, related.map { it.id }.toSet(), excluded, gains)
        }

        fun learn(context: SessionContext, sessions: Collection<SessionRecord>, compound: TyreCompound?, excludeId: String? = null): TyreModel {
            val p = pool(context, sessions, excludeId)
            if (p.laps.isEmpty() && p.gains.isEmpty()) return EMPTY.copy(sessionsExcluded = p.excluded, compound = compound)
            val relatedIds = p.related
            val excluded = p.excluded
            // Gomme déclarée : seuls les relais de la même gomme ; non déclarée : tous, confiance limitée.
            val stints = p.laps.filter { compound == null || it.compound == compound }
                .groupBy { it.session to it.stintStart }.values
                .filter { it.size >= MIN_STINT_LAPS }
                .map { rows -> Stint(rows.first().session, rows.map { it.age to it.lap }) }
            val gains = p.gains.filter { compound == null || it.second == compound }.map { it.first to it.third }
            val usedSessions = stints.map { it.session }.distinct()
            val related2 = usedSessions.count { it in relatedIds }
            val cap = related2 > 0 || compound == null

            val slopes = stints.mapNotNull { st -> slope(st.laps.map { it.first.toDouble() to it.second.timeMs!!.toDouble() })?.let { st.session to it } }
            val lives = mutableListOf<Pair<String, Double>>()
            var withoutLoss = 0
            for (st in stints) {
                val ref = st.laps.take(4).map { it.second.timeMs!! }.sorted().take(2).average()
                val ordered = st.laps.sortedBy { it.first }
                val hit = ordered.zipWithNext().firstOrNull { (a, b) -> a.second.timeMs!! - ref >= LOSS_MS && b.second.timeMs!! - ref >= LOSS_MS }
                if (hit != null) lives += st.session to hit.first.first.toDouble() else if (ordered.size >= 6) withoutLoss++
            }
            val axle = axle(stints)
            return TyreModel(
                slopeMsPerLap = learned(slopes, cap),
                performantLaps = learned(lives, cap),
                stintsWithoutLoss = withoutLoss,
                freshGainMs = learned(gains, cap),
                axle = axle,
                stints = stints.size,
                sessions = usedSessions.size,
                sessionsExcluded = excluded,
                relatedUsed = related2,
                compound = compound,
            )
        }

        /** Confiance d'un apprentissage par relais : insuffisant sous 2 relais, relativement fiable à partir de 4 relais sur 3 courses peu dispersés. */
        fun confidence(samples: Int, sessions: Int, relativeSpread: Double): Confidence = when {
            samples < 2 -> Confidence.INSUFFICIENT
            samples >= 4 && sessions >= 3 && relativeSpread <= 0.35 -> Confidence.FAIRLY_RELIABLE
            else -> Confidence.PROVISIONAL
        }

        private fun learned(values: List<Pair<String, Double>>, cap: Boolean): Learned? {
            if (values.isEmpty()) return null
            val xs = values.map { it.second }
            val mean = xs.average()
            val sd = if (xs.size > 1) sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)) else abs(mean)
            val sessions = values.map { it.first }.distinct().size
            var c = confidence(xs.size, sessions, if (mean != 0.0) sd / abs(mean) else 1.0)
            if (cap && c == Confidence.FAIRLY_RELIABLE) c = Confidence.PROVISIONAL
            return Learned(mean, sd, xs.size, sessions, c)
        }

        private fun slope(points: List<Pair<Double, Double>>): Double? {
            if (points.size < MIN_STINT_LAPS) return null
            val mx = points.map { it.first }.average()
            val my = points.map { it.second }.average()
            val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
            if (sxx <= 0.0) return null
            return points.sumOf { (it.first - mx) * (it.second - my) } / sxx
        }

        /** Essieu dont la glisse augmente le plus entre la première et la seconde moitié des relais. */
        private fun axle(stints: List<Stint>): GripArea? {
            var front = 0.0
            var rear = 0.0
            var n = 0
            for (st in stints) {
                val ordered = st.laps.sortedBy { it.first }.map { it.second }
                val half = ordered.size / 2
                val a = ordered.take(half)
                val b = ordered.drop(half)
                val fa = a.mapNotNull { it.frontEvents }
                val fb = b.mapNotNull { it.frontEvents }
                val ra = a.mapNotNull { it.rearEvents }
                val rb = b.mapNotNull { it.rearEvents }
                if (fa.isEmpty() || fb.isEmpty() || ra.isEmpty() || rb.isEmpty()) continue
                front += fb.average() - fa.average()
                rear += rb.average() - ra.average()
                n++
            }
            if (n < 2) return null
            return when {
                front / n >= 1.0 && front >= rear * 2 -> GripArea.FRONT
                rear / n >= 1.0 && rear >= front * 2 -> GripArea.REAR
                else -> null
            }
        }

        const val MIN_STINT_LAPS = 4
        const val LOSS_MS = 700.0
    }
}
