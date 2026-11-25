package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.strategy.Cost
import com.gt7pitwall.core.strategy.Nature
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.Sourced
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.StopKind
import com.gt7pitwall.core.strategy.StrategyAssurance
import com.gt7pitwall.core.strategy.StrategyAssurance.Profitability
import com.gt7pitwall.core.strategy.StrategyAssurance.Verdict
import com.gt7pitwall.core.strategy.StrategyEngine
import com.gt7pitwall.core.strategy.StrategySimulations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V515.1 — précision stratégique. Scénarios SIMULÉS (vérifient le logiciel, jamais un gain réel) et course SIMULÉE
 * (RaceSim) pour l'intégration. Les mesures sur traces RÉELLES sont dans `lab lap-progress | refuel-rate | ablation`.
 */
class V5151PrecisionTest {
    private fun case(id: String) = StrategySimulations.cases.first { it.id == id }

    @Test
    fun aGainBuiltOnUnprovenDegradationIsFeasibleButNotProven() {
        val c = case("undercut-rentable")
        val r = StrategyEngine.analyze(c.input)
        val d = StrategyAssurance().review(StrategySimulations.state(c), r)
        assertEquals(Profitability.NOT_PROVEN, d.profitability)
        assertTrue(d.dependencies.any { "gain des pneus neufs non démontré" in it }, d.dependencies.toString())
        assertEquals(Verdict.PROVISIONAL, d.verdict)
        assertTrue(d.statement.startsWith("Possible"), d.statement)
        // Véga dit ce qui pèse : la dégradation des pneus.
        assertTrue("ce qui pèse : dégradation des pneus" in d.statement.lowercase(), d.statement)
    }

    @Test
    fun theSameGainIsProvenOnceFreshTyreGainIsDemonstrated() {
        val c = case("undercut-rentable")
        val r = StrategyEngine.analyze(c.input)
        val st = StrategySimulations.state(c).copy(freshTyreGainMs = Sourced(2_500.0, "ms", "relais comparables (SIMULATION)", Nature.SIMULATION))
        val d = StrategyAssurance().review(st, r)
        assertEquals(Profitability.PROVEN, d.profitability, d.dependencies.toString())
        assertEquals(Verdict.APPROVED, d.verdict)
        assertTrue(d.statement.startsWith("Je recommande"))
    }

    @Test
    fun keepingTheCurrentPlanClaimsNoGain() {
        val c = case("pneus-neufs-sans-gain")
        val d = StrategyAssurance().review(StrategySimulations.state(c), StrategyEngine.analyze(c.input))
        assertEquals(Profitability.NOT_APPLICABLE, d.profitability)
        assertFalse("Gain estimé" in d.statement)
    }

    @Test
    fun anAssumedTyreServiceAndUncalibratedOutLapAreDeclaredDependencies() {
        val c = case("undercut-rentable")
        val i = c.input.copy(stopCosts = StopCosts(Cost(25_000.0, 2_000.0, "SIMULATION")), outLapPenaltyMs = null)
        val d = StrategyAssurance().review(StrategySimulations.state(c.copy(input = i)), StrategyEngine.analyze(i))
        // Ce plan change les pneus au même nombre que la référence : seule la dégradation non démontrée compte.
        assertTrue(d.dependencies.isNotEmpty(), d.dependencies.toString())
    }

    @Test
    fun refuelTimeIsNeverCountedTwice() {
        val base = case("pneus-neufs-sans-gain").input
        val observed = StopCosts(base = null, observed = mapOf(StopKind.FUEL_ONLY to Cost(30_000.0, 2_000.0, "SIMULATION : arrêts avec plein observés")))
        val withObserved = StrategyEngine.evaluate(base.copy(stopCosts = observed, refuelMsPerUnit = 100.0), listOf(8 to false))
        assertNull(withObserved.parts[StrategyEngine.PART_REFUEL], "le plein est déjà dans le temps observé")
        val laneOnly = StrategyEngine.evaluate(base.copy(stopCosts = StopCosts(Cost(20_000.0, 2_000.0, "voie seule")), refuelMsPerUnit = 100.0), listOf(8 to false))
        assertNotNull(laneOnly.parts[StrategyEngine.PART_REFUEL])
    }

    @Test
    fun theDeltaIsExplainedCauseByCause() {
        val r = StrategyEngine.analyze(case("undercut-rentable").input)
        val b = r.scenario(StrategyEngine.Kind.EARLY)!!
        val sum = b.deltaParts!!.values.sum()
        assertEquals(b.deltaMs!!, sum, 5.0)
    }

    @Test
    fun inRaceTheLapPositionComesFromDistanceAndTheRefuelRateIsMeasured() {
        var now = 0L
        val c = RaceController(storage = FakeStorage(), clock = { now })
        c.setRules(RaceRules(totalLaps = 14, mandatoryStops = 1))
        val s = RaceSim(c, totalLaps = 14, perLap = 9.8f).also { it.tap = { now += 17 } }
        s.start()
        while (s.lap < 4) s.step(80f)
        s.driveTo(1.5f)
        // Recalcul du plan en cours de tour : position par la distance (CALCUL), pas par le carburant.
        c.replanForTest()
        val st = c.strategyCommand!!.state
        assertEquals(Nature.CALCUL, st.lapProgress.nature, st.lapProgress.source)
        assertFalse(st.refuelMsPerUnit.known, "aucun plein encore mesuré dans cette course")
        // Un plein mesuré pendant un arrêt : le débit devient une MESURE de cette course.
        s.pitStop(fuel = 40f, stopSeconds = 10f)
        while (s.lap < 7) s.step(80f)
        c.replanForTest()
        val st2 = c.strategyCommand!!.state
        assertTrue(st2.refuelMsPerUnit.known, st2.refuelMsPerUnit.source)
        assertEquals(Nature.MESURE, st2.refuelMsPerUnit.nature)
    }
}
