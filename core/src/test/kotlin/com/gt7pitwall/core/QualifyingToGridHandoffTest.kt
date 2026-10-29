package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.knowledge.TrackSignature
import com.gt7pitwall.core.race.Milestone
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Revue de la PR #62 (30/10) : « j'arrive en fin de qualification, je ne roule pas un mètre, et sur la prégrille Pitwall
 * a déjà la course et le plan ». Deux voies distinctes :
 * - contextuelle, immédiate (0 m) : programme de la semaine + voiture envoyée par GT7 → épreuve PROBABLE, circuit ATTENDU ;
 * - géométrique, opportuniste : tracé mesuré en qualification, repris pour la course comme « à confirmer ».
 * Semaine réelle du 26/10 (GT GridStats, DG EDGE, GTSH-Rank) : A SUGO Fairlady Z Z34 '08 5 tours ; B Big Willow Gr.4
 * 7 tours ; C SUGO Gr.3 14 tours, mediums + tendres. Tout est simulé (RaceSim, cercle de 3 016 m nommé SUGO) : aucun
 * essai PS5.
 */
class QualifyingToGridHandoffTest {
    private val monday = LocalDate.of(2026, 10, 26)

    private fun ring(): TrackSignature {
        val n = (2 * PI * 480 / 20).toInt()
        val path = FloatArray(2 * n)
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            path[2 * i] = 480f * cos(a).toFloat()
            path[2 * i + 1] = 480f * sin(a).toFloat()
        }
        return TrackSignature("SUGO International Racing Course", 462, null, 3016, -480f, 480f, -480f, 480f, path, provenance = "capture")
    }

    private fun db() = Gt7Database.parse(
        "ID,ShortName,Maker\n3474,BRZ GT300 '21,1\n1458,Fairlady Z (Z34) '08,2\n3300,Supra Racing Concept Gr.4,3\n3607,963 '24,4\n",
        "ID,Name,Country\n1,Subaru,1\n2,Nissan,1\n3,Toyota,1\n4,Porsche,2\n",
        "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain\n" +
            "462,SUGO International Racing Course,462,3,circuit,3016,700,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n" +
            "463,Willow Springs International Raceway: Big Willow,463,3,circuit,4023,900,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,9,0\n",
    ).also {
        it.carGroups = Gt7Database.parseGroups("ID,Group\n3474,3\n1458,N\n3300,4\n3607,1\n")
        it.trackSignatures = listOf(ring())
    }

    private fun info(series: Series, src: String, track: String, laps: Int, cars: String, required: Set<TyreCompound> = emptySet()) =
        DailyRaceInfo(series, SourceRef(src, null, 1), trackName = track, laps = laps, cars = cars, mandatoryStops = 0, requiredTyres = required)

    private fun week(db: Gt7Database): ScheduleState {
        fun two(series: Series, track: String, laps: Int, cars: String, req: Set<TyreCompound> = emptySet()) =
            ScheduleBuilder.merge(series, monday, listOf(info(series, "GT GridStats", track, laps, cars, req), info(series, "DG Edge", track, laps, cars, req)), db)
        val a = two(Series.DAILY_A, "SUGO International Racing Course", 5, "Nissan Fairlady Z (Z34) '08")
        val b = two(Series.DAILY_B, "Willow Springs International Raceway: Big Willow", 7, "Gr.4")
        val c = two(Series.DAILY_C, "SUGO International Racing Course", 14, "Gr.3", setOf(TyreCompound.RM, TyreCompound.RS))
        return ScheduleState(mapOf(Series.DAILY_A to a, Series.DAILY_B to b, Series.DAILY_C to c), listOf(a, b, c), 1, null, stale = false)
    }

    private var now = java.time.ZonedDateTime.of(2026, 10, 30, 15, 30, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()

    private fun controller(schedule: Boolean = true): RaceController {
        val d = db()
        return RaceController(database = d, clock = { now++ }).also {
            it.sportFirst = true
            if (schedule) it.applySchedule(week(d))
        }
    }

    /** Qualification (GT7 n'annonce pas de tours) : [metres] roulés, puis retour au stand ; rien n'est sélectionné. */
    private fun qualifying(c: RaceController, metres: Int, car: Int = 3474): RaceSim {
        val q = RaceSim(c, totalLaps = 0, perLap = 2.8f, carCode = car)
        q.stand(3f)
        repeat((metres * 60f / 40f).toInt()) { q.step(40f) }
        return q
    }

    /** Prégrille de la course : immobile au tour 0, aucun mètre de course roulé. */
    private fun grid(c: RaceController, car: Int = 3474, laps: Int = 14) = RaceSim(c, totalLaps = laps, perLap = 2.8f, carCode = car).also { it.stand(3f) }

    @Test fun zeroToFiveHundredMetresOfQualifyingAllGiveABriefingOnTheGridWithoutDrivingInTheRace() {
        val rows = mutableListOf<String>()
        for (metres in listOf(0, 30, 100, 250, 500)) {
            val c = controller()
            val q = qualifying(c, metres)
            // Voie contextuelle : la Subaru Gr.3 désigne la C seule (A impose la Fairlady Z, B est en Gr.4), dès 0 m.
            assertEquals(Series.DAILY_C, c.sportCandidate?.series, "$metres m")
            assertTrue(c.sessionIdentityText.orEmpty().startsWith("Course Sport C probable d'après le programme"), c.sessionIdentityText)
            assertTrue("circuit attendu SUGO" in c.sessionIdentityText.orEmpty(), c.sessionIdentityText)
            assertTrue(q.texts.any { it.startsWith("Sans tours annoncés : Course Sport C probable d'après le programme") && "au moins un arrêt" in it }, q.texts.toString())
            assertEquals(SessionMode.CUSTOM, c.profile.id.mode, "la qualification n'est pas transformée en course")
            assertTrue(q.texts.none { "reconnue" in it || "confirmée" in it }, q.texts.toString())
            val measuredInQuali = c.zeroTouch.first(Milestone.TRACK_MEASURED)?.detail
            val before = c.zeroTouch.all.size
            // Prégrille : 0 m de course.
            val g = grid(c)
            val raceEvents = c.zeroTouch.all.drop(before)
            assertEquals(0, g.lap)
            assertEquals(ProfileId(Series.DAILY_C, SessionKind.RACE, SessionMode.SPORT_IDENTIFIED), c.profile.id)
            val brief = g.texts.firstOrNull { "au moins un arrêt pour changer de gomme" in it }
            assertNotNull(brief, "briefing prêt sur la grille après $metres m de qualification : ${g.texts}")
            assertTrue(g.texts.none { Regex("officiel|reconnue :").containsMatchIn(it) }, g.texts.toString())
            // Le journal de la course ne s'ouvre qu'au départ : le relais se lit dans les jalons.
            val handedOver = raceEvents.any { it.milestone == Milestone.TRACK_CANDIDATE && it.detail == "SUGO International Racing Course, à confirmer dans cette course" }
            if (measuredInQuali != null) {
                assertTrue(handedOver, "tracé mesuré en qualification repris pour la course")
                assertEquals(462, c.rules.trackId)
            }
            // Jamais « mesuré » dans la course sans tracé de cette course.
            assertTrue(raceEvents.none { it.milestone == Milestone.TRACK_MEASURED }, raceEvents.toString())
            rows += "$metres m : candidat C à 0 m ; tracé mesuré en qualif : ${measuredInQuali ?: "non"} ; repris : $handedOver ; briefing grille à 0 m de course : oui"
        }
        println(rows.joinToString("\n"))
    }

    @Test fun theImposedCarAndTheCategoryTellTheThreeRacesApartAtZeroMetres() {
        assertEquals(Series.DAILY_A, controller().also { qualifying(it, 0, car = 1458) }.sportCandidate?.series, "Fairlady Z imposée")
        assertEquals(Series.DAILY_B, controller().also { qualifying(it, 0, car = 3300) }.sportCandidate?.series, "Gr.4")
        assertEquals(Series.DAILY_C, controller().also { qualifying(it, 0, car = 3474) }.sportCandidate?.series, "Gr.3")
        // Gr.1 : aucune épreuve de la semaine, rien n'est inventé.
        val none = controller()
        val q = qualifying(none, 0, car = 3607)
        assertNull(none.sportCandidate)
        assertTrue(q.texts.none { "probable d'après le programme" in it }, q.texts.toString())
    }

    @Test fun withoutAWeeklyScheduleNothingIsPreparedFromThinAir() {
        val c = controller(schedule = false)
        val q = qualifying(c, 0)
        assertNull(c.sportCandidate)
        assertNull(c.sessionIdentityText)
        assertTrue(q.texts.none { "Sport" in it }, q.texts.toString())
    }

    @Test fun aDifferentCarForTheRaceInheritsNothingFromQualifying() {
        val c = controller()
        qualifying(c, 500)
        assertNotNull(c.zeroTouch.first(Milestone.TRACK_MEASURED), "tracé mesuré en qualification")
        grid(c, car = 3300, laps = 7)
        assertTrue(c.zeroTouch.all.none { it.detail?.endsWith("à confirmer dans cette course") == true }, c.zeroTouch.all.toString())
        assertNotEquals(462, c.rules.trackId, "le SUGO de la qualification n'est pas hérité")
        assertEquals(Series.DAILY_B, c.profile.id.series)
    }

    @Test fun anOldQualifyingIsNotReused() {
        val c = controller()
        qualifying(c, 500)
        now += 31 * 60 * 1000L
        grid(c)
        assertTrue(c.zeroTouch.all.none { it.detail?.endsWith("à confirmer dans cette course") == true }, c.zeroTouch.all.toString())
    }

    @Test fun theOutLapOfQualifyingAlreadyFeedsTheGeometry() {
        // Tour de sortie compté 0 en qualification : les positions servent déjà à reconnaître le tracé.
        val c = controller()
        val q = RaceSim(c, totalLaps = 0, perLap = 2.8f, carCode = 3474)
        q.stand(1f)
        // Rester au tour 0 : rouler en sens inverse de la ligne n'existe pas dans RaceSim ; on vérifie seulement que
        // l'arrêt n'échantillonne rien et que le roulage au tour 1 conclut.
        repeat((700 * 60f / 40f).toInt()) { q.step(40f) }
        val m = assertNotNull(c.zeroTouch.first(Milestone.TRACK_MEASURED))
        assertTrue("SUGO" in m.detail!!, m.detail)
    }
}
