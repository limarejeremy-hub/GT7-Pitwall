package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.SessionMatcher
import com.gt7pitwall.core.sport.SessionMatcher.Result
import com.gt7pitwall.core.sport.Sourced
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Zero touch : l'épreuve Sport (A, B, C) est reconnue par la télémétrie, jamais devinée, jamais mélangée. */
class SessionIdentityTest {
    private val feed = javaClass.getResource("/gtplanet-daily-2026-10-19.xml")!!.readText()
    private val db: Gt7Database = run {
        val dir = File("../app/src/main/assets/gt7db")
        Gt7Database.parse(File(dir, "cars.csv").readText(), File(dir, "maker.csv").readText(), File(dir, "course.csv").readText())
    }
    private val tuesday = LocalDate.of(2026, 10, 20)

    // Semaine du 19/10 (fiche GTPlanet réelle) : A Gilles-Villeneuve 5 tours, B Interlagos 6 tours, C Suzuka 11 tours.
    private val schedule = ScheduleBuilder.build(GtPlanetFeed.parse(feed, 1), db, tuesday, 1, null)

    @Test
    fun theNumberOfLapsAnnouncedByGt7DesignatesTheRace() {
        val m = SessionMatcher.match(schedule.editions, 11, null, tuesday)
        assertIs<Result.Match>(m)
        assertEquals(Series.DAILY_C, m.series)
        assertEquals(Series.DAILY_A, (SessionMatcher.match(schedule.editions, 5, null, tuesday) as Result.Match).series)
    }

    @Test
    fun contradictingEvidenceExcludesARace() {
        // Course réelle du 26/10 : 14 tours, aucune course de la semaine n'en a 14.
        assertIs<Result.NoMatch>(SessionMatcher.match(schedule.editions, 14, null, tuesday))
        // 6 tours comme la B, mais à Suzuka (circuit de la C) : ni l'une ni l'autre.
        assertIs<Result.NoMatch>(SessionMatcher.match(schedule.editions, 6, 10, tuesday))
        // Les deux preuves concordent.
        val both = SessionMatcher.match(schedule.editions, 11, 10, tuesday)
        assertIs<Result.Match>(both)
        assertEquals(2, both.evidence.size)
    }

    @Test
    fun twoRacesWithTheSameDistanceWaitForTheTrack() {
        val c = schedule.editions.getValue(Series.DAILY_C)
        val eds = schedule.editions + (Series.DAILY_A to schedule.editions.getValue(Series.DAILY_A).copy(laps = Sourced(11, FieldStatus.PROBABLE)))
        assertEquals(Result.Ambiguous(listOf(Series.DAILY_A, Series.DAILY_C)), SessionMatcher.match(eds, 11, null, tuesday))
        assertEquals(Series.DAILY_C, (SessionMatcher.match(eds, 11, c.trackId, tuesday) as Result.Match).series)
    }

    @Test
    fun nothingIsDecidedWithoutAProgrammeForToday() {
        assertEquals(Result.Unknown, SessionMatcher.match(schedule.editions, 11, null, LocalDate.of(2026, 11, 3)))
        assertEquals(Result.Unknown, SessionMatcher.match(schedule.editions, 0, null, tuesday))
    }

    private fun controller(): RaceController {
        var now = java.time.ZonedDateTime.of(2026, 10, 20, 20, 0, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()
        return RaceController(database = db, storage = FakeStorage(), clock = { now++ }).also { it.applySchedule(schedule) }
    }

    @Test
    fun aStaleRaceBProfileIsDroppedWhenGt7AnnouncesFourteenLaps() {
        val c = controller()
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        assertEquals(6, c.rules.totalLaps)
        val s = RaceSim(c, totalLaps = 14, perLap = 3f)
        s.start()
        assertEquals(SessionMode.CUSTOM, c.profile.id.mode, c.sessionIdentityText)
        assertTrue(s.texts.any { "ne correspond pas" in it && "je n'applique pas son règlement" in it }, s.texts.toString())
        assertTrue(c.sessionIdentityText!!.startsWith("Hors programme Sport"))
    }

    @Test
    fun theRightRaceIsSelectedWithoutTouchingTheScreen() {
        val c = controller()
        c.selectProfile(ProfileId(Series.DAILY_A, SessionKind.RACE))
        val s = RaceSim(c, totalLaps = 11, perLap = 3f)
        s.start()
        assertEquals(ProfileId(Series.DAILY_C, SessionKind.RACE), c.profile.id)
        assertEquals(1, c.rules.mandatoryStops)
        assertEquals(2, c.rules.fuelMultiplier)
        assertTrue(s.texts.any { it.startsWith("Course Sport C probable (11 tours).") && "11 tours. " in it }, s.texts.toString())
        assertTrue(c.sessionIdentityText!!.startsWith("Course Sport C probable"))
    }

    @Test
    fun aCustomRaceNeedsTwoProofsBeforeBecomingSport() {
        val c = controller()
        c.selectProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
        val s = RaceSim(c, totalLaps = 11, perLap = 3f)
        s.start()
        assertEquals(SessionMode.CUSTOM, c.profile.id.mode)
        assertEquals("Épreuve à confirmer : Course Sport C", c.sessionIdentityText)
    }
}
