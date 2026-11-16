package com.gt7pitwall.core

import com.gt7pitwall.core.display.GtDash
import com.gt7pitwall.core.display.GtDash.SectorState
import com.gt7pitwall.core.display.GtDash.Zone
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tableau de bord GT3 (V507) : LED de rupteur, rapport, chrono, delta et secteurs en direct. Données synthétiques. */
class GtDashTest {
    private val base: TelemetryPacket = TelemetryParser.parseDecrypted(TestPackets.plain(lap = 3, speedMps = 50f))!!

    private fun at(rpm: Float, lo: Int = 6000, hi: Int = 7500, limiter: Boolean = false, gear: Int = 4) = base.copy(
        engineRpm = rpm, rpmAlertMin = lo, rpmAlertMax = hi, currentGear = gear,
        flags = base.flags or (if (limiter) TelemetryPacket.FLAG_REV_LIMIT else 0),
    )

    @Test fun shiftLightsFollowTheGt7ThresholdsOfTheCar() {
        assertEquals(0, GtDash.leds(at(5000f)).lit, "sous le début du témoin GT7 : rien")
        assertEquals(7, GtDash.leds(at(6750f)).lit, "à mi-chemin : la moitié des 15 LED")
        val shift = GtDash.leds(at(7500f))
        assertTrue(shift.shift); assertEquals(15, shift.lit)
        assertEquals(Zone.SHIFT, shift.zone(0), "passage de rapport : toutes en bleu")
        assertTrue(GtDash.leds(at(7000f, limiter = true)).shift, "drapeau rupteur de GT7")
        val ramp = GtDash.leds(at(7400f))
        assertEquals(listOf(Zone.GREEN, Zone.YELLOW, Zone.RED), listOf(ramp.zone(0), ramp.zone(7), ramp.zone(14)))
    }

    @Test fun withoutThresholdsThereAreNoLedsNeverAGuess() {
        assertFalse(GtDash.leds(at(7000f, lo = 0, hi = 0)).available)
        assertFalse(GtDash.leds(at(7000f, lo = 7000, hi = 6000)).available)
        assertFalse(GtDash.leds(null).available)
    }

    @Test fun gearAndPedalsComeFromThePacket() {
        assertEquals("R", GtDash.gearLabel(0))
        assertEquals("4", GtDash.gearLabel(4))
        assertEquals("N", GtDash.gearLabel(15))
        assertNull(GtDash.gearLabel(null))
        val d = GtDash.of(at(6000f, gear = 3).copy(throttle = 255, brake = 0, suggestedGear = 4), live = true, liveLap = null, laps = emptyList())
        assertEquals("3", d.gear); assertEquals(4, d.suggestedGear)
        assertEquals(180, d.speedKmh)
        assertEquals(1f, d.throttle); assertEquals(0f, d.brake)
        assertEquals(GtDash.TimeSource.NONE, d.lapTimeSource)
        assertNull(d.lapTimeMs, "ni GT7 ni tour suivi : pas de chrono")
        val none = GtDash.of(null, live = false, liveLap = null, laps = emptyList())
        assertNull(none.gear); assertNull(none.speedKmh); assertNull(none.rpm); assertNull(none.throttle)
    }

    /** Course simulée : deux tours de référence, puis un tour plus lent observé en direct. */
    @Test fun liveLapTimeDeltaAndSectorsAreMeasuredAgainstTheBestLap() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10)
        s.start()
        s.driveLaps(3) // le tour 1 commence à la ligne : tours 2 et 3 suivis en entier
        val c = s.controller
        assertTrue(c.snapshot.laps.any { it.sectorsMs != null }, "secteurs mesurés")
        // Tour en cours plus lent (70 m/s au lieu de 80) : à mi-tour, le delta est positif.
        s.driveTo(Math.PI.toFloat(), speed = 70f)
        val d = c.gtDash(live = true)
        assertEquals(GtDash.TimeSource.PITWALL, d.lapTimeSource, "format A : chrono mesuré par Pitwall")
        assertNotNull(d.lapTimeMs)
        val half = (Math.PI * s.radius / 70.0 * 1000).toInt()
        assertTrue(kotlin.math.abs(d.lapTimeMs!! - half) < 300, "chrono ${d.lapTimeMs} ≈ $half ms")
        assertNotNull(d.deltaMs)
        assertTrue(d.deltaMs!! > 1_000, "plus lent que le meilleur tour : ${d.deltaMs} ms")
        assertEquals(2, d.sector, "à mi-tour : secteur 2")
        assertEquals(SectorState.SLOWER, d.sectors[0].state, "premier secteur bouclé plus lentement")
        assertEquals(SectorState.CURRENT, d.sectors[1].state)
        assertEquals(SectorState.PENDING, d.sectors[2].state)
    }

    @Test fun aFasterSectorIsPurple() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10)
        s.start()
        s.driveLaps(3, speed = 70f)
        s.driveTo((Math.PI * 0.9).toFloat(), speed = 90f)
        val d = s.controller.gtDash(live = true)
        assertTrue(d.deltaMs!! < 0, "plus rapide : delta négatif (${d.deltaMs})")
        assertEquals(SectorState.BEST, d.sectors[0].state)
    }

    @Test fun noReferenceMeansNoDeltaButStillALapTime() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10)
        s.start()
        s.driveTo(1f)
        val d = s.controller.gtDash(live = true)
        assertNull(d.deltaMs, "aucun tour complet : aucun delta")
        assertNull(d.sector)
        assertNotNull(d.lapTimeMs)
        assertTrue(d.sectors.all { it.state == SectorState.PENDING })
    }
}
