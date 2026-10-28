package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SessionMatcher
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #51 (vraie Sport C, SUGO, 28/10) : le nom du circuit publié par la fiche de l'épreuve choisie devenait une
 * « preuve » de circuit, et l'épreuve passait « reconnue : tours et circuit concordent » alors que la géométrie n'avait
 * rien reconnu. Ces tests échouent sur l'ancienne version.
 */
class SportEvidenceLeakRegressionTest {
    private val monday = LocalDate.of(2026, 10, 26)

    /**
     * Base de test : le cercle de RaceSim fait 3 016 m. [sugoLength] : longueur donnée à « SUGO » (compatible ou non avec
     * le circuit réellement parcouru), « Laguna Seca » quasi de même longueur pour le cas des circuits voisins.
     */
    private fun db(sugoLength: Int, laguna: Boolean = false) = Gt7Database.parse(
        "ID,ShortName,Maker\n3474,BRZ GT300 '21,1\n",
        "ID,Name,Country\n1,Subaru,1\n",
        "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain\n" +
            "462,SUGO International Racing Course,462,3,circuit,$sugoLength,700,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n" +
            (if (laguna) "463,WeatherTech Raceway Laguna Seca,463,3,circuit,3030,600,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,11,0\n" else "") +
            "464,Fuji International Speedway,464,3,circuit,4563,1500,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,16,0\n",
    )

    private fun info(series: Series, src: String, track: String, laps: Int, required: Set<TyreCompound> = emptySet()) =
        DailyRaceInfo(series, SourceRef(src, null, 1), trackName = track, laps = laps, mandatoryStops = 0, requiredTyres = required)

    /** Semaine du 26/10 : A SUGO 5 tours, C SUGO 14 tours RM + RS obligatoires (deux sources concordantes). */
    private fun schedule(db: Gt7Database): ScheduleState {
        val rmrs = setOf(TyreCompound.RM, TyreCompound.RS)
        val a = ScheduleBuilder.merge(Series.DAILY_A, monday, listOf(info(Series.DAILY_A, "GTPlanet", "SUGO International Racing Course", 5)), db)
        val c = ScheduleBuilder.merge(
            Series.DAILY_C, monday,
            listOf(
                info(Series.DAILY_C, "GTPlanet", "SUGO International Racing Course", 14, rmrs),
                info(Series.DAILY_C, "DG Edge", "SUGO International Racing Course", 14, rmrs),
            ),
            db,
        )
        return ScheduleState(mapOf(Series.DAILY_A to a, Series.DAILY_C to c), listOf(a, c), 1, null, stale = false)
    }

    private fun controller(db: Gt7Database): RaceController {
        var now = java.time.ZonedDateTime.of(2026, 10, 28, 15, 30, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()
        return RaceController(database = db, clock = { now++ }).also {
            it.applySchedule(schedule(db))
            // Comme le 28/10 : le pilote a CHOISI « Course Sport C » ; ce choix n'est pas une détection du mode.
            it.selectProfile(ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_C))
        }
    }

    @Test fun anotherCircuitWithAnIncompatibleLapIsNeverRecognisedAsSugo() {
        val db = db(sugoLength = 5807) // la piste parcourue (3 016 m) n'a pas la longueur de « SUGO »
        val c = controller(db)
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.start()
        sim.driveLaps(2)
        assertNotEquals(462, c.rules.trackId, "le nom publié ne fait pas le circuit")
        assertFalse("reconnue" in c.sessionIdentityText.orEmpty(), c.sessionIdentityText)
        assertTrue(sim.texts.none { "reconnue" in it || "Circuit reconnu" in it }, sim.texts.toString())
        assertTrue(c.journal.recent().any { "Circuit publié SUGO International Racing Course non retenu" in it.text })
        assertTrue(c.journal.recent().none { it.decision?.contains("confirmée") == true }, c.journal.recent().map { it.decision }.toString())
    }

    @Test fun sugoWithoutGeometryIsOnlyProbableButPreparedFromTheGrid() {
        val db = db(sugoLength = 3016)
        val c = controller(db)
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        // Grille (tour 0, 14 tours annoncés) : fiche C préparée sous statut probable, avant tout tour.
        sim.stand(3f)
        assertEquals(Series.DAILY_C, c.profile.id.series)
        assertTrue("probable" in c.sessionIdentityText.orEmpty(), c.sessionIdentityText)
        // Gomme de départ inconnue, RM + RS obligatoires : jamais « 2 arrêts » présentés comme définitifs.
        assertTrue(sim.texts.any { "au moins un arrêt pour changer de gomme" in it }, sim.texts.toString())
        assertTrue(sim.texts.none { "Stratégie : 2 arrêts" in it }, sim.texts.toString())
        while (sim.lap == 0) sim.step(30f)
        sim.driveLaps(2)
        // Longueur compatible : circuit de travail probable, jamais une épreuve « reconnue ».
        assertEquals(462, c.rules.trackId)
        assertTrue("probable" in c.sessionIdentityText.orEmpty(), c.sessionIdentityText)
        assertTrue(sim.texts.none { "reconnue" in it }, sim.texts.toString())
        // Le circuit de la fiche (attendu) sert de circuit de travail, mais n'est jamais compté comme une preuve.
        assertEquals(SessionMatcher.Confidence.PROBABLE, (c.sessionIdentity as SessionMatcher.Result.Match).confidence)
        assertTrue(c.journal.recent().none { it.decision?.contains("confirmée") == true }, c.journal.recent().map { it.decision }.toString())
    }

    @Test fun onlyAMeasuredCircuitRecognisesTheRace() {
        val db = db(sugoLength = 3016)
        val sched = schedule(db)
        val today = monday.plusDays(2)
        val byLength = SessionMatcher.match(sched.editions, 14, 462, today, trackMeasured = false)
        assertIs<SessionMatcher.Result.Match>(byLength)
        assertEquals(SessionMatcher.Confidence.PROBABLE, byLength.confidence)
        assertTrue("longueur du tour compatible" in byLength.evidence)
        val measured = SessionMatcher.match(sched.editions, 14, 462, today, trackMeasured = true)
        assertIs<SessionMatcher.Result.Match>(measured)
        assertEquals(SessionMatcher.Confidence.RECOGNIZED, measured.confidence)
        assertTrue("circuit mesuré" in measured.evidence)
    }

    @Test fun neighbouringLengthsAreNeverDecisive() {
        // SUGO 3 016 et Laguna Seca 3 030 dans la base : la longueur ne tranche pas, rien n'est reconnu.
        val db = db(sugoLength = 3016, laguna = true)
        val c = controller(db)
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.start()
        sim.driveLaps(2)
        assertFalse("reconnue" in c.sessionIdentityText.orEmpty(), c.sessionIdentityText)
        assertTrue(sim.texts.none { "Circuit reconnu" in it }, sim.texts.toString())
    }

    @Test fun lapsTellAAndCApartButNothingProvesTheOfficialMode() {
        val db = db(sugoLength = 3016)
        val sched = schedule(db)
        val today = monday.plusDays(2)
        val a = SessionMatcher.match(sched.editions, 5, null, today)
        val cc = SessionMatcher.match(sched.editions, 14, null, today)
        assertIs<SessionMatcher.Result.Match>(a)
        assertIs<SessionMatcher.Result.Match>(cc)
        assertEquals(Series.DAILY_A, a.series)
        assertEquals(Series.DAILY_C, cc.series)
        assertEquals(SessionMatcher.Confidence.PROBABLE, cc.confidence)
    }

    @Test fun aCustomRaceOfFourteenLapsNeverBecomesSport() {
        val db = db(sugoLength = 3016)
        var now = java.time.ZonedDateTime.of(2026, 10, 28, 15, 30, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()
        val c = RaceController(database = db, clock = { now++ }).also { it.applySchedule(schedule(db)) }
        c.selectProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.start()
        sim.driveLaps(3)
        assertEquals(SessionMode.CUSTOM, c.profile.id.mode)
        assertNotEquals(SessionMode.SPORT_OFFICIAL, c.profile.id.mode)
    }

    @Test fun qualifyingWithoutLapsStartsNoRace() {
        val db = db(sugoLength = 3016)
        val c = controller(db)
        c.selectProfile(ProfileId(Series.DAILY_C, SessionKind.QUALIFYING))
        val sim = RaceSim(c, totalLaps = 0, perLap = 2.8f, carCode = 3474)
        sim.start()
        sim.driveLaps(2)
        assertEquals(SessionKind.QUALIFYING, c.profile.id.kind)
        assertTrue(sim.texts.none { "reconnue" in it || "Stratégie :" in it }, sim.texts.toString())
    }
}
