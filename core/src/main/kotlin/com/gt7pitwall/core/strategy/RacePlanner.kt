package com.gt7pitwall.core.strategy

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** D'où vient un chiffre du plan : l'ingénieur le dit pour ne jamais présenter une estimation comme une mesure. */
enum class DataSource { GAME, RULES, MEASURED, HISTORY, ESTIMATED }

data class PlannedStop(
    /** Rentrer au stand à la fin de ce tour. */
    val endOfLap: Int,
    /** Carburant à ajouter, en unités GT7 (null si la consommation n'est pas connue). */
    val fuel: Int?,
    /** Arrêt nécessaire pour le carburant. */
    val forFuel: Boolean,
    /** Arrêt qui compte pour les arrêts imposés par le règlement. */
    val mandatory: Boolean,
    /** Gomme imposée à monter à cet arrêt (déclarée manquante). */
    val tyreToFit: TyreCompound? = null,
    /** Changer les pneus à cet arrêt (durée de vie donnée par le pilote, gomme imposée, ou performance). */
    val changeTyres: Boolean = false,
    /** Changement de pneus choisi pour la performance (dégradation mesurée), et non imposé. */
    val forTyres: Boolean = false,
) {
    /** Arrêt pour les pneus uniquement : ni essence, ni obligation. */
    val tyresOnly: Boolean get() = changeTyres && !forFuel && !mandatory

    /** Motifs de l'arrêt, dans l'ordre de priorité : règlement, carburant, pneus. */
    val reasons: List<String>
        get() = buildList {
            if (mandatory) add("règlement")
            if (tyreToFit != null) add("gomme imposée")
            if (forFuel) add("carburant")
            if (changeTyres && tyreToFit == null) add("pneus")
        }

    /** Arrêt réglementaire sans besoin d'essence. */
    val mandatoryOnly: Boolean get() = mandatory && !forFuel && fuel != null

    /** Consommation connue et rien à ajouter (arrêt pour les pneus ou le règlement). */
    val noFuelNeeded: Boolean get() = !forFuel && fuel != null
}

enum class PlanWarning {
    /** Le carburant à bord ne suffit pas pour finir le tour en cours. */
    CANNOT_REACH_PITS,

    /** Plus assez de tours pour faire tous les arrêts obligatoires. */
    MANDATORY_STOPS_IMPOSSIBLE,

    /** Plus assez d'arrêts possibles pour monter toutes les gommes imposées. */
    TYRES_IMPOSSIBLE,

    /** Même avec les arrêts possibles, le carburant ne suffit pas (relais plus longs qu'un plein). */
    FUEL_IMPOSSIBLE,

    /** Les pneus actuels ont déjà dépassé la durée de vie donnée par le pilote. */
    TYRES_OVERDUE,
}

data class RacePlan(
    val totalLaps: Int,
    val lapsSource: DataSource,
    val stops: List<PlannedStop>,
    val perLap: Float?,
    /** MEASURED (télémétrie de cette course) ou HISTORY (mémoire voiture × circuit × multiplicateur). */
    val fuelSource: DataSource?,
    /** Temps perdu par passage dans la voie des stands (base GT7), hors ravitaillement. */
    val pitLaneDeltaS: Float?,
    val requiredTyres: Set<TyreCompound>,
    val warnings: Set<PlanWarning> = emptySet(),
    /** Gommes imposées alors que les pneus de départ ne sont pas déclarés : le plan compte un changement par gomme. */
    val startTyreUnknown: Boolean = false,
    /**
     * Issue #30 : deux gommes imposées et gomme de départ inconnue. Un plan par gomme de départ possible (« si tu pars en
     * durs… ») ; aucun n'est choisi tant que la gomme n'est pas déclarée.
     */
    val scenarios: Map<TyreCompound, RacePlan> = emptyMap(),
) {
    val estimatedLaps: Boolean get() = lapsSource == DataSource.ESTIMATED

    fun nextStop(currentLap: Int): PlannedStop? = stops.firstOrNull { it.endOfLap >= currentLap }
}

/**
 * Plan de course à partir du règlement et de données réelles.
 * Nombre d'arrêts minimal pour le carburant, le règlement et les gommes imposées ; relais équilibrés ;
 * premier relais limité au carburant à bord ; aucun arrêt planifié à la fin du dernier tour.
 */
object RacePlanner {
    /** Marge de sécurité, en tours, gardée à chaque relais. */
    const val SAFETY_LAPS = 0.3f

    data class Input(
        val rules: RaceRules,
        /** Nombre de tours envoyé par GT7 (0 si course au temps / inconnu). */
        val gameTotalLaps: Int,
        /** Tour en cours (0 avant le départ). */
        val currentLap: Int,
        /** Part du tour en cours restant à parcourir (0..1). */
        val currentLapRemaining: Float = 1f,
        val fuelNow: Float,
        val tank: Float,
        val perLap: Float?,
        val fuelSource: DataSource?,
        val avgLapMs: Int?,
        val raceElapsedMs: Long?,
        /** Arrêts déjà prouvés ou confirmés. */
        val stopsDone: Int,
        /** Gommes imposées pas encore déclarées. */
        val tyresMissing: List<TyreCompound> = emptyList(),
        /** Tours déjà bouclés avec le train de pneus actuel. */
        val tyreAgeLaps: Int = 0,
        val pitLaneDeltaS: Float? = null,
        /**
         * Tours d'arrêt du plan déjà annoncé (sans les arrêts pour les pneus seuls, voir [tyreStopLaps]). Ils sont conservés
         * tant qu'ils restent faisables : un plan qui glisse d'un tour à chaque tour ne déclencherait jamais l'appel au stand.
         */
        val previousStops: List<Int> = emptyList(),
        /**
         * Changements de pneus retenus pour la performance (fin de ces tours) : s'il y a déjà un arrêt à ce tour, les pneus y
         * sont changés ; sinon un arrêt pour les pneus seuls est ajouté, sans essence. Le carburant n'en dépend pas.
         */
        val tyreStopLaps: List<Int> = emptyList(),
    )

    fun plan(i: Input): RacePlan? {
        val base = planOne(i) ?: return null
        val required = i.rules.requiredTyres
        // Gomme de départ inconnue, deux gommes imposées, rien encore monté : un plan conditionnel par gomme de départ.
        if (!base.startTyreUnknown || required.size != 2 || i.tyresMissing.size != 2) return base
        val scenarios = required.sortedBy { it.ordinal }.mapNotNull { start ->
            planOne(i.copy(rules = i.rules.copy(startTyre = start), tyresMissing = required.filter { it != start }))?.let { start to it }
        }.toMap()
        return base.copy(scenarios = scenarios)
    }

    private fun planOne(i: Input): RacePlan? {
        val rules = i.rules
        val (total, lapsSource) = totalLaps(i) ?: return null
        val perLap = i.perLap?.takeIf { it > 0f }
        val warnings = mutableSetOf<PlanWarning>()
        fun result(stops: List<PlannedStop>) = RacePlan(
            total, lapsSource, stops, perLap, if (perLap != null) i.fuelSource else null, i.pitLaneDeltaS, rules.requiredTyres, warnings,
            startTyreUnknown = rules.requiredTyres.isNotEmpty() && rules.startTyre == null,
        )

        val lapIndex = max(i.currentLap, 1)
        if (i.currentLap > total) return result(emptyList()) // course terminée
        val r = if (i.currentLap >= 1) i.currentLapRemaining.coerceIn(0f, 1f) else 1f
        val lapsAfterCurrent = total - lapIndex
        val lapsToGo = lapsAfterCurrent + r

        val mandatoryLeft = max(0, rules.mandatoryStops - i.stopsDone)
        val tyresLeft = i.tyresMissing.size
        // Sans consommation connue ni obligation, on ne sait rien : pas de plan plutôt qu'un faux « pas d'arrêt ».
        if (perLap == null && mandatoryLeft == 0 && tyresLeft == 0 && rules.tyreStintLaps == null) return null

        if (perLap != null && i.fuelNow < r * perLap) warnings += PlanWarning.CANNOT_REACH_PITS

        val fuelStops = if (perLap == null || i.tank <= 0f) {
            0
        } else {
            val need = (lapsToGo + SAFETY_LAPS) * perLap
            // Assez pour finir sans la marge : pas d'arrêt pour quelques dixièmes, la radio dira de gérer.
            if (need <= i.fuelNow || lapsToGo * perLap <= i.fuelNow) 0 else ceil((need - i.fuelNow) / i.tank).toInt()
        }
        // Durée de vie des pneus donnée par le pilote : relais limités à T tours (le premier à T - tours déjà faits).
        val units = lapsAfterCurrent + 1
        val tyreLife = rules.tyreStintLaps?.takeIf { it > 0 }
        val firstTyreLimit = tyreLife?.let { (it - i.tyreAgeLaps).coerceAtLeast(1) }
        if (tyreLife != null && i.tyreAgeLaps >= tyreLife && units > 1) warnings += PlanWarning.TYRES_OVERDUE
        val tyreStops = if (tyreLife == null || units <= firstTyreLimit!!) 0 else ceil((units - firstTyreLimit).toDouble() / tyreLife).toInt()

        // On ne peut s'arrêter qu'à la fin d'un tour qui n'est pas le dernier.
        val maxStops = lapsAfterCurrent
        var k = maxOf(fuelStops, mandatoryLeft, tyresLeft, tyreStops)
        if (k > maxStops) {
            if (mandatoryLeft > maxStops) warnings += PlanWarning.MANDATORY_STOPS_IMPOSSIBLE
            if (tyresLeft > maxStops) warnings += PlanWarning.TYRES_IMPOSSIBLE
            if (fuelStops > maxStops) warnings += PlanWarning.FUEL_IMPOSSIBLE
            k = maxStops
        }
        if (k == 0) return result(withTyreStops(emptyList(), i, lapIndex, total, perLap))

        // Relais en tours entiers (le premier inclut le tour en cours), au moins un tour chacun.
        val stints = IntArray(k + 1) { idx -> units / (k + 1) + if (idx < units % (k + 1)) 1 else 0 }
        run {
            var maxFirst = units - k
            if (perLap != null) maxFirst = minOf(maxFirst, floor(i.fuelNow / perLap - r - SAFETY_LAPS).toInt() + 1)
            if (firstTyreLimit != null) maxFirst = minOf(maxFirst, firstTyreLimit)
            maxFirst = maxFirst.coerceIn(1, units - k)
            if (stints[0] > maxFirst) {
                stints[0] = maxFirst
                val rest = units - maxFirst
                for (s in 1..k) stints[s] = rest / k + if (s - 1 < rest % k) 1 else 0
            }
        }
        if (perLap != null) {
            val tankLaps = i.tank / perLap - SAFETY_LAPS
            if (stints.drop(1).any { it > tankLaps }) warnings += PlanWarning.FUEL_IMPOSSIBLE
        }

        // Stabilité : on garde les tours d'arrêt déjà annoncés s'ils sont toujours tenables.
        keptStints(i, lapIndex, total, k, r, perLap)?.let { kept -> kept.copyInto(stints) }

        val stops = mutableListOf<PlannedStop>()
        var lap = lapIndex - 1
        var fuel = i.fuelNow
        var tyreAge = i.tyreAgeLaps
        for (s in 0 until k) {
            lap += stints[s]
            var added: Int? = null
            if (perLap != null) {
                val used = if (s == 0) (r + stints[0] - 1) * perLap else stints[s] * perLap
                fuel -= used
                val needNext = (stints[s + 1] + SAFETY_LAPS) * perLap
                val add = (needNext - fuel).coerceIn(0f, (i.tank - fuel).coerceAtLeast(0f))
                fuel += add
                added = add.roundToInt()
            }
            // Pneus : on change si le relais suivant dépasserait leur durée de vie, ou pour monter une gomme imposée.
            tyreAge += stints[s]
            val tyreToFit = i.tyresMissing.getOrNull(s)
            val change = tyreToFit != null || (tyreLife != null && tyreAge + stints[s + 1] > tyreLife)
            if (change) tyreAge = 0
            stops += PlannedStop(
                endOfLap = lap,
                fuel = added,
                forFuel = added != null && added >= 1,
                mandatory = s < mandatoryLeft,
                tyreToFit = tyreToFit,
                changeTyres = change,
            )
        }
        // Un arrêt sans essence, sans obligation ni pneus n'a aucune raison d'être (arrondi du carburant).
        return result(withTyreStops(stops.filter { it.forFuel || it.mandatory || it.changeTyres || it.fuel == null }, i, lapIndex, total, perLap))
    }

    /** Ajoute les changements de pneus de performance : sur l'arrêt déjà prévu à ce tour, sinon un arrêt pour les pneus seuls. */
    private fun withTyreStops(stops: List<PlannedStop>, i: Input, lapIndex: Int, total: Int, perLap: Float?): List<PlannedStop> {
        val laps = i.tyreStopLaps.filter { it in lapIndex until total }.distinct()
        if (laps.isEmpty()) return stops
        val out = stops.map { s -> if (s.endOfLap in laps) s.copy(changeTyres = true, forTyres = true) else s }.toMutableList()
        for (l in laps) if (out.none { it.endOfLap == l }) {
            out += PlannedStop(l, fuel = if (perLap != null) 0 else null, forFuel = false, mandatory = false, changeTyres = true, forTyres = true)
        }
        return out.sortedBy { it.endOfLap }
    }

    private fun keptStints(i: Input, lapIndex: Int, total: Int, k: Int, r: Float, perLap: Float?): IntArray? {
        val ends = i.previousStops.filter { it in lapIndex until total }.sorted()
        if (ends.size != k || ends.distinct().size != k) return null
        val bounds = listOf(lapIndex - 1) + ends + total
        val stints = IntArray(k + 1) { s -> bounds[s + 1] - bounds[s] }
        if (stints.any { it < 1 }) return null
        if (perLap != null) {
            if ((r + stints[0] - 1 + SAFETY_LAPS) * perLap > i.fuelNow) return null
            val tankLaps = i.tank / perLap - SAFETY_LAPS
            if (stints.drop(1).any { it > tankLaps }) return null
        }
        i.rules.tyreStintLaps?.takeIf { it > 0 }?.let { life ->
            if (stints[0] > (life - i.tyreAgeLaps).coerceAtLeast(1) || stints.drop(1).any { it > life }) return null
        }
        return stints
    }

    private fun totalLaps(i: Input): Pair<Int, DataSource>? {
        val rules = i.rules
        return when {
            i.gameTotalLaps > 0 -> i.gameTotalLaps to DataSource.GAME
            rules.totalLaps != null && rules.totalLaps > 0 -> rules.totalLaps to DataSource.RULES
            rules.durationMin != null && rules.durationMin > 0 && i.avgLapMs != null && i.avgLapMs > 0 -> {
                val durationMs = rules.durationMin * 60_000L
                val elapsed = i.raceElapsedMs ?: 0L
                // À l'expiration du temps, on termine le tour en cours.
                val total = if (i.currentLap >= 1) {
                    (i.currentLap - 1) + ceil((durationMs - elapsed).coerceAtLeast(0).toDouble() / i.avgLapMs).toInt() + 1
                } else {
                    floor(durationMs.toDouble() / i.avgLapMs).toInt() + 1
                }
                total to DataSource.ESTIMATED
            }
            else -> null
        }
    }
}
