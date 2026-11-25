package com.gt7pitwall.core.strategy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * V515 — Strategy Engine : comparateur de stratégies COMPLÈTES jusqu'à l'arrivée, carburant, pneus et arrêts calculés
 * ENSEMBLE (jamais le carburant puis les pneus séparément).
 *
 * Méthode (principes des stratèges F1/WEC et du simulateur open source TUMFTM/race-simulation, idées seulement) :
 * temps au tour = rythme + dégradation × âge des pneus (si mesurée ou apprise) + effet du carburant embarqué (si
 * calibré, sinon 0 et dit) + pénalité du tour de sortie en pneus neufs (si démontrée, sinon 0 et dit) ; plus le coût de
 * chaque arrêt selon son contenu ([StopCosts]) et du remplissage (si son débit est connu) ; plus le coût en temps d'une
 * économie de carburant (ESTIMATION expérimentale de 3,2 s par unité, V512). Les règlements F1/WEC ne sont jamais
 * appliqués : seules les contraintes de la course GT7 (tours, réservoir, arrêts et gommes imposés) comptent.
 *
 * Exploration BORNÉE : toutes les suites d'au plus [Input.maxStops] arrêts (fin de tour), chacun avec ou sans pneus,
 * avec l'économie minimale qui rend un relais faisable (≤ 10 %). Au-delà de [MAX_EVALUATIONS] combinaisons, le nombre
 * d'arrêts explorés est réduit et c'est dit. Calcul pur, appelé aux moments stratégiques (pas à chaque paquet).
 *
 * Adversaires : GT7 ne transmet ni écarts ni arrêts adverses. Undercut et overcut ne sont donc que des POTENTIELS
 * CONDITIONNELS (« si l'adversaire est à moins de X s ») : jamais un dépassement annoncé.
 */
object StrategyEngine {
    /** Marge de carburant gardée à chaque relais et à l'arrivée (tours) : celle du plan (RacePlanner). */
    const val SAFETY_LAPS = RacePlanner.SAFETY_LAPS
    const val MAX_EVALUATIONS = 120_000
    const val MIN_UNCERTAINTY_MS = 500.0
    /** Gain exigé d'un arrêt de plus, en plus de deux fois l'incertitude (comme le comparateur V512). */
    const val MIN_EXTRA_STOP_GAIN_MS = StrategyOptions.MIN_EXTRA_STOP_GAIN_MS.toDouble()

    data class Input(
        /** Tour en cours (≥ 1). */
        val currentLap: Int,
        /** Part du tour en cours restant à parcourir (0..1). */
        val lapFractionLeft: Float,
        val totalLaps: Int,
        val fuelNow: Float,
        val tank: Float,
        /** Consommation par tour ; null : inconnue (aucun calcul de carburant). */
        val perLap: Float?,
        val perLapMeasured: Boolean,
        val tyreAge: Int,
        val tyreAgeCertain: Boolean = true,
        /** Durée de vie des pneus DÉCLARÉE par le pilote (tours), sinon null. */
        val tyreLifeLaps: Int? = null,
        val paceMs: Double?,
        val paceSpreadMs: Double = 0.0,
        /** Dégradation par tour d'âge des pneus (ms) ; null : inconnue (le gain des pneus neufs n'est pas chiffré). */
        val slopeMsPerLap: Double? = null,
        val slopeSpread: Double = 0.0,
        /** Pénalité du tour de sortie en pneus neufs (ms) ; null : non démontrée (comptée 0, dite). */
        val outLapPenaltyMs: Double? = null,
        val outLapSpread: Double = 0.0,
        /** Effet du carburant embarqué sur le chrono (ms par unité) ; null : non calibré (compté 0, dit). */
        val fuelEffectMsPerUnit: Double? = null,
        val stopCosts: StopCosts? = null,
        /** Temps de remplissage (ms par unité) ; null : inconnu (non compté, dit). */
        val refuelMsPerUnit: Double? = null,
        val mandatoryStopsLeft: Int = 0,
        /** Une gomme imposée reste à monter : au moins un changement de pneus. */
        val mustChangeTyres: Boolean = false,
        val maxSaving: Float = FuelIntelligence.MAX_SAVING_FRACTION.toFloat(),
        val saveCostMsPerUnit: Double = FuelIntelligence.LIFT_COST_MS_PER_UNIT,
        val saveCostSpread: Double = FuelIntelligence.LIFT_COST_MS_PER_UNIT,
        /** Plan actuel (scénario A) : tours d'arrêt et changement de pneus. */
        val plannedStops: List<PlannedStop> = emptyList(),
        /** L'entrée des stands est-elle encore possible à ce tour ? (faux : premier arrêt au tour suivant au plus tôt) */
        val pitEntryStillPossible: Boolean = true,
        val maxStops: Int = 3,
    ) {
        val firstStopLap: Int get() = if (pitEntryStillPossible) currentLap else currentLap + 1
    }

    enum class Kind(val code: Char, val label: String) {
        CURRENT('A', "stratégie actuelle"),
        EARLY('B', "arrêt anticipé (undercut potentiel)"),
        LATE('C', "arrêt retardé (overcut potentiel)"),
        EXTEND_SAVE('D', "relais prolongé avec économie de carburant"),
        TYRES_AT_REFUEL('E', "pneus changés au ravitaillement"),
        REFUEL_NO_TYRES('F', "ravitaillement sans pneus"),
        TYRES_NO_FUEL('G', "pneus sans carburant"),
        EXTRA_STOP('H', "arrêt supplémentaire"),
        NO_STOP('I', "aucun nouvel arrêt"),
        BEST('J', "meilleure stratégie trouvée"),
    }

    data class StopPlan(val lap: Int, val tyres: Boolean, val fuelAdded: Float?)

    data class Stint(val fromLap: Int, val toLap: Int, val laps: Float, val tyreAgeStart: Int, val tyreAgeEnd: Int, val fuelUsed: Float?, val saving: Float)

    data class Scenario(
        val kind: Kind,
        val stops: List<StopPlan>,
        val stints: List<Stint>,
        val feasible: Boolean,
        val issues: List<String>,
        /** Temps restant estimé jusqu'à l'arrivée (ms) ; null : non chiffrable. */
        val totalMs: Double?,
        /** Écart avec le scénario A (ms, négatif = plus rapide) et son incertitude ; null : non comparable. */
        val deltaMs: Double?,
        val uncertaintyMs: Double?,
        /** Plus petite marge de carburant (tours) à un arrêt ou à l'arrivée. */
        val minMarginLaps: Float?,
        /** Hausse relative de consommation qui rendrait ce plan infaisable (0,05 = +5 %). */
        val breaksIfConsumptionUp: Float?,
        /** Économie de carburant demandée (fraction, 0 si aucune) et son coût estimé (ms). */
        val saving: Float,
        val savingCostMs: Double,
        /** Ce qui n'est pas chiffré dans ce scénario (dit, jamais remplacé par un chiffre inventé). */
        val unknowns: List<String>,
        /** Conditions qui rendraient ce scénario invalide. */
        val invalidIf: List<String>,
    ) {
        val stopLaps: List<Int> get() = stops.map { it.lap }
        val tyreStops: Int get() = stops.count { it.tyres }
        fun describe(): String = if (stops.isEmpty()) "aucun arrêt" + if (saving > 0f) " (économie ${pct(saving)})" else "" else stops.joinToString(", ") { s ->
            "fin du tour ${s.lap}" + (if (s.tyres) " pneus" else "") + (s.fuelAdded?.let { if (it > 0.05f) " +${it.roundToInt()} carburant" else " sans carburant" } ?: "")
        } + if (saving > 0f) ", économie ${pct(saving)}" else ""
    }

    /** Fenêtre stratégique du prochain arrêt : tours possibles, tour préféré, et pourquoi. */
    data class Window(val from: Int, val to: Int, val preferred: Int?, val basis: String)

    /** Undercut / overcut : potentiels conditionnels (aucune référence adverse fiable dans GT7). */
    data class Cut(
        val tyresLosingPace: Boolean?,
        val freshTyreGainMs: Double?,
        val outLapCostMs: Double?,
        val fuelAllows: Boolean,
        val nextStintNeedsSaving: Float?,
        val nextStintNeedsExtraStop: Boolean,
        val deltaMs: Double?,
        val uncertaintyMs: Double?,
        val beatsUncertainty: Boolean,
        val opponentReference: Boolean,
        val verdict: String,
    )

    data class Report(
        val scenarios: List<Scenario>,
        val reference: Scenario?,
        val best: Scenario?,
        /** Le meilleur ne bat pas la stratégie actuelle au-delà de l'incertitude : on garde A. */
        val indistinguishable: Boolean,
        val recommended: Scenario?,
        val window: Window?,
        val undercut: Cut?,
        val overcut: Cut?,
        val evaluated: Int,
        val partial: Boolean,
        val notes: List<String>,
    ) {
        fun scenario(k: Kind): Scenario? = scenarios.firstOrNull { it.kind == k }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Évalue une suite d'arrêts (fin de tour, pneus oui/non) : faisabilité carburant, pneus, temps. */
    fun evaluate(i: Input, stopsIn: List<Pair<Int, Boolean>>, kind: Kind = Kind.BEST): Scenario {
        val stops = stopsIn.sortedBy { it.first }
        val issues = mutableListOf<String>()
        val unknowns = mutableListOf<String>()
        val invalidIf = mutableListOf<String>()
        val perLap = i.perLap?.takeIf { it > 0f }
        // Bornes : tours d'arrêt valides et distincts.
        if (stops.any { it.first < i.firstStopLap }) issues += if (!i.pitEntryStillPossible && stops.any { it.first == i.currentLap }) "entrée des stands déjà passée à ce tour" else "tour d'arrêt déjà passé"
        if (stops.any { it.first >= i.totalLaps }) issues += "pas d'arrêt à la fin du dernier tour"
        if (stops.map { it.first }.distinct().size != stops.size) issues += "deux arrêts au même tour"
        if (stops.size < i.mandatoryStopsLeft) issues += "arrêts obligatoires non respectés (${stops.size} sur ${i.mandatoryStopsLeft})"
        if (i.mustChangeTyres && stops.none { it.second }) issues += "gomme imposée jamais montée"
        // Relais.
        val bounds = listOf(i.currentLap - 1) + stops.map { it.first } + i.totalLaps
        val stints = mutableListOf<Stint>()
        var age = i.tyreAge
        var fuel = i.fuelNow
        var minMargin = Float.MAX_VALUE
        var breakFrac = Float.MAX_VALUE
        var savedUnits = 0.0
        var maxSave = 0f
        val stopPlans = mutableListOf<StopPlan>()
        for (s in 0 until bounds.size - 1) {
            val from = bounds[s] + 1
            val to = bounds[s + 1]
            val laps = if (s == 0) (to - i.currentLap) + i.lapFractionLeft else (to - from + 1).toFloat()
            val ageStart = age
            val ageEnd = age + (to - from + 1) - 1
            i.tyreLifeLaps?.let { life -> if (ageEnd + 1 > life) issues += "pneus au-delà de leur durée déclarée ($life tours) au relais ${s + 1}" }
            var saving = 0f
            var used: Float? = null
            if (perLap != null) {
                val need = laps * perLap
                val reserve = SAFETY_LAPS * perLap
                // Carburant disponible pour ce relais : à bord (premier relais) ou après le plein de l'arrêt précédent.
                val available = if (s == 0) fuel else {
                    // Plein : juste ce qu'il faut pour ce relais et la réserve, borné au réservoir.
                    val arrive = fuel
                    val add = (need + reserve - arrive).coerceIn(0f, max(0f, i.tank - arrive))
                    stopPlans[s - 1] = stopPlans[s - 1].copy(fuelAdded = add)
                    arrive + add
                }
                // Tolérance d'arrondi (1/1000 d'unité) : pas d'économie « fantôme » de 0 %.
                if (available - reserve < need - 1e-3f) {
                    // Économie minimale pour tenir ce relais avec la réserve.
                    val s0 = 1f - (available - reserve) / need
                    if (s0 <= i.maxSaving + 1e-4f) saving = s0.coerceAtLeast(0f).takeIf { it >= 0.005f } ?: 0f else issues += if (s == 0) "carburant insuffisant pour atteindre le tour $to" else "relais ${s + 1} plus long qu'un plein"
                }
                used = need * (1f - saving)
                savedUnits += need * saving
                maxSave = max(maxSave, saving)
                val margin = (available - used) / perLap
                minMargin = min(minMargin, margin)
                if (saving == 0f) breakFrac = min(breakFrac, ((available - reserve) / need) - 1f)
                fuel = available - used
            }
            stints += Stint(from, to, laps, ageStart, ageEnd, used, saving)
            if (s < stops.size) {
                stopPlans += StopPlan(stops[s].first, stops[s].second, null)
                age = if (stops[s].second) 0 else ageEnd + 1
            }
        }
        if (perLap == null) unknowns += "consommation inconnue : carburant non vérifié"
        // Temps.
        var total: Double? = null
        val costs = i.stopCosts
        val pace = i.paceMs
        if (pace != null) {
            var t = 0.0
            for ((idx, st) in stints.withIndex()) {
                for (k in 0 until (st.toLap - st.fromLap + 1)) {
                    val frac = if (idx == 0 && k == 0) i.lapFractionLeft.toDouble() else 1.0
                    var lap: Double = pace
                    i.slopeMsPerLap?.let { lap += it * (st.tyreAgeStart + k) }
                    t += lap * frac
                }
                // Tour de sortie après un changement de pneus.
                if (idx > 0 && stopPlans[idx - 1].tyres) i.outLapPenaltyMs?.let { t += it }
            }
            i.fuelEffectMsPerUnit?.let { eff ->
                // Carburant embarqué : intégré relais par relais (moyenne du relais × tours).
                var f = i.fuelNow
                for ((idx, st) in stints.withIndex()) {
                    if (idx > 0) f += stopPlans[idx - 1].fuelAdded ?: 0f
                    val u = st.fuelUsed ?: 0f
                    t += eff * (f - u / 2) * st.laps
                    f -= u
                }
            }
            var stopUnknown = false
            for (sp in stopPlans) {
                val k = StopKind.of(fuel = (sp.fuelAdded ?: 1f) > 0.05f, tyres = sp.tyres)
                val c = costs?.cost(k)
                if (c == null) stopUnknown = true else t += c.ms
                i.refuelMsPerUnit?.let { r -> t += r * (sp.fuelAdded ?: 0f) }
            }
            t += savedUnits * i.saveCostMsPerUnit
            total = if (stopUnknown) null else t
            if (stopUnknown) unknowns += "coût d'un arrêt inconnu"
        } else unknowns += "rythme inconnu : temps non chiffré"
        if (i.slopeMsPerLap == null) unknowns += "dégradation des pneus inconnue : gain des pneus neufs non chiffré"
        if (i.outLapPenaltyMs == null && stopPlans.any { it.tyres }) unknowns += "tour de sortie en pneus neufs : effet non démontré (compté 0)"
        if (i.fuelEffectMsPerUnit == null) unknowns += "effet du poids du carburant non calibré (compté 0)"
        if (i.refuelMsPerUnit == null && stopPlans.any { (it.fuelAdded ?: 0f) > 0.05f }) unknowns += "temps de remplissage non compté"
        if (savedUnits > 0) unknowns += "coût de l'économie : estimation expérimentale de ${(i.saveCostMsPerUnit / 1000).roundToInt()} s par unité (un seul tour réel)"
        val feasible = issues.isEmpty()
        if (feasible && perLap != null && breakFrac < 1f) invalidIf += "consommation en hausse de plus de ${pct(breakFrac.coerceAtLeast(0f))}"
        if (feasible && maxSave > 0f) invalidIf += "économie de ${pct(maxSave)} non tenue"
        if (feasible && stopPlans.isNotEmpty()) invalidIf += "arrêt manqué au tour ${stopPlans.first().lap}"
        return Scenario(
            kind, stopPlans, stints, feasible, issues.distinct(), total, null, null,
            if (perLap != null && minMargin != Float.MAX_VALUE) minMargin else null,
            if (perLap != null && breakFrac != Float.MAX_VALUE) breakFrac.coerceAtLeast(0f) else null,
            maxSave, savedUnits * i.saveCostMsPerUnit, unknowns.distinct(), invalidIf,
        )
    }

    /** Écart avec la référence et son incertitude : seuls les éléments qui diffèrent entre les deux comptent. */
    private fun withDelta(i: Input, s: Scenario, ref: Scenario?): Scenario {
        if (ref == null || s.totalMs == null || ref.totalMs == null) return s.copy(deltaMs = null, uncertaintyMs = null)
        fun ageSum(x: Scenario) = x.stints.sumOf { st -> (st.tyreAgeStart..st.tyreAgeEnd).sum().toDouble() }
        var v = 0.0
        val slopeU = i.slopeSpread * abs(ageSum(s) - ageSum(ref))
        v += slopeU * slopeU
        val kinds = { x: Scenario -> x.stops.map { StopKind.of((it.fuelAdded ?: 1f) > 0.05f, it.tyres) } }
        val a = kinds(s).toMutableList()
        val b = kinds(ref).toMutableList()
        val common = a.filter { b.remove(it) }
        val differing = kinds(s).toMutableList().also { l -> common.forEach { l.remove(it) } } + b
        differing.forEach { k -> i.stopCosts?.cost(k)?.spread?.let { v += it * it } }
        val saveU = abs(s.savingCostMs - ref.savingCostMs) / i.saveCostMsPerUnit.coerceAtLeast(1.0) * i.saveCostSpread
        v += saveU * saveU
        val outU = i.outLapSpread * abs(s.tyreStops - ref.tyreStops)
        v += outU * outU
        return s.copy(deltaMs = s.totalMs - ref.totalMs, uncertaintyMs = max(sqrt(v), MIN_UNCERTAINTY_MS))
    }

    /** Toutes les suites d'arrêts faisables, bornées ; renvoie (scénarios, nombre évalué, exploration partielle). */
    private fun explore(i: Input): Triple<List<Scenario>, Int, Boolean> {
        val candidates = (i.firstStopLap until i.totalLaps).toList()
        val out = mutableListOf<Scenario>()
        var evaluated = 0
        var partial = false
        var maxN = min(i.maxStops, candidates.size)
        fun combos(n: Int): Double {
            var c = 1.0
            for (k in 0 until n) c = c * (candidates.size - k) / (k + 1)
            return c * (1 shl n)
        }
        while (maxN > 1 && (0..maxN).sumOf { combos(it) } > MAX_EVALUATIONS) { maxN--; partial = true }
        fun rec(start: Int, chosen: MutableList<Int>, n: Int) {
            if (chosen.size == n) {
                for (mask in 0 until (1 shl n)) {
                    val stops = chosen.mapIndexed { idx, l -> l to ((mask shr idx) and 1 == 1) }
                    val s = evaluate(i, stops)
                    evaluated++
                    if (s.feasible) out += s
                }
                return
            }
            for (k in start until candidates.size) {
                chosen += candidates[k]
                rec(k + 1, chosen, n)
                chosen.removeAt(chosen.size - 1)
            }
        }
        for (n in 0..maxN) rec(0, mutableListOf(), n)
        return Triple(out, evaluated, partial)
    }

    fun analyze(i: Input): Report {
        val notes = mutableListOf<String>()
        if (i.totalLaps <= 0 || i.currentLap > i.totalLaps) return Report(emptyList(), null, null, true, null, null, null, null, 0, false, listOf("course terminée ou nombre de tours inconnu"))
        if (i.currentLap == i.totalLaps) notes += "dernier tour : plus aucun arrêt possible"
        val planned = i.plannedStops.filter { it.endOfLap >= i.currentLap }.map { it.endOfLap to it.changeTyres }
        val refRaw = evaluate(i, planned, Kind.CURRENT)
        val (all, evaluated, partial) = explore(i)
        if (partial) notes += "exploration partielle : nombre d'arrêts réduit pour rester rapide"
        val ref = refRaw
        fun d(s: Scenario) = withDelta(i, s, ref)
        val firstRef = planned.firstOrNull()
        val timed = all.map(::d)
        // À temps égal (à 1 ms près), le plan le plus proche du plan actuel : pas de changement sans raison.
        fun closeness(x: Scenario): Int = abs((x.stopLaps.firstOrNull() ?: 0) - (firstRef?.first ?: 0)) + abs(x.stops.size - planned.size) * 10
        fun bestOf(xs: List<Scenario>) = xs.filter { it.totalMs != null }
            .minWithOrNull(compareBy<Scenario> { Math.round(it.totalMs!!) }.thenBy { closeness(it) }) ?: xs.firstOrNull()
        val scenarios = mutableListOf(d(ref))
        if (firstRef != null) {
            fun shifted(delta: Int, kind: Kind) = planned.mapIndexed { idx, (l, t) -> if (idx == 0) (l + delta) to t else l to t }
                .let { evaluate(i, it, kind) }.let(::d)
            scenarios += listOf(shifted(-1, Kind.EARLY), shifted(-2, Kind.EARLY)).let { c -> c.filter { it.feasible }.minByOrNull { it.totalMs ?: Double.MAX_VALUE } ?: c.first() }
            scenarios += listOf(shifted(1, Kind.LATE), shifted(2, Kind.LATE)).let { c -> c.filter { it.feasible }.minByOrNull { it.totalMs ?: Double.MAX_VALUE } ?: c.first() }
            // E / F : même calendrier, pneus changés au premier ravitaillement / jamais changés.
            scenarios += d(evaluate(i, planned.mapIndexed { idx, (l, t) -> l to (t || idx == 0) }, Kind.TYRES_AT_REFUEL))
            scenarios += d(evaluate(i, planned.map { (l, _) -> l to false }, Kind.REFUEL_NO_TYRES))
        }
        // D : relais prolongé grâce à une économie (moins d'arrêts ou premier arrêt plus tard que A).
        bestOf(timed.filter { it.saving > 0f && (it.stops.size < planned.size || (it.stopLaps.firstOrNull() ?: Int.MAX_VALUE) > (firstRef?.first ?: Int.MAX_VALUE)) })
            ?.let { scenarios += it.copy(kind = Kind.EXTEND_SAVE) }
            ?: scenarios.add(Scenario(Kind.EXTEND_SAVE, emptyList(), emptyList(), false, listOf("aucune économie réaliste (≤ ${pct(i.maxSaving)}) ne prolonge le relais"), null, null, null, null, null, 0f, 0.0, emptyList(), emptyList()))
        // G : un arrêt pour les pneus sans carburant.
        bestOf(timed.filter { s -> s.stops.any { it.tyres && (it.fuelAdded ?: 1f) <= 0.05f } })?.let { scenarios += it.copy(kind = Kind.TYRES_NO_FUEL) }
        // H : un arrêt de plus que A.
        bestOf(timed.filter { it.stops.size == planned.size + 1 })?.let { scenarios += it.copy(kind = Kind.EXTRA_STOP) }
        // I : aucun nouvel arrêt.
        scenarios += timed.firstOrNull { it.stops.isEmpty() }?.copy(kind = Kind.NO_STOP) ?: d(evaluate(i, emptyList(), Kind.NO_STOP))
        // J : meilleure stratégie trouvée (plusieurs arrêts si la course l'exige).
        val best = bestOf(timed.filter { it.feasible })?.copy(kind = Kind.BEST)
        best?.let { scenarios += it }
        // Recommandation : le meilleur seulement s'il bat A au-delà de l'incertitude (et de 2 s + 2× pour un arrêt de plus).
        val refOk = ref.feasible
        val bestBeats = best != null && best.deltaMs != null && best.uncertaintyMs != null && run {
            val extra = best.stops.size > ref.stops.size || best.tyreStops > ref.tyreStops
            val need = if (extra) max(2 * best.uncertaintyMs, MIN_EXTRA_STOP_GAIN_MS) else best.uncertaintyMs
            -best.deltaMs > need
        }
        val recommended = when {
            refOk && !bestBeats -> scenarios.first()
            bestBeats -> best
            // Plan actuel infaisable : la meilleure stratégie faisable (même sans chiffrage), sinon rien.
            else -> best ?: timed.firstOrNull()
        }
        if (!refOk) notes += "le plan actuel n'est pas faisable : ${ref.issues.joinToString(", ")}"
        return Report(
            scenarios.distinctBy { it.kind }, ref, best, refOk && !bestBeats, recommended,
            window(i, timed, best), cut(i, scenarios, ref, early = true), cut(i, scenarios, ref, early = false),
            evaluated, partial, notes,
        )
    }

    /** Fenêtre du prochain arrêt : premiers arrêts dont le meilleur plan reste dans l'incertitude du meilleur global. */
    private fun window(i: Input, timed: List<Scenario>, best: Scenario?): Window? {
        val withStop = timed.filter { it.stops.isNotEmpty() }
        if (withStop.isEmpty()) return null
        val byFirst = withStop.groupBy { it.stopLaps.first() }
        // Référence de la fenêtre : le meilleur plan AVEC arrêt (même si aller au bout sans arrêt est aussi possible).
        val b = withStop.filter { it.totalMs != null }.minByOrNull { it.totalMs!! }
        if (b != null) {
            val ok = byFirst.mapNotNull { (lap, xs) ->
                val m = xs.filter { it.totalMs != null }.minByOrNull { it.totalMs!! } ?: return@mapNotNull null
                val u = withDelta(i, m, b).uncertaintyMs ?: MIN_UNCERTAINTY_MS
                if (m.totalMs!! - b.totalMs!! <= u) lap else null
            }
            if (ok.isNotEmpty()) return Window(ok.min(), ok.max(), b.stopLaps.first(), "temps estimé dans l'incertitude du meilleur plan avec arrêt")
        }
        // Sans temps : seulement les tours où un arrêt reste faisable (carburant, obligations).
        val laps = byFirst.keys
        return Window(laps.min(), laps.max(), null, "faisabilité seulement : temps non chiffrable")
    }

    /** Undercut (arrêt avancé) ou overcut (arrêt retardé) : potentiel conditionnel, jamais un dépassement promis. */
    private fun cut(i: Input, scenarios: List<Scenario>, ref: Scenario, early: Boolean): Cut? {
        if (ref.stops.isEmpty()) return null
        val alt = scenarios.firstOrNull { it.kind == (if (early) Kind.EARLY else Kind.LATE) } ?: return null
        val slope = i.slopeMsPerLap
        val losing = slope?.let { it > max(2 * i.slopeSpread, 50.0) }
        val gain = slope?.let { it * i.tyreAge }?.takeIf { early }
        val nextSave = alt.stints.drop(1).maxOfOrNull { it.saving }?.takeIf { it > 0f }
        val extra = !alt.feasible && alt.issues.any { "plein" in it }
        val beats = alt.feasible && alt.deltaMs != null && alt.uncertaintyMs != null && -alt.deltaMs > alt.uncertaintyMs
        val verdict = when {
            !alt.feasible -> "${if (early) "Undercut" else "Overcut"} impossible : ${alt.issues.firstOrNull() ?: "plan infaisable"}."
            alt.deltaMs == null -> "${if (early) "Undercut" else "Overcut"} : faisable, mais gain non chiffrable (${alt.unknowns.firstOrNull() ?: "données manquantes"})."
            beats -> "${if (early) "Undercut" else "Overcut"} : gain estimé ${StrategyOptions.seconds((-alt.deltaMs).roundToLong())} pour nous, au-delà de l'incertitude ; face à un adversaire, seulement s'il est dans cet écart (écart inconnu de GT7)."
            else -> "${if (early) "Undercut" else "Overcut"} : pas de gain démontré (${StrategyOptions.seconds(alt.deltaMs.roundToLong())}, incertitude ${StrategyOptions.seconds(alt.uncertaintyMs!!.roundToLong())})."
        }
        return Cut(losing, gain, i.outLapPenaltyMs, alt.feasible, nextSave, extra, alt.deltaMs, alt.uncertaintyMs, beats, opponentReference = false, verdict = verdict)
    }

    private fun pct(f: Float) = "${(f * 100).roundToInt()} %"
}
