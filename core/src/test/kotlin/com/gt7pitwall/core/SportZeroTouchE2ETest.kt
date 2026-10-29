package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.profile.PitwallLocalCareer
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
import com.gt7pitwall.core.sport.SessionMatcher
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #30, spécification zéro touch Sport du 29/10 : « je choisis seulement dans GT7 ». Aucun appel à selectProfile :
 * le contrôleur part du profil neutre (celui d'une installation neuve), avec « Sport d'abord » préréglé une fois.
 *
 * Ce que l'UDP ne permet PAS de distinguer, et que ces tests vérifient qu'on ne prétend jamais : une course Sport en
 * ligne d'un salon privé qui la reproduit (mêmes tours, même voiture, même circuit) ; la gomme montée ; une
 * qualification d'un chrono ou d'essais libres (GT7 n'annonce pas de tours dans les trois cas).
 */
class SportZeroTouchE2ETest {
    private val monday = LocalDate.of(2026, 10, 26)

    /** SUGO a la longueur du cercle de RaceSim (3 016 m) ; Fuji non. */
    private val db = Gt7Database.parse(
        "ID,ShortName,Maker\n3474,BRZ GT300 '21,1\n3607,963 '24,2\n",
        "ID,Name,Country\n1,Subaru,1\n2,Porsche,2\n",
        "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain\n" +
            "462,SUGO International Racing Course,462,3,circuit,3016,700,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n" +
            "464,Fuji International Speedway,464,3,circuit,4563,1500,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,16,0\n",
    )

    private fun info(series: Series, src: String, track: String, laps: Int, required: Set<TyreCompound> = emptySet(), stops: Int = 0) =
        DailyRaceInfo(series, SourceRef(src, null, 1), trackName = track, laps = laps, mandatoryStops = stops, requiredTyres = required)

    private fun twoSources(series: Series, track: String, laps: Int, required: Set<TyreCompound> = emptySet()) =
        ScheduleBuilder.merge(series, monday, listOf(info(series, "GTPlanet", track, laps, required), info(series, "DG Edge", track, laps, required)), db)

    /** Semaine du 26/10 : A SUGO 5 tours ; B Fuji 8 tours, durs + mediums ; C SUGO 14 tours, mediums + tendres. */
    private fun week(): ScheduleState {
        val a = twoSources(Series.DAILY_A, "SUGO International Racing Course", 5)
        val b = twoSources(Series.DAILY_B, "Fuji International Speedway", 8, setOf(TyreCompound.RH, TyreCompound.RM))
        val c = twoSources(Series.DAILY_C, "SUGO International Racing Course", 14, setOf(TyreCompound.RM, TyreCompound.RS))
        return ScheduleState(mapOf(Series.DAILY_A to a, Series.DAILY_B to b, Series.DAILY_C to c), listOf(a, b, c), 1, null, stale = false)
    }

    /** Installation neuve, aucun choix dans Pitwall ; « Sport d'abord » préréglé une fois. */
    private fun zeroTouch(store: FakeStorage = FakeStorage(), schedule: ScheduleState? = week()): RaceController {
        var now = java.time.ZonedDateTime.of(2026, 10, 28, 15, 30, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()
        return RaceController(database = db, storage = store, clock = { now++ }).also {
            it.sportFirst = true
            schedule?.let(it::applySchedule)
        }
    }

    private fun RaceController.milestones() = zeroTouch.all.map { it.milestone }

    @Test fun sportCGridWithoutAnySelectionIsAProbableCandidateWithAConditionalBriefing() {
        val c = zeroTouch()
        assertTrue(c.zeroTouch.all.isEmpty(), "rien avant le premier paquet : pas d'annonce fictive depuis les menus")
        assertEquals(SessionMode.CUSTOM, c.profile.id.mode, "profil neutre d'une installation neuve")
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.stand(3f) // grille : tour 0, 14 tours annoncés, voiture immobile
        assertEquals(ProfileId(Series.DAILY_C, SessionKind.RACE, SessionMode.SPORT_IDENTIFIED), c.profile.id)
        assertEquals(SessionMatcher.Confidence.PROBABLE, (c.sessionIdentity as SessionMatcher.Result.Match).confidence)
        assertTrue(sim.texts.any { it.startsWith("Course Sport C probable (14 tours).") }, sim.texts.toString())
        // Deux scénarios conditionnels, aucune gomme choisie d'office.
        val brief = sim.texts.first { "au moins un arrêt pour changer de gomme" in it }
        assertTrue("Si tu pars en course mediums : arrêt fin du tour" in brief && "Si tu pars en course tendres : arrêt fin du tour" in brief, brief)
        assertTrue("Gomme de départ non confirmée" in brief, brief)
        assertTrue(sim.texts.none { Regex("officiel|(?<!non )confirmée|reconnue :").containsMatchIn(it) }, sim.texts.toString())
        // Jalons, avec délai depuis le premier paquet.
        val m = c.milestones()
        for (x in listOf(Milestone.UDP_CONNECTED, Milestone.CARCODE_CONFIRMED, Milestone.SPORT_CANDIDATE, Milestone.GRID_DETECTED, Milestone.STRATEGY_READY, Milestone.TYRE_UNCONFIRMED)) {
            assertTrue(x in m, "$x absent de $m")
        }
        assertFalse(Milestone.RACE_STARTED in m)
        assertEquals("probable", c.zeroTouch.first(Milestone.SPORT_CANDIDATE)!!.confidence)
        assertNotNull(c.zeroTouch.first(Milestone.STRATEGY_READY)!!.sinceUdpMs)
        assertEquals("3474 Subaru BRZ GT300 '21", c.zeroTouch.first(Milestone.CARCODE_CONFIRMED)!!.detail)
        // Départ : un seul briefing, la course est lancée.
        while (sim.lap == 0) sim.step(30f)
        sim.driveLaps(1)
        assertEquals(1, sim.texts.count { "au moins un arrêt pour changer de gomme" in it }, sim.texts.toString())
        assertTrue(Milestone.RACE_STARTED in c.milestones())
        assertEquals(1, c.milestones().count { it == Milestone.GRID_DETECTED })
    }

    @Test fun declaringTheStartTyreByVoicePicksTheMatchingScenario() {
        val c = zeroTouch()
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.stand(3f)
        sim.say("je pars en tendres")
        if (c.rules.startTyre == null) sim.say("oui")
        assertEquals(TyreCompound.RS, c.rules.startTyre, sim.texts.takeLast(4).toString())
        val plan = c.plan!!
        assertFalse(plan.startTyreUnknown)
        assertTrue(plan.scenarios.isEmpty(), "gomme connue : plus de scénario conditionnel")
        assertEquals(listOf(TyreCompound.RM), plan.stops.mapNotNull { it.tyreToFit })
        assertEquals(1, plan.stops.size, "un seul arrêt, justifié par la gomme imposée")
    }

    @Test fun qualifyingWithoutLapsIsOnlySuspectedThenTheRaceIsPreparedWithoutSelection() {
        val c = zeroTouch()
        val quali = RaceSim(c, totalLaps = 0, perLap = 2.8f, carCode = 3474)
        quali.start()
        quali.driveLaps(2)
        assertTrue(Milestone.QUALIFYING_SUSPECTED in c.milestones())
        assertEquals("supposée", c.zeroTouch.first(Milestone.QUALIFYING_SUSPECTED)!!.confidence)
        assertFalse(Milestone.RACE_STARTED in c.milestones())
        // Pas de phase imposée, pas de stratégie de course, pas d'épreuve tranchée : A et C sont toutes deux à SUGO.
        assertEquals(SessionMode.CUSTOM, c.profile.id.mode)
        assertNull(c.sportCandidate, "sans tours, la longueur de SUGO ne départage pas A et C")
        assertTrue(quali.texts.none { "Stratégie :" in it || "Sport C" in it }, quali.texts.toString())
        // Retour au menu puis course de 14 tours : nouvelle session, C préparée sans aucune sélection.
        val race = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        race.stand(3f)
        assertEquals(ProfileId(Series.DAILY_C, SessionKind.RACE, SessionMode.SPORT_IDENTIFIED), c.profile.id)
        assertEquals(Series.DAILY_C, c.sportCandidate?.series)
        assertTrue(race.texts.any { "au moins un arrêt pour changer de gomme" in it }, race.texts.toString())
    }

    @Test fun sportAAndSportCOnTheSameCircuitAreToldApartByLaps() {
        val a = zeroTouch()
        RaceSim(a, totalLaps = 5, perLap = 2.8f, carCode = 3474).stand(3f)
        assertEquals(Series.DAILY_A, a.profile.id.series)
        val c = zeroTouch()
        RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474).stand(3f)
        assertEquals(Series.DAILY_C, c.profile.id.series)
        assertTrue(c.rules.requiredTyres == setOf(TyreCompound.RM, TyreCompound.RS))
        assertTrue(a.rules.requiredTyres.isEmpty(), "les gommes de la C ne passent pas à la A")
    }

    @Test fun anotherRaceWithAnotherCarInheritsNeitherTyreNorRules() {
        val store = FakeStorage()
        val c = zeroTouch(store)
        val first = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        first.stand(3f)
        first.say("je pars en tendres")
        if (c.rules.startTyre == null) first.say("oui")
        assertEquals(TyreCompound.RS, c.rules.startTyre)
        first.start()
        first.driveLaps(2)
        // Course suivante : Sport B à Fuji en Porsche 963.
        val second = RaceSim(c, totalLaps = 8, perLap = 4f, carCode = 3607)
        second.stand(3f)
        assertEquals(ProfileId(Series.DAILY_B, SessionKind.RACE, SessionMode.SPORT_IDENTIFIED), c.profile.id)
        assertEquals(setOf(TyreCompound.RH, TyreCompound.RM), c.rules.requiredTyres)
        assertNull(c.rules.startTyre, "la gomme de la course précédente n'est pas héritée")
        assertNotEquals(462, c.rules.trackId, "le circuit de la C n'est pas hérité")
        val cars = c.zeroTouch.all.filter { it.milestone == Milestone.CARCODE_CONFIRMED }.map { it.detail }
        assertTrue(cars.size >= 2 && cars.last()!!.startsWith("3607"), cars.toString())
        val brief = second.texts.firstOrNull { "au moins un arrêt pour changer de gomme" in it } ?: error(second.texts.toString() + c.plan)
        assertTrue("Si tu pars en course durs" in brief && "Si tu pars en course mediums" in brief, brief)
    }

    @Test fun anIdenticalCustomLobbyIsNeverCountedAsAnOfficialSportRace() {
        // Salon privé reproduisant exactement la C (14 tours, même voiture, même circuit) : indiscernable en UDP.
        val store = FakeStorage()
        val c = zeroTouch(store)
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.start()
        sim.driveLaps(15)
        assertNotEquals(SessionMode.SPORT_OFFICIAL, c.profile.id.mode)
        assertEquals(SessionMode.SPORT_IDENTIFIED, c.profile.id.mode, "règlement de la semaine utilisé, jamais « officiel »")
        assertEquals(0, PitwallLocalCareer.fromSessions(store.sessions).verifiedSportRaces)
        assertTrue(sim.texts.none { Regex("sport officiel|course officielle").containsMatchIn(it.lowercase()) }, sim.texts.toString())
    }

    @Test fun withoutAWeeklyScheduleNothingIsAnnouncedOrPlannedFromThinAir() {
        val c = zeroTouch(schedule = null)
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.stand(3f)
        assertEquals(SessionMode.CUSTOM, c.profile.id.mode)
        assertNull(c.sportCandidate)
        assertFalse(Milestone.SPORT_CANDIDATE in c.milestones())
        assertTrue(sim.texts.none { "Sport" in it || "Stratégie :" in it }, sim.texts.toString())
    }

    @Test fun withoutSportFirstTheNeutralProfileStaysCustom() {
        val c = zeroTouch().also { it.sportFirst = false }
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.stand(3f)
        assertEquals(SessionMode.CUSTOM, c.profile.id.mode)
    }

    @Test fun aPitVisitIsMarkedOnceStartedAndOnceEnded() {
        val c = zeroTouch()
        val sim = RaceSim(c, totalLaps = 14, perLap = 2.8f, carCode = 3474)
        sim.start()
        sim.driveLaps(6)
        sim.pitStop(fuel = 0f, changeTyres = true)
        sim.driveLaps(1)
        assertEquals(1, c.milestones().count { it == Milestone.PIT_VISIT_STARTED }, c.zeroTouch.all.toString())
        assertEquals(1, c.milestones().count { it == Milestone.PIT_VISIT_ENDED }, c.zeroTouch.all.toString())
    }
}
