package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.Milestone
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.tyres.StartTyreAdvisor
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #65 et revue #66, côté contrôleur : conseil dès la qualification, pas de redite à la grille, garde-fous du
 * règlement, jamais une gomme enregistrée. Simulé (RaceSim), aucun essai PS5.
 */
class StartTyreAdviceRaceTest {
    private val db = Gt7Database.parse(
        "ID,ShortName,Maker\n3474,BRZ GT300 '21,1\n",
        "ID,Name,Country\n1,Subaru,1\n",
        "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain\n" +
            "462,SUGO International Racing Course,462,3,circuit,3016,700,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n",
    ).also { it.carGroups = Gt7Database.parseGroups("ID,Group\n3474,3\n") }

    private var now = java.time.ZonedDateTime.of(2026, 10, 30, 15, 30, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()

    private fun controller(allowed: Set<TyreCompound>? = setOf(TyreCompound.RH, TyreCompound.RM, TyreCompound.RS), stale: Boolean = false): RaceController {
        val req = setOf(TyreCompound.RM, TyreCompound.RS)
        val monday = LocalDate.of(2026, 10, 26)
        fun info(src: String) = DailyRaceInfo(
            Series.DAILY_C, SourceRef(src, null, 1), trackName = "SUGO International Racing Course", laps = 14, cars = "Gr.3",
            mandatoryStops = 0, requiredTyres = req, allowedTyres = allowed,
        )
        val c = ScheduleBuilder.merge(Series.DAILY_C, monday, listOf(info("GT GridStats"), info("DG Edge")), db)
        return RaceController(database = db, clock = { now++ }).also {
            it.sportFirst = true
            it.applySchedule(ScheduleState(mapOf(Series.DAILY_C to c), listOf(c), 1, null, stale = stale))
        }
    }

    private fun adviceTexts(sim: RaceSim) = sim.texts.filter { "conseil" in it.lowercase() || "départ possible" in it || "options proches" in it || "Règlement non confirmé" in it }

    @Test fun adviceIsReadyFromQualifyingAndNotRepeatedOnTheGrid() {
        val c = controller()
        val q = RaceSim(c, totalLaps = 0, perLap = 2.8f, carCode = 3474)
        q.stand(10f)
        val quali = q.texts.filter { it.startsWith("Pour la course : ") }
        assertEquals(1, quali.size, q.texts.toString())
        assertTrue("historique insuffisant pour en conseiller un" in quali.single(), quali.single())
        assertNotNull(c.zeroTouch.first(Milestone.QUALIF_ADVICE_READY))
        assertNull(c.rules.startTyre)
        // Prégrille : même conclusion → pas de redite ; l'écran garde le conseil.
        val g = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        g.stand(10f)
        assertTrue(adviceTexts(g).isEmpty(), g.texts.toString())
        assertNotNull(c.startTyreAdvice)
        assertEquals(StartTyreAdvisor.Status.NO_HISTORY, c.startTyreAdvice!!.status)
        assertNull(c.rules.startTyre, "un conseil n'est jamais une gomme montée")
        // Départ : plus rien sur la gomme.
        while (g.lap == 0) g.step(30f)
        g.driveLaps(1)
        assertTrue(adviceTexts(g).isEmpty(), g.texts.toString())
    }

    @Test fun withoutQualifyingTheGridGivesOneAdviceQuickly() {
        val c = controller()
        val g = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        g.stand(10f)
        val said = adviceTexts(g)
        assertEquals(1, said.size, g.texts.toString())
        assertTrue(said.single().startsWith("Sans place de grille : départ possible en"), said.single())
        assertNotNull(c.zeroTouch.first(Milestone.START_TYRE_ADVICE))
    }

    @Test fun unknownAllowedTyresGiveNoAdvice() {
        val c = controller(allowed = null)
        val g = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        g.stand(3f)
        val a = assertNotNull(c.startTyreAdvice)
        assertEquals(StartTyreAdvisor.Status.RULES_UNKNOWN, a.status, a.sentence)
        assertTrue(a.legalStarts.isEmpty())
        assertTrue(g.texts.none { "je conseille" in it || "départ possible" in it }, g.texts.toString())
    }

    @Test fun aStaleScheduleOrAnUnverifiedCustomRaceGivesNoAdvice() {
        val stale = controller(stale = true).also { it.selectProfile(ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_C)) }
        RaceSim(stale, totalLaps = 14, perLap = 2.8f, carCode = 3474).stand(3f)
        assertEquals(StartTyreAdvisor.Status.RULES_UNKNOWN, stale.startTyreAdvice?.status, stale.startTyreAdvice?.sentence)
        val custom = controller().also { it.sportFirst = false }
        RaceSim(custom, totalLaps = 14, perLap = 2.8f, carCode = 3474).stand(3f)
        val a = custom.startTyreAdvice
        assertTrue(a == null || a.status == StartTyreAdvisor.Status.RULES_UNKNOWN, a?.sentence)
    }

    @Test fun noAdviceOnceTheDriverHasSaidHisTyres() {
        val c = controller()
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.stand(0.5f)
        sim.say("je pars en tendres")
        assertEquals(TyreCompound.RS, c.rules.startTyre)
        sim.stand(3f)
        assertTrue(adviceTexts(sim).none { "départ possible" in it }, sim.texts.toString())
        assertNull(c.startTyreAdvice)
    }
}
