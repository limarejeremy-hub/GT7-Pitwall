package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.race.StrategyDialogue.ObservationKind
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreService
import com.gt7pitwall.core.telemetry.Wheels
import com.gt7pitwall.core.tyres.GripArea
import com.gt7pitwall.core.tyres.GripDiagnosis
import com.gt7pitwall.core.tyres.GripLevel
import com.gt7pitwall.core.tyres.StintContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D1 / D2 : un ravitaillement ne prouve pas un changement de pneus, et inversement ; diagnostic prudent. */
class TyreFuelIndependenceTest {

    /** 15 tours à 8 % par tour avec 60 % à bord : un ravitaillement nécessaire. */
    private var plannedFuel = 0f

    private fun fuelStopRace(store: FakeStorage = FakeStorage()): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = store)
        val sim = RaceSim(c, totalLaps = 15, perLap = 8f, fuel = 60f)
        sim.start()
        sim.driveLaps(2)
        val stop = c.plan!!.stops.first()
        plannedFuel = stop.fuel!!.toFloat()
        while (sim.lap < stop.endOfLap) sim.driveLaps(1)
        return c to sim
    }

    @Test
    fun refuelWithoutTyreChangeKeepsTheTyreStint() {
        val (c, sim) = fuelStopRace()
        sim.driveLaps(0)
        sim.pitStop(fuel = plannedFuel)
        assertEquals(StopEvidence.REFUEL, c.ledger.stopList.single().evidence)
        assertTrue(sim.texts.takeLast(3).any { it.endsWith("As-tu changé les pneus ?") }, sim.texts.takeLast(3).toString())
        sim.say("non")
        assertEquals(TyreService.NOT_CHANGED, c.ledger.stopList.single().tyres)
        sim.driveLaps(2)
        val lap = sim.lap
        val stint = c.ledger.tyreStint(lap, null)
        assertEquals(1, stint.startLap, "le relais pneus continue depuis le départ")
        assertEquals(lap - 1, stint.age)
        assertTrue(stint.certain)
        assertEquals(1, c.engineer.stintContext.startLap)
        assertTrue(c.ledger.changes.isEmpty())
    }

    @Test
    fun anUnansweredQuestionLeavesTheTyresUnknownNotNew() {
        val (c, sim) = fuelStopRace()
        sim.pitStop(fuel = plannedFuel)
        // Pas de réponse : la question expire au bout d'une minute.
        sim.driveLaps(2)
        assertFalse(c.ledger.awaitingTyreChangeAnswer)
        assertEquals(TyreService.UNKNOWN, c.ledger.stopList.single().tyres)
        assertFalse(c.ledger.tyreStint(sim.lap, null).certain, "âge des pneus incertain")
        // Un « oui » bien plus tard ne déclare pas des pneus neufs.
        sim.say("oui")
        assertTrue(c.ledger.changes.isEmpty())
        assertTrue(c.ledger.tyreStint(sim.lap, null).startLap == 1)
    }

    @Test
    fun tyresOnlyAndCombinedStops() {
        // Pneus seulement : arrêt déclaré sans carburant, pneus changés.
        val c = RaceController(storage = FakeStorage())
        val sim = RaceSim(c, totalLaps = 15, perLap = 2f)
        sim.start()
        sim.driveLaps(4)
        sim.say("j'ai fait mon arrêt")
        sim.say("oui")
        val stop = c.ledger.stopList.single()
        assertEquals(TyreService.CHANGED, stop.tyres)
        assertFalse(stop.refuelled, "un changement de pneus ne prouve pas un ravitaillement")
        sim.driveLaps(3)
        assertEquals(5, c.ledger.tyreStint(sim.lap, null).startLap)
        assertEquals(3, c.ledger.tyreAge(sim.lap))

        // Combiné : ravitaillement mesuré et pneus changés.
        val (c2, sim2) = fuelStopRace()
        sim2.pitStop(fuel = plannedFuel)
        sim2.say("oui")
        val s2 = c2.ledger.stopList.single()
        assertTrue(s2.refuelled)
        assertEquals(TyreService.CHANGED, s2.tyres)
        sim2.driveLaps(1)
        assertEquals(1, c2.ledger.tyreAge(sim2.lap))
    }

    @Test
    fun compoundChangeAndArchive() {
        val store = FakeStorage()
        val (c, sim) = fuelStopRace(store)
        c.setRules(c.rules.copy(startTyre = TyreCompound.RM))
        sim.pitStop(fuel = plannedFuel)
        sim.say("j'ai mis les durs course")
        assertEquals(TyreCompound.RH, c.ledger.changes.single().compound)
        assertEquals(TyreService.CHANGED, c.ledger.stopList.single().tyres)
        sim.driveLaps(2)
        val archived = store.sessionsJson.values.mapNotNull(RaceJson::session).maxBy { it.updatedAtMs }
        assertEquals(TyreService.CHANGED, archived.stops.single().tyres)
        val stopLap = archived.stops.single().lap
        val before = archived.laps.first { it.lap == stopLap - 1 }
        val after = archived.laps.last()
        assertEquals(TyreCompound.RM, before.tyreCompound)
        assertEquals(stopLap - 2, before.tyreAge)
        assertEquals(TyreCompound.RH, after.tyreCompound)
        assertTrue(after.tyreAge!! < before.tyreAge!!)
    }

    // ------------------------------------------------------------------ Diagnostic d'adhérence

    private fun lap(
        n: Int, ms: Int, temp: Float = 85f, tcs: Float = 0.02f, locks: Int = 1, spins: Int = 1,
        front: Int = 0, rear: Int = 0, temps: Wheels? = null,
    ) = LapRecord(
        n, ms, 2f, false, null, 250f, tcs, avgTyreTemp = temp, lockups = locks, wheelspins = spins,
        wheelTemps = temps, frontLockups = front, rearLockups = 0, frontWheelspins = 0, rearWheelspins = rear,
    )

    @Test
    fun highTemperatureWithoutDegradationIsStable() {
        val laps = (2..9).map { lap(it, 100_000 + (it % 2) * 150, temp = 104f) }
        val r = GripDiagnosis.analyze(laps, StintContext())
        assertEquals(GripLevel.STABLE, r.level)
        assertTrue(r.alternatives.isEmpty())
        assertTrue(r.evidence.none { "température" in it || "degré" in it }, "la température n'est pas une preuve d'usure ni d'adhérence")
    }

    @Test
    fun aSingleSlowLapIsAMistakeNotWear() {
        val laps = (2..7).map { lap(it, 100_000 + it * 20) } + lap(8, 103_500, locks = 5, spins = 4)
        val r = GripDiagnosis.analyze(laps, StintContext())
        assertEquals(GripLevel.STABLE, r.level)
        assertEquals(8, r.isolatedLap)
        assertTrue(r.alternatives.first().contains("erreur"), r.alternatives.toString())
    }

    @Test
    fun repeatedMeasurableDegradationIsPersistentAndLocated() {
        // Chronos qui se dégradent de 350 ms par tour, glisse croissante à l'avant.
        val laps = (2..10).map { n -> lap(n, 100_000 + (n - 2) * 350, front = (n - 2) / 2, tcs = 0.02f + (n - 2) * 0.006f) }
        val r = GripDiagnosis.analyze(laps, StintContext())
        assertTrue(r.level >= GripLevel.PERSISTENT, r.toString())
        assertTrue(GripArea.FRONT in r.areas, r.areas.toString())
        assertTrue(r.slopeMsPerLap!! > 250, r.toString())
        assertTrue(r.alternatives.any { "trafic" in it }, "autres causes toujours citées")
        // Les tours d'un ancien relais pneus ne comptent pas.
        val fresh = GripDiagnosis.analyze(laps, StintContext(startLap = 9))
        assertEquals(GripLevel.INSUFFICIENT, fresh.level)
    }

    @Test
    fun declarationsAreKeptApartAndRainSuspendsTheComparison() {
        val laps = (2..7).map { lap(it, 100_000 + (if (it >= 6) 450 else 0)) }
        val r = GripDiagnosis.analyze(laps, StintContext(declared = listOf(ObservationKind.GRIP_REAR)))
        assertEquals(GripLevel.POSSIBLE, r.level)
        assertEquals(listOf("perte d'adhérence à l'arrière"), r.declared)
        assertTrue(r.evidence.none { "arrière" in it }, "le déclaré n'entre pas dans les mesures")
        assertEquals(GripLevel.INSUFFICIENT, GripDiagnosis.analyze(laps, StintContext(raining = true)).level)
        assertNull(GripDiagnosis.analyze(laps.take(2), StintContext()).slopeMsPerLap)
    }

    @Test
    fun refuelDoesNotResetTheDegradationWarning() {
        val (c, sim) = fuelStopRace()
        c.setRules(RaceRules(totalLaps = 15))
        sim.pitStop(fuel = plannedFuel)
        sim.say("non")
        // Le relais pneus est toujours celui du départ, même après un plein.
        assertEquals(1, c.engineer.stintContext.startLap)
        sim.driveLaps(1)
        assertEquals(1, c.engineer.stintContext.startLap)
        assertTrue(c.engineer.stintContext.excludedLaps.contains(c.ledger.stopList.single().lap))
    }
}
