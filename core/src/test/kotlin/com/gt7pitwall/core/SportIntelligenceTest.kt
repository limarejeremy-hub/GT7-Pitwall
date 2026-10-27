package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.SessionMatcher
import com.gt7pitwall.core.sport.SessionMatcher.Confidence
import com.gt7pitwall.core.sport.SessionMatcher.Result
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.sport.SportAnnouncement
import com.gt7pitwall.core.sport.SportNames
import com.gt7pitwall.core.strategy.TyreCompound
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Courses Sport : trois preuves (tours GT7, catégorie de voiture, circuit), deux niveaux de certitude, une annonce
 * qui ne dit que le vérifié, et une question simple au pilote quand deux règlements restent possibles.
 */
class SportIntelligenceTest {
    private val dir = File("../app/src/main/assets/gt7db")
    private val db: Gt7Database = Gt7Database.parse(
        File(dir, "cars.csv").readText(), File(dir, "maker.csv").readText(), File(dir, "course.csv").readText(),
    ).also { it.carGroups = Gt7Database.parseGroups(File(dir, "cargrp.csv").readText()) }
    private val tuesday = LocalDate.of(2026, 10, 20)
    private val monday = LocalDate.of(2026, 10, 19)
    private val feed = javaClass.getResource("/gtplanet-daily-2026-10-19.xml")!!.readText()
    // Semaine du 19/10 (fiche GTPlanet réelle) : A Gilles-Villeneuve 5 tours, B Interlagos 6 tours Gr.3, C Suzuka 11 tours.
    private val schedule = ScheduleBuilder.build(GtPlanetFeed.parse(feed, 1), db, tuesday, 1, null)

    @Test
    fun carCategoriesAreReadFromTheFactSheetAndTheBase() {
        assertEquals(setOf("3"), SportNames.carGroups("Gr.3 — Garage/Specified Car"))
        assertEquals(setOf("4"), SportNames.carGroups("Group 4"))
        assertEquals(setOf("N"), SportNames.carGroups("N300 (Garage Car)"))
        assertNull(SportNames.carGroups("Mazda Roadster S (ND) '15"))
        // Base communautaire gt7info : la Lancer Evolution Final Gr.3 est bien une Gr.3.
        val lancer = db.cars.values.first { it.name == "Lancer Evolution Final Gr.3" }
        assertEquals("3", db.carGroup(lancer.id))
    }

    @Test
    fun aRoadCarExcludesAGr3Race() {
        val b = schedule.editions.getValue(Series.DAILY_B)
        assertEquals(setOf("3"), SportNames.carGroups(b.cars.value))
        // 6 tours comme la B, mais voiture de route : ce n'est pas la B.
        assertIs<Result.NoMatch>(SessionMatcher.match(schedule.editions, 6, null, tuesday, carGroup = "N"))
        val ok = SessionMatcher.match(schedule.editions, 6, null, tuesday, carGroup = "3")
        assertIs<Result.Match>(ok)
        assertEquals(listOf("6 tours", "voiture Gr.3"), ok.evidence)
        assertEquals(Confidence.PROBABLE, ok.confidence)
    }

    @Test
    fun recognisedOnlyWhenLapsAndTrackAgree() {
        val c = schedule.editions.getValue(Series.DAILY_C)
        assertEquals(Confidence.PROBABLE, (SessionMatcher.match(schedule.editions, 11, null, tuesday) as Result.Match).confidence)
        assertEquals(Confidence.RECOGNIZED, (SessionMatcher.match(schedule.editions, 11, c.trackId, tuesday) as Result.Match).confidence)
    }

    private fun edition(required: Set<TyreCompound>?, secondSource: Boolean): Result.Match {
        val v = SourceRef("Victory", null, 1)
        val g = SourceRef("GTPlanet", null, 1)
        val infos = listOfNotNull(
            DailyRaceInfo(Series.DAILY_C, v, trackName = "Suzuka Circuit", laps = 14, requiredTyres = required, mandatoryStops = 1, fuelMultiplier = 2),
            DailyRaceInfo(Series.DAILY_C, g, trackName = "Suzuka Circuit", laps = 14, requiredTyres = required, mandatoryStops = 1, fuelMultiplier = 2)
                .takeIf { secondSource },
        )
        val ed = ScheduleBuilder.merge(Series.DAILY_C, monday, infos, db)
        return Result.Match(Series.DAILY_C, ed, listOf("14 tours", "circuit"), Confidence.RECOGNIZED)
    }

    @Test
    fun theAnnouncementOnlySaysWhatIsVerified() {
        // Deux sources concordantes : dit sans réserve (exemple de la mission).
        assertEquals(
            "Course Sport C reconnue. 14 tours. Gommes course durs et course mediums imposées. 1 arrêt obligatoire. Carburant fois 2, usure : non confirmé. Je prépare la stratégie.",
            SportAnnouncement.text(edition(setOf(TyreCompound.RH, TyreCompound.RM), secondSource = true), 14),
        )
        // Une seule source : elle est nommée.
        val one = SportAnnouncement.text(edition(setOf(TyreCompound.RH, TyreCompound.RM), secondSource = false), 14)
        assertTrue("imposées selon Victory" in one && "obligatoire selon Victory" in one, one)
        // Aucune source ne dit les gommes imposées : jamais inventées.
        val unknown = SportAnnouncement.text(edition(null, secondSource = true), 14)
        assertTrue("Gommes imposées : non confirmé." in unknown, unknown)
    }

    private fun controller(): RaceController {
        var now = java.time.ZonedDateTime.of(2026, 10, 20, 20, 0, 0, 0, GtPlanetFeed.PARIS).toInstant().toEpochMilli()
        return RaceController(database = db, storage = FakeStorage(), clock = { now++ }).also { it.applySchedule(schedule) }
    }

    @Test
    fun twoPossibleRacesWithDifferentRulesAskTheDriverWhoAnswersWithTheLetter() {
        // Semaine où A et C ont toutes deux 11 tours, règlements différents.
        val a = schedule.editions.getValue(Series.DAILY_A)
        val eds = schedule.editions + (Series.DAILY_A to a.copy(laps = com.gt7pitwall.core.sport.Sourced(11, com.gt7pitwall.core.sport.FieldStatus.PROBABLE)))
        val c = controller()
        c.applySchedule(schedule.copy(editions = eds))
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        val s = RaceSim(c, totalLaps = 11, perLap = 3f)
        s.start()
        assertTrue(s.texts.any { it == "Course de 11 tours : A ou C ? Dis-moi la lettre." }, s.texts.toString())
        assertTrue(c.answersPendingQuestion("la C"))
        val said = c.utterance("la C")
        assertEquals(ProfileId(Series.DAILY_C, SessionKind.RACE, com.gt7pitwall.core.race.SessionMode.SPORT_IDENTIFIED), c.profile.id)
        assertEquals(1, c.rules.mandatoryStops)
        assertTrue(said.any { it.text.startsWith("Noté, Course Sport C. 11 tours.") }, said.toString())
        // Plus de question en attente : « la C » n'est plus une réponse.
        assertTrue(!c.answersPendingQuestion("la C"))
    }
}
