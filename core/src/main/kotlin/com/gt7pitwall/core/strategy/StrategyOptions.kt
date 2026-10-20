package com.gt7pitwall.core.strategy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
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
)

/** Ce qui est fait pendant un arrêt : le coût d'un arrêt dépend de ce qu'il contient. */
enum class StopKind(val label: String) {
    /** Passage sans intervention (arrêt obligatoire sans plein ni pneus). */
    NONE("arrêt sans intervention"),
    FUEL_ONLY("ravitaillement seul"),
    TYRES_ONLY("pneus seuls"),
    FUEL_AND_TYRES("ravitaillement et pneus"),
    ;

    val tyres: Boolean get() = this == TYRES_ONLY || this == FUEL_AND_TYRES
    val fuel: Boolean get() = this == FUEL_ONLY || this == FUEL_AND_TYRES

    companion object {
        fun of(fuel: Boolean, tyres: Boolean): StopKind = when {
            fuel && tyres -> FUEL_AND_TYRES
            fuel -> FUEL_ONLY
            tyres -> TYRES_ONLY
            else -> NONE
        }
    }
}

/** Un temps (ms), son incertitude, d'où il vient, et s'il s'agit d'une hypothèse plutôt que d'une mesure. */
data class Cost(val ms: Double, val spread: Double, val source: String, val assumed: Boolean = false)

/**
 * Coût d'un arrêt selon ce qu'il contient, sans jamais compter deux fois une intervention :
 * - [observed] : temps totaux observés sur des arrêts passés de même contenu (ils comprennent déjà ce qui y a été fait) ;
 * - sinon composé : [base] (passage par les stands sans pneus) + [tyreService] si les pneus sont changés.
 * Le ravitaillement n'est compté que s'il est dans un temps observé ou dans la base ([baseIncludesRefuel]) : sinon il
 * est ignoré, ce qui ne change rien entre deux stratégies qui ajoutent la même quantité d'essence.
 */
data class StopCosts(
    /** Passage par les stands sans changement de pneus ; null : inconnu. */
    val base: Cost?,
    /** La base vient d'arrêts avec plein (elle le comprend) ; faux : voie des stands seule, plein non compté. */
    val baseIncludesRefuel: Boolean = false,
    /** Temps d'un changement de pneus : appris, sinon hypothèse provisoire de 10 s ± 10 s. */
    val tyreService: Cost = ASSUMED_TYRE_SERVICE,
    val observed: Map<StopKind, Cost> = emptyMap(),
) {
    fun cost(kind: StopKind): Cost? {
        observed[kind]?.let { return it }
        val b = base ?: return null
        if (!kind.tyres) return b
        val t = tyreService
        return Cost(b.ms + t.ms, sqrt(b.spread * b.spread + t.spread * t.spread), "${b.source} + ${t.source}", assumed = t.assumed || b.assumed)
    }

    companion object {
        /** Hypothèse provisoire, jamais une mesure : elle pèse dans l'incertitude avant toute annonce de gain. */
        val ASSUMED_TYRE_SERVICE = Cost(10_000.0, 10_000.0, "changement de pneus supposé à 10 s ± 10 s (hypothèse provisoire)", assumed = true)
    }
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
    /** Incertitude de cet écart (ms) : rythme, dégradation, et coûts des arrêts qui diffèrent de la stratégie normale. */
    val uncertaintyMs: Long? = null,
    /** Hypothèses dont dépend l'écart (temps de changement des pneus supposé…). */
    val assumptions: List<String> = emptyList(),
) {
    /** Contenu de chaque arrêt (carburant, pneus), dans l'ordre. */
    val stopKinds: List<StopKind>
        get() = plan?.stops?.map { StopKind.of(it.forFuel, it.changeTyres) } ?: stopLaps.map { StopKind.FUEL_ONLY }

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
        /**
         * Perte par arrêt (ms) sans détail de contenu ; null : inconnue. Ancienne forme : elle sert de base à tous les arrêts,
         * le changement de pneus s'y ajoute (hypothèse prudente). Préférer [stopCosts].
         */
        val pitLossMs: Double? = null,
        val pitLossSpread: Double = 0.0,
        val pitLossFromLaneOnly: Boolean = false,
        /** Coûts des arrêts selon leur contenu. */
        val stopCosts: StopCosts? = null,
    ) {
        fun costs(): StopCosts? = stopCosts ?: pitLossMs?.let {
            val src = if (pitLossFromLaneOnly) "voie des stands seule (base GT7)" else "perte par arrêt, contenu des arrêts non précisé"
            StopCosts(Cost(it, pitLossSpread, src), baseIncludesRefuel = !pitLossFromLaneOnly)
        }
    }

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
        // Temps : rythme et dégradation (identiques pour toutes les options à âge des pneus égal), puis coût de chaque arrêt
        // selon son contenu. L'incertitude d'une option ajoute celle des arrêts qui diffèrent de la stratégie normale.
        val costs = pace.costs()
        val baseU = if (pace.lapMs == null) null else {
            val slopeU = pace.slopeSpread * lapsLeft
            sqrt(slopeU * slopeU + (pace.lapSpreadMs * 2) * (pace.lapSpreadMs * 2))
        }
        val normalKinds = options.first().stopKinds
        val timed = options.map { o ->
            val laps = if (o.feasible) estimate(o.stopLaps, currentLap, lapsLeft, input.tyreAgeLaps, pace.copy(pitLossMs = null, stopCosts = null), o.tyreLaps) else null
            val kinds = o.stopKinds
            val differing = multisetDiff(kinds, normalKinds) + multisetDiff(normalKinds, kinds)
            val unknown = differing.any { costs?.cost(it) == null }
            val stopMs = kinds.sumOf { costs?.cost(it)?.ms ?: 0.0 }
            val variance = differing.sumOf { k -> costs?.cost(k)?.spread?.let { it * it } ?: 0.0 }
            val assumptions = differing.mapNotNull { costs?.cost(it) }.filter { it.assumed }.map { it.source }.distinct()
            o.copy(
                estimatedMs = if (laps == null || (unknown && o.kind != OptionKind.NORMAL)) null else laps + stopMs.roundToLong(),
                uncertaintyMs = baseU?.let { sqrt(it * it + variance).roundToLong().coerceAtLeast(MIN_UNCERTAINTY_MS) },
                assumptions = assumptions,
                issues = if (o.feasible && unknown && o.kind != OptionKind.NORMAL) o.issues + "coût de l'arrêt inconnu" else o.issues,
            )
        }
        val base = timed.first().estimatedMs
        val withDelta = timed.map { o -> o.copy(deltaMs = if (base != null && o.estimatedMs != null) o.estimatedMs - base else null) }

        val feasible = withDelta.filter { it.feasible }
        val comparable = baseU != null && base != null && feasible.any { it.kind != OptionKind.NORMAL && it.estimatedMs != null }
        // Un arrêt de plus (ou un changement de pneus de plus) n'est retenu que si son bénéfice dépasse nettement son coût
        // et l'incertitude, hypothèses comprises : des pneus qui semblent fatigués ne suffisent pas.
        fun threshold(o: StrategyOption): Long {
            val u = o.uncertaintyMs ?: Long.MAX_VALUE
            val extraStop = o.stopLaps.size > normal.stops.size || o.tyreLaps.size > options.first().tyreLaps.size
            return if (extraStop) maxOf(2 * u, MIN_EXTRA_STOP_GAIN_MS) else u
        }
        val candidates = feasible.filter { it.estimatedMs != null }
        val best = if (comparable) candidates.filter { o -> o.kind == OptionKind.NORMAL || -(o.deltaMs ?: 0) > threshold(o) }.minByOrNull { it.estimatedMs!! } else null
        val gain = best?.deltaMs?.let { -it } ?: 0
        val indistinguishable = best == null || best.kind == OptionKind.NORMAL
        val recommended = if (indistinguishable) OptionKind.NORMAL else best!!.kind
        // Incertitude annoncée : celle de l'option recommandée, sinon de la meilleure alternative.
        val challenger = candidates.filter { it.kind != OptionKind.NORMAL }.minByOrNull { it.estimatedMs!! }
        val uncertainty = (if (!indistinguishable) best?.uncertaintyMs else challenger?.uncertaintyMs) ?: baseU?.roundToLong()?.coerceAtLeast(MIN_UNCERTAINTY_MS)

        val tyreOptions = feasible.any { it.kind in TYRE_KINDS }
        val reason = reason(withDelta, recommended, indistinguishable, comparable, uncertainty, gain, normalStops.isEmpty(), tyres.degrading && tyreOptions, normal)
        // Hypothèses utilisées par les options pneus : dites dans les données, jamais présentées comme des mesures.
        val hyp = withDelta.flatMap { it.assumptions }.distinct().map { "hypothèse : $it" }
        val costBasis = costs?.let { c ->
            listOfNotNull(
                c.base?.let { "passage aux stands : ${it.source}" + if (!c.baseIncludesRefuel) " (temps du plein non compté)" else "" },
                c.observed.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ") { (k, v) -> "${k.label} ${seconds(v.ms.roundToLong())}" }?.let { "arrêts observés : $it" },
                if (!c.tyreService.assumed && withDelta.any { o -> o.stopKinds.any { it.tyres } }) "changement de pneus : ${c.tyreService.source}" else null,
            )
        } ?: emptyList()
        return StrategyReport(withDelta, recommended, reason, indistinguishable, uncertainty, (basis + costBasis + hyp).distinct(), unverifiedRules)
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
    /** Éléments de [a] absents de [b], en tenant compte des répétitions. */
    private fun multisetDiff(a: List<StopKind>, b: List<StopKind>): List<StopKind> {
        val left = b.toMutableList()
        return a.filter { !left.remove(it) }
    }

    /**
     * Temps restant : rythme × tours, dérive des chronos selon l'âge des pneus, et coût des arrêts. Ici chaque arrêt compte
     * comme un ravitaillement (pneus changés aux tours [tyreLaps]) ; [compare] utilise le contenu réel de chaque arrêt.
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
        val costs = pace.costs()
        total += stopLaps.sumOf { l -> costs?.cost(StopKind.of(fuel = true, tyres = l in tyreLaps))?.ms ?: 0.0 }
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
                        "fin du tour ${only.endOfLap}. Aucun ravitaillement nécessaire. Gain estimé ${seconds(gain)}, au-delà de notre incertitude ($u)." +
                        if (rec.assumptions.isNotEmpty()) " Temps de changement des pneus supposé, à confirmer." else ""
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
