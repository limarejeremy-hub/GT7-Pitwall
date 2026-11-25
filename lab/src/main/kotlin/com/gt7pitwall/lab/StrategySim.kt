package com.gt7pitwall.lab

import com.gt7pitwall.core.strategy.Cost
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.Nature
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.StrategyAssurance
import com.gt7pitwall.core.strategy.StrategyEngine
import com.gt7pitwall.core.strategy.StrategyOptions
import com.gt7pitwall.core.strategy.StrategySimulations
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pitwall Lab — `strategy-sim [trace...]` (V515-A) : simulateur de stratégies.
 *
 * 1. Les 14 scénarios EXPLICITEMENT SIMULÉS de [StrategySimulations] : moteur + contrôleur, attendu et obtenu.
 * 2. Traces RÉELLES : à la fin de chaque tour, l'état est reconstruit depuis les paquets (MESURE) puis on compare
 *    la stratégie réellement suivie (observée), la recommandation V514 ([StrategyOptions]) et la candidate V515
 *    ([StrategyEngine] + [StrategyAssurance]). Ce qui n'a pas été couru est CONTREFACTUEL, jamais une victoire démontrée.
 *    Erreur vérifiable : temps restant prévu par V515 contre temps réellement mis (chronos GT7).
 *
 * Hypothèses de reconstruction, dites dans le rapport : arrêts obligatoires = arrêts sans plein observés (règlement
 * inconnu de la trace) ; coût d'un arrêt = perte mesurée sur une course PRÉCÉDENTE de la même trace, sinon inconnu ;
 * aucune confirmation de pneus (âge incertain après un arrêt) ; dégradation = pente des chronos propres du relais.
 */
object StrategySim {
    fun run(traces: List<File>): JSONObject {
        val sim = JSONArray()
        for (c in StrategySimulations.cases) {
            val r = StrategyEngine.analyze(c.input)
            val d = StrategyAssurance().review(StrategySimulations.state(c), r)
            sim.put(JSONObject().put("cas", c.id).put("titre", c.title).put("nature", "SIMULATION")
                .put("attendu", c.expectation)
                .put("recommande", d.chosen?.describe() ?: JSONObject.NULL)
                .put("verdict", d.verdict.label)
                .put("phrase_vega", d.statement)
                .put("fenetre", r.window?.let { "tours ${it.from}–${it.to}" + (it.preferred?.let { p -> ", tour $p préféré" } ?: "") + " (${it.basis})" } ?: JSONObject.NULL)
                .put("undercut", r.undercut?.verdict ?: JSONObject.NULL)
                .put("overcut", r.overcut?.verdict ?: JSONObject.NULL)
                .put("scenarios", JSONArray(r.scenarios.map { s ->
                    JSONObject().put("code", s.kind.code.toString()).put("nom", s.kind.label).put("faisable", s.feasible)
                        .put("plan", s.describe()).put("ecart_s", s.deltaMs?.let { Math.round(it / 100) / 10.0 } ?: JSONObject.NULL)
                        .put("incertitude_s", s.uncertaintyMs?.let { Math.round(it / 100) / 10.0 } ?: JSONObject.NULL)
                        .put("problemes", JSONArray(s.issues))
                }))
                .put("combinaisons_evaluees", r.evaluated))
        }
        val real = JSONArray()
        val summary = mutableListOf<JSONObject>()
        for (f in traces) {
            val races = RaceLaps.load(f).filter { it.complete }
            val measuredLoss = mutableListOf<Double>() // pertes au stand mesurées sur les courses PRÉCÉDENTES de la trace
            races.forEachIndexed { ri, race -> real.put(replay(f.name, ri + 1, race, measuredLoss.toList(), summary)); measuredLoss += pitLosses(race) }
        }
        return JSONObject()
            .put("nature", "Simulateur de stratégies V515 : scénarios SIMULÉS et traces RÉELLES (MESURE) avec comparaisons CONTREFACTUELLES. Aucun résultat simulé n'est une victoire démontrée.")
            .put("simulations", sim)
            .put("traces_reelles", real)
            .put("synthese", JSONArray(summary))
    }

    /** Pertes au stand mesurées dans une course : chronos des tours d'entrée et d'arrêt moins 2 tours propres médians. */
    private fun pitLosses(race: RaceLaps.Race): List<Double> {
        val med = RaceLaps.median(race.laps.filter { it.clean }.map { it.timeMs!!.toDouble() }) ?: return emptyList()
        return race.laps.filter { it.stopped }.mapNotNull { s ->
            val before = race.laps.firstOrNull { it.n == s.n - 1 } ?: return@mapNotNull null
            val a = before.timeMs ?: return@mapNotNull null
            val b = s.timeMs ?: return@mapNotNull null
            (a + b - 2 * med).takeIf { it > 0 }
        }
    }

    private fun replay(trace: String, idx: Int, race: RaceLaps.Race, priorLosses: List<Double>, summary: MutableList<JSONObject>): JSONObject {
        val laps = race.laps
        val total = race.totalLaps
        val tank = laps.first().samples.first().p.fuelCapacity.takeIf { it > 0f } ?: 100f
        val actualStops = laps.filter { it.stopped }.map { it.n }
        val mandatory = laps.count { it.stopped && !it.refuelled }
        val loss = RaceLaps.median(priorLosses)
        val costs = loss?.let { StopCosts(Cost(it, maxOf(3_000.0, it * 0.2), "perte mesurée sur ${priorLosses.size} arrêt(s) d'une course précédente de la trace")) }
        val rows = JSONArray()
        val errors = mutableListOf<Double>()
        var v514Changes = 0
        var v515Changes = 0
        var lastV514: List<Int>? = null
        var lastV515: List<Int>? = null
        val assurance = StrategyAssurance()
        for (k in 2 until total) {
            val done = laps.filter { it.n <= k }
            if (done.size < k) continue
            val stopsDone = actualStops.count { it <= k }
            val fuelNow = done.last().fuelEnd
            val per = done.filter { !it.stopped && !it.beforeStop && !it.refuelled && (it.fuelStart - it.fuelEnd) > 0f }.takeLast(3)
                .map { (it.fuelStart - it.fuelEnd).toDouble() }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            val clean = done.filter { it.clean }
            val pace = RaceLaps.median(clean.map { it.timeMs!!.toDouble() })
            val lastStop = actualStops.filter { it <= k }.maxOrNull()
            val stintClean = clean.filter { lastStop == null || it.n > lastStop + 1 }
            val slope = if (stintClean.size >= 4) RaceLaps.slope(stintClean.map { it.n.toDouble() }, stintClean.map { it.timeMs!!.toDouble() }) else null
            val slopeSe = slope?.let { b ->
                val x = stintClean.map { it.n.toDouble() }; val y = stintClean.map { it.timeMs!!.toDouble() }
                val mx = x.average(); val my = y.average()
                sqrt(x.indices.sumOf { val e = y[it] - (my + b * (x[it] - mx)); e * e } / (x.size - 2) / x.sumOf { (it - mx) * (it - mx) })
            }
            val tyreAge = k - (lastStop ?: 0)
            // V514 : plan + comparaison existants.
            val rules = RaceRules(totalLaps = total, mandatoryStops = mandatory)
            val pin = RacePlanner.Input(
                rules = rules, gameTotalLaps = total, currentLap = k + 1, currentLapRemaining = 1f, fuelNow = fuelNow, tank = tank,
                perLap = per, fuelSource = DataSource.MEASURED, avgLapMs = pace?.toInt(), raceElapsedMs = null, stopsDone = stopsDone,
                tyreAgeLaps = tyreAge, previousStops = lastV514?.filter { it > k } ?: emptyList(),
            )
            val v514Plan = RacePlanner.plan(pin)
            val v514 = StrategyOptions.compare(pin, StrategyOptions.Pace(pace, 0.0, slope, slopeSe ?: 0.0, stopCosts = costs), emptyList(), emptyList())
            val v514Stops = v514?.recommendedOption?.stopLaps ?: v514Plan?.stops?.map { it.endOfLap }
            // V515 : moteur + contrôleur, plan actuel = plan V514.
            val input = StrategyEngine.Input(
                currentLap = k + 1, lapFractionLeft = 1f, totalLaps = total, fuelNow = fuelNow, tank = tank, perLap = per,
                perLapMeasured = per != null, tyreAge = tyreAge, tyreAgeCertain = lastStop == null, paceMs = pace,
                slopeMsPerLap = slope, slopeSpread = slopeSe ?: 0.0, stopCosts = costs,
                mandatoryStopsLeft = (mandatory - stopsDone).coerceAtLeast(0), plannedStops = v514Plan?.stops ?: emptyList(),
            )
            val report = StrategyEngine.analyze(input)
            // Trace réelle : valeurs MESURÉES (paquets), règlement reconstitué = HYPOTHÈSE (non validé).
            val state = StrategySimulations.state(StrategySimulations.Case("reel", "réel", input, expectation = "")).copy(
                perLap = com.gt7pitwall.core.strategy.Sourced(per, "unités/tour", "moyenne des 3 derniers tours hors stands (MESURE)", if (per != null) Nature.MESURE else Nature.INCONNU),
                paceMs = com.gt7pitwall.core.strategy.Sourced(pace, "ms", "médiane des tours propres (MESURE)", if (pace != null) Nature.MESURE else Nature.INCONNU, 1_000_000L),
                fuelNow = com.gt7pitwall.core.strategy.Sourced(fuelNow, "unités", "GT7 (MESURE)", Nature.MESURE, 1_000_000L),
                rulesValid = com.gt7pitwall.core.strategy.Sourced(false, "", "règlement reconstitué d'après la course (HYPOTHÈSE)", Nature.HYPOTHESE),
                rulesIssues = listOf("règlement reconstitué d'après la course"),
            )
            val decision = assurance.review(state, report) { st -> StrategyEngine.withDelta(input, StrategyEngine.evaluate(input, st), report.reference) }
            val v515Stops = decision.chosen?.stopLaps
            if (lastV514 != null && v514Stops != lastV514) v514Changes++
            if (lastV515 != null && v515Stops != lastV515) v515Changes++
            lastV514 = v514Stops; lastV515 = v515Stops
            // Erreur vérifiable : temps restant prévu (V515, scénario retenu) contre temps réellement mis.
            val actualRemaining = laps.filter { it.n > k }.takeIf { r -> r.all { it.timeMs != null } }?.sumOf { it.timeMs!!.toDouble() }
            val followed = decision.chosen?.stopLaps == actualStops.filter { it > k }
            val predicted = decision.chosen?.totalMs
            if (followed && predicted != null && actualRemaining != null) errors += predicted - actualRemaining
            rows.put(JSONObject().put("apres_tour", k)
                .put("arrets_reels_restants_OBSERVE", JSONArray(actualStops.filter { it > k }))
                .put("V514_arrets", v514Stops?.let { JSONArray(it) } ?: JSONObject.NULL)
                .put("V515_arrets", v515Stops?.let { JSONArray(it) } ?: JSONObject.NULL)
                .put("V515_plan", decision.chosen?.describe() ?: JSONObject.NULL)
                .put("V515_verdict", decision.verdict.label)
                .put("V515_fenetre", report.window?.let { "${it.from}–${it.to}" + (it.preferred?.let { p -> " ($p)" } ?: "") } ?: JSONObject.NULL)
                .put("temps_restant_prevu_s", predicted?.let { Math.round(it / 100) / 10.0 } ?: JSONObject.NULL)
                .put("temps_restant_reel_s", actualRemaining?.let { Math.round(it / 100) / 10.0 } ?: JSONObject.NULL)
                .put("suivi_reel", followed))
        }
        val s = JSONObject().put("trace", trace).put("course", idx).put("tours", total)
            .put("arrets_reels_OBSERVE", JSONArray(actualStops))
            .put("hypothese_arrets_obligatoires", mandatory)
            .put("cout_arret", costs?.base?.let { "${Math.round(it.ms / 100) / 10.0} s ± ${Math.round(it.spread / 100) / 10.0} (${it.source})" } ?: "inconnu (aucune course précédente mesurée)")
            .put("changements_de_plan_V514", v514Changes).put("changements_de_plan_V515", v515Changes)
            .put("previsions_verifiables", errors.size)
            .put("erreur_moyenne_temps_restant_s", if (errors.isEmpty()) JSONObject.NULL else Math.round(errors.map { abs(it) }.average() / 100) / 10.0)
            .put("biais_temps_restant_s", if (errors.isEmpty()) JSONObject.NULL else Math.round(errors.average() / 100) / 10.0)
        summary += s
        return JSONObject(s.toString()).put("par_tour", rows)
    }
}
