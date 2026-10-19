package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SportScheduleTest {
    private val feed = javaClass.getResource("/gtplanet-daily-2026-10-19.xml")!!.readText()
    private val db: Gt7Database = run {
        val dir = File("../app/src/main/assets/gt7db")
        Gt7Database.parse(File(dir, "cars.csv").readText(), File(dir, "maker.csv").readText(), File(dir, "course.csv").readText())
    }
    private val monday = LocalDate.of(2026, 10, 19)

    // --- Lecture de la fiche GTPlanet (format réel du 19 octobre 2026) ---

    @Test
    fun theWeeklyFactSheetIsReadFieldByField() {
        val posts = GtPlanetFeed.parse(feed, fetchedAtMs = 1)
        assertEquals(2, posts.size)
        val week = posts.first()
        assertEquals(monday, week.validFrom)
        val b = week.races.getValue(Series.DAILY_B)
        assertEquals("Autodromo de Interlagos", b.trackName)
        assertEquals(6, b.laps)
        assertEquals("Gr.3 — Garage/Specified Car", b.cars)
        assertEquals("BOP (M)", b.bop)
        assertEquals(setOf(TyreCompound.RH), b.allowedTyres)
        assertNull(b.requiredTyres) // la fiche ne dit rien d'une gomme imposée
        assertEquals(0, b.mandatoryStops) // « – » : la source indique aucun arrêt
        assertEquals(1, b.fuelMultiplier)
        assertEquals(1, b.tyreMultiplier)
        assertEquals("Partially Allowed (Brake Balance)", b.settings)
        val c = week.races.getValue(Series.DAILY_C)
        assertEquals("Suzuka Circuit", c.trackName)
        assertEquals(11, c.laps)
        assertEquals(1, c.mandatoryStops)
        assertEquals(2, c.fuelMultiplier)
        assertEquals(4, c.tyreMultiplier)
        assertEquals(setOf(TyreCompound.RM), c.allowedTyres)
        val a = week.races.getValue(Series.DAILY_A)
        assertEquals("Circuit Gilles-Villeneuve", a.trackName)
        assertEquals(setOf(TyreCompound.SS), a.allowedTyres)
    }

    @Test
    fun onlyTheLabelledListIsRead_neverTheProse() {
        // La prose dit « a single pit stop is mandatory » pour C : seule la ligne « Mandatory Pit Stop » compte, et elle
        // est lue ; pour B, la prose ne dit rien et la ligne dit « – ».
        val b = GtPlanetFeed.parse(feed, 1).first().races.getValue(Series.DAILY_B)
        assertTrue(b.lines.all { ":" in it })
        assertEquals(9, b.lines.size)
    }

    @Test
    fun unreadableValuesStayUnknown_requiredTyresAreReadWhenStated() {
        val older = GtPlanetFeed.parse(feed, 1)[1].races.getValue(Series.DAILY_B)
        assertNull(older.tyreMultiplier) // « ten » : illisible, jamais deviné
        assertEquals(setOf(TyreCompound.RM, TyreCompound.RH), older.requiredTyres)
        assertNull(older.bop)
        assertNull(older.settings)
    }

    @Test
    fun aFeedWithoutFactSheetsGivesNothing() {
        assertTrue(GtPlanetFeed.parse("<rss><channel><item><title>x</title><pubDate>Mon, 19 Oct 2026 16:10:06 +0000</pubDate>" +
            "<content:encoded><![CDATA[<p>Race B is six laps of Interlagos.</p>]]></content:encoded></item></channel></rss>", 1).isEmpty())
        assertTrue(GtPlanetFeed.parse("pas du xml", 1).isEmpty())
    }

    // --- Semaines et circuits ---

    @Test
    fun theCurrentWeekIsPickedAndTracksAreMatchedToTheGameDatabase() {
        val state = ScheduleBuilder.build(GtPlanetFeed.parse(feed, 1), db, today = monday.plusDays(3), lastFetchMs = 1, lastError = null)
        assertFalse(state.stale)
        val b = state.editions.getValue(Series.DAILY_B)
        assertEquals("DAILY_B@2026-10-19", b.id)
        assertEquals(LocalDate.of(2026, 10, 26), b.validTo)
        assertEquals(152, b.trackId) // Autodromo de Interlagos
        assertEquals(10, state.editions.getValue(Series.DAILY_C).trackId) // Suzuka Circuit, pas le tracé Est
        assertEquals(2004, state.editions.getValue(Series.DAILY_A).trackId)
        assertEquals(FieldStatus.PROBABLE, b.laps.status) // une seule source
        assertEquals(4, state.history.size) // A, B, C de cette semaine + B de la précédente
    }

    @Test
    fun anOldScheduleIsReportedStaleAndNotApplied() {
        val state = ScheduleBuilder.build(GtPlanetFeed.parse(feed, 1), db, today = monday.plusDays(9), lastFetchMs = 1, lastError = "réseau")
        assertTrue(state.stale)
        assertTrue(state.editions.isEmpty())
    }

    @Test
    fun twoAgreeingSourcesConfirm_twoDisagreeingSourcesConflict() {
        val s1 = SourceRef("GTPlanet", null, 1)
        val s2 = SourceRef("Autre", null, 1)
        val a = DailyRaceInfo(Series.DAILY_B, s1, trackName = "Autodromo de Interlagos", laps = 6, fuelMultiplier = 1, mandatoryStops = 0)
        val b = DailyRaceInfo(Series.DAILY_B, s2, trackName = "Autódromo de Interlagos", laps = 7, fuelMultiplier = 1)
        val ed = ScheduleBuilder.merge(Series.DAILY_B, monday, listOf(a, b), db)
        assertEquals(FieldStatus.CONFIRMED, ed.trackName.status) // accent près, même circuit
        assertEquals(FieldStatus.CONFIRMED, ed.fuelMultiplier.status)
        assertEquals(FieldStatus.CONFLICT, ed.laps.status)
        assertNull(ed.laps.value)
        assertTrue(ed.laps.note!!.contains("GTPlanet : 6"))
        assertEquals(FieldStatus.PROBABLE, ed.mandatoryStops.status)
        assertEquals(FieldStatus.UNKNOWN, ed.requiredTyres.status)
    }

    @Test
    fun ambiguousTrackNamesAreNotChosen() {
        assertTrue(ScheduleBuilder.matchTrack("Willow Springs International Raceway", db).size > 1)
        assertEquals(listOf(10), ScheduleBuilder.matchTrack("Suzuka Circuit", db).map { it.id })
        assertTrue(ScheduleBuilder.matchTrack("Sugo", db).isEmpty())
    }

    // --- Profils remplis automatiquement ---

    private fun controller(store: FakeStorage = FakeStorage()) = RaceController(database = db, storage = store)

    private fun schedule(today: LocalDate = monday.plusDays(1)) =
        ScheduleBuilder.build(GtPlanetFeed.parse(feed, 1), db, today, 1, null)

    @Test
    fun choosingRaceBGivesThisWeeksRulesWithTheirStatus() {
        val c = controller()
        c.applySchedule(schedule())
        val said = c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        assertEquals(RaceRules(totalLaps = 6, allowedTyres = setOf(TyreCompound.RH), startTyre = TyreCompound.RH, trackId = 152), c.rules)
        assertEquals("DAILY_B@2026-10-19", c.profile.editionId)
        assertFalse(c.profile.confirmed)
        assertEquals(FieldStatus.PROBABLE, c.profile.ruleStatus[RuleField.MANDATORY_STOPS])
        // Une seule gomme autorisée : l'obligation de gomme est sans objet ; rien d'important n'est inconnu.
        assertTrue(c.ruleGaps.isEmpty(), c.ruleGaps.toString())
        assertTrue(said.any { "semaine du 19 octobre" in it.text && "Autodromo de Interlagos" in it.text && "à confirmer" in it.text }, said.toString())
    }

    @Test
    fun raceCGetsItsObligationsAndMultipliers() {
        val c = controller()
        c.applySchedule(schedule())
        c.selectProfile(ProfileId(Series.DAILY_C, SessionKind.RACE))
        assertEquals(11, c.rules.totalLaps)
        assertEquals(1, c.rules.mandatoryStops)
        assertEquals(2, c.rules.fuelMultiplier)
        assertEquals(4, c.rules.tyreMultiplier)
        assertEquals(10, c.rules.trackId)
    }

    @Test
    fun qualifyingOnlyTakesTrackAndTyres() {
        val c = controller()
        c.applySchedule(schedule())
        c.selectProfile(ProfileId(Series.DAILY_C, SessionKind.QUALIFYING))
        assertEquals(RaceRules(allowedTyres = setOf(TyreCompound.RM), startTyre = TyreCompound.RM, trackId = 10), c.rules)
    }

    @Test
    fun aNewWeekNeverKeepsLastWeeksRules() {
        val store = FakeStorage()
        val c = controller(store)
        // Semaine précédente : Spa, 7 tours, 1 arrêt, x3 ; le pilote avait aussi donné la durée des pneus.
        c.applySchedule(schedule(today = LocalDate.of(2026, 10, 13)))
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        assertEquals(462, c.rules.trackId)
        assertEquals(1, c.rules.mandatoryStops)
        c.setRules(c.rules.copy(tyreStintLaps = 5))
        c.confirmRules("2026-10-13")
        // Nouvelle semaine : tout vient de la nouvelle édition, rien de l'ancienne.
        c.applySchedule(schedule(today = monday.plusDays(1)))
        assertEquals(152, c.rules.trackId)
        assertEquals(0, c.rules.mandatoryStops)
        assertEquals(1, c.rules.fuelMultiplier)
        assertNull(c.rules.tyreStintLaps)
        assertFalse(c.profile.confirmed)
        // Relu au lancement suivant.
        assertEquals("DAILY_B@2026-10-19", controller(store).profile.editionId)
    }

    @Test
    fun unknownObligationsAreReportedAsGapsNotAsZero() {
        val c = controller()
        c.applySchedule(schedule(today = LocalDate.of(2026, 10, 13)))
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        // Semaine précédente : multiplicateur d'usure illisible (« ten ») → inconnu, mais pas important pour les gaps.
        assertEquals(FieldStatus.UNKNOWN, c.profile.ruleStatus[RuleField.TYRE_MULTIPLIER])
        assertEquals(1, c.rules.tyreMultiplier) // valeur neutre…
        assertTrue(c.aiFacts().lines.none { "fois" in it && "usure" in it }) // …jamais présentée comme vérifiée
        // Sans fiche du tout pour une semaine : profil manuel, aucun champ annoncé comme vérifié.
        val bare = controller()
        bare.selectProfile(ProfileId(Series.DAILY_A, SessionKind.RACE))
        assertNull(bare.profile.editionId)
    }

    @Test
    fun aDriverCorrectionIsKeptAsSuchAndConfirmationUpgradesProbableFields() {
        val c = controller()
        c.applySchedule(schedule())
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        c.setRules(c.rules.copy(totalLaps = 7))
        assertEquals(FieldStatus.PILOT, c.profile.ruleStatus[RuleField.LAPS])
        c.confirmRules("2026-10-20")
        assertEquals(FieldStatus.CONFIRMED, c.profile.ruleStatus[RuleField.FUEL_MULTIPLIER])
        assertEquals(FieldStatus.PILOT, c.profile.ruleStatus[RuleField.LAPS])
        assertEquals("2026-10-19", c.profile.eventDate) // date de l'édition
        // Même semaine relue : la confirmation et la correction du pilote sont conservées.
        c.applySchedule(schedule())
        assertEquals(7, c.rules.totalLaps)
        assertTrue(c.profile.confirmed)
    }

    @Test
    fun theDriverCanDeclareAnUnknownObligationEvenWithoutChangingTheValue() {
        val c = controller()
        c.applySchedule(schedule(today = LocalDate.of(2026, 10, 13)))
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        c.declareRules(c.rules.copy(tyreMultiplier = 1), setOf(RuleField.TYRE_MULTIPLIER))
        assertEquals(FieldStatus.PILOT, c.profile.ruleStatus[RuleField.TYRE_MULTIPLIER])
    }

    @Test
    fun aNewWeekDuringARaceWaitsForTheEndOfTheSession() {
        val c = controller()
        c.applySchedule(schedule(today = LocalDate.of(2026, 10, 13)))
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        val s = RaceSim(c, totalLaps = 7)
        s.start()
        s.driveLaps(2)
        c.applySchedule(schedule(today = monday.plusDays(1)))
        assertEquals(462, c.rules.trackId) // pas en pleine course
        // Les autres épreuves sont déjà à jour.
        c.endSession()
        assertEquals(152, c.rules.trackId)
    }

    @Test
    fun theProfileStateSurvivesItsJson() {
        val store = FakeStorage()
        val c = controller(store)
        c.applySchedule(schedule())
        c.selectProfile(ProfileId(Series.DAILY_C, SessionKind.RACE))
        val again = controller(store)
        assertEquals(c.profile, again.profile)
        assertEquals(c.rules, again.rules)
        assertNotNull(again.profile.editionFingerprint)
    }
}

class RefreshPolicyTest {
    private val feed = javaClass.getResource("/gtplanet-daily-2026-10-19.xml")!!.readText()
    private val posts = com.gt7pitwall.core.sport.GtPlanetFeed.parse(feed, 1)
    private val paris = java.time.ZoneId.of("Europe/Paris")
    private fun at(day: Int, hour: Int) = java.time.ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, paris)
    private fun state(day: Int) = ScheduleBuilder.build(posts, Gt7Database.EMPTY, LocalDate.of(2026, 10, day), 0, null)
    private fun ms(z: java.time.ZonedDateTime) = z.toInstant().toEpochMilli()

    @Test
    fun noCacheMeansRead() {
        assertTrue(com.gt7pitwall.core.sport.RefreshPolicy.needsRefresh(at(20, 10), null, null))
    }

    @Test
    fun aFreshWeekIsNotReadAgainBeforeADay() {
        val policy = com.gt7pitwall.core.sport.RefreshPolicy
        val read = at(20, 9)
        assertFalse(policy.needsRefresh(at(20, 9).plusMinutes(10), ms(read), state(20)))
        assertFalse(policy.needsRefresh(at(20, 20), ms(read), state(20)))
        assertTrue(policy.needsRefresh(at(21, 10), ms(read), state(21)))
    }

    @Test
    fun onMondayTheNewWeekIsLookedForEveryHalfHour() {
        val policy = com.gt7pitwall.core.sport.RefreshPolicy
        // Lundi 26 : la semaine du 19 est finie, la nouvelle fiche n'est pas encore lue.
        val lastRead = at(26, 8)
        assertFalse(policy.needsRefresh(at(26, 8).plusMinutes(20), ms(lastRead), state(26)))
        assertTrue(policy.needsRefresh(at(26, 9), ms(lastRead), state(26)))
    }

    @Test
    fun ageIsReadable() {
        val policy = com.gt7pitwall.core.sport.RefreshPolicy
        assertEquals("il y a 3 h", policy.age(3 * 3_600_000L, 0))
        assertEquals("jamais lu", policy.age(0, null))
    }
}
