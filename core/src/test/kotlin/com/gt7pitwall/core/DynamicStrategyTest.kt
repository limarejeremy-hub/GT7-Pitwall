package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.OptionKind
import com.gt7pitwall.core.strategy.RaceRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicStrategyTest {
    /** Historique : 10 % du réservoir par tour ; en réalité 14,5 : le plan de départ est trop optimiste. */
    private fun optimisticStart(): RaceSim {
        val history = CarTrackRecord(3607, 462, 100_000, 101_000, fuelPerLapX1 = 10f, fuelSamples = 30)
        val store = FakeStorage(RaceRules(totalLaps = 10, trackId = 462), mutableMapOf(history.key to history))
        return RaceSim(RaceController(storage = store), totalLaps = 10, perLap = 14.5f)
    }

    @Test
    fun aPlanChangeIsExplainedByWhatWasMeasured() {
        val s = optimisticStart()
        s.start()
        val first = s.controller.plan!!
        assertEquals(DataSource.HISTORY, first.fuelSource)
        s.driveLaps(2)
        val now = s.controller.plan!!
        assertEquals(DataSource.MEASURED, now.fuelSource)
        val change = s.texts.firstOrNull { it.startsWith("Nouveau plan") }
        assertNotNull(change, s.texts.toString())
        assertTrue("La consommation mesurée remplace l'estimation" in change, change)
        assertTrue("% du réservoir par tour" in change, change)
        // L'historique (10 % par tour) permettait de finir sans arrêt ; la mesure (14,5 %) l'impose.
        assertTrue(first.stops.isEmpty())
        assertTrue(now.stops.isNotEmpty())
        assertTrue("Un arrêt devient nécessaire" in change, change)
        // Gardé pour « pourquoi tu changes notre stratégie ? » et dans l'archive.
        assertTrue(s.controller.lastPlanChange!!.reason.contains("consommation mesurée"))
        assertTrue(s.controller.strategyBrief!!.lastChange!!.contains("consommation mesurée"))
    }

    @Test
    fun aSteadyRaceKeepsItsPlanLapAfterLap() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10, perLap = 14.5f)
        s.start()
        s.driveLaps(2)
        val plan = s.controller.plan!!.stops.map { it.endOfLap }
        s.said.clear()
        s.driveLaps(2)
        assertEquals(plan, s.controller.plan!!.stops.map { it.endOfLap })
        assertTrue(s.texts.none { it.startsWith("Nouveau plan") }, s.texts.toString())
        // La comparaison est recalculée à chaque tour, sans changer le plan.
        assertEquals(OptionKind.NORMAL, s.controller.strategy!!.recommended)
    }

    @Test
    fun theStructuredAnswersAreReadyForTheRadioAndInventNothingAboutRivals() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10, perLap = 14.5f)
        s.start()
        s.driveLaps(2)
        val brief = assertNotNull(s.controller.strategyBrief)
        assertTrue(brief.recommendation.startsWith("Stratégie normale : arrêt à la fin du tour"), brief.recommendation)
        assertTrue("% du réservoir" in brief.recommendation, brief.recommendation)
        assertTrue(brief.othersPitting.startsWith("je n'ai aucune information sur leurs écarts ni leurs pneus"), brief.othersPitting)
        assertTrue(brief.extendOneLap.startsWith("non,") || brief.extendOneLap.startsWith("oui,"), brief.extendOneLap)
        assertTrue("pour finir" in brief.fuelToFinish && "il en reste" in brief.fuelToFinish, brief.fuelToFinish)
        val facts = s.controller.aiFacts().lines
        assertTrue(facts.any { it.startsWith("Stratégie recommandée : ") })
        assertTrue(facts.any { it.startsWith("Si d'autres voitures rentrent aux stands : ") })
    }

    @Test
    fun unknownObligationsMakeTheStrategyExplicitlyUnvalidated() {
        val c = RaceController(storage = FakeStorage())
        val s = RaceSim(c, totalLaps = 10, perLap = 14.5f)
        c.selectProfile(com.gt7pitwall.core.race.ProfileId(com.gt7pitwall.core.race.Series.DAILY_B, com.gt7pitwall.core.race.SessionKind.RACE))
        // Édition dont les obligations ne sont pas publiées.
        val state = com.gt7pitwall.core.sport.ScheduleState(
            editions = mapOf(
                com.gt7pitwall.core.race.Series.DAILY_B to com.gt7pitwall.core.sport.ScheduleBuilder.merge(
                    com.gt7pitwall.core.race.Series.DAILY_B,
                    java.time.LocalDate.of(2026, 10, 19),
                    listOf(com.gt7pitwall.core.sport.DailyRaceInfo(com.gt7pitwall.core.race.Series.DAILY_B, com.gt7pitwall.core.sport.SourceRef("GTPlanet", null, 1), laps = 10)),
                    com.gt7pitwall.core.knowledge.Gt7Database.EMPTY,
                ),
            ),
            history = emptyList(), lastFetchMs = 1, lastError = null, stale = false,
        )
        c.applySchedule(state)
        s.start()
        s.driveLaps(2)
        val report = c.strategy!!
        assertTrue("arrêts obligatoires" in report.unverifiedRules, report.unverifiedRules.toString())
        assertTrue(c.strategyBrief!!.facts().any { "stratégie non validée réglementairement" in it })
    }

    @Test
    fun theReasonIsArchivedWithThePlan() {
        val store = FakeStorage(RaceRules(totalLaps = 10, trackId = 462), mutableMapOf(CarTrackRecord(3607, 462, 100_000, 101_000, 10f, 30).let { it.key to it }))
        val s = RaceSim(RaceController(storage = store), totalLaps = 10, perLap = 14.5f)
        s.start()
        s.driveLaps(3)
        val plans = store.sessions.single().plans
        assertTrue(plans.any { it.reason?.contains("consommation mesurée") == true }, plans.toString())
        assertEquals(DataSource.HISTORY.name, plans.first().fuelSource)
    }
}
