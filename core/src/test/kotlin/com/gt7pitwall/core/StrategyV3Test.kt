package com.gt7pitwall.core

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.PlanEntry
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.race.StopEntry
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.ForecastTrack
import com.gt7pitwall.core.strategy.PlannedStop
import com.gt7pitwall.core.strategy.RacePlan
import com.gt7pitwall.core.strategy.RaceReview
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Race Engineer V3, phases 3 et 4 : stratégie personnalisée, décisions stables, apprentissage après la course. */
class StrategyV3Test {

    private val ctx = SessionContext(EventProfile(id = ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_B)), RaceRules(totalLaps = 10), 3607, 100f)

    private fun record(id: String, predicted: Float?, actual: Float, source: String = "HISTORY", grid: Int? = null, finish: Int? = null) = SessionRecord(
        id, ctx, 0, 0, SessionOutcome.FINISHED,
        laps = (1..10).map { l -> LapEntry(l, 90_000 + (if (l == 5 || l == 6) 12_000 else 0), actual, refuelled = l == 5) },
        stops = listOf(StopEntry(5, StopEvidence.REFUEL, 30f, TyreService.NOT_CHANGED)),
        plans = listOf(PlanEntry(0, listOf(5), null, predicted, source, pitLossMs = 22_000), PlanEntry(3, listOf(6), "conso", predicted, source)),
        gridPosition = grid, finishPosition = finish,
    )

    @Test
    fun theRaceReviewMeasuresForecastErrorsWithoutClaimingCredit() {
        val r = RaceReview.of(record("a", 3.0f, 3.15f, grid = 8, finish = 5), adviceGiven = 2, adviceImproved = 1)
        assertEquals(5.0, r.fuelErrorPct!!, 0.01)
        assertEquals(listOf(24_000), r.pitLossActualMs, "tour d'entrée + tour de sortie − deux tours typiques")
        assertEquals(2_000, r.pitLossErrorMs)
        assertEquals(1, r.planChanges)
        val text = r.lines().joinToString(" ")
        assertTrue("prévue 3,00 par tour (historique), mesurée 3,15 : écart de +5,0 %" in text, text)
        assertTrue("3 places gagnées ; GT7 ne dit pas pourquoi, aucun lien avec la stratégie n'est établi" in text, text)
        assertTrue("2 consignes, 1 suivie d'une amélioration mesurée" in text, text)
    }

    @Test
    fun forecastErrorsCorrectTheNextStartingEstimateOnlyWhenRepeated() {
        assertNull(ForecastTrack.of(listOf(RaceReview.of(record("a", 3.0f, 3.15f)))).fuelCorrection, "une seule course : pas de correction")
        val two = ForecastTrack.of(listOf(RaceReview.of(record("a", 3.0f, 3.15f)), RaceReview.of(record("b", 3.0f, 3.12f))))
        assertEquals(4.5, two.fuelCorrection!!, 0.01)
        assertTrue("4,5 % en moyenne sur 2 courses" in two.text()!!, two.text())
        // Écart faible : rien à corriger. Consommation mesurée dès le départ : pas une prévision de l'historique.
        assertNull(ForecastTrack.of(listOf(RaceReview.of(record("a", 3.0f, 3.03f)), RaceReview.of(record("b", 3.0f, 3.02f)))).fuelCorrection)
        assertNull(ForecastTrack.of(listOf(RaceReview.of(record("a", 3.0f, 3.3f, "MEASURED")), RaceReview.of(record("b", 3.0f, 3.3f, "MEASURED")))).fuelCorrection)
        // Plafonnée : jamais plus de 8 %.
        val big = ForecastTrack.of(listOf(RaceReview.of(record("a", 3.0f, 3.6f)), RaceReview.of(record("b", 3.0f, 3.6f))))
        assertEquals(8.0, big.fuelCorrection!!, 0.01)
    }

    @Test
    fun theControllerArchivesTheReviewAndLearnsFromItsForecasts() {
        val storage = FakeStorage()
        val c = RaceController(storage = storage)
        c.selectProfile(ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_B))
        c.setRules(c.rules.copy(trackId = 462, totalLaps = 6))
        // Trois courses : la consommation réelle dépasse la prévision tirée de l'historique.
        for (per in listOf(5f, 5.5f, 5.5f)) {
            val s = RaceSim(c, totalLaps = 6, perLap = per)
            s.start()
            s.driveLaps(6)
            s.driveLaps(1)
        }
        val next = RaceSim(c, totalLaps = 6, perLap = 5.5f)
        next.start()
        assertNotNull(c.lastReview)
        assertTrue(c.journal.recent(2000).any { it.text.startsWith("Bilan : Consommation") }, "bilan journalisé")
        val corr = assertNotNull(c.fuelCorrectionPct, c.forecastTrack().toString())
        assertTrue(corr > 2.0, corr.toString())
        assertEquals(DataSource.HISTORY, c.plan!!.fuelSource)
        // Briefing sur la grille, une seule fois, avec la base de chaque chiffre.
        val brief = next.texts.filter { it.startsWith("Briefing") }
        assertEquals(1, brief.size, next.texts.toString())
        assertTrue("courses comparables" in brief.single() && "corrigée de +" in brief.single(), brief.single())
        assertTrue(c.aiFacts().lines.any { it.startsWith("Justesse de mes prévisions") })
    }

    private fun plan(vararg stops: Int) = RacePlan(
        totalLaps = 12, lapsSource = DataSource.GAME, stops = stops.map { PlannedStop(it, 30, forFuel = true, mandatory = false) },
        perLap = 5f, fuelSource = DataSource.MEASURED, pitLaneDeltaS = null, requiredTyres = emptySet(),
    )

    @Test
    fun aStopPushedBackByOneLapMustBeConfirmedButAnEarlierStopIsImmediate() {
        val e = RaceEngineer()
        assertEquals(1, e.onPlan(plan(6), currentLap = 1).size, "premier plan annoncé")
        assertTrue(e.onPlan(plan(7), currentLap = 2).isEmpty(), "repoussé d'un tour : on attend la confirmation")
        assertTrue(e.onPlan(plan(6), currentLap = 3).isEmpty(), "retour au plan annoncé : rien à dire, aucun changement d'avis")
        assertTrue(e.onPlan(plan(7), currentLap = 3).isEmpty())
        assertEquals(1, e.onPlan(plan(7), currentLap = 4).size, "confirmé au recalcul suivant")
        val earlier = e.onPlan(plan(5), currentLap = 4)
        assertEquals(1, earlier.size, "arrêt avancé (risque) : tout de suite")
        assertEquals("plan", earlier.single().topic)
    }

    @Test
    fun differentSportRulesGiveDifferentBriefings() {
        val a = RaceController()
        a.selectProfile(ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_A))
        a.setRules(a.rules.copy(totalLaps = 5))
        val b = RaceController()
        b.selectProfile(ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_C))
        b.setRules(b.rules.copy(totalLaps = 20, mandatoryStops = 1, fuelMultiplier = 3))
        val sa = RaceSim(a, totalLaps = 5).also { it.start() }
        val sb = RaceSim(b, totalLaps = 20, perLap = 9f).also { it.start() }
        val ba = sa.texts.single { it.startsWith("Briefing") }
        val bb = sb.texts.single { it.startsWith("Briefing") }
        assertTrue("Course Sport A" in ba || "Sport A" in ba, ba)
        assertTrue("Sport C" in bb, bb)
        // Sans historique : rien n'est inventé.
        assertTrue("Aucune course comparable" in ba && "Aucune course comparable" in bb)
        assertFalse(Regex("\\d+ ?% d'usure").containsMatchIn(ba + bb))
    }

    @Test
    fun withoutOpponentDataNothingIsInventedAndTheEngineWorksWithoutAnyAi() = runBlocking<Unit> {
        val c = RaceController()
        val s = RaceSim(c, totalLaps = 12, perLap = 6f)
        s.start()
        s.driveLaps(3)
        // Aucun service d'IA configuré : le moteur local répond seul.
        val conv = RadioConversation(c)
        val tyres = conv.onUtterance("au prochain arrêt je mets quoi comme pneus", null, model = null)
        assertEquals(c.nextTyreSentence(), tyres.last().text)
        val others = c.strategyBrief?.othersPitting ?: ""
        assertTrue("aucune information sur leurs écarts ni leurs pneus" in others, others)
        // Aucune phrase n'affirme un écart ou un arrêt adverse.
        assertTrue(s.texts.none { Regex("(?i)(il|l'adversaire|la voiture de devant) (rentre|est rentré) aux stands|\\d+[,.]?\\d* secondes? (devant|derrière)").containsMatchIn(it) }, s.texts.toString())
    }
}
