package com.gt7pitwall.core

import com.gt7pitwall.core.strategy.StrategyAssurance
import com.gt7pitwall.core.strategy.StrategyAssurance.Verdict
import com.gt7pitwall.core.strategy.StrategyEngine
import com.gt7pitwall.core.strategy.StrategyEngine.Kind
import com.gt7pitwall.core.strategy.StrategySimulations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V515 : Strategy Engine et contrôleur sur les 14 scénarios SIMULÉS de [StrategySimulations]. Ils vérifient la logique
 * (faisabilité, comparaison au-delà de l'incertitude, carburant et pneus calculés ensemble, prudence), jamais la
 * justesse d'un modèle sur GT7 ni un résultat de course.
 */
class V515StrategyEngineTest {
    private fun case(id: String) = StrategySimulations.cases.first { it.id == id }
    private fun run(id: String) = StrategyEngine.analyze(case(id).input)
    private fun decide(id: String): StrategyAssurance.Decision {
        val c = case(id)
        return StrategyAssurance().review(StrategySimulations.state(c), StrategyEngine.analyze(c.input))
    }

    @Test
    fun aProfitableUndercutBeatsTheUncertaintyButStaysConditional() {
        val r = run("undercut-rentable")
        val cut = assertNotNull(r.undercut)
        assertTrue(cut.beatsUncertainty, cut.toString())
        assertFalse(cut.opponentReference)
        assertTrue("adversaire" in cut.verdict && "inconnu" in cut.verdict, cut.verdict)
        assertTrue(cut.freshTyreGainMs!! > 0)
        // Le meilleur plan s'arrête plus tôt que le plan actuel (tour 9).
        assertTrue(r.recommended!!.stopLaps.first() < 9, r.recommended!!.describe())
        val w = assertNotNull(r.window)
        assertTrue(w.preferred != null && w.from <= w.preferred!! && w.preferred!! <= w.to)
    }

    @Test
    fun anUndercutWithoutRealDegradationIsNotRecommended() {
        val r = run("undercut-perdant")
        assertFalse(r.undercut!!.beatsUncertainty)
        // Le tour d'arrêt prévu est gardé ; avec 30 ms par tour de dégradation, le moteur juge même que changer les
        // pneus (8 s de service) coûte plus qu'il ne rapporte : carburant et pneus calculés ensemble.
        assertEquals(listOf(9), r.recommended!!.stopLaps)
    }

    @Test
    fun aProfitableOvercutDelaysTheStop() {
        val r = run("overcut-rentable")
        assertTrue(r.overcut!!.beatsUncertainty, r.overcut.toString())
        assertTrue(r.recommended!!.stopLaps.first() > 6, r.recommended!!.describe())
    }

    @Test
    fun freshTyresWithoutMeasuredGainCostTimeAndAreNotChosen() {
        val r = run("pneus-neufs-sans-gain")
        val e = r.scenario(Kind.TYRES_AT_REFUEL)!!
        assertTrue(e.deltaMs!! > 0, "les pneus coûtent le service : ${e.deltaMs}")
        assertTrue(e.unknowns.any { "gain des pneus neufs non chiffré" in it })
        assertEquals(Kind.CURRENT, r.recommended!!.kind)
        assertTrue(r.recommended!!.stops.none { it.tyres })
    }

    @Test
    fun anEarlierTyreChangeThatWouldNeedAnExtraStopIsRejected() {
        val r = run("pneus-imposent-arret")
        val early = r.scenario(Kind.EARLY)!!
        assertFalse(early.feasible)
        assertTrue(early.issues.any { "durée déclarée" in it }, early.issues.toString())
        assertEquals(listOf(10), r.recommended!!.stopLaps)
        assertTrue(r.recommended!!.stops.single().tyres)
    }

    @Test
    fun aFuelSavingThatAvoidsTheStopIsFeasibleButNotClaimedWithoutProof() {
        val r = run("economie-evite-arret")
        val none = r.scenario(Kind.NO_STOP)!!
        assertTrue(none.feasible)
        assertTrue(none.saving in 0.05f..0.08f, "économie : ${none.saving}")
        assertTrue(none.unknowns.any { "estimation expérimentale" in it })
        // Le coût de l'économie (3,2 s ± 3,2 s par unité) est trop incertain : on garde l'arrêt prévu.
        assertEquals(Kind.CURRENT, r.recommended!!.kind)
        // Le carburant et l'arrêt sont calculés ensemble : l'option D est la même économie.
        assertTrue(r.scenario(Kind.EXTEND_SAVE)!!.feasible)
    }

    @Test
    fun anInsufficientFuelLoadMovesTheStopForward() {
        val r = run("plein-insuffisant")
        assertFalse(r.reference!!.feasible)
        assertTrue(r.reference!!.issues.any { "carburant insuffisant" in it })
        assertTrue(r.recommended!!.feasible)
        assertTrue(r.recommended!!.stopLaps.first() <= 6, r.recommended!!.describe())
    }

    @Test
    fun aRegulationThatCannotBeMetIsBlocked() {
        val d = decide("reglement-impossible")
        assertEquals(Verdict.BLOCKED, d.verdict)
        assertTrue(d.statement.startsWith("Je n'ai aucune stratégie fiable"), d.statement)
    }

    @Test
    fun aSuddenConsumptionRiseMakesThePlannedStopUnreachable() {
        val r = run("conso-en-hausse")
        assertFalse(r.reference!!.feasible)
        assertTrue(r.recommended!!.stopLaps.first() < 8)
        // Avec la consommation d'avant, le plan disait quand il deviendrait invalide.
        val before = StrategyEngine.analyze(case("conso-en-hausse").input.copy(perLap = 9f, fuelNow = 40f)).reference!!
        assertTrue(before.invalidIf.any { "consommation en hausse" in it }, before.invalidIf.toString())
    }

    @Test
    fun aMissedStopIsRecoveredAtTheEarliestLap() {
        val r = run("arret-manque")
        assertFalse(r.reference!!.feasible)
        assertEquals(8, r.recommended!!.stopLaps.first())
    }

    @Test
    fun onTheLastLapNoStopIsProposed() {
        val r = run("dernier-tour")
        assertTrue(r.notes.any { "dernier tour" in it })
        assertTrue(r.recommended!!.stops.isEmpty())
    }

    @Test
    fun noOpponentDataIsInventedAnywhere() {
        val d = decide("adversaires-absents")
        assertTrue(d.checks.any { it.name == "adversaires" && "conditionnels" in it.detail })
        assertFalse(Regex("\\d+[,.]?\\d* ?s (devant|derrière)").containsMatchIn(d.statement), d.statement)
    }

    @Test
    fun aStaleSportSheetOrInterruptedTelemetryMakesTheDecisionProvisional() {
        assertEquals(Verdict.PROVISIONAL, decide("reglement-perime").verdict)
        val t = decide("telemetrie-coupee")
        assertEquals(Verdict.PROVISIONAL, t.verdict)
        assertTrue(t.reasons.any { "télémétrie" in it })
    }

    @Test
    fun theDecisionIsStableWhenTheGainIsWithinTheHysteresis() {
        val c = case("undercut-perdant")
        val a = StrategyAssurance()
        val r1 = StrategyEngine.analyze(c.input)
        val first = a.review(StrategySimulations.state(c), r1) { st -> StrategyEngine.withDelta(c.input, StrategyEngine.evaluate(c.input, st), r1.reference) }
        // Le plan bouge d'un tour avec un écart négligeable : la décision précédente, RÉÉVALUÉE, est gardée.
        val moved = c.input.copy(plannedStops = listOf(c.input.plannedStops.first().copy(endOfLap = 8)))
        val r2 = StrategyEngine.analyze(moved)
        val second = a.review(StrategySimulations.state(c.copy(input = moved)), r2) { st -> StrategyEngine.withDelta(moved, StrategyEngine.evaluate(moved, st), r2.reference) }
        assertEquals(first.chosen!!.key, second.chosen!!.key, second.checks.toString())
        assertTrue(second.reasons.any { "stabilité" in it })
    }

    @Test
    fun noWearPercentageNorInventedNumberInTheStatement() {
        for (c in StrategySimulations.cases) {
            val d = StrategyAssurance().review(StrategySimulations.state(c), StrategyEngine.analyze(c.input))
            assertFalse(Regex("usure").containsMatchIn(d.statement), "${c.id} : ${d.statement}")
        }
    }

    @Test
    fun theSearchStaysBoundedOnALongRace() {
        val i = case("pneus-neufs-sans-gain").input.copy(totalLaps = 60, currentLap = 2, fuelNow = 100f, perLap = 5f)
        val t0 = System.nanoTime()
        val r = StrategyEngine.analyze(i)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(r.evaluated <= StrategyEngine.MAX_EVALUATIONS + 1, "${r.evaluated}")
        assertTrue(ms < 5_000, "trop lent : $ms ms")
        assertNull(r.notes.firstOrNull { "terminée" in it })
    }
}
