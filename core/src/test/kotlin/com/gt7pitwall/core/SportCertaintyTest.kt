package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.profile.PitwallLocalCareer
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * « Sport officiel » n'existe que par une action explicite du pilote, même après redémarrage ; un appel au stand dicté
 * par un règlement incertain (une seule fiche, course seulement probable) n'est jamais présenté comme certain.
 */
class SportCertaintyTest {
    private val monday = LocalDate.of(2026, 10, 19)
    private val db = Gt7Database.parse(
        "ID,ShortName,Maker\n3607,Test GT,1\n",
        "ID,Name,Country\n1,Testeur,1\n",
        "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain\n" +
            "900,Anneau de Test,900,3,circuit,3016,500,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n",
    )

    private fun schedule(sources: List<String>): ScheduleState {
        val b = ScheduleBuilder.merge(
            Series.DAILY_B, monday,
            sources.map { DailyRaceInfo(Series.DAILY_B, SourceRef(it, null, 1), trackName = "Anneau de Test", laps = 6, mandatoryStops = 1, requiredTyres = setOf(TyreCompound.RM)) },
            db,
        )
        return ScheduleState(mapOf(Series.DAILY_B to b), listOf(b), 1, null, stale = false)
    }

    private var now = java.time.ZonedDateTime.of(2026, 10, 20, 20, 0, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()
    private fun controller(store: FakeStorage, sources: List<String>) =
        RaceController(database = db, storage = store, clock = { now++ }).also { it.applySchedule(schedule(sources)) }

    // --- « Sport officiel » : seulement par le pilote ---

    @Test
    fun nothingButTheDriverMakesARaceOfficialEvenAfterARestart() {
        val store = FakeStorage()
        val c = controller(store, listOf("GTPlanet", "Victory"))
        // Installation neuve : course personnalisée ; le programme de la semaine ne change pas le type actif.
        assertNotEquals(SessionMode.SPORT_OFFICIAL, c.profile.id.mode)
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        s.driveLaps(1)
        assertEquals(SessionMode.SPORT_IDENTIFIED, c.profile.id.mode)
        // Redémarrage de l'application : le type relu n'est jamais promu « officiel ».
        val restarted = RaceController(database = db, storage = store, clock = { now++ })
        restarted.applySchedule(schedule(listOf("GTPlanet", "Victory")))
        assertEquals(SessionMode.SPORT_IDENTIFIED, restarted.profile.id.mode)
        // La réponse vocale « la B » ne vaut pas déclaration d'officialité non plus (testée dans SportIntelligenceTest).
        // Seul le bouton « Sport officiel » le fait.
        restarted.selectProfile(ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_B))
        assertEquals(ProfileId(Series.DAILY_B, SessionKind.RACE, SessionMode.SPORT_OFFICIAL), restarted.profile.id)
    }

    @Test
    fun theCareerOnlyCountsRacesTheDriverDeclaredOfficial() {
        val store = FakeStorage()
        val c = controller(store, listOf("GTPlanet", "Victory"))
        val auto = RaceSim(c, totalLaps = 6, perLap = 3f)
        auto.start()
        auto.driveLaps(7)
        assertEquals(0, PitwallLocalCareer.fromSessions(store.sessions).verifiedSportRaces)
        c.selectProfile(ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_B))
        val declared = RaceSim(c, totalLaps = 6, perLap = 3f)
        declared.start()
        declared.driveLaps(7)
        assertEquals(1, PitwallLocalCareer.fromSessions(store.sessions).verifiedSportRaces)
    }

    // --- Appel au stand et règlement incertain ---

    private fun boxCalls(sources: List<String>, mode: SessionMode = SessionMode.SPORT_OFFICIAL, confirm: Boolean = false): Pair<RaceController, List<String>> {
        val c = controller(FakeStorage(), sources)
        c.selectProfile(ProfileId.of(mode, Series.DAILY_B))
        if (confirm) c.confirmRules("2026-10-20")
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        s.driveLaps(5)
        return c to s.texts.filter { it.startsWith("Box, box") || it.startsWith("Stand au prochain tour") || it.startsWith("Dernier secteur") }
    }

    @Test
    fun aMandatoryStopFromASingleFactSheetIsCalledAsToBeConfirmed() {
        val (c, calls) = boxCalls(listOf("Victory"))
        assertEquals("seule la fiche Victory la donne", c.regulationDoubt())
        assertTrue(calls.isNotEmpty(), calls.toString())
        assertTrue(calls.all { "Obligation à confirmer : seule la fiche Victory la donne." in it }, calls.toString())
    }

    @Test
    fun twoConcordantSourcesOrTheDriversConfirmationMakeItCertain() {
        val (c, calls) = boxCalls(listOf("GTPlanet", "Victory"))
        assertNull(c.regulationDoubt())
        assertTrue(calls.isNotEmpty() && calls.none { "à confirmer" in it }, calls.toString())
        val (confirmed, confirmedCalls) = boxCalls(listOf("Victory"), confirm = true)
        assertNull(confirmed.regulationDoubt())
        assertTrue(confirmedCalls.none { "à confirmer" in it }, confirmedCalls.toString())
    }

    @Test
    fun aProbableRaceMakesItsObligationsUncertain() {
        // Profil C resté actif, GT7 annonce 6 tours : la B est seulement probable (tours sans circuit reconnu).
        val c = controller(FakeStorage(), listOf("GTPlanet", "Victory"))
        val cRace = ScheduleBuilder.merge(Series.DAILY_C, monday, listOf(DailyRaceInfo(Series.DAILY_C, SourceRef("GTPlanet", null, 1), trackName = "Autre", laps = 9)), db)
        c.applySchedule(schedule(listOf("GTPlanet", "Victory")).let { it.copy(editions = it.editions + (Series.DAILY_C to cRace)) })
        c.selectProfile(ProfileId(Series.DAILY_C, SessionKind.RACE))
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        assertEquals("course B seulement probable", c.regulationDoubt())
        // Une course personnalisée garde le règlement du pilote, sans réserve.
        val custom = controller(FakeStorage(), listOf("Victory"))
        custom.selectProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
        assertNull(custom.regulationDoubt())
        assertFalse(custom.profile.id.sport)
    }
}
