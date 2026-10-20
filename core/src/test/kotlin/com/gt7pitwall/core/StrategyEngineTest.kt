package com.gt7pitwall.core

import com.gt7pitwall.core.race.DriverNote
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.PlanEntry
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.race.StopEntry
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.FuelUnits
import com.gt7pitwall.core.strategy.HistoryModel
import com.gt7pitwall.core.strategy.OptionKind
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.StrategyOptions
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategyEngineTest {
    // --- Unités du carburant ---

    @Test
    fun fuelIsSpokenAsAShareOfTheTankNeverInLitres() {
        assertEquals("23 % du réservoir", FuelUnits.amount(23f, 100f))
        assertEquals("50 % du réservoir", FuelUnits.amount(2.5f, 5f)) // kart : capacité 5
        assertEquals("4,5 % du réservoir par tour", FuelUnits.perLap(4.5f, 100f))
        assertEquals("23 unités GT7", FuelUnits.amount(23f, null))
        assertEquals("23 unités GT7", FuelUnits.amount(23f, 0f)) // électrique : pas de capacité
        assertNull(FuelUnits.percent(10f, 0f))
    }

    @Test
    fun aWholeRaceNeverTalksAboutLitres() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10, perLap = 14.5f)
        s.controller.setRules(RaceRules(totalLaps = 10, mandatoryStops = 1))
        s.start()
        s.driveLaps(3)
        s.say("essence ?")
        s.say("stratégie ?")
        s.driveLaps(2)
        s.pitStop(fuel = 45f)
        s.driveLaps(6)
        val all = s.texts + s.controller.aiFacts().lines + listOfNotNull(s.controller.strategy?.reason)
        assertTrue(all.none { Regex("(?i)\\blitres?\\b").containsMatchIn(it) }, all.filter { "litre" in it.lowercase() }.toString())
        assertTrue(s.texts.any { "% du réservoir" in it }, s.texts.toString())
    }

    // --- Apprentissage mesurable ---

    private val raceB = ProfileId(Series.DAILY_B, SessionKind.RACE)
    private val rules = RaceRules(totalLaps = 12, trackId = 462)
    private fun ctx(car: Int = 3607, id: ProfileId = raceB, r: RaceRules = rules, version: String? = null) =
        SessionContext(EventProfile(id = id, gameVersion = version), r, car, 100f)

    /** Course simulée : consommation réelle [fuel] (bruit ±3 %), rythme 100 s ; un arrêt au tour [stop] s'il y en a un. */
    private fun race(
        n: Int,
        fuel: Double = 6.0,
        ctx: SessionContext = ctx(),
        stop: Int? = null,
        slopeMs: Int = 0,
        plannedFuel: Float? = null,
        seed: Int = n,
    ): SessionRecord {
        val rnd = Random(seed)
        val laps = (1..12).map { l ->
            val age = if (stop != null && l > stop) l - stop - 1 else l - 1
            val pit = stop != null && (l == stop || l == stop + 1)
            LapEntry(
                lap = l,
                timeMs = 100_000 + slopeMs * age + rnd.nextInt(-300, 300) + (if (pit) 12_000 else 0) + (if (l == 1) 3_000 else 0),
                fuelUsed = if (stop != null && l == stop + 1) null else (fuel * (1 + rnd.nextDouble(-0.03, 0.03))).toFloat(),
                refuelled = stop != null && l == stop + 1,
            )
        }
        return SessionRecord(
            id = "s$n-${ctx.carCode}-${ctx.profile.id.code}",
            context = ctx,
            startedAtMs = n.toLong(),
            updatedAtMs = n.toLong(),
            outcome = SessionOutcome.FINISHED,
            laps = laps,
            stops = listOfNotNull(stop?.let { StopEntry(it, StopEvidence.REFUEL, 40f) }),
            plans = listOfNotNull(plannedFuel?.let { PlanEntry(1, listOfNotNull(stop), fuelPerLap = it, fuelSource = DataSource.HISTORY.name) }),
        )
    }

    @Test
    fun moreComparableRacesImproveTheEstimateAndItsConfidence() {
        val sessions = mutableListOf<SessionRecord>()
        val history = mutableListOf<HistoryModel>()
        for (n in 1..5) {
            sessions += race(n)
            history += HistoryModel.learn(ctx(), sessions)
        }
        val one = history[0].fuelPerLap!!
        val five = history[4].fuelPerLap!!
        assertEquals(Confidence.PROVISIONAL, one.confidence) // une seule course : jamais « fiable »
        assertEquals(1, one.sessions)
        assertEquals(Confidence.FAIRLY_RELIABLE, five.confidence)
        assertEquals(5, five.sessions)
        assertTrue(five.samples > one.samples)
        assertTrue(abs(five.mean - 6.0) < 0.05, "${five.mean}")
        // Erreur moyenne sur 20 tirages : cinq courses comparables prédisent mieux qu'une.
        fun err(n: Int, seed: Int) = abs(HistoryModel.learn(ctx(), (1..n).map { race(it, seed = seed * 100 + it) }).fuelPerLap!!.mean - 6.0)
        val errOne = (1..20).map { err(1, it) }.average()
        val errFive = (1..20).map { err(5, it) }.average()
        assertTrue(errFive < errOne, "1 course : $errOne, 5 courses : $errFive")
        assertEquals(Confidence.FAIRLY_RELIABLE, history[4].lapTimeMs!!.confidence)
        assertEquals(100_000.0, history[4].lapTimeMs!!.mean, 200.0)
    }

    @Test
    fun incompatibleRacesNeverChangeThePrediction() {
        val good = (1..3).map { race(it) }
        val base = HistoryModel.learn(ctx(), good).fuelPerLap!!
        val noise = listOf(
            race(10, fuel = 9.0, ctx = ctx(car = 99)), // autre voiture
            race(11, fuel = 9.0, ctx = ctx(id = ProfileId(Series.DAILY_B, SessionKind.QUALIFYING))), // qualification
            race(12, fuel = 18.0, ctx = ctx(r = rules.copy(fuelMultiplier = 3))), // autre multiplicateur
            race(13, fuel = 9.0, ctx = ctx(r = rules.copy(trackId = 300))), // autre circuit
            race(14, fuel = 9.0, ctx = ctx(id = ProfileId(Series.DAILY_C, SessionKind.RACE))), // autre épreuve
        )
        val model = HistoryModel.learn(ctx(), good + noise)
        assertEquals(base, model.fuelPerLap)
        assertEquals(3, model.sessionsUsed)
        // Même épreuve mais autre voiture, multiplicateur ou circuit : écartées ; qualification et Course C : pas candidates.
        assertEquals(3, model.sessionsExcluded)
    }

    @Test
    fun anotherGameVersionIsExcludedWhenBothAreDeclared() {
        val old = (1..3).map { race(it, fuel = 9.0, ctx = ctx(version = "1.60")) }
        assertNull(HistoryModel.learn(ctx(version = "1.61"), old).fuelPerLap)
        assertNotNull(HistoryModel.learn(ctx(), old).fuelPerLap) // version non précisée aujourd'hui : comparable
    }

    @Test
    fun refuelOutLapIncidentAndAberrantLapsAreIgnored() {
        val r = race(1, stop = 6)
        val withIncident = r.copy(
            laps = r.laps.map { if (it.lap == 9) it.copy(fuelUsed = 30f, timeMs = 160_000) else it },
            notes = listOf(DriverNote(4, "tête-à-queue au virage 3")),
        )
        val m = HistoryModel.learn(ctx(), listOf(withIncident, race(2), race(3)))
        assertEquals(6.0, m.fuelPerLap!!.mean, 0.1)
        assertEquals(100_000.0, m.lapTimeMs!!.mean, 300.0)
        assertTrue(m.fuelPerLap!!.relativeSpread < 0.05)
    }

    @Test
    fun pitLossAndForecastErrorAreLearnedFromRealRaces() {
        val m = HistoryModel.learn(ctx(), listOf(race(1, stop = 6, plannedFuel = 6.6f), race(2, stop = 5, plannedFuel = 6.6f)))
        val loss = assertNotNull(m.pitLossMs)
        assertEquals(24_000.0, loss.mean, 1_500.0) // 12 s sur le tour d'arrêt + 12 s sur le tour de sortie
        val err = assertNotNull(m.fuelForecastError)
        assertEquals(0.10, err.mean, 0.03) // prévu 6,6 pour 6,0 réels : 10 % d'erreur
    }

    @Test
    fun aPaceDriftIsLearnedOnlyWhenItIsReallyMeasured() {
        val flat = HistoryModel.learn(ctx(), (1..3).map { race(it, stop = 6) })
        assertNull(flat.paceSlopeMsPerLap) // aucune dégradation supposée
        val drifting = HistoryModel.learn(ctx(), (1..3).map { race(it, stop = 6, slopeMs = 400) })
        val slope = assertNotNull(drifting.paceSlopeMsPerLap)
        assertEquals(400.0, slope.mean, 120.0)
    }

    // --- Trois stratégies ---

    private fun input(
        fuelNow: Float = 100f,
        perLap: Float = 14.5f,
        lap: Int = 1,
        tyreAge: Int = 0,
        r: RaceRules = RaceRules(totalLaps = 10),
    ) = RacePlanner.Input(
        rules = r, gameTotalLaps = 10, currentLap = lap, fuelNow = fuelNow, tank = 100f, perLap = perLap,
        fuelSource = DataSource.MEASURED, avgLapMs = 100_000, raceElapsedMs = null, stopsDone = 0, tyreAgeLaps = tyreAge,
    )

    @Test
    fun withoutMeasuredDifferenceTheNormalPlanIsKeptAndSaidSo() {
        val rep = assertNotNull(StrategyOptions.compare(input(), StrategyOptions.Pace(100_000.0, pitLossMs = 25_000.0, pitLossSpread = 3_000.0), listOf("test"), emptyList()))
        assertEquals(3, rep.options.size)
        assertEquals(OptionKind.NORMAL, rep.recommended)
        assertTrue(rep.indistinguishable)
        assertTrue("incertitude" in rep.reason, rep.reason)
        val normal = rep.option(OptionKind.NORMAL)!!
        assertTrue(normal.feasible)
        // Les variantes ont le même nombre d'arrêts : aucun gain fictif.
        rep.options.filter { it.feasible }.forEach { assertEquals(0L, it.deltaMs) }
    }

    @Test
    fun anExtendedStintBeyondTheFuelIsRejectedWithItsReason() {
        // 14,5 % par tour, réservoir plein : au plus 6 tours avant de rentrer.
        val rep = StrategyOptions.compare(input(), StrategyOptions.Pace(100_000.0), emptyList(), emptyList())!!
        val normalStop = rep.option(OptionKind.NORMAL)!!.stopLaps.first()
        val ext = rep.option(OptionKind.EXTENDED)!!
        if (normalStop + 1 > 6) {
            assertFalse(ext.feasible)
            assertTrue(ext.issues.single().contains("autonomie insuffisante"), ext.issues.toString())
        }
        val lowFuel = StrategyOptions.compare(input(fuelNow = 75f), StrategyOptions.Pace(100_000.0), emptyList(), emptyList())!!
        val e2 = lowFuel.option(OptionKind.EXTENDED)!!
        assertFalse(e2.feasible)
        assertTrue("autonomie insuffisante" in e2.issues.single(), e2.issues.toString())
    }

    @Test
    fun aMeasuredPaceDropOnOldTyresCallsForATyreChangeNotJustAnEarlierRefuel() {
        // Pneus déjà âgés de 4 tours, chronos qui se dégradent de 1,5 s par tour d'âge des pneus (mesuré). 10 % par tour :
        // un plein fait 9 tours. Avancer le ravitaillement seul ne change rien aux pneus : aucun gain.
        val pace = StrategyOptions.Pace(100_000.0, slopeMsPerLap = 1_500.0, slopeSpread = 100.0, pitLossMs = 25_000.0, pitLossSpread = 2_000.0)
        val rep = StrategyOptions.compare(input(fuelNow = 50f, perLap = 10f, lap = 2, tyreAge = 4), pace, emptyList(), emptyList())!!
        val early = rep.option(OptionKind.EARLY)!!
        assertTrue(early.feasible, early.issues.toString())
        assertEquals(0L, early.deltaMs, "un ravitaillement ne rajeunit pas les pneus")
        // Changer les pneus au ravitaillement (ou plus tôt) fait gagner du temps, au-delà de l'incertitude et du temps du changement.
        assertTrue(rep.recommended in StrategyOptions.TYRE_KINDS, rep.reason)
        assertFalse(rep.indistinguishable)
        assertTrue("gain estimé" in rep.reason && "pneus" in rep.reason, rep.reason)
    }

    @Test
    fun noStopNeededMeansStayingOut() {
        val rep = StrategyOptions.compare(input(perLap = 5f), StrategyOptions.Pace(100_000.0), emptyList(), emptyList())!!
        assertEquals(1, rep.options.size)
        assertTrue(rep.options.single().stopLaps.isEmpty())
        assertEquals("Aucun arrêt nécessaire : on reste en piste jusqu'à l'arrivée.", rep.reason)
    }

    @Test
    fun unknownPaceGivesNoRankingInSeconds() {
        val rep = StrategyOptions.compare(input(), StrategyOptions.Pace(lapMs = null), emptyList(), listOf("arrêts obligatoires"))!!
        assertEquals(OptionKind.NORMAL, rep.recommended)
        assertTrue(rep.options.all { it.estimatedMs == null })
        assertTrue("ne permettent pas de chiffrer" in rep.reason, rep.reason)
        assertEquals(listOf("arrêts obligatoires"), rep.unverifiedRules)
    }

    // --- Intégration : la course apprend des courses précédentes ---

    @Test
    fun theControllerPlansFromComparableArchivedRacesWithTheirConfidence() {
        val store = FakeStorage()
        fun run(perLap: Float, car: Int = 3607) {
            val c = RaceController(storage = store)
            c.selectProfile(raceB)
            c.setRules(RaceRules(totalLaps = 6, trackId = 462))
            val s = RaceSim(c, totalLaps = 6, perLap = perLap, carCode = car)
            s.start()
            s.driveLaps(7)
        }
        repeat(3) { run(6f) }
        run(12f, car = 99) // autre voiture : ignorée
        val c = RaceController(storage = store)
        c.selectProfile(raceB)
        val s = RaceSim(c, totalLaps = 6)
        s.start()
        val learned = assertNotNull(c.historyModel.fuelPerLap)
        assertEquals(3, learned.sessions)
        assertEquals(6.0, learned.mean, 0.3)
        assertEquals(DataSource.HISTORY, c.plan?.fuelSource)
        assertEquals(6f, c.plan!!.perLap!!, 0.3f)
        val basis = c.strategy!!.basis.joinToString()
        assertTrue("consommation apprise" in basis && "3 courses" in basis, basis)
        // Dès les premiers tours mesurés, la mesure de la course remplace l'historique.
        s.driveLaps(2)
        assertEquals(DataSource.MEASURED, c.plan?.fuelSource)
    }

    @Test
    fun aPlannedStopNeverBecomesARecordedStop() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10, perLap = 14.5f)
        s.start()
        s.driveLaps(3)
        assertTrue(s.controller.plan!!.stops.isNotEmpty())
        assertEquals(0, s.controller.ledger.stopCount)
        s.controller.command(DriverCommand.Ask(DriverQuery.PLAN))
        assertEquals(0, s.controller.ledger.stopCount)
    }
}
