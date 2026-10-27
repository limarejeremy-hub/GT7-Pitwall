package com.gt7pitwall.core

import com.gt7pitwall.core.analysis.CornerPass
import com.gt7pitwall.core.analysis.LapSample
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.telemetry.TyreSurfaces
import com.gt7pitwall.core.telemetry.Wheels
import com.gt7pitwall.core.tyres.BalanceTrend
import com.gt7pitwall.core.tyres.BrakingMonitor
import com.gt7pitwall.core.tyres.ThermalStress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Croisements n°2 (freinage), n°3 (équilibre) et n°4 (thermique), issue #36. Données synthétiques, non validées sur PS5. */
class HandlingCrossTest {
    private val tarmac = TyreSurfaces.parse("TTTT".toByteArray())

    private fun brake(decel: Float = 12f, front: Float = 0.98f, kmh: Float = 120f, temp: Float = 80f, surfaces: TyreSurfaces? = tarmac) = LapSample(
        tick = 0, x = 0f, z = 0f, speedMps = kmh / 3.6f, throttle = 0, brake = 160, yawRate = 0f, wheelAngle = null,
        frontSlip = front, rearSlip = 1f, tcs = false, offTrack = false, surfaces = surfaces,
        slips = Wheels(front, front, 1f, 1f), gear = 4, rpm = 6000f, longAccel = -decel, tyreTemps = Wheels(temp, temp, temp, temp),
    )

    private fun lap(n: Int, each: () -> LapSample) = BrakingMonitor.lap(n, (1..3).associateWith { List(20) { each() } })

    @Test
    fun sameBrakePressureLessDecelerationIsSeen() {
        val laps = listOf(lap(2) { brake() }, lap(3) { brake() }, lap(4) { brake(10.8f, 0.93f) }, lap(5) { brake(10.8f, 0.93f) })
        val r = BrakingMonitor.compare(laps, 1, emptySet())
        assertEquals(BrakingMonitor.Level.DEGRADATION_SUSPECTED, r.level, r.text())
        assertTrue(r.text().contains("décélération −1,20 m/s², glissement avant −5,0 points"), r.text())
    }

    @Test
    fun brakingFalsePositivesAreAvoided() {
        val ref = listOf(lap(2) { brake() }, lap(3) { brake() })
        // Freinage sur vibreur, autre vitesse (freiné plus tard = autre endroit, pas moins d'adhérence), pneus froids, pluie.
        val kerb = TyreSurfaces.parse("CTTT".toByteArray())
        assertEquals(BrakingMonitor.Level.INSUFFICIENT, BrakingMonitor.compare(ref + listOf(lap(4) { brake(9f, surfaces = kerb) }, lap(5) { brake(9f, surfaces = kerb) }), 1, emptySet()).level)
        assertEquals(BrakingMonitor.Level.INSUFFICIENT, BrakingMonitor.compare(ref + listOf(lap(4) { brake(9f, kmh = 160f) }, lap(5) { brake(9f, kmh = 160f) }), 1, emptySet()).level)
        assertEquals(BrakingMonitor.Level.INSUFFICIENT, BrakingMonitor.compare(ref + listOf(lap(4) { brake(9f, temp = 60f) }, lap(5) { brake(9f, temp = 60f) }), 1, emptySet()).level)
        assertEquals(BrakingMonitor.Level.INSUFFICIENT, BrakingMonitor.compare(ref + listOf(lap(4) { brake(9f) }, lap(5) { brake(9f) }), 1, emptySet(), raining = true).level)
        // Un seul freinage raté : pas une tendance.
        assertEquals(BrakingMonitor.Level.STABLE, BrakingMonitor.compare(ref + listOf(lap(4) { brake(9f) }, lap(5) { brake() }), 1, emptySet()).level)
    }

    private fun pass(corner: Int, lap: Int, rotation: Float?, kerb: Float? = 0f) = CornerPass(
        corner, lap, 5_000, null, null, null, 120f, 80f, 0f, null, 110f, false, false, 0f, 0f, rotation, false, kerbShare = kerb,
    )

    @Test
    fun understeerRisingIsSeenOnlyAcrossMostCorners() {
        val passes = (2..7).flatMap { l -> (1..4).map { c -> pass(c, l, if (l >= 6) 0.85f else 0.97f) } }
        val r = BalanceTrend.analyze(passes, 1)
        assertEquals(BalanceTrend.Verdict.MORE_UNDERSTEER, r.verdict, r.text)
        // Un seul virage, ou passages sur vibreur : rien.
        val one = (2..7).flatMap { l -> (1..4).map { c -> pass(c, l, if (l >= 6 && c == 1) 0.80f else 0.97f) } }
        assertEquals(BalanceTrend.Verdict.STABLE, BalanceTrend.analyze(one, 1).verdict)
        val kerbs = (2..7).flatMap { l -> (1..4).map { c -> pass(c, l, if (l >= 6) 0.80f else 0.97f, kerb = if (l >= 6) 0.5f else 0f) } }
        // Les tours sur vibreur sont écartés : il reste les tours propres, stables ; jamais de sous-virage attribué.
        assertEquals(BalanceTrend.Verdict.STABLE, BalanceTrend.analyze(kerbs, 1).verdict)
        // Format sans angle des roues : jamais deviné.
        val noAngle = BalanceTrend.analyze((2..7).map { pass(1, it, null) }, 1)
        assertEquals(BalanceTrend.Verdict.UNKNOWN, noAngle.verdict)
        assertTrue(noAngle.text.contains("format C"), noAngle.text)
    }

    private fun lapRecord(n: Int, fl: Float, other: Float = 80f, frontLocks: Int = 0) = LapRecord(
        n, 90_000, 3f, false, wheelTemps = Wheels(fl, other, other, other), frontLockups = frontLocks, rearWheelspins = 0,
    )

    @Test
    fun theMostStressedTyreIsNamedWithoutCallingItWear() {
        val laps = listOf(lapRecord(2, 86f), lapRecord(3, 87f), lapRecord(4, 92f, frontLocks = 1), lapRecord(5, 95f, frontLocks = 2))
        val r = ThermalStress.analyze(laps, 1)
        assertEquals("avant gauche", r.wheel)
        assertTrue(r.text.contains("14 °C au-dessus des trois autres") && r.text.contains("Température élevée ≠ usure"), r.text)
        assertTrue(r.text.contains("blocages avant en hausse (+3)"), r.text)
        assertNull(ThermalStress.analyze(listOf(lapRecord(2, 81f), lapRecord(3, 81f), lapRecord(4, 82f)), 1).wheel, "écarts faibles : pneus équilibrés")
    }
}
