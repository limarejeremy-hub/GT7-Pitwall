package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.profile.PitwallLocalCareer
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SessionMatcher
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.sport.SportAnnouncement
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Identifier A/B/C n'est pas prouver une course officielle : salon privé, épreuve probable, règlement incomplet,
 * sources contradictoires. Rien ne doit fausser la carrière locale ni présenter une obligation inconnue comme absente.
 */
class SportOfficialityTest {
    private val monday = LocalDate.of(2026, 10, 19)

    // Un seul circuit dans la base, de la longueur du circuit de RaceSim (cercle de 480 m de rayon, 3 016 m).
    private val db = Gt7Database.parse(
        "ID,ShortName,Maker\n3607,Test GT,1\n",
        "ID,Name,Country\n1,Testeur,1\n",
        "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain\n" +
            "900,Anneau de Test,900,3,circuit,3016,500,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n" +
            "901,Autre Circuit,901,3,circuit,5800,900,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,16,0\n",
    )

    private fun info(series: Series, src: String, track: String, laps: Int?, stops: Int? = null, required: Set<TyreCompound>? = null) =
        DailyRaceInfo(series, SourceRef(src, null, 1), trackName = track, laps = laps, mandatoryStops = stops, requiredTyres = required)

    /** Semaine de test : B à 6 tours sur l'Anneau de Test (8 tours en C sur un autre circuit). */
    private fun schedule(bInfos: List<DailyRaceInfo>): ScheduleState {
        val b = ScheduleBuilder.merge(Series.DAILY_B, monday, bInfos, db)
        val c = ScheduleBuilder.merge(Series.DAILY_C, monday, listOf(info(Series.DAILY_C, "GTPlanet", "Autre Circuit", 8, stops = 0)), db)
        return ScheduleState(mapOf(Series.DAILY_B to b, Series.DAILY_C to c), listOf(b, c), 1, null, stale = false)
    }

    private val completeB = listOf(
        info(Series.DAILY_B, "GTPlanet", "Anneau de Test", 6, stops = 1, required = setOf(TyreCompound.RM)),
        info(Series.DAILY_B, "Victory", "Anneau de Test", 6, stops = 1, required = setOf(TyreCompound.RM)),
    )

    private fun controller(store: FakeStorage, sched: ScheduleState): RaceController {
        var now = java.time.ZonedDateTime.of(2026, 10, 20, 20, 0, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()
        return RaceController(database = db, storage = store, clock = { now++ }).also { it.applySchedule(sched) }
    }

    @Test
    fun aPrivateLobbyReproducingRaceBUsesItsRulesButNeverCountsAsAnOfficialRace() {
        val store = FakeStorage()
        val c = controller(store, schedule(completeB))
        c.selectProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        // Une seule preuve (6 tours) : une course personnalisée reste personnalisée.
        assertEquals(SessionMode.CUSTOM, c.profile.id.mode)
        s.driveLaps(1)
        // Tours et circuit concordent : règlement de la B appliqué, mais type « Sport reconnu », pas officiel.
        assertEquals(ProfileId(Series.DAILY_B, SessionKind.RACE, SessionMode.SPORT_IDENTIFIED), c.profile.id)
        assertEquals(1, c.rules.mandatoryStops)
        assertEquals("Course Sport B reconnue (6 tours, circuit)", c.sessionIdentityText)
        s.driveLaps(6)
        // Annonce différée par le régulateur radio (jamais en plein virage) : dite dans les secondes qui suivent.
        assertTrue(s.texts.any { it.startsWith("Course Sport B reconnue. 6 tours. Gomme course mediums imposée.") }, s.texts.toString())
        // Jamais affirmée officielle.
        assertFalse(s.texts.any { t -> Regex("(sport officiel|course officielle|officielle reconnue)").containsMatchIn(t.lowercase()) }, s.texts.toString())
        val record = store.sessions.single()
        assertEquals(SessionOutcome.FINISHED, record.outcome)
        assertEquals(SessionMode.SPORT_IDENTIFIED, record.context.profile.id.mode)
        val career = PitwallLocalCareer.fromSessions(store.sessions)
        assertEquals(0, career.verifiedSportRaces, "un salon privé identique ne compte jamais comme course Sport officielle")
        assertEquals(1, career.completedSessions)
    }

    @Test
    fun aProbableRaceIsNeverPromotedToOfficial() {
        val store = FakeStorage()
        val c = controller(store, schedule(completeB))
        // Profil C officiel resté d'une course précédente ; GT7 annonce 6 tours : la B est seulement probable.
        c.selectProfile(ProfileId(Series.DAILY_C, SessionKind.RACE))
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        assertEquals(ProfileId(Series.DAILY_B, SessionKind.RACE, SessionMode.SPORT_IDENTIFIED), c.profile.id)
        assertEquals(SessionMatcher.Confidence.PROBABLE, (c.sessionIdentity as SessionMatcher.Result.Match).confidence)
        assertTrue(s.texts.any { it.startsWith("Course Sport B probable (6 tours).") }, s.texts.toString())
        s.driveLaps(7)
        assertEquals(0, PitwallLocalCareer.fromSessions(store.sessions).verifiedSportRaces)
    }

    @Test
    fun theDriversOwnOfficialChoiceIsKeptWhenTheEvidenceAgrees() {
        val store = FakeStorage()
        val c = controller(store, schedule(completeB))
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        s.driveLaps(7)
        assertEquals(ProfileId(Series.DAILY_B, SessionKind.RACE, SessionMode.SPORT_OFFICIAL), c.profile.id)
        assertEquals(1, PitwallLocalCareer.fromSessions(store.sessions).verifiedSportRaces)
    }

    @Test
    fun anIncompleteRegulationNeverBecomesNoObligation() {
        // Fiche sans arrêts ni gommes imposées : la stratégie n'en déduit jamais « aucune obligation ».
        val store = FakeStorage()
        val c = controller(store, schedule(listOf(info(Series.DAILY_B, "GTPlanet", "Anneau de Test", 6))))
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        assertTrue(RuleField.MANDATORY_STOPS in c.ruleGaps && RuleField.REQUIRED_TYRES in c.ruleGaps, c.ruleGaps.toString())
        val s = RaceSim(c, totalLaps = 6, perLap = 3f)
        s.start()
        s.driveLaps(2)
        val plan = s.texts.firstOrNull { "pas d'arrêt" in it }
        assertTrue(plan != null && "l'arrêt obligatoire et les gommes imposées ne sont pas confirmés" in plan, s.texts.toString())
        val ed = schedule(listOf(info(Series.DAILY_B, "GTPlanet", "Anneau de Test", 6))).editions.getValue(Series.DAILY_B)
        val said = SportAnnouncement.text(SessionMatcher.Result.Match(Series.DAILY_B, ed, listOf("6 tours", "circuit"), SessionMatcher.Confidence.RECOGNIZED), 6)
        assertTrue("Gommes imposées : non confirmé." in said && "Arrêts obligatoires : non confirmé." in said, said)
        assertTrue("Carburant : non confirmé, usure : non confirmé." in said, said)
        assertFalse("aucun" in said.lowercase(), said)
    }

    @Test
    fun contradictorySourcesAreSaidAsSuch() {
        val ed = schedule(
            listOf(
                info(Series.DAILY_B, "GTPlanet", "Anneau de Test", 6, stops = 0, required = setOf(TyreCompound.RM)),
                info(Series.DAILY_B, "Victory", "Anneau de Test", 7, stops = 1, required = setOf(TyreCompound.RH)),
            ),
        ).editions.getValue(Series.DAILY_B)
        assertEquals(FieldStatus.CONFLICT, ed.laps.status)
        assertEquals(FieldStatus.CONFLICT, ed.mandatoryStops.status)
        // Tours contradictoires : ils ne servent pas de preuve ; le circuit seul ne donne qu'une épreuve probable.
        val m = SessionMatcher.match(mapOf(Series.DAILY_B to ed), 6, ed.trackId, LocalDate.of(2026, 10, 20))
        assertEquals(SessionMatcher.Confidence.PROBABLE, (m as SessionMatcher.Result.Match).confidence)
        val said = SportAnnouncement.text(m, 6)
        assertTrue("Gommes imposées : les sources se contredisent, non confirmé." in said, said)
        assertTrue("Arrêts obligatoires : les sources se contredisent, non confirmé." in said, said)
    }
}
