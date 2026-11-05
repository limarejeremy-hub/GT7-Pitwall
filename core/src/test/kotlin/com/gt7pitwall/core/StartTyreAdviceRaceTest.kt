package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.Milestone
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #65, côté contrôleur : un conseil sur la grille, jamais une gomme enregistrée, rien si le pilote l'a déjà dite. */
class StartTyreAdviceRaceTest {
    private val db = Gt7Database.parse(
        "ID,ShortName,Maker\n3474,BRZ GT300 '21,1\n",
        "ID,Name,Country\n1,Subaru,1\n",
        "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain\n" +
            "462,SUGO International Racing Course,462,3,circuit,3016,700,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n",
    ).also { it.carGroups = Gt7Database.parseGroups("ID,Group\n3474,3\n") }

    private fun controller(): RaceController {
        val req = setOf(TyreCompound.RM, TyreCompound.RS)
        val monday = LocalDate.of(2026, 10, 26)
        fun info(src: String) = DailyRaceInfo(Series.DAILY_C, SourceRef(src, null, 1), trackName = "SUGO International Racing Course", laps = 14, cars = "Gr.3", mandatoryStops = 0, requiredTyres = req)
        val c = ScheduleBuilder.merge(Series.DAILY_C, monday, listOf(info("GT GridStats"), info("DG Edge")), db)
        var now = java.time.ZonedDateTime.of(2026, 10, 30, 15, 30, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()
        return RaceController(database = db, clock = { now++ }).also {
            it.sportFirst = true
            it.applySchedule(ScheduleState(mapOf(Series.DAILY_C to c), listOf(c), 1, null, stale = false))
        }
    }

    @Test fun oneAdviceOnTheGridWithoutHistoryAndNoTyreRecorded() {
        val c = controller()
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.stand(5f)
        val said = sim.texts.filter { it.startsWith("Sans place de grille mesurée") || Regex("^P\\d+").containsMatchIn(it) }
        assertEquals(1, said.size, sim.texts.toString())
        assertTrue("historique insuffisant pour en conseiller un" in said.single(), said.single())
        assertNull(c.rules.startTyre, "un conseil n'est jamais une gomme montée")
        assertNotNull(c.zeroTouch.first(Milestone.START_TYRE_ADVICE))
        assertNotNull(c.startTyreAdvice)
        // Départ : plus rien sur la gomme.
        while (sim.lap == 0) sim.step(30f)
        sim.driveLaps(1)
        assertEquals(1, sim.texts.count { it.startsWith("Sans place de grille mesurée") }, sim.texts.toString())
    }

    @Test fun noAdviceOnceTheDriverHasSaidHisTyres() {
        val c = controller()
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.stand(1f)
        sim.say("je pars en tendres")
        assertEquals(TyreCompound.RS, c.rules.startTyre)
        sim.stand(5f)
        assertTrue(sim.texts.none { it.startsWith("Sans place de grille mesurée") }, sim.texts.toString())
        assertNull(c.startTyreAdvice)
    }
}
