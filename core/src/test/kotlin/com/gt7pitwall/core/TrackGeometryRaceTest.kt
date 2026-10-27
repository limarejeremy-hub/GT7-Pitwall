package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.knowledge.TrackGeometryRecognizer
import com.gt7pitwall.core.knowledge.TrackSignature
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SessionMatcher
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.strategy.RaceRules
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L'ingénieur reconnaît le circuit par sa géométrie pendant le premier tour (et non plus à sa fin, par la longueur) :
 * stratégie et reconnaissance de la course Sport en profitent plus tôt. Course simulée (RaceSim : cercle de 480 m de
 * rayon centré sur l'origine), pas un essai PS5.
 */
class TrackGeometryRaceTest {
    private fun circle(name: String, course: Int?, reverseCourse: Int?, clockwise: Boolean, cx: Float = 0f): TrackSignature {
        val n = (2 * PI * 480 / 20).toInt()
        val path = FloatArray(2 * n)
        for (i in 0 until n) {
            val a = (if (clockwise) -1 else 1) * 2 * PI * i / n
            path[2 * i] = cx + 480f * cos(a).toFloat()
            path[2 * i + 1] = 480f * sin(a).toFloat()
        }
        return TrackSignature(name, course, reverseCourse, 3016, cx - 480f, cx + 480f, -480f, 480f, path)
    }

    private fun db(vararg sigs: TrackSignature) = Gt7Database.parse(
        "ID,ShortName,Maker\n3607,Test GT,1\n",
        "ID,Name,Country\n1,Testeur,1\n",
        "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain\n" +
            "900,Anneau de Test,900,3,circuit,3016,500,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n" +
            "902,Anneau de Test Reverse,900,3,circuit,3016,500,10.0,8.0,0,0,0,0,0,0,1,1,20.0,0,10,0\n" +
            "901,Autre Circuit,901,3,circuit,5800,900,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,16,0\n",
    ).also { it.trackSignatures = sigs.toList() }

    private var now = java.time.ZonedDateTime.of(2026, 10, 20, 20, 0, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()

    /** Avance jusqu'à [fraction] du premier tour. */
    private fun RaceSim.driveInto(fraction: Float) {
        while (lap < 1) step(60f)
        val target = (fraction * circumference / (60f / 60f)).toInt()
        repeat(target) { step(60f) }
    }

    @Test
    fun theCircuitIsRecognisedDuringTheFirstLapAndTheStrategyUsesIt() {
        val c = RaceController(database = db(circle("Anneau de Test", 900, 902, clockwise = false)), storage = FakeStorage(), clock = { now++ })
        c.selectProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
        c.setRules(RaceRules())
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        s.driveInto(0.4f)
        assertEquals(1, s.lap, "toujours dans le premier tour")
        assertEquals(900, c.rules.trackId)
        val v = c.trackGeometryVerdict
        assertIs<TrackGeometryRecognizer.Verdict.Identified>(v)
        assertTrue(v.distanceM < 0.4f * s.circumference + 50f)
        s.driveLaps(1)
        assertTrue(s.texts.any { it == "Circuit reconnu : Anneau de Test." }, s.texts.toString())
    }

    @Test
    fun aStaleStoredCircuitIsReplacedByTheGeometry() {
        // Course du 26/10 : circuit d'une course précédente resté dans le profil.
        val store = FakeStorage(rules = RaceRules(trackId = 901))
        val c = RaceController(database = db(circle("Anneau de Test", 900, 902, clockwise = false)), storage = store, clock = { now++ })
        c.selectProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
        c.setRules(RaceRules(trackId = 901))
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        s.driveInto(0.5f)
        assertEquals(900, c.rules.trackId)
        assertTrue(store.journals.values.flatten().any { "Circuit reconnu par la géométrie : Anneau de Test" in it && "remplacé" in it }, store.journals.toString())
    }

    @Test
    fun theReverseLayoutIsRecognisedByItsDirection() {
        // La signature est parcourue dans l'autre sens : c'est la configuration inverse.
        val c = RaceController(database = db(circle("Anneau de Test", 900, 902, clockwise = true)), storage = FakeStorage(), clock = { now++ })
        c.selectProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
        c.setRules(RaceRules())
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        s.driveInto(0.5f)
        assertEquals(902, c.rules.trackId)
        assertTrue((c.trackGeometryVerdict as TrackGeometryRecognizer.Verdict.Identified).reverse)
    }

    @Test
    fun anUnknownCircuitChangesNothingAndIsJournaled() {
        val store = FakeStorage(rules = RaceRules(trackId = 901))
        val c = RaceController(database = db(circle("Ailleurs", 901, null, clockwise = false, cx = 20_000f)), storage = store, clock = { now++ })
        c.selectProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
        c.setRules(RaceRules(trackId = 901))
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        s.driveInto(0.6f)
        // Aucune signature ne correspond : le circuit enregistré n'est pas changé par la géométrie (la vérification
        // par la longueur, en fin de tour, garde son rôle).
        assertEquals(901, c.rules.trackId)
        assertIs<TrackGeometryRecognizer.Verdict.Unknown>(c.trackGeometryVerdict)
        assertTrue(store.journals.values.flatten().any { "Géométrie : aucune signature ne correspond" in it }, store.journals.toString())
    }

    @Test
    fun aSportRaceIsRecognisedBeforeTheEndOfTheFirstLap() {
        val database = db(circle("Anneau de Test", 900, 902, clockwise = false))
        val src = listOf(SourceRef("GTPlanet", null, 1), SourceRef("Victory", null, 1))
        val monday = LocalDate.of(2026, 10, 19)
        val b = ScheduleBuilder.merge(Series.DAILY_B, monday, src.map { DailyRaceInfo(Series.DAILY_B, it, trackName = "Anneau de Test", laps = 6, mandatoryStops = 1) }, database)
        val c = RaceController(database = database, storage = FakeStorage(), clock = { now++ })
        c.applySchedule(ScheduleState(mapOf(Series.DAILY_B to b), listOf(b), 1, null, stale = false))
        c.selectProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        s.driveInto(0.4f)
        assertEquals(1, s.lap)
        val id = c.sessionIdentity
        assertIs<SessionMatcher.Result.Match>(id)
        assertEquals(SessionMatcher.Confidence.RECOGNIZED, id.confidence)
        // Reconnue ≠ officielle.
        assertEquals(SessionMode.SPORT_IDENTIFIED, c.profile.id.mode)
        assertEquals(1, c.rules.mandatoryStops)
    }
}
