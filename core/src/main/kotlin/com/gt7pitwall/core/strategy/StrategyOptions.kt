package com.gt7pitwall.core.strategy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Les stratégies comparées : carburant (normale, anticipée, prolongée) et pneus. */
enum class OptionKind(val label: String) {
    NORMAL("stratégie normale"),
    EARLY("arrêt anticipé"),
    EXTENDED("relais prolongé"),
    /** Changer les pneus à la fin du tour en cours. */
    TYRES_NOW("changement de pneus à la fin de ce tour"),
    /** Changer les pneus à la fin du tour suivant. */
    TYRES_NEXT("changement de pneus au prochain tour"),
    /** Changer les pneus au tour qui fait gagner le plus de temps (plus tard dans la course). */
    TYRES_PLANNED("changement de pneus au meilleur tour"),
    /** Changer les pneus au ravitaillement déjà prévu. */
    TYRES_WITH_FUEL("changement de pneus au ravitaillement"),
    /** Garder les pneus actuels jusqu'à l'arrivée (sans l'arrêt pneus retenu). */
    STAY_OUT("rester en piste avec ces pneus"),
}

/** Ce que la comparaison doit savoir des pneus, en plus du rythme. */
data class TyreInputs(
    /** Signes de dégradation (diagnostic d'adhérence ou chronos qui se dégradent nettement). */
    val degrading: Boolean = false,
    /** Temps ajouté par un changement de pneus (ms), appris ; null : inconnu, une marge prudente est comptée. */
    val serviceMs: Double? = null,
)

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
) {
    /** Arrêts où les pneus sont changés (le relais pneus repart de zéro), indépendamment du carburant. */
    val tyreLaps: List<Int> get() = plan?.stops?.filter { it.changeTyres }?.map { it.endOfLap } ?: emptyList()

    /** Arrêt pour les pneus seuls que cette stratégie ajoute. */
    val tyresOnlyStop: PlannedStop? get() = plan?.stops?.firstOrNull { it.tyresOnly && it.forTyres }
}

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
        tyres: TyreInputs = TyreInputs(),
    ): StrategyReport? {
        val normal = RacePlanner.plan(input) ?: return null
        val normalStops = normal.stops.map { it.endOfLap }
        val options = mutableListOf(option(OptionKind.NORMAL, normal, normalStops, emptyList()))
        // Variantes carburant : le premier arrêt hors arrêt pneus seuls, avancé ou retardé d'un tour.
        val fuelStops = normal.stops.filterNot { it.tyresOnly && it.forTyres }.map { it.endOfLap }
        val first = fuelStops.firstOrNull()
        val fuelInput = input.copy(previousStops = fuelStops)
        if (first != null) {
            options += variant(fuelInput, fuelStops, OptionKind.EARLY, first - 1)
            options += variant(fuelInput, fuelStops, OptionKind.EXTENDED, first + 1)
        }
        val currentLap = max(input.currentLap, 1)
        val lapsLeft = normal.totalLaps - currentLap + 1
        // Variantes pneus : seulement si la dégradation des chronos est connue (mesurée ou apprise).
        val slope = pace.slopeMsPerLap
        if (slope != null && slope > 0) {
            fun tyreOption(kind: OptionKind, laps: List<Int>): StrategyOption? {
                val p = RacePlanner.plan(input.copy(previousStops = fuelStops, tyreStopLaps = laps)) ?: return null
                return option(kind, p, p.stops.map { it.endOfLap }, emptyList())
            }
            val total = normal.totalLaps
            if (currentLap < total) tyreOption(OptionKind.TYRES_NOW, listOf(currentLap))?.let { options += it }
            if (currentLap + 1 < total) tyreOption(OptionKind.TYRES_NEXT, listOf(currentLap + 1))?.let { options += it }
            normal.stops.firstOrNull { it.forFuel && !it.changeTyres }?.let { tyreOption(OptionKind.TYRES_WITH_FUEL, listOf(it.endOfLap))?.let { o -> options += o } }
            // Le meilleur tour pour un arrêt pneus plus tard : on ne rentre pas « maintenant » si attendre rapporte davantage.
            ((currentLap + 2) until total).mapNotNull { l -> tyreOption(OptionKind.TYRES_PLANNED, listOf(l)) }
                .filter { it.feasible }
                .minByOrNull { estimate(it.stopLaps, currentLap, lapsLeft, input.tyreAgeLaps, pace, it.tyreLaps) ?: Long.MAX_VALUE }
                ?.let { options += it }
            if (input.tyreStopLaps.isNotEmpty()) tyreOption(OptionKind.STAY_OUT, emptyList())?.let { options += it }
        }
        // Une même suite d'arrêts et de changements de pneus n'est comparée qu'une fois.
        val distinct = options.distinctBy { it.stopLaps to it.tyreLaps }
        options.clear()
        options += distinct
        val serviceMs = tyres.serviceMs ?: TYRE_SERVICE_MARGIN_MS
        val timed = options.map { o ->
            val est = if (o.feasible) estimate(o.stopLaps, currentLap, lapsLeft, input.tyreAgeLaps, pace, o.tyreLaps) else null
            // Pneus changés à un arrêt qui aurait eu lieu de toute façon : le temps du changement s'ajoute.
            val extraChanges = o.tyreLaps.count { it in normal.stops.map { s -> s.endOfLap } && it !in options.first().tyreLaps }
            o.copy(estimatedMs = est?.let { it + (serviceMs * extraChanges).toLong() })
        }
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
        // Un arrêt de plus (ou un changement de pneus de plus) n'est retenu que si son bénéfice dépasse nettement son coût
        // et l'incertitude : des pneus qui semblent fatigués ne suffisent pas.
        fun threshold(o: StrategyOption): Long {
            val u = uncertainty ?: Long.MAX_VALUE
            val extraStop = o.stopLaps.size > normal.stops.size || o.tyreLaps.size > options.first().tyreLaps.size
            return if (extraStop) maxOf(2 * u, MIN_EXTRA_STOP_GAIN_MS) else u
        }
        val best = if (comparable) feasible.filter { o -> o.kind == OptionKind.NORMAL || -(o.deltaMs ?: 0) > threshold(o) }.minByOrNull { it.estimatedMs!! } else null
        val gain = best?.deltaMs?.let { -it } ?: 0
        val indistinguishable = best == null || best.kind == OptionKind.NORMAL
        val recommended = if (indistinguishable) OptionKind.NORMAL else best!!.kind

        val tyreOptions = feasible.any { it.kind in TYRE_KINDS }
        val reason = reason(withDelta, recommended, indistinguishable, comparable, uncertainty, gain, normalStops.isEmpty(), tyres.degrading && tyreOptions, normal)
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
    fun estimate(stopLaps: List<Int>, currentLap: Int, lapsLeft: Int, tyreAge: Int, pace: Pace, tyreLaps: List<Int> = stopLaps): Long? {
        val lap = pace.lapMs ?: return null
        var total = lap * lapsLeft
        val slope = pace.slopeMsPerLap
        if (slope != null) {
            // Les pneus ne repartent de zéro qu'aux arrêts où ils sont changés : un ravitaillement seul n'y change rien.
            var age = tyreAge
            for (l in currentLap until currentLap + lapsLeft) {
                total += slope * age
                age = if (l in tyreLaps) 0 else age + 1
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
        tyreDoubt: Boolean,
        normalPlan: RacePlan,
    ): String {
        val normal = options.first()
        if (!normal.feasible) return "Le plan normal pose problème : ${normal.issues.joinToString(", ")}."
        val rec = options.first { it.kind == recommended }
        if (!indistinguishable && recommended in TYRE_KINDS) {
            val u = seconds(uncertainty ?: 0)
            val only = rec.tyresOnlyStop
            val fuelEnough = normalPlan.stops.none { it.forFuel }
            return when {
                recommended == OptionKind.STAY_OUT ->
                    "Je recommande de garder ces pneus jusqu'à l'arrivée : l'arrêt pour les pneus ne nous fait plus gagner de temps " +
                        "(environ ${seconds(gain)} de mieux sans lui, incertitude $u)."
                only != null && fuelEnough ->
                    "Le carburant suffit, mais tes pneus nous coûtent du temps. Je recommande un arrêt pour les pneus uniquement, " +
                        "fin du tour ${only.endOfLap}. Aucun ravitaillement nécessaire. Gain estimé ${seconds(gain)}, au-delà de notre incertitude ($u)."
                only != null -> "Je recommande un arrêt pour les pneus uniquement, fin du tour ${only.endOfLap}, en plus du ravitaillement prévu : " +
                    "gain estimé ${seconds(gain)}, au-delà de notre incertitude ($u)."
                else -> "Je recommande de changer les pneus au ravitaillement, fin du tour ${rec.tyreLaps.first()} : gain estimé ${seconds(gain)}, " +
                    "au-delà de notre incertitude ($u)."
            }
        }
        if (tyreDoubt && indistinguishable) {
            val base = if (noStop) "Aucun arrêt nécessaire pour le carburant. " else ""
            return base + "Les pneus montrent des signes de dégradation, mais je ne peux pas encore démontrer qu'un arrêt nous ferait gagner du temps."
        }
        if (noStop) return "Aucun arrêt nécessaire : on reste en piste jusqu'à l'arrivée."
        val others = options.drop(1).filter { !it.feasible && it.kind !in TYRE_KINDS }.map { "${it.kind.label} : ${it.issues.first()}" }
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

    val TYRE_KINDS = setOf(OptionKind.TYRES_NOW, OptionKind.TYRES_NEXT, OptionKind.TYRES_PLANNED, OptionKind.TYRES_WITH_FUEL, OptionKind.STAY_OUT)

    /** Gain minimal exigé d'un arrêt ou d'un changement de pneus supplémentaire, en plus de deux fois l'incertitude. */
    const val MIN_EXTRA_STOP_GAIN_MS = 2_000L

    /** Temps d'un changement de pneus inconnu : marge prudente comptée (annoncée comme telle), jamais un gain. */
    const val TYRE_SERVICE_MARGIN_MS = 10_000.0
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
