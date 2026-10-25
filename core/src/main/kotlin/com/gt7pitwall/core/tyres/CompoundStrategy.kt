package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.engineer.FrenchFormat
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.Learned
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.StopKind
import com.gt7pitwall.core.strategy.StrategyOptions
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Performance apprise d'une gomme, dans les seules courses comparables (voiture, circuit, épreuve, multiplicateur d'usure).
 * [paceMs] : rythme typique des pneus jeunes (1 à 3 tours d'âge), ramené au carburant moyen quand l'effet du carburant est
 * séparable. [slopeMsPerLap] : perte par tour d'âge, corrigée du carburant si possible (sinon celle de [model], où
 * l'allègement de la voiture masque une partie de la dégradation). Durée compétitive et gain des pneus neufs : [model].
 */
data class CompoundPerformance(
    val compound: TyreCompound,
    val model: TyreModel,
    val paceMs: Learned?,
    val slopeMsPerLap: Learned?,
    val fuelCorrected: Boolean,
    val laps: Int,
    val stints: Int,
    val sessions: Int,
) {
    val confidence: Confidence
        get() = listOfNotNull(paceMs?.confidence, slopeMsPerLap?.confidence).minByOrNull { it.ordinal } ?: Confidence.INSUFFICIENT

    /** « mediums : 1:45.2 à pneus jeunes, +0,30 s par tour d'âge (corrigé du carburant), 6 relais sur 3 courses ». */
    fun summary(): String = buildString {
        append(compound.spoken).append(" : ")
        if (paceMs == null && slopeMsPerLap == null) {
            append("historique insuffisant ($stints relais exploitable${if (stints > 1) "s" else ""})")
            return@buildString
        }
        paceMs?.let { append(FrenchFormat.lapTimeSpeech(it.mean.roundToInt())).append(" à pneus jeunes") }
        slopeMsPerLap?.let {
            if (paceMs != null) append(", ")
            append(signed(it.mean)).append(" par tour d'âge")
            append(if (fuelCorrected) " (corrigé du carburant)" else " (effet du carburant non séparé)")
        }
        model.performantLaps?.takeIf { it.confidence != Confidence.INSUFFICIENT }?.let { append(", perte nette vers ${it.mean.roundToLong()} tours") }
        append(", $stints relais sur $sessions course${if (sessions > 1) "s" else ""}, ${confidence.label}")
    }

    companion object {
        /** « +0,25 s » : deux décimales, une perte par tour d'âge est souvent de quelques dixièmes. */
        fun signed(ms: Double): String = (if (ms >= 0) "+" else "−") + String.format(java.util.Locale.FRANCE, "%.2f s", abs(ms) / 1000.0)
    }
}

/** Effet du carburant sur le chrono (ms par unité GT7 à bord), séparé de l'âge des pneus grâce aux pleins sans changement. */
data class FuelEffect(val msPerUnit: Double, val standardError: Double, val laps: Int)

/** Ce que l'historique dit des gommes pour cette épreuve. */
data class CompoundBook(
    val compounds: Map<TyreCompound, CompoundPerformance>,
    /** null : effet du carburant non séparable de l'âge des pneus (aucun plein sans changement de pneus dans l'historique). */
    val fuelEffect: FuelEffect?,
    val sessionsExcluded: Int,
) {
    operator fun get(c: TyreCompound?): CompoundPerformance? = c?.let { compounds[it] }

    companion object {
        val EMPTY = CompoundBook(emptyMap(), null, 0)

        /**
         * Régression sur les tours propres : chrono = niveau du relais + pente de sa gomme × âge + k × carburant. Le
         * niveau de chaque relais absorbe tout ce qui lui est propre (piste, réglages) ; k n'est retenu que s'il est
         * séparable de l'âge (sinon la pente brute est gardée, signalée comme non corrigée).
         */
        fun learn(context: SessionContext, sessions: Collection<SessionRecord>, excludeId: String? = null): CompoundBook {
            val pool = TyreModel.pool(context, sessions, excludeId)
            val rows = pool.laps.filter { it.compound != null && it.lap.timeMs != null }
            val stints = rows.groupBy { it.session to it.stintStart }.filterValues { it.size >= TyreModel.MIN_STINT_LAPS }
            if (stints.isEmpty()) {
                return CompoundBook(emptyMap(), null, pool.excluded)
            }
            val compounds = stints.values.map { it.first().compound!! }.distinct()
            // Carburant au milieu du tour : à la fin du tour + la moitié de ce qu'il a consommé.
            fun fuelOf(r: StintLap): Double? = r.lap.fuelAfter?.let { it + (r.lap.fuelUsed ?: 0f) / 2f }?.toDouble()
            val withFuel = stints.values.filter { st -> st.all { fuelOf(it) != null } }
            val fit = if (withFuel.size >= 2) fit(withFuel, compounds, ::fuelOf) else null
            val k = fit?.fuel
            val meanFuel = withFuel.flatten().mapNotNull(::fuelOf).average().takeIf { !it.isNaN() } ?: 0.0

            val book = compounds.associateWith { c ->
                val own = stints.values.filter { it.first().compound == c }
                val model = TyreModel.learn(context, sessions, c, excludeId)
                // Rythme des pneus jeunes de chaque relais, ramené au carburant moyen si k est connu.
                val young = own.mapNotNull { st ->
                    val r = st.filter { it.age in 1..3 }
                    if (r.isEmpty()) return@mapNotNull null
                    val t = r.map { row ->
                        val corr = if (k != null) k.msPerUnit * ((fuelOf(row) ?: meanFuel) - meanFuel) else 0.0
                        row.lap.timeMs!! - corr
                    }.average()
                    st.first().session to t
                }
                val slope = fit?.slopes?.get(c)?.let { (m, se, n) ->
                    val ss = own.map { it.first().session }.distinct().size
                    Learned(m, se * sqrt(n.toDouble()), n, ss, TyreModel.confidence(own.size, ss, if (m != 0.0) se / abs(m) else 1.0))
                }?.takeIf { k != null }
                CompoundPerformance(
                    compound = c,
                    model = model,
                    paceMs = learned(young, own.size),
                    slopeMsPerLap = slope ?: model.slopeMsPerLap,
                    fuelCorrected = slope != null,
                    laps = own.sumOf { it.size },
                    stints = own.size,
                    sessions = own.map { it.first().session }.distinct().size,
                )
            }
            return CompoundBook(book, k, pool.excluded)
        }

        private fun learned(values: List<Pair<String, Double>>, stints: Int): Learned? {
            if (values.size < 2) return null
            val xs = values.map { it.second }
            val mean = xs.average()
            val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1))
            val sessions = values.map { it.first }.distinct().size
            return Learned(mean, sd, xs.size, sessions, TyreModel.confidence(stints, sessions, if (mean != 0.0) sd / abs(mean) else 1.0))
        }

        private class Fit(val slopes: Map<TyreCompound, Triple<Double, Double, Int>>, val fuel: FuelEffect?)

        /** Moindres carrés à effets fixes par relais ; [fuel] null si le carburant est presque colinéaire à l'âge. */
        private fun fit(
            stints: List<List<StintLap>>,
            compounds: List<TyreCompound>,
            fuelOf: (StintLap) -> Double?,
        ): Fit? {
            // Effets fixes : on retire la moyenne de chaque relais (âge, carburant, chrono).
            data class P(val c: Int, val age: Double, val fuel: Double, val t: Double)
            val pts = stints.flatMap { st ->
                val ma = st.map { it.age.toDouble() }.average()
                val mf = st.map { fuelOf(it)!! }.average()
                val mt = st.map { it.lap.timeMs!!.toDouble() }.average()
                st.map { P(compounds.indexOf(it.compound), it.age - ma, fuelOf(it)!! - mf, it.lap.timeMs!! - mt) }
            }
            val nc = compounds.size
            fun design(p: P, withFuel: Boolean) = DoubleArray(nc + if (withFuel) 1 else 0) { j ->
                when {
                    j < nc -> if (p.c == j) p.age else 0.0
                    else -> p.fuel
                }
            }
            // Le carburant est-il séparable de l'âge ? R² du carburant expliqué par les âges (par gomme).
            val fuelOnAge = solve(pts.map { design(it, false) }, pts.map { it.fuel })
            val sst = pts.sumOf { it.fuel * it.fuel }
            val separable = fuelOnAge != null && sst > 0.0 && run {
                val res = pts.sumOf { p -> val x = design(p, false); val e = p.fuel - x.indices.sumOf { x[it] * fuelOnAge.beta[it] }; e * e }
                1.0 - res / sst < MAX_COLLINEARITY_R2
            }
            val withFuel = separable
            val r = solve(pts.map { design(it, withFuel) }, pts.map { it.t }) ?: return null
            val dof = pts.size - stints.size - r.beta.size
            if (dof < 2) return null
            val sigma2 = r.rss / dof
            val slopes = compounds.indices.associate { j ->
                val n = pts.count { it.c == j }
                compounds[j] to Triple(r.beta[j], sqrt(sigma2 * r.inverseDiag[j]), n)
            }.filterValues { it.third >= TyreModel.MIN_STINT_LAPS }
            val fuel = if (withFuel) FuelEffect(r.beta[nc], sqrt(sigma2 * r.inverseDiag[nc]), pts.size) else null
            return Fit(slopes, fuel)
        }

        private class Solution(val beta: DoubleArray, val inverseDiag: DoubleArray, val rss: Double)

        /** Équations normales (XᵀX) β = Xᵀy par élimination de Gauss ; null si la matrice est singulière. */
        private fun solve(x: List<DoubleArray>, y: List<Double>): Solution? {
            if (x.isEmpty()) return null
            val m = x.first().size
            val a = Array(m) { i -> DoubleArray(2 * m) { j -> if (j < m) x.indices.sumOf { r -> x[r][i] * x[r][j] } else if (j - m == i) 1.0 else 0.0 } }
            val b = DoubleArray(m) { i -> x.indices.sumOf { r -> x[r][i] * y[r] } }
            for (col in 0 until m) {
                val piv = (col until m).maxByOrNull { abs(a[it][col]) }!!
                if (abs(a[piv][col]) < 1e-9) return null
                if (piv != col) {
                    val t = a[piv]; a[piv] = a[col]; a[col] = t
                    val tb = b[piv]; b[piv] = b[col]; b[col] = tb
                }
                val d = a[col][col]
                for (j in 0 until 2 * m) a[col][j] /= d
                b[col] /= d
                for (r in 0 until m) if (r != col) {
                    val f = a[r][col]
                    if (f != 0.0) {
                        for (j in 0 until 2 * m) a[r][j] -= f * a[col][j]
                        b[r] -= f * b[col]
                    }
                }
            }
            val rss = x.indices.sumOf { r -> val e = y[r] - (0 until m).sumOf { x[r][it] * b[it] }; e * e }
            return Solution(b, DoubleArray(m) { a[it][m + it] }, rss)
        }

        /** Au-delà, l'âge et le carburant évoluent ensemble : leurs effets ne sont pas séparables. */
        const val MAX_COLLINEARITY_R2 = 0.9
    }
}

/** Scénarios pneus comparés jusqu'à l'arrivée. */
enum class TyreScenarioKind(val label: String) {
    CONTINUE("continuer avec ces pneus"),
    SOFT("passer en tendres"),
    MEDIUM("passer en mediums"),
    HARD("passer en durs"),
}

/** Un scénario : gomme, meilleur tour d'arrêt, temps estimé jusqu'à l'arrivée (ms, arrêt compris) et son incertitude. */
data class TyreScenario(
    val kind: TyreScenarioKind,
    val compound: TyreCompound?,
    val stopLap: Int?,
    val estimatedMs: Long?,
    val uncertaintyMs: Long?,
    val feasible: Boolean,
    val issues: List<String> = emptyList(),
) {
    val estimable: Boolean get() = feasible && estimatedMs != null
}

/** Comparaison des scénarios pneus ; [recommended] null : pas de conclusion possible, [reason] dit pourquoi. */
data class TyreScenarioReport(
    val scenarios: List<TyreScenario>,
    val recommended: TyreScenario?,
    val reason: String,
    val basis: List<String>,
    /** Changer de pneus bat nettement « continuer », même si les gommes neuves restent indiscernables entre elles. */
    val changeAdvised: Boolean = recommended != null && recommended.kind != TyreScenarioKind.CONTINUE,
) {
    fun of(kind: TyreScenarioKind): TyreScenario? = scenarios.firstOrNull { it.kind == kind }
}

/**
 * Compare « continuer », « tendres », « mediums » et « durs » sur le temps total jusqu'à l'arrivée, arrêt compris. Un
 * changement monté à un arrêt déjà prévu (essence, arrêt obligatoire) ne coûte que le changement de pneus ; sinon il
 * coûte un arrêt pour les pneus seuls. L'essence est la même dans tous les scénarios : elle ne change pas l'écart.
 * Aucune recommandation sans données suffisantes pour chaque gomme comparée, ni quand l'écart est sous l'incertitude.
 */
object TyreScenarios {
    data class Input(
        val currentLap: Int,
        val totalLaps: Int,
        val current: TyreCompound?,
        val tyreAge: Int,
        val ageCertain: Boolean,
        /** Chronos propres du relais en cours (âge, ms), carburant non corrigé. */
        val liveLaps: List<Pair<Int, Int>>,
        val book: CompoundBook,
        val costs: StopCosts?,
        /** Arrêts déjà prévus (fin de tour) : y changer les pneus ne coûte que le changement lui-même. */
        val plannedStops: List<Int>,
        val allowed: Set<TyreCompound>,
        val requiredMissing: Set<TyreCompound>,
        /** Durée de vie des pneus donnée par le pilote, si elle l'a été. */
        val tyreLife: Int?,
    )

    fun compare(i: Input): TyreScenarioReport {
        val left = i.totalLaps - i.currentLap + 1 // tours restants, celui en cours compris
        val basis = mutableListOf<String>()
        fun none(reason: String) = TyreScenarioReport(emptyList(), null, reason, basis)
        if (left <= 1) return none("Dernier tour : plus rien à comparer.")
        val cur = i.current ?: return none("Gomme actuelle inconnue : je ne compare pas les gommes sans elle.")
        if (!i.ageCertain) return none("Âge des pneus incertain (arrêt sans réponse sur les pneus) : je ne compare pas les gommes.")
        val family = cur.family
        val targets = mapOf(
            TyreScenarioKind.SOFT to TyreCompound.Hardness.SOFT,
            TyreScenarioKind.MEDIUM to TyreCompound.Hardness.MEDIUM,
            TyreScenarioKind.HARD to TyreCompound.Hardness.HARD,
        ).mapValues { (_, h) -> TyreCompound.of(family, h) }

        val curPerf = i.book[cur]
        val curSlope = curPerf?.slopeMsPerLap?.takeIf { it.confidence != Confidence.INSUFFICIENT }
        // Niveau des pneus actuels à l'âge zéro : mesuré dans ce relais (pente apprise retirée), sinon appris.
        val liveBase = curSlope?.let { s -> i.liveLaps.takeIf { it.size >= 2 }?.map { (age, t) -> t - s.mean * age }?.average() }
        val curPace = curPerf?.paceMs?.takeIf { it.confidence != Confidence.INSUFFICIENT }
        val base = liveBase ?: curPace?.mean
        curPerf?.let { basis += it.summary() } ?: basis.add("${cur.spoken} : historique insuffisant")
        i.book.fuelEffect?.let { basis += "effet du carburant : ${it.msPerUnit.roundToInt()} ms par unité à bord, séparé de l'âge des pneus" }
            ?: basis.add("effet du carburant non séparé de l'âge des pneus (aucun plein sans changement dans l'historique)")
        if (i.allowed.isEmpty()) basis += "gommes autorisées inconnues : à vérifier dans le règlement"

        fun drive(from: Int, to: Int, startAge: Int, slope: Double) = (from..to).sumOf { l -> slope * (startAge + l - from) }

        val scenarios = mutableListOf<TyreScenario>()
        // Continuer : les pneus actuels jusqu'à l'arrivée.
        run {
            val issues = mutableListOf<String>()
            if (i.requiredMissing.isNotEmpty()) issues += "le règlement impose encore les ${i.requiredMissing.joinToString(" et ") { it.spoken }}"
            i.tyreLife?.let { if (i.tyreAge + left > it) issues += "tes pneus tiennent $it tours d'après toi, il en faudrait ${i.tyreAge + left}" }
            val est = if (base != null && curSlope != null) base * left + drive(i.currentLap, i.totalLaps, i.tyreAge, curSlope.mean) else null
            val unc = curSlope?.let { s -> se(s) * (i.currentLap..i.totalLaps).sumOf { (i.tyreAge + it - i.currentLap).toDouble() } }
            scenarios += TyreScenario(
                TyreScenarioKind.CONTINUE, cur, null, est?.roundToLong(), unc?.roundToLong(), issues.isEmpty(),
                issues + if (est == null) listOf("dégradation des ${cur.spoken} inconnue (historique insuffisant)") else emptyList(),
            )
        }
        for ((kind, c) in targets) {
            if (c == null) continue
            val issues = mutableListOf<String>()
            if (i.allowed.isNotEmpty() && c !in i.allowed) issues += "${c.spoken} non autorisés par le règlement"
            if (i.requiredMissing.isNotEmpty() && c !in i.requiredMissing) issues += "le règlement impose encore les ${i.requiredMissing.joinToString(" et ") { it.spoken }}"
            val perf = i.book[c]
            val pace = perf?.paceMs?.takeIf { it.confidence != Confidence.INSUFFICIENT }
            val slope = perf?.slopeMsPerLap?.takeIf { it.confidence != Confidence.INSUFFICIENT }
            val offset = if (c == cur) 0.0 else if (pace != null && curPace != null) pace.mean - curPace.mean else null
            if (c != cur) perf?.let { basis += it.summary() } ?: basis.add("${c.spoken} : historique insuffisant")
            if (base == null || curSlope == null || slope == null || offset == null) {
                scenarios += TyreScenario(kind, c, null, null, null, issues.isEmpty(), issues + "historique insuffisant pour les ${c.spoken}")
                continue
            }
            // Meilleur tour d'arrêt : fin du tour s, de ce tour à l'avant-dernier.
            var best: Pair<Int, Double>? = null
            for (s in i.currentLap until i.totalLaps) {
                val after = i.totalLaps - s
                // Durée de vie donnée par le pilote : ni les pneus actuels ni les neufs ne doivent la dépasser.
                if (i.tyreLife != null && (i.tyreAge + s - i.currentLap + 1 > i.tyreLife || after > i.tyreLife)) continue
                val stop = (if (s in i.plannedStops) i.costs?.tyreService?.ms else i.costs?.cost(StopKind.TYRES_ONLY)?.ms) ?: continue
                val t = base * left + drive(i.currentLap, s, i.tyreAge, curSlope.mean) + offset * after + drive(s + 1, i.totalLaps, 0, slope.mean) + stop
                if (best == null || t < best.second) best = s to t
            }
            if (best == null) {
                scenarios += TyreScenario(kind, c, null, null, null, false, issues + "coût d'un arrêt inconnu ou durée de vie trop courte")
                continue
            }
            val (s, t) = best
            val after = i.totalLaps - s
            val stopSpread = (if (s in i.plannedStops) i.costs?.tyreService?.spread else i.costs?.cost(StopKind.TYRES_ONLY)?.spread) ?: 0.0
            val paceSe = if (c == cur) 0.0 else sqrt(se(pace!!).let { it * it } + se(curPace!!).let { it * it })
            val unc = sqrt(
                square(paceSe * after) + square(se(slope) * (0 until after).sum()) + square(MODEL_MS_PER_LAP * after) +
                    square(se(curSlope) * (0..(s - i.currentLap)).sumOf { i.tyreAge + it }) + square(stopSpread),
            )
            scenarios += TyreScenario(kind, c, s, t.roundToLong(), unc.roundToLong(), issues.isEmpty(), issues)
        }

        val ok = scenarios.filter { it.estimable }.sortedBy { it.estimatedMs }
        val missing = scenarios.filter { it.feasible && !it.estimable }
        val reason: String
        var recommended: TyreScenario? = null
        when {
            ok.isEmpty() -> reason = "Historique insuffisant : je ne peux pas comparer les gommes sur cette épreuve."
            ok.size == 1 -> reason = "Un seul scénario chiffrable (${ok.first().kind.label}) : rien à comparer" +
                (missing.takeIf { it.isNotEmpty() }?.let { m -> ", historique insuffisant pour " + m.joinToString(", ") { it.kind.label } } ?: "") + "."
            else -> {
                val (a, b) = ok[0] to ok[1]
                val gap = b.estimatedMs!! - a.estimatedMs!!
                val unc = sqrt(square((a.uncertaintyMs ?: 0).toDouble()) + square((b.uncertaintyMs ?: 0).toDouble()))
                if (gap > 2 * unc && gap >= MIN_GAP_MS) {
                    recommended = a
                    reason = "${a.kind.label.replaceFirstChar { it.uppercase() }}" + (a.stopLap?.let { " (arrêt fin du tour $it)" } ?: "") +
                        " : environ ${StrategyOptions.seconds(gap)} de mieux que ${b.kind.label}, à ± ${StrategyOptions.seconds(unc.roundToLong())}."
                } else {
                    reason = "Écart entre ${a.kind.label} et ${b.kind.label} (${StrategyOptions.seconds(gap)}) inférieur à l'incertitude : " +
                        "aucun scénario n'est démontré meilleur."
                }
            }
        }
        // Gommes neuves indiscernables entre elles, mais toutes nettement meilleures que continuer : on le dit.
        val cont = ok.firstOrNull { it.kind == TyreScenarioKind.CONTINUE }
        val bestChange = ok.firstOrNull { it.kind != TyreScenarioKind.CONTINUE }
        if (recommended == null && cont != null && bestChange != null && bestChange.estimatedMs!! < cont.estimatedMs!!) {
            val gap = cont.estimatedMs - bestChange.estimatedMs
            val unc = sqrt(square((cont.uncertaintyMs ?: 0).toDouble()) + square((bestChange.uncertaintyMs ?: 0).toDouble()))
            if (gap > 2 * unc && gap >= MIN_GAP_MS) {
                return TyreScenarioReport(
                    scenarios, null,
                    "Changer de pneus" + (bestChange.stopLap?.let { " (fin du tour $it)" } ?: "") + " : environ ${StrategyOptions.seconds(gap)} de mieux que " +
                        "continuer, à ± ${StrategyOptions.seconds(unc.roundToLong())}. $reason",
                    basis.distinct(), changeAdvised = true,
                )
            }
        }
        return TyreScenarioReport(scenarios, recommended, reason, basis.distinct())
    }

    /** Erreur standard d'une moyenne apprise. */
    private fun se(l: Learned): Double = l.spread / sqrt(l.samples.coerceAtLeast(1).toDouble())

    private fun square(x: Double) = x * x

    /**
     * Erreur de modèle par tour roulé sur la nouvelle gomme (trafic, dégradation non linéaire, piste qui évolue) : ces
     * effets ne sont pas mesurés, on les compte comme une incertitude plutôt que de les ignorer.
     */
    const val MODEL_MS_PER_LAP = 150.0

    /** En dessous, un gain n'a pas de sens à la radio. */
    const val MIN_GAP_MS = 1_000L
}
