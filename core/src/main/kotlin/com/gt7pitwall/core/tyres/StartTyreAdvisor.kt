package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.Learned
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.StopKind
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Issue #65 : quelle gomme monter au départ, d'après le règlement de la course, l'historique du pilote et, si elle est
 * mesurée, la place sur la grille. Calcul pur, borné (programmation dynamique, jamais d'énumération de tous les plans) :
 * pour chaque suite de gommes légale, le meilleur découpage des relais, sur le temps total estimé arrêts compris.
 *
 * Règles de prudence (revue #66) :
 * - **Gommes autorisées inconnues** : aucun conseil. Une gomme roulée ailleurs, ou la seule liste des gommes imposées,
 *   ne prouve pas qu'une gomme est autorisée dans CETTE course.
 * - **Une gomme de départ légale non chiffrable** (historique insuffisant) : pas de recommandation, seulement une
 *   tendance « parmi les gommes chiffrables ».
 * - **Écart** : au moins [MIN_GAP_MS] et plus du double de l'incertitude de la différence. Celle-ci compte le rythme et la
 *   dégradation de chaque gomme, le coût des arrêts, l'effet de la grille, et une erreur de modèle de
 *   [MODEL_MS_PER_LAP] par tour roulé sur une gomme différente d'un plan à l'autre. Le gain est dit à la seconde près.
 * - **Grille** : n'agit que par les pertes mesurées dans l'historique du pilote au début du relais de départ
 *   ([TrafficEffect], provisoires). GT7 ne transmet ni écarts ni gommes adverses.
 * - **Exploration partielle**, dite comme telle : au plus [MAX_STOPS] arrêts, un seul de plus que le minimum (aucun de plus
 *   au-delà de [LONG_RACE_LAPS] tours) ; chaque arrêt est compté comme un changement de pneus ; le carburant n'entre que
 *   par le nombre minimal d'arrêts qu'il impose ([Input.fuelStopsMin]).
 * - Un conseil n'est jamais une gomme montée : rien ici ne modifie le règlement ni le registre des arrêts.
 */
object StartTyreAdvisor {
    /** Zone de départ (GT7 ne donne pas toujours le nombre de voitures : seuils absolus). */
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

    /**
     * Écart moyen (ms par tour) des premiers tours propres du relais de départ au rythme de la gomme, par zone de départ
     * et par gomme. Le tour 1 est exclu par [TyreModel.pool] (départ, chaos) : ce sont les tours 2 et 3. Le rythme de
     * référence vient des mêmes courses : estimation provisoire, jamais une preuve de cause.
     */
    data class TrafficEffect(val byZone: Map<Pair<GridZone, TyreCompound>, Learned>) {
        operator fun get(zone: GridZone, c: TyreCompound): Learned? = byZone[zone to c]?.takeIf { it.confidence != Confidence.INSUFFICIENT }

        companion object {
            val NONE = TrafficEffect(emptyMap())

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
                        // Dispersion au moins égale à l'effet lui-même tant que l'échantillon est petit.
                        val sd = maxOf(if (xs.size > 1) sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)) else 0.0, abs(m))
                        val conf = if (xs.size >= MIN_TRAFFIC_SESSIONS) Confidence.PROVISIONAL else Confidence.INSUFFICIENT
                        Learned(m, sd, xs.size, v.map { it.first }.distinct().size, conf)
                    },
                )
            }
        }
    }

    data class Input(
        val totalLaps: Int,
        /** Gommes autorisées par le règlement de CETTE course (vide : inconnu, aucun conseil). */
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
        /** Arrêts que le carburant impose au minimum (plan de course), 0 si inconnu ou aucun. */
        val fuelStopsMin: Int = 0,
        /** D'où vient le règlement (« fiche de la semaine, probable »…), pour le journal et l'écran. */
        val rulesSource: String? = null,
        /** L'autonomie est connue (consommation mesurée ou apprise) : sinon le carburant n'entre pas dans le calcul, et c'est dit. */
        val fuelKnown: Boolean = true,
    )

    enum class Status(val label: String) {
        RECOMMENDED("conseil"),
        CLOSE("options proches"),
        INCOMPLETE("conseil incomplet"),
        NO_HISTORY("historique insuffisant"),
        RULES_UNKNOWN("règlement inconnu"),
        IMPOSSIBLE("règlement impossible ou contradictoire"),
    }

    /** Un plan : gommes dans l'ordre, fin de tour de chaque arrêt, temps estimé et incertitude propre (ms). */
    data class Plan(val compounds: List<TyreCompound>, val stops: List<Int>, val estimatedMs: Long, val uncertaintyMs: Long) {
        val start: TyreCompound get() = compounds.first()

        /** Gomme roulée à chaque tour (1..n). */
        fun compoundAt(lap: Int): TyreCompound = compounds[stops.count { it < lap }]
    }

    data class Advice(
        val status: Status,
        /** Gomme conseillée au départ ; non null seulement si [status] = RECOMMENDED. */
        val recommended: TyreCompound?,
        /** Tendance parmi les gommes chiffrables, quand la conclusion n'est pas permise. */
        val leaning: TyreCompound?,
        val bestByStart: Map<TyreCompound, Plan>,
        /** Gommes de départ que le règlement connu permet (vide si le règlement est inconnu). */
        val legalStarts: Set<TyreCompound>,
        val sentence: String,
        val basis: List<String>,
    )

    fun advise(i: Input): Advice {
        val basis = mutableListOf<String>()
        i.rulesSource?.let { basis += "règlement : $it" }
        basis += "chronos de qualification non utilisés : seuls les relais de course comparables servent au calcul"
        val where = i.grid?.let { (p, n) -> "P$p" + (n?.let { " sur $it" } ?: "") }
        val prefix = where?.let { "$it : " } ?: "Sans place de grille : "
        fun none(status: Status, text: String) = Advice(status, null, null, emptyMap(), emptySet(), prefix + text, basis)
        if (i.allowed.isEmpty()) return none(Status.RULES_UNKNOWN, "gommes autorisées inconnues pour cette course, pas de conseil de départ.")
        if (i.totalLaps <= 0) return none(Status.RULES_UNKNOWN, "nombre de tours inconnu, pas de conseil de départ.")
        if (i.required.any { it !in i.allowed }) return none(Status.IMPOSSIBLE, "gommes imposées hors des gommes autorisées : règlement contradictoire, pas de conseil.")
        val candidates = i.allowed.sortedBy { it.ordinal }
        val minByTyres = if (i.required.size >= 2) i.required.size - 1 else 0
        val minByLife = i.tyreLife?.takeIf { it > 0 }?.let { (i.totalLaps + it - 1) / it - 1 } ?: 0
        val kMin = maxOf(i.mandatoryStops, minByTyres, minByLife, i.fuelStopsMin)
        val kMax = minOf(if (i.totalLaps > LONG_RACE_LAPS) kMin else kMin + 1, MAX_STOPS, i.totalLaps - 1)
        if (kMin > kMax) return none(Status.IMPOSSIBLE, "trop d'arrêts imposés pour ${i.totalLaps} tours (ou au-delà de $MAX_STOPS arrêts), pas de conseil.")
        basis += "exploration de $kMin à $kMax arrêt(s), chaque arrêt compté avec changement de pneus" +
            (if (i.fuelStopsMin > 0) ", dont ${i.fuelStopsMin} imposé(s) par le carburant" else "")
        if (!i.fuelKnown) basis += "autonomie inconnue : le carburant n'est pas pris en compte, plan non optimisé sur l'essence"
        val tyreStop = i.costs?.cost(StopKind.TYRES_ONLY)
        if (tyreStop == null) basis += "coût d'un arrêt inconnu : seuls les plans au nombre minimal d'arrêts sont comparés"
        val zone = i.grid?.first?.let(GridZone::of)

        // Départs légaux déduits du règlement, sans énumérer : une gomme autorisée peut partir si les gommes imposées
        // restantes tiennent dans les relais suivants (au plus kMax arrêts).
        val legalStarts = candidates.filter { c -> (i.required - c).size <= kMax }.toMutableSet()
        // Seules les gommes chiffrables entrent dans l'énumération (les autres ne donneraient aucun plan) : au plus
        // [MAX_COMPOUNDS] suites de longueur kMax + 1, quel que soit le nombre de gommes autorisées.
        val chiffrable = candidates.filter { c -> i.book[c]?.let { it.paceMs != null && it.slopeMsPerLap != null && it.confidence != Confidence.INSUFFICIENT } == true }
            .sortedByDescending { it in i.required }.take(MAX_COMPOUNDS)
        if (chiffrable.size < candidates.count { c -> i.book[c]?.let { it.paceMs != null && it.slopeMsPerLap != null && it.confidence != Confidence.INSUFFICIENT } == true }) {
            basis += "plus de $MAX_COMPOUNDS gommes chiffrables : seules $MAX_COMPOUNDS sont comparées"
        }
        val best = mutableMapOf<TyreCompound, Plan>()
        for (k in kMin..kMax) {
            if (k > kMin && tyreStop == null) continue
            for (seq in sequences(chiffrable, k + 1)) {
                if (!seq.containsAll(i.required)) continue
                val perf = seq.map { i.book[it] }
                val plan = bestSplit(seq, perf.map { it!! }, i, zone, k, tyreStop?.ms, tyreStop?.spread) ?: continue
                val cur = best[seq.first()]
                if (cur == null || plan.estimatedMs < cur.estimatedMs) best[seq.first()] = plan
            }
        }
        if (legalStarts.isEmpty()) return none(Status.IMPOSSIBLE, "aucun plan ne respecte le règlement (gommes imposées, arrêts), pas de conseil.")
        val missing = legalStarts - best.keys
        missing.forEach { basis += "${it.spoken} : historique insuffisant pour chiffrer un départ" }
        if (zone != null) {
            val measured = best.keys.filter { i.traffic[zone, it] != null }
            basis += if (measured.isEmpty()) "effet de la place $where non mesuré dans ton historique : elle ne change pas le calcul"
            else "pertes de début de course mesurées $where (${zone.label}, tours 2 et 3, estimation provisoire) pour : ${measured.joinToString(", ") { it.spoken }}"
        }
        val legalText = legalStarts.sortedBy { it.ordinal }.joinToString(" ou ") { it.spoken }
        if (best.isEmpty()) {
            return Advice(Status.NO_HISTORY, null, null, best, legalStarts, "${prefix}départ possible en $legalText ; historique insuffisant pour en conseiller un.", basis)
        }
        val ranked = best.values.sortedBy { it.estimatedMs }
        val a = ranked[0]
        fun planText(p: Plan) = p.compounds.drop(1).zip(p.stops).joinToString(", ") { (c, s) -> "${c.hardness?.spoken ?: c.spoken} fin du tour $s" }
            .ifEmpty { "sans changer de pneus" }
        val limited = a.compounds.any { i.book[it]?.confidence == Confidence.PROVISIONAL }
        if (missing.isNotEmpty()) {
            return Advice(
                Status.INCOMPLETE, null, a.start, best, legalStarts,
                "${prefix}parmi les seules gommes chiffrables, départ en ${a.start.spoken} (${planText(a)}) ; " +
                    "${missing.sortedBy { it.ordinal }.joinToString(" et ") { it.spoken }} sans historique : conseil incomplet, à toi de voir.",
                basis,
            )
        }
        if (ranked.size == 1) {
            return Advice(Status.RECOMMENDED, a.start, a.start, best, legalStarts, "${prefix}départ en ${a.start.spoken}, ${planText(a)} : seule option légale chiffrée.", basis)
        }
        val b = ranked[1]
        val gap = b.estimatedMs - a.estimatedMs
        val differing = (1..i.totalLaps).count { a.compoundAt(it) != b.compoundAt(it) }
        val unc = sqrt(sq(a.uncertaintyMs.toDouble()) + sq(b.uncertaintyMs.toDouble()) + sq(MODEL_MS_PER_LAP * differing))
        val seconds = (gap / 1000.0).roundToLong()
        return if (gap >= MIN_GAP_MS && gap > 2 * unc) {
            Advice(
                Status.RECOMMENDED, a.start, a.start, best, legalStarts,
                "${prefix}je conseille de partir en ${a.start.spoken}, ${planText(a)} : environ $seconds s de mieux qu'en ${b.start.spoken}" +
                    (if (limited) ", estimation provisoire" else "") + ". Conseil seulement, dis-moi ce que tu montes.",
                basis,
            )
        } else {
            Advice(
                Status.CLOSE, null, a.start, best, legalStarts,
                "${prefix}options proches entre ${a.start.spoken} et ${b.start.spoken} : pars avec celle que tu préfères.",
                basis,
            )
        }
    }

    /**
     * Meilleur découpage des relais pour une suite de gommes (programmation dynamique sur les tours) : O(gommes × tours²),
     * borné quelle que soit la longueur de la course.
     */
    private fun bestSplit(
        seq: List<TyreCompound>, perf: List<CompoundPerformance>, i: Input, zone: GridZone?, stops: Int, stopMs: Double?, stopSpread: Double?,
    ): Plan? {
        val n = i.totalLaps
        val m = seq.size
        val life = i.tyreLife?.takeIf { it > 0 } ?: n
        fun cost(s: Int, len: Int): Pair<Double, Double> {
            val p = perf[s]
            val pace = p.paceMs!!
            val slope = p.slopeMsPerLap!!
            val ages = len * (len + 1) / 2.0
            var t = pace.mean * len + slope.mean * ages
            var u2 = sq(se(pace) * len) + sq(se(slope) * ages)
            if (s == 0 && zone != null) i.traffic[zone, seq[0]]?.let { tr ->
                val l = minOf(TRAFFIC_LAPS, len)
                t += tr.mean * l
                u2 += sq(se(tr) * l)
            }
            return t to u2
        }
        val inf = Double.MAX_VALUE
        // best[j][L] : meilleur temps des j premiers relais couvrant L tours.
        val bestT = Array(m + 1) { DoubleArray(n + 1) { inf } }
        val bestU = Array(m + 1) { DoubleArray(n + 1) }
        val from = Array(m + 1) { IntArray(n + 1) { -1 } }
        bestT[0][0] = 0.0
        for (j in 1..m) for (l in j..n) for (len in 1..minOf(life, l - (j - 1))) {
            val prev = bestT[j - 1][l - len]
            if (prev == inf) continue
            val (t, u2) = cost(j - 1, len)
            if (prev + t < bestT[j][l]) {
                bestT[j][l] = prev + t
                bestU[j][l] = bestU[j - 1][l - len] + u2
                from[j][l] = l - len
            }
        }
        if (bestT[m][n] == inf) return null
        val ends = mutableListOf<Int>()
        var l = n
        for (j in m downTo 1) {
            ends += l
            l = from[j][l]
        }
        val stopLaps = ends.reversed().dropLast(1)
        var total = bestT[m][n]
        var u2 = bestU[m][n]
        if (stops > 0 && stopMs != null) {
            total += stops * stopMs
            u2 += sq(stops * (stopSpread ?: 0.0))
        }
        return Plan(seq, stopLaps, total.roundToLong(), sqrt(u2).roundToLong())
    }

    /** Suites de [n] gommes prises dans [c] (avec répétition) : au plus [MAX_COMPOUNDS]^([MAX_STOPS] + 1). */
    private fun sequences(c: List<TyreCompound>, n: Int): List<List<TyreCompound>> =
        if (n == 0) listOf(emptyList()) else sequences(c, n - 1).flatMap { s -> c.map { s + it } }

    private fun se(l: Learned): Double = l.spread / sqrt(l.samples.coerceAtLeast(1).toDouble())
    private fun sq(x: Double) = x * x

    const val FRONT_MAX = 4
    const val MIDDLE_MAX = 10

    /** Premiers tours du relais de départ où le trafic pèse (le tour 1 est exclu des tours propres). */
    const val TRAFFIC_LAPS = 3
    const val MIN_TRAFFIC_SESSIONS = 2
    const val MAX_STOPS = 3
    const val LONG_RACE_LAPS = 40
    const val MIN_GAP_MS = 1_000L

    /** Gommes chiffrables comparées au plus (une course personnalisée peut en autoriser beaucoup plus). */
    const val MAX_COMPOUNDS = 4

    /** Le conseil s'affiche avant le départ : qualification (aucun tour annoncé) ou grille (tour 0), jamais en course. */
    fun showable(totalLaps: Int?, currentLap: Int?): Boolean = (totalLaps ?: 0) <= 0 || (currentLap ?: 0) == 0

    /** Erreur de modèle par tour roulé sur une gomme différente entre deux plans (trafic, piste, dégradation non linéaire). */
    const val MODEL_MS_PER_LAP = 150.0
}
