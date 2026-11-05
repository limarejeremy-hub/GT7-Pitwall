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
        val quali = q.texts.filter { it.startsWith("Si c'est bien la Course Sport C : ") }
        assertEquals(1, quali.size, q.texts.toString())
        assertTrue("historique insuffisant pour en conseiller un" in quali.single(), quali.single())
        // File radio : le conseil passe avant l'annonce (raccourcie) de l'épreuve candidate.
        val iAdvice = q.texts.indexOf(quali.single())
        val iCandidate = q.texts.indexOfFirst { it.startsWith("Course Sport C probable d'après le programme") }
        assertTrue(iCandidate < 0 || iAdvice < iCandidate, q.texts.toString())
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
        // Le conseil passe avant le long briefing de la grille.
        val iBrief = g.texts.indexOfFirst { it.startsWith("Briefing ") }
        val iAnnounce = g.texts.indexOfFirst { it.startsWith("Course Sport C probable (14 tours") }
        assertTrue(iAnnounce < 0 || g.texts.indexOf(said.single()) < iAnnounce, g.texts.toString())
        assertTrue(iBrief < 0 || g.texts.indexOf(said.single()) < iBrief, g.texts.toString())
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

    @Test fun anotherRaceRightAfterIsNeverSilencedByTheAntiRepeat() {
        // C (SUGO, 14 tours) puis une autre épreuve de 7 tours dans les 30 min, même voiture, même conclusion
        // (historique insuffisant) : le conseil de la seconde course est dit.
        val req = setOf(TyreCompound.RM, TyreCompound.RS)
        val all = setOf(TyreCompound.RH, TyreCompound.RM, TyreCompound.RS)
        val monday = LocalDate.of(2026, 10, 26)
        fun info(series: Series, src: String, laps: Int) = DailyRaceInfo(
            series, SourceRef(src, null, 1), trackName = "SUGO International Racing Course", laps = laps, cars = "Gr.3",
            mandatoryStops = 0, requiredTyres = req, allowedTyres = all,
        )
        val cEd = ScheduleBuilder.merge(Series.DAILY_C, monday, listOf(info(Series.DAILY_C, "GT GridStats", 14), info(Series.DAILY_C, "DG Edge", 14)), db)
        val bEd = ScheduleBuilder.merge(Series.DAILY_B, monday, listOf(info(Series.DAILY_B, "GT GridStats", 7), info(Series.DAILY_B, "DG Edge", 7)), db)
        val c = RaceController(database = db, clock = { now++ }).also {
            it.sportFirst = true
            it.applySchedule(ScheduleState(mapOf(Series.DAILY_C to cEd, Series.DAILY_B to bEd), listOf(cEd, bEd), 1, null, stale = false))
        }
        val first = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        first.stand(10f)
        assertEquals(1, adviceTexts(first).size, first.texts.toString())
        first.start()
        first.driveLaps(1)
        val second = RaceSim(c, totalLaps = 7, perLap = 2.8f, carCode = 3474)
        second.stand(10f)
        assertEquals(Series.DAILY_B, c.profile.id.series)
        assertEquals(1, adviceTexts(second).size, "le conseil de la B n'est pas avalé par celui de la C : ${second.texts}")
    }

    @Test fun theCardStaysVisibleDuringQualifyingLapsAndDisappearsOnceTheRaceRuns() {
        assertTrue(StartTyreAdvisor.showable(totalLaps = 0, currentLap = 0), "qualification, à l'arrêt")
        assertTrue(StartTyreAdvisor.showable(totalLaps = 0, currentLap = 3), "qualification, tour 3")
        assertTrue(StartTyreAdvisor.showable(totalLaps = 14, currentLap = 0), "grille")
        assertTrue(!StartTyreAdvisor.showable(totalLaps = 14, currentLap = 1), "course lancée")
    }
}
