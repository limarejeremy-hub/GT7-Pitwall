package com.gt7pitwall.core.strategy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Les trois stratégies comparées. */
enum class OptionKind(val label: String) {
    NORMAL("stratégie normale"),
    EARLY("arrêt anticipé"),
    EXTENDED("relais prolongé"),
}

/** Une stratégie : le plan de [RacePlanner], sa faisabilité, et son temps estimé quand c'est possible. */
data class StrategyOption(
    val kind: OptionKind,
    /** Tours d'arrêt de cette stratégie (vide : aller au bout sans s'arrêter). */
    val stopLaps: List<Int>,
    val plan: RacePlan?,
    val feasible: Boolean,
    /** Pourquoi elle est irréalisable ou risquée. */
    val issues: List<String> = emptyList(),
    /** Temps restant estimé (ms), null si les données ne le permettent pas. */
    val estimatedMs: Long? = null,
    /** Écart avec la stratégie normale (ms, négatif = plus rapide). */
    val deltaMs: Long? = null,
)

/**
 * Comparaison des stratégies, structurée pour l'écran et la radio : la recommandation, pourquoi, et sur quoi elle
 * repose. [indistinguishable] : les écarts sont inférieurs à l'incertitude, aucune n'est démontrée meilleure.
 */
data class StrategyReport(
    val options: List<StrategyOption>,
    val recommended: OptionKind,
    val reason: String,
    val indistinguishable: Boolean,
    /** Incertitude sur l'écart de temps (ms), null si le temps n'est pas estimable. */
    val uncertaintyMs: Long?,
    /** Ce sur quoi reposent les chiffres (mesures, historique, base GT7…). */
    val basis: List<String>,
    /** Obligations du règlement encore inconnues : la stratégie n'est pas validée réglementairement. */
    val unverifiedRules: List<String>,
) {
    val recommendedOption: StrategyOption get() = options.first { it.kind == recommended }
    fun option(kind: OptionKind): StrategyOption? = options.firstOrNull { it.kind == kind }
}

/**
 * Compare la stratégie normale, l'arrêt anticipé et le relais prolongé, en réutilisant [RacePlanner] (les variantes
 * ne sont que le même plan avec le premier arrêt avancé ou retardé d'un tour : RacePlanner vérifie qu'il reste tenable).
 *
 * Temps estimé = rythme (mesuré ou appris) sur les tours restants + évolution des chronos au fil d'un relais si elle a
 * été mesurée + perte par arrêt (apprise, sinon voie des stands de la base GT7). Aucun avantage d'undercut ou
 * d'overcut : GT7 ne transmet rien sur les adversaires. Si la perte aux stands est inconnue et que les stratégies n'ont
 * pas le même nombre d'arrêts, aucun classement en secondes n'est donné.
 */
object StrategyOptions {
    data class Pace(
        /** Rythme de référence (ms) et son incertitude. */
        val lapMs: Double?,
        val lapSpreadMs: Double = 0.0,
        /** Évolution des chronos par tour de relais (ms), mesurée ; null : aucune supposée. */
        val slopeMsPerLap: Double? = null,
        val slopeSpread: Double = 0.0,
        /** Perte par arrêt (ms) et son incertitude ; null : inconnue. */
        val pitLossMs: Double? = null,
        val pitLossSpread: Double = 0.0,
        val pitLossFromLaneOnly: Boolean = false,
    )

    fun compare(
        input: RacePlanner.Input,
        pace: Pace,
        basis: List<String>,
        unverifiedRules: List<String>,
    ): StrategyReport? {
        val normal = RacePlanner.plan(input) ?: return null
        val normalStops = normal.stops.map { it.endOfLap }
        val options = mutableListOf(option(OptionKind.NORMAL, normal, normalStops, emptyList()))
        val first = normalStops.firstOrNull()
        if (first != null) {
            options += variant(input, normalStops, OptionKind.EARLY, first - 1)
            options += variant(input, normalStops, OptionKind.EXTENDED, first + 1)
        }
        val currentLap = max(input.currentLap, 1)
        val lapsLeft = normal.totalLaps - currentLap + 1
        val timed = options.map { o -> o.copy(estimatedMs = if (o.feasible) estimate(o.stopLaps, currentLap, lapsLeft, input.tyreAgeLaps, pace) else null) }
        val base = timed.first().estimatedMs
        val withDelta = timed.map { o -> o.copy(deltaMs = if (base != null && o.estimatedMs != null) o.estimatedMs - base else null) }

        // Incertitude de l'écart : perte aux stands (si le nombre d'arrêts diffère) et pente des chronos (si utilisée).
        val uncertainty = if (pace.lapMs == null) null else {
            val pit = pace.pitLossSpread
            val slope = (pace.slopeSpread) * lapsLeft
            sqrt(pit * pit + slope * slope + (pace.lapSpreadMs * 2) * (pace.lapSpreadMs * 2)).roundToInt().toLong().coerceAtLeast(MIN_UNCERTAINTY_MS)
        }

        val feasible = withDelta.filter { it.feasible }
        val differentStopCounts = feasible.map { it.stopLaps.size }.distinct().size > 1
        val comparable = uncertainty != null && feasible.all { it.estimatedMs != null } && !(differentStopCounts && pace.pitLossMs == null)
        val best = if (comparable) feasible.minByOrNull { it.estimatedMs!! } else null
        val gain = best?.deltaMs?.let { -it } ?: 0
        val indistinguishable = best == null || best.kind == OptionKind.NORMAL || gain <= (uncertainty ?: Long.MAX_VALUE)
        val recommended = if (indistinguishable) OptionKind.NORMAL else best!!.kind

        val reason = reason(withDelta, recommended, indistinguishable, comparable, uncertainty, gain, normalStops.isEmpty())
        return StrategyReport(withDelta, recommended, reason, indistinguishable, uncertainty, basis, unverifiedRules)
    }

    private fun option(kind: OptionKind, plan: RacePlan, stops: List<Int>, issues: List<String>) =
        StrategyOption(kind, stops, plan, feasible = plan.warnings.none { it in BLOCKING }, issues = issues + plan.warnings.filter { it in BLOCKING }.map(::warningText))

    /** Même plan, premier arrêt déplacé : tenable seulement si RacePlanner le garde (carburant, pneus, obligations). */
    private fun variant(input: RacePlanner.Input, normalStops: List<Int>, kind: OptionKind, newFirst: Int): StrategyOption {
        val lapIndex = max(input.currentLap, 1)
        val wanted = listOf(newFirst) + normalStops.drop(1)
        val why = mutableListOf<String>()
        if (newFirst < lapIndex) why += "l'arrêt ne peut plus être avancé : le tour est déjà commencé"
        val total = RacePlanner.plan(input)?.totalLaps ?: 0
        if (newFirst >= total) why += "pas d'arrêt possible à la fin du dernier tour"
        if (wanted.zipWithNext().any { (a, b) -> b <= a }) why += "il chevaucherait l'arrêt suivant"
        if (why.isNotEmpty()) return StrategyOption(kind, wanted, null, feasible = false, issues = why)
        val plan = RacePlanner.plan(input.copy(previousStops = wanted))
        val stops = plan?.stops?.map { it.endOfLap } ?: emptyList()
        if (plan == null || stops != wanted) {
            return StrategyOption(kind, wanted, plan, feasible = false, issues = listOf(infeasibleReason(input, wanted, kind)))
        }
        return option(kind, plan, stops, emptyList())
    }

    /** Pourquoi RacePlanner refuse ces tours d'arrêt. */
    private fun infeasibleReason(i: RacePlanner.Input, stops: List<Int>, kind: OptionKind): String {
        val perLap = i.perLap
        val lapIndex = max(i.currentLap, 1)
        val r = if (i.currentLap >= 1) i.currentLapRemaining.coerceIn(0f, 1f) else 1f
        if (perLap != null && perLap > 0f) {
            val firstStint = stops.first() - lapIndex + 1
            if ((r + firstStint - 1 + RacePlanner.SAFETY_LAPS) * perLap > i.fuelNow) return "autonomie insuffisante pour aller jusqu'au tour ${stops.first()}"
            val total = (i.gameTotalLaps.takeIf { it > 0 } ?: i.rules.totalLaps ?: 0)
            val bounds = stops + total
            val longest = bounds.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0
            if (longest > i.tank / perLap - RacePlanner.SAFETY_LAPS) return "le relais suivant dépasserait un plein"
        }
        i.rules.tyreStintLaps?.let { life ->
            if (stops.first() - lapIndex + 1 > (life - i.tyreAgeLaps).coerceAtLeast(1)) return "les pneus ne tiendraient pas jusque-là (durée que tu m'as donnée)"
        }
        return if (kind == OptionKind.EARLY) "arrêt anticipé incompatible avec le plan" else "relais prolongé incompatible avec le plan"
    }

    /**
     * Temps restant : rythme × tours, plus la dérive mesurée des chronos selon l'âge des pneus dans chaque relais, plus la
     * perte par arrêt. null si le rythme est inconnu.
     */
    fun estimate(stopLaps: List<Int>, currentLap: Int, lapsLeft: Int, tyreAge: Int, pace: Pace): Long? {
        val lap = pace.lapMs ?: return null
        var total = lap * lapsLeft
        val slope = pace.slopeMsPerLap
        if (slope != null) {
            var age = tyreAge
            for (l in currentLap until currentLap + lapsLeft) {
                total += slope * age
                age = if (l in stopLaps) 0 else age + 1
            }
        }
        total += (pace.pitLossMs ?: 0.0) * stopLaps.size
        return total.roundToInt().toLong()
    }

    private fun reason(
        options: List<StrategyOption>,
        recommended: OptionKind,
        indistinguishable: Boolean,
        comparable: Boolean,
        uncertainty: Long?,
        gain: Long,
        noStop: Boolean,
    ): String {
        val normal = options.first()
        if (!normal.feasible) return "Le plan normal pose problème : ${normal.issues.joinToString(", ")}."
        if (noStop) return "Aucun arrêt nécessaire : on reste en piste jusqu'à l'arrivée."
        val others = options.drop(1).filter { !it.feasible }.map { "${it.kind.label} : ${it.issues.first()}" }
        val othersText = if (others.isEmpty()) "" else " ${others.joinToString(" ; ").replaceFirstChar { it.uppercase() }}."
        return when {
            !comparable -> "On garde la stratégie normale : les données ne permettent pas de chiffrer un gain ($HOW).$othersText"
            indistinguishable -> "On garde la stratégie normale : les autres options ne font pas gagner plus que notre incertitude " +
                "(environ ${seconds(uncertainty ?: 0)}).$othersText"
            else -> "Je recommande le ${options.first { it.kind == recommended }.kind.label} : gain estimé ${seconds(gain)}, " +
                "au-delà de notre incertitude (${seconds(uncertainty ?: 0)}).$othersText"
        }
    }

    private const val HOW = "rythme ou perte aux stands inconnus"
    private const val MIN_UNCERTAINTY_MS = 500L

    private val BLOCKING = setOf(PlanWarning.CANNOT_REACH_PITS, PlanWarning.FUEL_IMPOSSIBLE, PlanWarning.MANDATORY_STOPS_IMPOSSIBLE, PlanWarning.TYRES_IMPOSSIBLE)

    private fun warningText(w: PlanWarning): String = when (w) {
        PlanWarning.CANNOT_REACH_PITS -> "carburant insuffisant pour finir le tour"
        PlanWarning.FUEL_IMPOSSIBLE -> "un relais dépasse un plein"
        PlanWarning.MANDATORY_STOPS_IMPOSSIBLE -> "plus assez de tours pour les arrêts obligatoires"
        PlanWarning.TYRES_IMPOSSIBLE -> "plus assez d'arrêts pour les gommes imposées"
        PlanWarning.TYRES_OVERDUE -> "pneus au-delà de leur durée"
    }

    fun seconds(ms: Long): String {
        val s = abs(ms) / 1000.0
        return if (s < 10) "${(s * 10).roundToInt() / 10.0} s".replace('.', ',') else "${s.roundToInt()} s"
    }
}
