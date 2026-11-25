package com.gt7pitwall.core

import com.gt7pitwall.core.display.StrategyBoard
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.strategy.Cost
import com.gt7pitwall.core.strategy.Nature
import com.gt7pitwall.core.strategy.PlannedStop
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.StrategyAssurance
import com.gt7pitwall.core.strategy.StrategyAssurance.Verdict
import com.gt7pitwall.core.strategy.StrategyEngine
import com.gt7pitwall.core.strategy.StrategyEngine.Kind
import com.gt7pitwall.core.strategy.StrategySimulations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Revues qualité et stratège de V515 : chaque test reproduit un défaut relevé sur la première version. */
class V515ReviewTest {
    private fun case(id: String) = StrategySimulations.cases.first { it.id == id }
    private fun decide(id: String): StrategyAssurance.Decision {
        val c = case(id)
        return StrategyAssurance().review(StrategySimulations.state(c), StrategyEngine.analyze(c.input))
    }

    @Test
    fun anInfeasibleCurrentPlanNeverAnnouncesAGain() {
        for (id in listOf("arret-manque", "plein-insuffisant", "conso-en-hausse")) {
            val d = decide(id)
            assertFalse("Gain estimé" in d.statement, "$id : ${d.statement}")
            assertTrue("n'est plus faisable" in d.statement, "$id : ${d.statement}")
        }
    }

    @Test
    fun aChangeOfTyresIsAChangeOfPlanNotKeepingIt() {
        val d = decide("undercut-perdant")
        // Même tour (9), mais sans les pneus prévus : ce n'est pas « on garde ».
        assertTrue(d.chosen!!.stops.none { it.tyres })
        assertTrue(d.statement.startsWith("Je recommande"), d.statement)
    }

    @Test
    fun keepingTheCurrentTyrePlanIsNotProvisionalForAnUnprovenOutLap() {
        val c = case("pneus-imposent-arret")
        val d = StrategyAssurance().review(StrategySimulations.state(c), StrategyEngine.analyze(c.input.copy(outLapPenaltyMs = null)))
        assertEquals(Verdict.APPROVED, d.verdict, d.reasons.toString())
    }

    @Test
    fun consumptionMarginIsNotZeroWhenTheRefuelCanGrow() {
        val r = StrategyEngine.analyze(case("pneus-neufs-sans-gain").input)
        assertTrue(r.reference!!.breaksIfConsumptionUp!! > 0.02f, "${r.reference!!.breaksIfConsumptionUp}")
    }

    @Test
    fun twoRequiredCompoundsNeedTwoTyreChanges() {
        val i = case("pneus-neufs-sans-gain").input.copy(tyreChangesRequired = 2,
            plannedStops = listOf(PlannedStop(8, null, forFuel = true, mandatory = false, changeTyres = true)))
        val r = StrategyEngine.analyze(i)
        assertFalse(r.reference!!.feasible)
        assertTrue(r.recommended!!.tyreStops >= 2, r.recommended!!.describe())
    }

    @Test
    fun theTyreServiceUncertaintyIsCountedOnce() {
        val r = StrategyEngine.analyze(case("pneus-neufs-sans-gain").input)
        assertEquals(1_000.0, r.scenario(Kind.TYRES_AT_REFUEL)!!.uncertaintyMs!!, 1.0)
    }

    @Test
    fun aSameStopCountVariantIsRecommendedWhenTheExtraStopBestFailsItsThreshold() {
        // Pneus usés (0,25 s/tour), arrêt prévu tard : changer au même arrêt mais plus tôt bat A au-delà de U, alors qu'un
        // arrêt de plus serait au mieux marginal.
        val i = case("undercut-rentable").input.copy(slopeMsPerLap = 250.0, slopeSpread = 40.0)
        val r = StrategyEngine.analyze(i)
        assertNotEquals(Kind.CURRENT, r.recommended!!.kind)
        assertEquals(1, r.recommended!!.stops.size, r.recommended!!.describe())
    }

    @Test
    fun aNegativeLapTimeSlopeNeverFavoursFreshTyres() {
        val i = case("undercut-rentable").input.copy(slopeMsPerLap = -300.0, slopeSpread = 50.0)
        val r = StrategyEngine.analyze(i)
        assertTrue(r.recommended!!.stops.none { it.tyres } || r.recommended!!.kind == Kind.CURRENT, r.recommended!!.describe())
        assertTrue(r.scenario(Kind.TYRES_AT_REFUEL)!!.unknowns.any { "ne se dégradent pas" in it })
    }

    @Test
    fun aZeroSlopeSpreadDoesNotTriggerAnImmediateStopForATinyGain() {
        val i = case("undercut-perdant").input.copy(slopeMsPerLap = 40.0, slopeSpread = 0.0, outLapPenaltyMs = null)
        val r = StrategyEngine.analyze(i)
        assertTrue(r.recommended!!.stopLaps.first() != i.currentLap, r.recommended!!.describe())
    }

    @Test
    fun aStopAtTheCurrentLapWithNoLapLeftIsNotANaN() {
        val i = case("arret-manque").input.copy(lapFractionLeft = 0f)
        val r = StrategyEngine.analyze(i)
        assertTrue(r.scenarios.all { s -> s.totalMs?.isNaN() != true && s.saving.isNaN().not() })
    }

    @Test
    fun theSavingIsNeverShownAsZeroPercent() {
        for (c in StrategySimulations.cases) for (s in StrategyEngine.analyze(c.input).scenarios) {
            assertFalse("économie 0 %" in s.describe(), "${c.id} : ${s.describe()}")
        }
    }

    @Test
    fun simulatedValuesAreLabelledSimulationNotMeasure() {
        val st = StrategySimulations.state(case("pneus-neufs-sans-gain"))
        assertEquals(Nature.SIMULATION, st.perLap.nature)
        assertEquals(Nature.SIMULATION, st.paceMs.nature)
    }

    @Test
    fun anUnknownConsumptionIsShownUnknownNotImpossible() {
        val i = case("pneus-neufs-sans-gain").input.copy(perLap = null)
        val r = StrategyEngine.analyze(i)
        val d = StrategyAssurance().review(StrategySimulations.state(case("pneus-neufs-sans-gain").copy(input = i)), r)
        val b = StrategyBoard.of(r, d)
        assertTrue(b.alternatives.first { it.code == 'D' }.status == StrategyBoard.Status.UNKNOWN, b.alternatives.toString())
        assertEquals(Verdict.INSUFFICIENT, d.verdict)
    }

    @Test
    fun aBlockedDecisionCitesItsOwnReason() {
        val c = case("reglement-impossible").copy(rulesValid = false)
        val d = StrategyAssurance().review(StrategySimulations.state(c), StrategyEngine.analyze(c.input))
        assertEquals(Verdict.BLOCKED, d.verdict)
        assertFalse("règlement non validé" in d.statement.substringBefore("Les rappels"), d.statement)
        assertNull(d.chosen)
    }

    @Test
    fun theNewIntentsDoNotStealOtherPhrases() {
        val none = listOf(
            "je suis plus sûr de moi dans les freinages", "Les pneus avant chauffent plus sur la fin ?", "ça glisse plus surtout à l'arrière",
            "je peux finir sans risque de panne ?", "c'est plus sûr de rentrer maintenant ?", "je peux pousser sans risque ?",
            "combien de temps je gagne si je change les pneus maintenant ?", "combien de tours je perds si je change les pneus maintenant ?",
            "on ne peut pas éviter l'arrêt", "je préfère éviter le stand ce tour-ci, trop de monde", "pas besoin de deuxième arrêt, on garde le plan",
        )
        for (q in none) {
            val a = StrategyDialogue.classify(q)
            assertTrue(a != StrategyDialogue.Ask.LeastRisk && a != StrategyDialogue.Ask.FuelIfTyresNow && a != StrategyDialogue.Ask.AvoidStop, "$q → $a")
        }
        assertEquals(StrategyDialogue.Ask.SamePlan, StrategyDialogue.classify("on garde notre stratégie"))
    }

    @Test
    fun aNewSessionNeverReusesThePreviousCommand() {
        var now = 0L
        val c = RaceController(storage = FakeStorage(), clock = { now })
        c.setRules(RaceRules(totalLaps = 14, mandatoryStops = 1))
        val s = RaceSim(c, totalLaps = 14, perLap = 9.8f).also { it.tap = { now += 17 } }
        s.start()
        while (s.lap < 4) s.step(80f)
        val cmd = c.strategyCommand!!
        // La référence V515 est le plan des rappels « Box ».
        assertEquals(c.plan!!.stops.map { it.endOfLap }, cmd.report.reference!!.stopLaps)
        // Nouvelle course : plus aucune commande de la précédente.
        val s2 = RaceSim(c, totalLaps = 14, perLap = 9.8f).also { it.tap = { now += 17 } }
        s2.start()
        val after = c.strategyCommand
        assertTrue(after == null || after !== cmd, "commande de l'autre course reprise")
    }
}
