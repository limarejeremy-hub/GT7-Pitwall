package com.gt7pitwall.core

import com.gt7pitwall.core.TestPackets.packet
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.strategy.FuelAdvice
import com.gt7pitwall.core.strategy.FuelStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionAndFuelTest {

    /** Simule un tour : plusieurs paquets en piste puis passage de ligne. */
    private fun SessionTracker.drive(
        fromLap: Int,
        fuelStart: Float,
        fuelEnd: Float,
        lapTimeMs: Int,
        prevLastLap: Int,
        totalLaps: Int = 10,
    ): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        for (i in 0..9) {
            val f = fuelStart + (fuelEnd - fuelStart) * i / 10f
            events += update(packet(lap = fromLap, totalLaps = totalLaps, fuel = f, lastLapMs = prevLastLap))
        }
        events += update(packet(lap = fromLap + 1, totalLaps = totalLaps, fuel = fuelEnd, lastLapMs = lapTimeMs))
        return events
    }

    @Test
    fun recordsLapTimesAndFuelPerLap() {
        val t = SessionTracker()
        t.update(packet(lap = 0, fuel = 100f))
        t.update(packet(lap = 1, fuel = 100f)) // départ : début du tour 1 observé
        val e1 = t.drive(1, 100f, 92f, 90_000, prevLastLap = -1)
        val lap1 = e1.filterIsInstance<SessionEvent.LapCompleted>().single()
        assertEquals(1, lap1.lap.lapNumber)
        assertEquals(90_000, lap1.lap.timeMs)
        assertEquals(8f, lap1.lap.fuelUsed!!, 0.001f)
        assertNull(lap1.previousBestMs)

        val e2 = t.drive(2, 92f, 84f, 89_500, prevLastLap = 90_000)
        val lap2 = e2.filterIsInstance<SessionEvent.LapCompleted>().single()
        assertEquals(89_500, lap2.lap.timeMs)
        assertEquals(90_000, lap2.previousBestMs)
        assertEquals(89_500, t.snapshot.bestRecordedLapMs)
    }

    @Test
    fun lapTimeArrivingAfterLineCrossingIsStillRecorded() {
        val t = SessionTracker()
        t.update(packet(lap = 1, fuel = 50f))
        t.update(packet(lap = 2, fuel = 45f, lastLapMs = -1)) // compteur avant le chrono
        assertTrue(t.snapshot.laps.isEmpty())
        val ev = t.update(packet(lap = 2, fuel = 45f, lastLapMs = 75_000))
        val lap = ev.filterIsInstance<SessionEvent.LapCompleted>().single()
        assertEquals(75_000, lap.lap.timeMs)
        // Début du tour 1 non observé (connexion en cours de tour) : pas de consommation inventée.
        assertNull(lap.lap.fuelUsed)
    }

    @Test
    fun refuelInvalidatesConsumptionSample() {
        val t = SessionTracker()
        t.update(packet(lap = 1, fuel = 30f))
        t.update(packet(lap = 2, fuel = 30f, lastLapMs = 80_000)) // début du tour 2 observé
        t.update(packet(lap = 2, fuel = 25f, lastLapMs = 80_000))
        val refuel = t.update(packet(lap = 2, fuel = 60f, lastLapMs = 80_000))
        assertTrue(SessionEvent.RefuelDetected in refuel)
        val ev = t.update(packet(lap = 3, fuel = 55f, lastLapMs = 95_000))
        val lap = ev.filterIsInstance<SessionEvent.LapCompleted>().single()
        assertTrue(lap.lap.refuelled)
        assertNull(lap.lap.fuelUsed)
    }

    @Test
    fun newSessionResetsHistory() {
        val t = SessionTracker()
        t.update(packet(lap = 1, fuel = 100f))
        t.drive(1, 100f, 90f, 90_000, -1)
        assertEquals(1, t.snapshot.laps.size)
        val ev = t.update(packet(lap = 0, fuel = 100f))
        assertTrue(SessionEvent.SessionReset in ev)
        assertTrue(t.snapshot.laps.isEmpty())
        // Changement de voiture : nouvelle session, et pas de faux ravitaillement.
        val ev2 = t.update(packet(lap = 0, fuel = 100f, carCode = 2000))
        assertTrue(SessionEvent.SessionReset in ev2)
        assertTrue(SessionEvent.RefuelDetected !in ev2)
    }

    @Test
    fun finalLapEvent() {
        val t = SessionTracker()
        t.update(packet(lap = 2, totalLaps = 3, fuel = 50f))
        val ev = t.update(packet(lap = 3, totalLaps = 3, fuel = 45f, lastLapMs = 70_000))
        assertTrue(ev.any { it is SessionEvent.FinalLapStarted })
    }

    @Test
    fun fuelMeasuringBeforeFirstFullLap() {
        val t = SessionTracker()
        t.update(packet(lap = 1, fuel = 100f))
        val r = FuelStrategy.compute(t.snapshot)!!
        assertEquals(FuelAdvice.Measuring, r.advice)
        assertNull(r.lapsOfFuel)
    }

    @Test
    fun comfortableWhenEnoughFuel() {
        val t = SessionTracker()
        t.update(packet(lap = 0, totalLaps = 5, fuel = 100f))
        t.drive(1, 100f, 90f, 90_000, -1, totalLaps = 5) // 10 / tour, 90 restants, 4 tours à faire
        val r = FuelStrategy.compute(t.snapshot)!!
        assertEquals(10f, r.perLap!!, 0.001f)
        assertEquals(9f, r.lapsOfFuel!!, 0.001f)
        assertEquals(4f, r.raceLapsRemaining!!, 0.001f)
        val a = assertIs<FuelAdvice.Comfortable>(r.advice)
        assertEquals(5f, a.marginLaps, 0.001f)
    }

    @Test
    fun pitRequiredWithLatestLapAndAmount() {
        val t = SessionTracker()
        t.update(packet(lap = 0, totalLaps = 20, fuel = 50f))
        t.drive(1, 50f, 40f, 90_000, -1, totalLaps = 20) // 10 / tour, 40 restants => 4 tours
        val r = FuelStrategy.compute(t.snapshot)!!
        // Début du tour 2 : 19 tours restants => 190 nécessaires, il en manque 150.
        val a = assertIs<FuelAdvice.PitRequired>(r.advice)
        assertEquals(155f, a.fuelToAdd, 0.01f) // 150 + demi-tour de sécurité
        assertEquals(5, a.latestPitLap) // fin du tour 2 + 3 tours complets
        assertEquals(2, a.stopsNeeded) // réservoir de 100
    }

    @Test
    fun tightWhenMarginBelowHalfLap() {
        val t = SessionTracker()
        t.update(packet(lap = 0, totalLaps = 3, fuel = 31f))
        t.drive(1, 31f, 21f, 90_000, -1, totalLaps = 3) // 10 / tour, 21 restants, 2 tours à faire
        val a = assertIs<FuelAdvice.Tight>(FuelStrategy.compute(t.snapshot)!!.advice)
        assertEquals(10.5f, a.targetPerLap, 0.001f)
    }

    @Test
    fun currentLapProgressReducesFuelNeeded() {
        val t = SessionTracker()
        t.update(packet(lap = 0, totalLaps = 3, fuel = 40f))
        t.drive(1, 40f, 30f, 90_000, -1, totalLaps = 3)
        t.update(packet(lap = 2, totalLaps = 3, fuel = 25f, lastLapMs = 90_000)) // mi-tour 2
        val r = FuelStrategy.compute(t.snapshot)!!
        assertEquals(1.5f, r.raceLapsRemaining!!, 0.001f)
        assertEquals(15f, r.fuelToFinish!!, 0.001f)
    }

    @Test
    fun timedRaceGivesRangeOnly() {
        val t = SessionTracker()
        t.update(packet(lap = 0, totalLaps = 0, fuel = 60f))
        t.drive(1, 60f, 54f, 90_000, -1, totalLaps = 0)
        val a = assertIs<FuelAdvice.RangeOnly>(FuelStrategy.compute(t.snapshot)!!.advice)
        assertEquals(9f, a.lapsOfFuel, 0.001f)
    }

    @Test
    fun noCapacityMeansNotAvailable() {
        val t = SessionTracker()
        t.update(packet(lap = 1, fuel = 0f, capacity = 0f))
        assertEquals(FuelAdvice.NotAvailable, FuelStrategy.compute(t.snapshot)!!.advice)
    }

    @Test
    fun zeroConsumptionDetected() {
        val t = SessionTracker()
        t.update(packet(lap = 0, totalLaps = 5, fuel = 100f))
        t.drive(1, 100f, 100f, 90_000, -1, totalLaps = 5)
        assertEquals(FuelAdvice.NoConsumption, FuelStrategy.compute(t.snapshot)!!.advice)
    }

    @Test
    fun finalLapAndFinishComeAfterLapTime() {
        val t = SessionTracker()
        t.update(packet(lap = 1, totalLaps = 2, fuel = 50f))
        // Le chrono arrive un paquet après le passage de ligne.
        assertTrue(t.update(packet(lap = 2, totalLaps = 2, fuel = 45f, lastLapMs = -1)).isEmpty())
        val ev = t.update(packet(lap = 2, totalLaps = 2, fuel = 45f, lastLapMs = 80_000))
        assertIs<SessionEvent.LapCompleted>(ev[0])
        assertIs<SessionEvent.FinalLapStarted>(ev[1])
        val end = t.update(packet(lap = 3, totalLaps = 2, fuel = 40f, lastLapMs = 79_000))
        assertIs<SessionEvent.LapCompleted>(end[0])
        assertIs<SessionEvent.RaceFinished>(end[1])
    }
}
