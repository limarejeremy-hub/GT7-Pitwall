package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.Cost
import com.gt7pitwall.core.strategy.Learned
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.StopKind
import com.gt7pitwall.core.strategy.StrategyOptions
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Issue #65 : quelle gomme monter au départ, d'après le règlement, l'historique du pilote et, si elle est mesurée, la
 * place sur la grille. Calcul pur (aucun modèle de langage) : tous les ordres de gommes légaux et toutes les longueurs de
 * relais faisables sont comparés sur le temps total estimé, arrêts compris.
 *
 * - Temps d'un relais : rythme des pneus jeunes de la gomme × tours + perte par tour d'âge × somme des âges, tirés de
 *   [CompoundBook] (courses comparables du pilote). Aucune gomme sans historique suffisant n'est chiffrée.
 * - Grille : GT7 ne transmet ni les écarts ni les gommes des autres. La place n'agit que par ce que l'historique du pilote
 *   a mesuré : la perte des premiers tours du relais de départ selon la zone de départ ([TrafficEffect]). Sans mesure,
 *   la place est dite et n'influe sur rien.
 * - Une recommandation exige un écart d'au moins [MIN_GAP_MS] et du double de l'incertitude ; sinon « options proches ».
 * - Un conseil n'est jamais une gomme montée : [RaceRules.startTyre] et le registre des arrêts n'en sont jamais modifiés.
 */
object StartTyreAdvisor {
    /** Zone de départ (GT7 ne donne pas toujours le nombre de voitures en qualification : seuils absolus). */
    enum class GridZone(val label: String) {
        FRONT("devant"), MIDDLE("au milieu"), BACK("derrière");

        companion object {
            fun of(position: Int): GridZone = when {
                position <= FRONT_MAX -> FRONT
                position <= MIDDLE_MAX -> MIDDLE
                else -> BACK
            }
        }
    }

    /** Perte moyenne (ms par tour) des [TRAFFIC_LAPS] premiers tours du relais de départ, par zone et par gomme. */
    data class TrafficEffect(val byZone: Map<Pair<GridZone, TyreCompound>, Learned>) {
        operator fun get(zone: GridZone, c: TyreCompound): Learned? = byZone[zone to c]?.takeIf { it.confidence != Confidence.INSUFFICIENT }

        companion object {
            val NONE = TrafficEffect(emptyMap())

            /**
             * Relais de départ des courses comparables dont la place sur la grille est connue : écart des premiers tours
             * propres au rythme de la gomme (pneus jeunes, perte par âge retirée). Au moins deux courses par zone et gomme.
             */
            fun learn(context: SessionContext, sessions: Collection<SessionRecord>, book: CompoundBook, excludeId: String? = null): TrafficEffect {
                val grid = sessions.associate { it.id to it.gridPosition }
                val pool = TyreModel.pool(context, sessions, excludeId)
                val residuals = mutableMapOf<Pair<GridZone, TyreCompound>, MutableList<Pair<String, Double>>>()
                pool.laps.filter { it.stintStart <= 1 && it.age in 1..TRAFFIC_LAPS && it.compound != null && it.lap.timeMs != null }
                    .groupBy { it.session to it.compound!! }
                    .forEach { (key, laps) ->
                        val (session, c) = key
                        val pos = grid[session]?.takeIf { it > 0 } ?: return@forEach
                        val perf = book[c] ?: return@forEach
                        val pace = perf.paceMs ?: return@forEach
                        val slope = perf.slopeMsPerLap?.mean ?: 0.0
                        val r = laps.map { it.lap.timeMs!! - pace.mean - slope * it.age }.average()
                        residuals.getOrPut(GridZone.of(pos) to c) { mutableListOf() } += session to r
                    }
                return TrafficEffect(
                    residuals.mapValues { (_, v) ->
                        val xs = v.map { it.second }
                        val m = xs.average()
                        val sd = if (xs.size > 1) sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)) else abs(m)
                        val conf = if (xs.size >= 2) Confidence.PROVISIONAL else Confidence.INSUFFICIENT
                        Learned(m, sd, xs.size, v.map { it.first }.distinct().size, conf)
                    },
                )
            }
        }
    }

    data class Input(
        val totalLaps: Int,
        /** Gommes autorisées (vide : non précisé par le règlement). */
        val allowed: Set<TyreCompound>,
        val required: Set<TyreCompound>,
        val mandatoryStops: Int,
        /** Durée de vie d'un train donnée par le pilote, si elle l'a été. */
        val tyreLife: Int?,
        val book: CompoundBook,
        val costs: StopCosts?,
        /** Place sur la grille et nombre de voitures, seulement si mesurés et stables. */
        val grid: Pair<Int, Int?>? = null,
        val traffic: TrafficEffect = TrafficEffect.NONE,
    )

    /** Un plan : gommes dans l'ordre, fin de tour de chaque arrêt, temps estimé et incertitude (ms). */
    data class Plan(val compounds: List<TyreCompound>, val stops: List<Int>, val estimatedMs: Long, val uncertaintyMs: Long) {
        val start: TyreCompound get() = compounds.first()
    }

    data class Advice(
        /** Gomme conseillée au départ ; null : pas de conclusion (options proches, données insuffisantes). */
        val recommended: TyreCompound?,
        /** Meilleur plan par gomme de départ chiffrable. */
        val bestByStart: Map<TyreCompound, Plan>,
        /** Gommes de départ que le règlement permet. */
        val legalStarts: Set<TyreCompound>,
        /** Phrase radio, courte. */
        val sentence: String,
        /** Ce sur quoi repose le conseil, et ce qui manque. */
        val basis: List<String>,
    )

    fun advise(i: Input): Advice {
        val basis = mutableListOf<String>()
        val where = i.grid?.let { (p, n) -> "P$p" + (n?.let { " sur $it" } ?: "") }
        val prefix = where?.let { "$it : " } ?: "Sans place de grille mesurée : "
        val candidates = (if (i.allowed.isNotEmpty()) i.allowed else i.required + i.book.compounds.keys).filter { it.family == TyreCompound.Family.RACING || i.allowed.isNotEmpty() }.toSet()
        if (i.allowed.isEmpty()) basis += "gommes autorisées non précisées : seules les gommes imposées ou déjà roulées sont envisagées"
        if (i.totalLaps <= 0 || candidates.isEmpty()) {
            return Advice(null, emptyMap(), emptySet(), "${prefix}règlement ou nombre de tours inconnu : pas de conseil de gomme.", basis)
        }
        val required = i.required
        if (required.any { it !in candidates }) {
            return Advice(null, emptyMap(), emptySet(), "${prefix}gommes imposées hors des gommes autorisées : règlement contradictoire, pas de conseil.", basis + "règlement contradictoire")
        }
        // Nombre d'arrêts : obligations, gommes imposées, durée de vie déclarée ; un arrêt de plus envisagé.
        val minByTyres = if (required.size >= 2) required.size - 1 else 0
        val minByLife = i.tyreLife?.takeIf { it > 0 }?.let { (i.totalLaps + it - 1) / it - 1 } ?: 0
        val kMin = maxOf(i.mandatoryStops, minByTyres, minByLife)
        // Longue course : un arrêt de plus que le minimum multiplierait les découpages ; il n'est pas envisagé.
        val kMax = minOf(if (i.totalLaps > LONG_RACE_LAPS) kMin else kMin + 1, MAX_STOPS, i.totalLaps - 1)
        if (kMin > kMax) {
            return Advice(null, emptyMap(), emptySet(), "${prefix}trop d'arrêts imposés pour ${i.totalLaps} tours : pas de conseil.", basis)
        }
        val legalStarts = mutableSetOf<TyreCompound>()
        val plans = mutableListOf<Plan>()
        val tyreStop = i.costs?.cost(StopKind.TYRES_ONLY)
        if (tyreStop == null) basis += "coût d'un arrêt inconnu : seuls les plans à nombre d'arrêts égal sont comparés"
        val zone = i.grid?.first?.let(GridZone::of)
        for (k in kMin..kMax) {
            for (seq in sequences(candidates.toList(), k + 1)) {
                if (!seq.containsAll(required)) continue
                legalStarts += seq.first()
                val perf = seq.map { i.book[it] }
                if (perf.any { it?.paceMs == null || it.slopeMsPerLap == null || it.confidence == Confidence.INSUFFICIENT }) continue
                if (k > 0 && tyreStop == null && k != kMin) continue
                for (split in splits(i.totalLaps, k + 1, i.tyreLife)) {
                    var t = 0.0
                    var u2 = 0.0
                    split.forEachIndexed { s, len ->
                        val p = perf[s]!!
                        val pace = p.paceMs!!
                        val slope = p.slopeMsPerLap!!
                        val ages = len * (len + 1) / 2.0
                        t += pace.mean * len + slope.mean * ages
                        // Pas d'erreur de modèle par tour ici : tous les plans couvrent les mêmes tours, elle s'annule
                        // dans leur différence. Restent les incertitudes du rythme et de la dégradation de chaque gomme.
                        u2 += sq(se(pace) * len) + sq(se(slope) * ages)
                        if (s == 0 && zone != null) i.traffic[zone, seq[0]]?.let { tr ->
                            val n = minOf(TRAFFIC_LAPS, len)
                            t += tr.mean * n
                            u2 += sq(se(tr) * n)
                        }
                    }
                    if (k > 0 && tyreStop != null) {
                        t += k * tyreStop.ms
                        u2 += sq(k * tyreStop.spread)
                    }
                    val stops = split.runningFold(0) { a, b -> a + b }.drop(1).dropLast(1)
                    plans += Plan(seq, stops, t.roundToLong(), sqrt(u2).roundToLong())
                }
            }
        }
        val best = plans.groupBy { it.start }.mapValues { (_, v) -> v.minBy { it.estimatedMs } }
        legalStarts.forEach { c -> if (c !in best) basis += "${c.spoken} : historique insuffisant pour chiffrer un départ" }
        if (zone != null) {
            val measured = best.keys.filter { i.traffic[zone, it] != null }
            basis += if (measured.isEmpty()) "effet de la place $where non mesuré dans ton historique : elle ne change pas le calcul"
            else "pertes de début de course mesurées $where (${zone.label}) pour : ${measured.joinToString(", ") { it.spoken }}"
        }
        val legalText = legalStarts.sortedBy { it.ordinal }.joinToString(" ou ") { it.spoken }
        if (best.isEmpty()) {
            return Advice(null, best, legalStarts, "${prefix}départ possible en $legalText ; historique insuffisant pour en conseiller un.", basis)
        }
        val ranked = best.values.sortedBy { it.estimatedMs }
        val a = ranked[0]
        fun planText(p: Plan) = p.compounds.drop(1).zip(p.stops).joinToString(", ") { (c, s) -> "${c.hardness?.spoken ?: c.spoken} fin du tour $s" }
            .ifEmpty { "sans changer de pneus" }
        if (ranked.size == 1) {
            val others = legalStarts - a.start
            return Advice(
                if (others.isEmpty()) a.start else null, best, legalStarts,
                if (others.isEmpty()) "${prefix}départ en ${a.start.spoken}, ${planText(a)}."
                else "${prefix}seul un départ en ${a.start.spoken} est chiffrable (${planText(a)}) ; historique insuffisant pour ${others.joinToString(" et ") { it.spoken }}.",
                basis,
            )
        }
        val b = ranked[1]
        val gap = b.estimatedMs - a.estimatedMs
        val unc = sqrt(sq(a.uncertaintyMs.toDouble()) + sq(b.uncertaintyMs.toDouble()))
        return if (gap >= MIN_GAP_MS && gap > 2 * unc) {
            Advice(
                a.start, best, legalStarts,
                "${prefix}je conseille de partir en ${a.start.spoken}, ${planText(a)} : environ ${StrategyOptions.seconds(gap)} de mieux " +
                    "qu'en ${b.start.spoken}, à ± ${StrategyOptions.seconds(unc.roundToLong())}. Conseil seulement, dis-moi ce que tu montes.",
                basis,
            )
        } else {
            Advice(
                null, best, legalStarts,
                "${prefix}options proches entre ${a.start.spoken} et ${b.start.spoken} (écart ${StrategyOptions.seconds(gap)}, " +
                    "sous l'incertitude) : pars avec celle que tu préfères.",
                basis,
            )
        }
    }

    /** Suites de [n] gommes prises dans [c] (avec répétition). */
    private fun sequences(c: List<TyreCompound>, n: Int): List<List<TyreCompound>> =
        if (n == 0) listOf(emptyList()) else sequences(c, n - 1).flatMap { s -> c.map { s + it } }

    /** Découpages de [laps] tours en [parts] relais d'au moins un tour, chacun au plus [life] tours. */
    private fun splits(laps: Int, parts: Int, life: Int?): List<List<Int>> {
        if (parts == 1) return if (life == null || laps <= life) listOf(listOf(laps)) else emptyList()
        return (1..laps - parts + 1).filter { life == null || it <= life }.flatMap { first -> splits(laps - first, parts - 1, life).map { listOf(first) + it } }
    }

    private fun se(l: Learned): Double = l.spread / sqrt(l.samples.coerceAtLeast(1).toDouble())
    private fun sq(x: Double) = x * x

    const val FRONT_MAX = 4
    const val MIDDLE_MAX = 10

    /** Premiers tours du relais de départ où le trafic pèse (mesuré dans l'historique). */
    const val TRAFFIC_LAPS = 3
    const val MAX_STOPS = 3
    const val LONG_RACE_LAPS = 40
    const val MIN_GAP_MS = 1_000L
}
