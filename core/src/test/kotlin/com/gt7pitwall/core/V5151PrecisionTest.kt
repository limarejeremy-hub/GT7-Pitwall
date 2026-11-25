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

    private val sansGain get() = case("pneus-neufs-sans-gain")

    /** Dépendances d'un plan [plan] contre le plan actuel (arrêt au tour 8 sans pneus), coûts [costs]. */
    private fun deps(plan: List<Pair<Int, Boolean>>, costs: StopCosts, state: (com.gt7pitwall.core.strategy.StrategyState) -> com.gt7pitwall.core.strategy.StrategyState = { it }): List<String> {
        val c = sansGain
        val i = c.input.copy(stopCosts = costs)
        val ref = StrategyEngine.evaluate(i, listOf(8 to false))
        val s = StrategyEngine.withDelta(i, StrategyEngine.evaluate(i, plan), ref)
        return StrategyAssurance().dependencies(state(StrategySimulations.state(c.copy(input = i))), s, ref, i.currentLap)
    }

    @Test
    fun anAssumedTyreServiceAndUncalibratedOutLapAreDeclaredDependencies() {
        // Un changement de pneus de plus que le plan actuel, temps de service supposé (10 s ± 10 s), tour de sortie inconnu.
        val d = deps(listOf(8 to true), StopCosts(Cost(25_000.0, 2_000.0, "voie seule (SIMULATION)")))
        assertTrue("temps de changement des pneus supposé" in d, d.toString())
        assertTrue("tour de sortie non calibré" in d, d.toString())
        // Arrêts avec pneus OBSERVÉS : leur perte couvre déjà le tour de sortie ; ni l'un ni l'autre n'est une dépendance.
        val seen = StopCosts(Cost(25_000.0, 2_000.0, "SIMULATION"), observed = mapOf(StopKind.FUEL_AND_TYRES to Cost(34_000.0, 2_000.0, "SIMULATION", samples = 4)))
        val d2 = deps(listOf(8 to true), seen)
        assertFalse(d2.any { "pneus supposé" in it || "tour de sortie" in it }, d2.toString())
    }

    @Test
    fun aStopCostLearnedOnASingleStopIsNotProofForAnExtraStop() {
        val d = deps(listOf(7 to false, 10 to false), StopCosts(Cost(25_000.0, 4_300.0, "SIMULATION : 1 arrêt observé", samples = 1)))
        assertTrue("coût d'arrêt appris sur 1 arrêt" in d, d.toString())
        val d3 = deps(listOf(7 to false, 10 to false), StopCosts(Cost(25_000.0, 2_000.0, "SIMULATION : 3 arrêts", samples = 3)))
        assertFalse(d3.any { "coût d'arrêt" in it }, d3.toString())
    }

    @Test
    fun aRefuelInsideAnObservedStopCostIsFlaggedWhenTheNumberOfStopsDiffers() {
        val d = deps(listOf(7 to false, 10 to false), StopCosts(Cost(30_000.0, 2_000.0, "SIMULATION : arrêts avec plein", samples = 5), baseIncludesRefuel = true))
        assertTrue(d.any { "plein compris dans le coût d'arrêt" in it }, d.toString())
        // Même quantité ajoutée au total (plein minimal) : le débit inconnu ne pèse pas.
        assertFalse("temps de remplissage inconnu" in d, d.toString())
    }

    @Test
    fun anUncalibratedFuelWeightIsDeclaredWhenItCouldReverseTheChoice() {
        // Arrêt avancé de 2 tours, même plein : environ 39 unités×tours de plus embarquées, 15 ms par unité et par tour
        // (garde) ≈ 0,6 s ≥ 0,5 s pour un écart nul.
        val d = deps(listOf(6 to false), StopCosts(Cost(25_000.0, 2_000.0, "SIMULATION", samples = 5)))
        assertTrue("effet du poids du carburant non calibré" in d, d.toString())
        // Plan identique au plan actuel à un tour près : effet sous la garde, rien à déclarer.
        val d1 = deps(listOf(7 to false), StopCosts(Cost(25_000.0, 2_000.0, "SIMULATION", samples = 5)))
        assertFalse("effet du poids du carburant non calibré" in d1, d1.toString())
    }

    @Test
    fun aNullOrNegativeFreshTyreGainDoesNotProveAnything() {
        val c = case("undercut-rentable")
        val r = StrategyEngine.analyze(c.input)
        val st = StrategySimulations.state(c).copy(freshTyreGainMs = Sourced(0.0, "ms/tour", "relais comparables (SIMULATION)", Nature.SIMULATION))
        val d = StrategyAssurance().review(st, r)
        assertEquals(Profitability.NOT_PROVEN, d.profitability)
        assertTrue(d.dependencies.any { "gain des pneus neufs non démontré" in it }, d.dependencies.toString())
    }

    @Test
    fun anUncertainTyreAgeKeepsATyreGainUnproven() {
        val c = case("undercut-rentable")
        val st = StrategySimulations.state(c).copy(
            freshTyreGainMs = Sourced(2_500.0, "ms/tour", "SIMULATION", Nature.SIMULATION), tyreAgeCertain = false,
        )
        val d = StrategyAssurance().review(st, StrategyEngine.analyze(c.input))
        assertEquals(Profitability.NOT_PROVEN, d.profitability)
        assertTrue("âge des pneus incertain" in d.dependencies, d.dependencies.toString())
    }

    @Test
    fun aProvisionalVerdictNeverCarriesAProvenGain() {
        // Gain des pneus démontré, mais règlement non confirmé : verdict provisoire, donc pas de « gain démontré ».
        val c = case("undercut-rentable").copy(rulesValid = false)
        val st = StrategySimulations.state(c).copy(freshTyreGainMs = Sourced(2_500.0, "ms/tour", "SIMULATION", Nature.SIMULATION))
        val d = StrategyAssurance().review(st, StrategyEngine.analyze(c.input))
        assertEquals(Verdict.PROVISIONAL, d.verdict)
        assertEquals(Profitability.NOT_PROVEN, d.profitability)
        assertTrue(d.reasons.first().startsWith("rentabilité non démontrée"), d.reasons.toString())
    }

    @Test
    fun anInfeasibleCurrentPlanIsAForcedChangeNotAKeptPlan() {
        val c = case("plein-insuffisant")
        val d = StrategyAssurance().review(StrategySimulations.state(c), StrategyEngine.analyze(c.input))
        assertEquals(Profitability.FORCED, d.profitability, d.statement)
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
        // CALCUL sur le niveau mesuré : 40 unités en 600 paquets de 17 ms (10,2 s) ≈ 255 ms par unité.
        assertEquals(Nature.CALCUL, st2.refuelMsPerUnit.nature)
        assertEquals(255.0, st2.refuelMsPerUnit.value!!, 255.0 * 0.05)
        assertNotNull(st2.refuelMsPerUnit.limit, "un seul plein : répétabilité non vérifiée")
    }

    @Test
    fun onLapTwoTheGridLapIsNeverTheDistanceReference() {
        var now = 0L
        val c = RaceController(storage = FakeStorage(), clock = { now })
        c.setRules(RaceRules(totalLaps = 14, mandatoryStops = 1))
        val s = RaceSim(c, totalLaps = 14, perLap = 9.8f).also { it.tap = { now += 17 } }
        s.start()
        while (s.lap < 2) s.step(80f)
        s.driveTo(1.5f)
        c.replanForTest()
        // Seul le tour 1 (départ de la grille) est complet : pas de référence de distance au tour 2.
        c.strategyCommand?.let { assertTrue(it.state.lapProgress.nature != Nature.CALCUL, it.state.lapProgress.source) }
        while (s.lap < 3) s.step(80f)
        s.driveTo(1.5f)
        c.replanForTest()
        assertEquals(Nature.CALCUL, c.strategyCommand!!.state.lapProgress.nature)
    }
}
