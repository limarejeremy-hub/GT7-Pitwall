package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.race.Comparability
import com.gt7pitwall.core.race.Compatibility
import com.gt7pitwall.core.race.DriverNote
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.HistorySummary
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.PlanEntry
import com.gt7pitwall.core.race.ProfileBook
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.StopEntry
import com.gt7pitwall.core.race.TyreEntry
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilesAndSessionsTest {
    private val raceB = ProfileId(Series.DAILY_B, SessionKind.RACE)
    private val qualiB = ProfileId(Series.DAILY_B, SessionKind.QUALIFYING)
    private val raceC = ProfileId(Series.DAILY_C, SessionKind.RACE)

    private var now = 1_000_000L
    private fun controller(store: FakeStorage) = RaceController(storage = store, clock = { now++ })

    // --- Profils d'épreuve ---

    @Test
    fun theOldSingleRuleSetBecomesTheOtherRaceProfileWithoutLoss() {
        val old = RaceRules(totalLaps = 12, mandatoryStops = 1, trackId = 462, fuelMultiplier = 2)
        val store = FakeStorage(rules = old)
        val c = controller(store)
        assertEquals(ProfileId.LEGACY, c.profile.id)
        assertEquals(old, c.rules)
        // Migration enregistrée : au lancement suivant, le profil est relu tel quel.
        val again = controller(store)
        assertEquals(old, again.rules)
        assertEquals(ProfileId.LEGACY, again.profile.id)
    }

    @Test
    fun eachEventKeepsItsOwnRules() {
        val store = FakeStorage()
        val c = controller(store)
        c.selectProfile(raceB)
        c.setRules(RaceRules(totalLaps = 12, mandatoryStops = 1, trackId = 462))
        c.selectProfile(raceC)
        assertEquals(RaceRules(), c.rules) // rien de la Course B
        c.setRules(RaceRules(totalLaps = 20, fuelMultiplier = 3, trackId = 300))
        c.selectProfile(raceB)
        assertEquals(12, c.rules.totalLaps)
        assertEquals(1, c.rules.mandatoryStops)
        // Relu au lancement suivant : épreuve active et règlements conservés.
        val again = controller(store)
        assertEquals(raceB, again.profile.id)
        assertEquals(12, again.rules.totalLaps)
    }

    @Test
    fun qualifyingOfTheSameEventOnlyInheritsTheTrack() {
        val c = controller(FakeStorage())
        c.selectProfile(raceB)
        c.setRules(RaceRules(totalLaps = 12, mandatoryStops = 1, fuelMultiplier = 2, trackId = 462))
        c.updateProfile(c.profile.copy(gameVersion = "1.62"))
        c.selectProfile(qualiB)
        assertEquals(RaceRules(trackId = 462), c.rules)
        assertEquals("1.62", c.profile.gameVersion)
        // Une autre série ne reprend rien.
        c.selectProfile(raceC)
        assertNull(c.rules.trackId)
    }

    @Test
    fun anyRuleChangeWithdrawsTheConfirmation() {
        val c = controller(FakeStorage())
        c.selectProfile(raceB)
        c.setRules(RaceRules(totalLaps = 12))
        c.confirmRules("2026-10-19")
        assertTrue(c.profile.confirmed)
        assertEquals("2026-10-19", c.profile.eventDate)
        c.setRules(RaceRules(totalLaps = 13))
        assertFalse(c.profile.confirmed)
        c.confirmRules("2026-10-19")
        c.updateProfile(c.profile.copy(gameVersion = "1.63"))
        assertFalse(c.profile.confirmed)
        // Une voix qui change le règlement le retire aussi.
        c.confirmRules("2026-10-19")
        c.command(DriverCommand.SetMandatoryStops(2))
        assertFalse(c.profile.confirmed)
    }

    @Test
    fun theDriverIsRemindedOnceWhenRulesAreNotConfirmed() {
        val s = RaceSim(controller(FakeStorage()), totalLaps = 10)
        s.controller.selectProfile(raceB)
        s.start()
        s.driveLaps(3)
        assertEquals(1, s.texts.count { "non confirmé" in it }, s.texts.toString())
        val confirmed = RaceSim(controller(FakeStorage()), totalLaps = 10)
        confirmed.controller.confirmRules("2026-10-19")
        confirmed.start()
        confirmed.driveLaps(2)
        assertTrue(confirmed.texts.none { "non confirmé" in it })
    }

    // --- Mémoire sans contamination ---

    @Test
    fun memoryLearnedInOneEventIsNeverUsedInAnother() {
        val store = FakeStorage()
        val b = RaceSim(controller(store), totalLaps = 12, perLap = 6f)
        b.controller.selectProfile(raceB)
        b.controller.setRules(RaceRules(totalLaps = 12, trackId = 462))
        b.start()
        b.driveLaps(3)
        assertTrue(store.memory.keys.any { it.startsWith("DAILY_B/RACE|") }, store.memory.keys.toString())

        // Course C, même voiture et même circuit : aucune mémoire reprise.
        val c = RaceSim(controller(store), totalLaps = 12)
        c.controller.selectProfile(raceC)
        c.controller.setRules(RaceRules(totalLaps = 12, trackId = 462))
        c.start()
        assertNull(c.controller.memoryRecord)
        assertNull(c.controller.plan?.perLap)

        // Qualification B : séparée de la course B.
        val q = RaceSim(controller(store), totalLaps = 0)
        q.controller.selectProfile(qualiB)
        q.start()
        assertNull(q.controller.memoryRecord)

        // Course B : la mémoire revient, annoncée comme historique.
        val b2 = RaceSim(controller(store), totalLaps = 12)
        b2.controller.selectProfile(raceB)
        b2.start()
        val rec = assertNotNull(b2.controller.memoryRecord)
        assertEquals(6f, rec.fuelPerLapX1!!, 0.3f)
        assertEquals(DataSource.HISTORY, b2.controller.plan?.fuelSource)
    }

    @Test
    fun theOldCarTrackMemoryStaysInTheOtherRaceProfile() {
        val legacy = CarTrackRecord(3607, 462, 110_000, 111_000, fuelPerLapX1 = 4f, fuelSamples = 10)
        val store = FakeStorage(RaceRules(trackId = 462, totalLaps = 12), mutableMapOf(legacy.key to legacy))
        val s = RaceSim(controller(store), totalLaps = 12)
        s.start()
        assertEquals(legacy, s.controller.memoryRecord)
        s.controller.selectProfile(raceB)
        s.controller.setRules(RaceRules(trackId = 462, totalLaps = 12))
        assertNull(s.controller.memoryRecord)
    }

    @Test
    fun memoryFromAnotherGameVersionIsSetAside() {
        val old = CarTrackRecord(3607, 462, 110_000, 111_000, 4f, 10, scope = raceB.memoryScope, gameVersion = "1.60")
        val store = FakeStorage(memory = mutableMapOf(old.key to old))
        val c = controller(store)
        c.selectProfile(raceB)
        c.setRules(RaceRules(trackId = 462, totalLaps = 12))
        c.updateProfile(c.profile.copy(gameVersion = "1.61"))
        val s = RaceSim(c, totalLaps = 12)
        s.start()
        assertNull(s.controller.memoryRecord)
        s.controller.updateProfile(s.controller.profile.copy(gameVersion = "1.60"))
        assertEquals(old, s.controller.memoryRecord)
    }

    // --- Archive des sessions ---

    @Test
    fun aSessionIsSavedLapByLapThenMarkedFinished() {
        val store = FakeStorage()
        val s = RaceSim(controller(store), totalLaps = 4, perLap = 5f)
        s.controller.selectProfile(raceB)
        s.controller.setRules(RaceRules(totalLaps = 4, trackId = 462))
        s.start()
        s.driveLaps(2)
        val running = store.sessions.single()
        assertEquals(SessionOutcome.IN_PROGRESS, running.outcome)
        assertEquals(2, running.completedLaps)
        assertEquals(raceB, running.context.profile.id)
        assertEquals(3607, running.context.carCode)
        assertEquals(100f, running.context.fuelCapacity)
        assertEquals(5f, running.laps[1].fuelUsed!!, 0.3f) // mesure, pas une estimation
        s.driveLaps(3)
        val done = store.sessions.single()
        assertEquals(SessionOutcome.FINISHED, done.outcome)
        assertEquals(4, done.completedLaps)
    }

    @Test
    fun stopsAreArchivedWithTheirProofAndTyresAsDeclarations() {
        val store = FakeStorage()
        val s = RaceSim(controller(store), totalLaps = 10, perLap = 14.5f)
        s.controller.setRules(RaceRules(totalLaps = 10, requiredTyres = setOf(TyreCompound.RM, TyreCompound.RH), startTyre = TyreCompound.RM))
        s.start()
        s.driveLaps(4)
        s.pitStop(fuel = 40f)
        s.say("j'ai mis les durs")
        s.driveLaps(1)
        val r = store.sessions.single()
        val stop = r.stops.single()
        assertEquals(StopEvidence.REFUEL, stop.evidence)
        assertTrue((stop.fuelAdded ?: 0f) > 30f)
        assertEquals(TyreCompound.RM, r.startTyre)
        assertEquals(TyreCompound.RH, r.tyres.single().compound)
        assertTrue(r.plans.isNotEmpty())
        assertEquals(r.plans.first().stopLaps, r.plannedStops)
    }

    @Test
    fun leavingForAnotherCarClosesTheSessionAsInterrupted() {
        val store = FakeStorage()
        val first = controller(store)
        val s = RaceSim(first, totalLaps = 10)
        s.start()
        s.driveLaps(3)
        s.controller.command(DriverCommand.DeclareStop)
        // Même contrôleur, autre voiture : GT7 a changé de session.
        val other = RaceSim(first, totalLaps = 10, carCode = 1234)
        other.start()
        other.driveLaps(1)
        val all = store.sessions.sortedBy { it.startedAtMs }
        assertEquals(2, all.size)
        assertEquals(SessionOutcome.INTERRUPTED, all[0].outcome)
        assertEquals(3, all[0].completedLaps)
        assertEquals(StopEvidence.DRIVER_DECLARED, all[0].stops.single().evidence)
        assertEquals(1234, all[1].context.carCode)
    }

    @Test
    fun aSessionLeftInProgressByACrashIsClosedAtTheNextLaunch() {
        val store = FakeStorage()
        val s = RaceSim(controller(store), totalLaps = 10)
        s.start()
        s.driveLaps(2)
        assertEquals(SessionOutcome.IN_PROGRESS, store.sessions.single().outcome)
        // L'application est tuée : rien d'autre n'est enregistré. Au lancement suivant :
        val next = controller(store)
        assertEquals(SessionOutcome.INTERRUPTED, next.archivedSessions.single().outcome)
        assertEquals(SessionOutcome.INTERRUPTED, store.sessions.single().outcome)
        assertEquals(2, store.sessions.single().completedLaps)
    }

    @Test
    fun stoppingTheTelemetryArchivesTheSessionAndNothingIsSavedWithoutALap() {
        val store = FakeStorage()
        val s = RaceSim(controller(store), totalLaps = 10)
        s.start()
        s.controller.endSession()
        assertTrue(store.sessions.isEmpty())
        val s2 = RaceSim(controller(store), totalLaps = 10)
        s2.start()
        s2.driveLaps(2)
        s2.controller.endSession()
        assertEquals(SessionOutcome.INTERRUPTED, store.sessions.single().outcome)
    }

    @Test
    fun driverIntentionsAreKeptAsDeclarations() {
        val store = FakeStorage()
        val s = RaceSim(controller(store), totalLaps = 10)
        s.start()
        s.driveLaps(2)
        s.controller.noteIntention("je compte rentrer au tour 6")
        assertEquals(DriverNote(3, "je compte rentrer au tour 6"), store.sessions.single().notes.single())
    }

    // --- Comparabilité ---

    private fun ctx(
        id: ProfileId = raceB,
        rules: RaceRules = RaceRules(totalLaps = 12, mandatoryStops = 1, trackId = 462, fuelMultiplier = 2),
        car: Int? = 3607,
        version: String? = null,
    ) = SessionContext(EventProfile(id = id, gameVersion = version), rules, car, 100f)

    @Test
    fun onlyTheSameEventCarTrackAndMultipliersAreComparable() {
        val base = ctx()
        assertEquals(Comparability.SAME_EVENT, Compatibility.compare(base, ctx()).first)
        assertEquals(Comparability.SAME_CAR_TRACK, Compatibility.compare(base, ctx(rules = base.rules.copy(totalLaps = 15))).first)
        val cases = mapOf(
            "épreuve différente" to ctx(id = raceC),
            "qualification et course séparées" to ctx(id = qualiB),
            "circuit différent" to ctx(rules = base.rules.copy(trackId = 300)),
            "voiture différente" to ctx(car = 99),
            "multiplicateur de carburant différent" to ctx(rules = base.rules.copy(fuelMultiplier = 3)),
            "multiplicateur d'usure différent" to ctx(rules = base.rules.copy(tyreMultiplier = 2)),
        )
        for ((why, other) in cases) {
            val (level, reason) = Compatibility.compare(base, other)
            assertEquals(Comparability.INCOMPATIBLE, level, why)
            assertEquals(why, reason)
        }
        assertEquals(Comparability.INCOMPATIBLE, Compatibility.compare(ctx(version = "1.61"), ctx(version = "1.62")).first)
        // Version déclarée d'un seul côté : comparable (le pilote ne l'a pas toujours notée).
        assertEquals(Comparability.SAME_EVENT, Compatibility.compare(ctx(version = "1.61"), ctx()).first)
        assertEquals(Comparability.INCOMPATIBLE, Compatibility.compare(ctx(rules = base.rules.copy(trackId = null)), ctx()).first)
    }

    @Test
    fun historySummaryCountsOnlyComparableSessions() {
        fun rec(id: String, c: SessionContext, laps: Int) =
            SessionRecord(id, c, 0, 0, SessionOutcome.FINISHED, (1..laps).map { LapEntry(it, 100_000, 5f, false) })
        val sessions = listOf(
            rec("a", ctx(), 12),
            rec("b", ctx(rules = ctx().rules.copy(totalLaps = 15)), 15),
            rec("c", ctx(id = qualiB), 5),
            rec("d", ctx(car = 99), 12),
        )
        val h = HistorySummary.of(ctx(), sessions)
        assertEquals(3, h.sameProfile)
        assertEquals(2, h.comparable)
        assertEquals(1, h.sameEvent)
        assertEquals(27, h.comparableLaps)
        assertEquals(1, HistorySummary.of(ctx(), sessions, excludeId = "a").comparable)
    }

    // --- Enregistrement JSON ---

    @Test
    fun sessionsAndProfilesSurviveTheirJson() {
        val r = SessionRecord(
            id = "1-3607",
            context = ctx(version = "1.62").copy(rules = RaceRules(totalLaps = 12, requiredTyres = setOf(TyreCompound.RM), trackId = 462)),
            startedAtMs = 1,
            updatedAtMs = 2,
            outcome = SessionOutcome.FINISHED,
            laps = listOf(LapEntry(1, 101_000, null, false), LapEntry(2, 100_500, 5.25f, true, fuelAfter = 80f)),
            stops = listOf(StopEntry(5, StopEvidence.REFUEL, 40f), StopEntry(8, StopEvidence.DRIVER_DECLARED, null)),
            startTyre = TyreCompound.RS,
            tyres = listOf(TyreEntry(6, TyreCompound.RM), TyreEntry(9, null)),
            plans = listOf(PlanEntry(1, listOf(5)), PlanEntry(4, listOf(5, 9), "conso plus haute")),
            notes = listOf(DriverNote(3, "je perds l'avant")),
        )
        assertEquals(r, RaceJson.session(RaceJson.session(r)))
        val book = ProfileBook.migrate(RaceRules(totalLaps = 10))
        book.select(raceB)
        book.updateRules(RaceRules(totalLaps = 12, trackId = 462))
        book.confirm("2026-10-19")
        val back = RaceJson.book(RaceJson.book(book))!!
        assertEquals(book.active, back.active)
        assertEquals(book.entries, back.entries)
    }

    @Test
    fun incompleteOrDamagedJsonNeverInventsData() {
        val minimal = RaceJson.session("""{"id":"x","laps":[{"lap":1}]}""")!!
        assertEquals(SessionOutcome.INTERRUPTED, minimal.outcome)
        assertNull(minimal.laps.single().timeMs)
        assertNull(minimal.laps.single().fuelUsed)
        assertNull(minimal.context.carCode)
        assertEquals(ProfileId.LEGACY, minimal.context.profile.id)
        assertNull(RaceJson.session("pas du json"))
        assertNull(RaceJson.book("{"))
    }
}
