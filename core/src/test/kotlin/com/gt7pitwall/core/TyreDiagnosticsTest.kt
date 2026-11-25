package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.Surface
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TyreSurfaces
import com.gt7pitwall.core.telemetry.Wheels
import com.gt7pitwall.core.tyres.GripLevel
import com.gt7pitwall.core.tyres.GripReport
import com.gt7pitwall.core.tyres.TyreDiagnostics
import com.gt7pitwall.core.tyres.TyreDiagnostics.IssueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V514-A : diagnostic des quatre pneus sur des paquets FABRIQUÉS. Ces tests vérifient le logiciel (seuils, relais,
 * récupération après arrêt, formats), jamais la justesse d'un modèle sur GT7 : rien n'est validé PS5 ici.
 */
class TyreDiagnosticsTest {
    private val radius = 0.33f

    /** Un paquet en course : [slip] glissement de chaque roue (1 = roue libre), [temps] AVG, AVD, ARG, ARD. */
    private fun pk(
        lap: Int, speedKmh: Float, temps: List<Float>, slip: List<Float> = listOf(1f, 1f, 1f, 1f),
        throttle: Int = 255, brake: Int = 0, format: PacketFormat = PacketFormat.A, surface: Surface? = null, r: Float = radius,
    ): TelemetryPacket {
        val v = speedKmh / 3.6f
        fun w(i: Int) = if (r > 0f) slip[i] * v / r else 0f
        return TelemetryPacket(
            packetId = 1, positionX = 0f, positionY = 0f, positionZ = 0f, velocityX = 0f, velocityY = 0f, velocityZ = 0f,
            bodyHeight = 0f, engineRpm = 7000f, fuelLevel = 50f, fuelCapacity = 100f, speedMps = v, turboBoost = 0f,
            oilPressure = 0f, waterTemperature = 85f, oilTemperature = 100f,
            tyreTempFrontLeft = temps[0], tyreTempFrontRight = temps[1], tyreTempRearLeft = temps[2], tyreTempRearRight = temps[3],
            currentLap = lap, totalLaps = 12, bestLapMs = -1, lastLapMs = -1, timeOfDayMs = 0, preRacePosition = -1,
            preRaceCarCount = 16, rpmAlertMin = 0, rpmAlertMax = 0, calculatedMaxSpeed = 0, flags = 1, currentGear = 4,
            suggestedGear = 0, throttle = throttle, brake = brake, clutchPedal = 0f, transmissionTopSpeed = 0f, carCode = 2184,
            format = format, wheelSpeed = Wheels(w(0), w(1), w(2), w(3)), tyreRadius = Wheels(r, r, r, r),
            surfaces = surface?.let { TyreSurfaces(it, it, it, it, "${it.code}".repeat(4)) },
        )
    }

    /**
     * Tours de 30 s à 20 Hz : la première moitié en pleine accélération en ligne droite (propulsion : arrière à 1,4 %),
     * puis un freinage. [spins] : patinages forts (ARG) par tour, chacun de 10 échantillons ; [locks] : blocages (AVG).
     */
    private fun drive(
        d: TyreDiagnostics, laps: IntRange, t0: Long = 0, temps: (Int) -> List<Float> = { listOf(80f, 80f, 78f, 78f) },
        spins: (Int) -> Int = { 0 }, locks: (Int) -> Int = { 0 }, format: PacketFormat = PacketFormat.A, surface: Surface? = null,
        stopLap: Int? = null, stopTemps: List<Float>? = null,
    ): Long {
        var t = t0
        for (lap in laps) {
            for (i in 0 until 600) {
                val accel = i < 300
                val spinNow = accel && i in 20 until 20 + spins(lap) * 25 && (i - 20) % 25 < 10
                val lockNow = !accel && i in 320 until 320 + locks(lap) * 25 && (i - 320) % 25 < 6
                val slip = listOf(if (lockNow) 0.7f else 1f, 1f, if (spinNow) 1.3f else if (accel) 1.014f else 1f, if (accel) 1.014f else 1f)
                d.onPacket(pk(lap, 180f, temps(lap), slip, throttle = if (accel) 255 else 0, brake = if (accel) 0 else 200, format = format, surface = surface), t)
                t += 50
            }
            if (lap == stopLap) {
                // Arrêt de 20 s au stand en fin de tour : pneus refroidis.
                repeat(400) { d.onPacket(pk(lap, 0f, stopTemps ?: listOf(55f, 55f, 55f, 55f), throttle = 0, brake = 0), t); t += 50 }
            }
        }
        return t
    }

    @Test
    fun formatAOnlyGivesTemperaturesAndDrivenWheelSlipWithReducedConfidence() {
        val d = TyreDiagnostics()
        drive(d, 1..4)
        val r = d.report()
        assertEquals(TyreDiagnostics.DrivenAxle.REAR, r.drivenAxle)
        assertFalse(r.surfacesKnown)
        assertEquals(80f, r.wheels[0].lapMeanC!!, 0.01f)
        assertEquals(80f, r.wheels[0].lapMaxC!!, 0.01f)
        assertNull(r.wheels[0].slipPct, "roue libre : pas de patinage chiffré")
        assertEquals(1.4f, r.wheels[2].slipPct!!, 0.05f)
        assertTrue(r.confidence < TyreDiagnostics.Confidence.HIGH)
        assertTrue(r.confidenceWhy.any { "surface" in it }, r.confidenceWhy.toString())
        assertTrue(r.issues.isEmpty(), r.issues.toString())
    }

    @Test
    fun formatCUsesTheSurfaceAndRaisesConfidence() {
        val d = TyreDiagnostics()
        drive(d, 1..4, format = PacketFormat.C, surface = Surface.TARMAC)
        val r = d.report()
        assertTrue(r.surfacesKnown)
        assertEquals(TyreDiagnostics.Confidence.HIGH, r.confidence)
        // Sur un vibreur, rien n'est compté (format C) : aucun patinage relevé.
        val k = TyreDiagnostics()
        drive(k, 1..4, spins = { 5 }, format = PacketFormat.C, surface = Surface.KERB)
        assertEquals(0, k.report().wheels[2].spinEvents)
    }

    @Test
    fun asymmetricTemperaturesOnOneAxleAreFlaggedWithTheirOrigin() {
        val d = TyreDiagnostics()
        drive(d, 1..6, temps = { lap -> if (lap >= 4) listOf(94f, 80f, 78f, 78f) else listOf(80f, 80f, 78f, 78f) })
        val r = d.report()
        val temp = r.issues.filter { it.kind == IssueKind.TEMPERATURE }
        assertTrue(temp.any { it.wheel == 0 && "Essieu avant" in it.text }, temp.toString())
        assertTrue(temp.all { "MESURE" in it.origin })
        assertEquals(14f, r.wheels[0].axleDeltaC!!, 0.01f)
        assertEquals(0, r.worstWheel)
    }

    @Test
    fun wheelspinCountsSamplesAndEventsSeparately() {
        val d = TyreDiagnostics()
        drive(d, 1..6, spins = { lap -> if (lap >= 4) 5 else 0 })
        val r = d.report()
        val rl = r.wheels[2]
        // Tours 4 et 5 terminés, tour 6 en cours : 3 × 5 événements de 10 échantillons.
        assertEquals(15, rl.spinEvents)
        assertEquals(150, rl.spinSamples)
        assertTrue(r.issues.any { it.kind == IssueKind.WHEELSPIN && it.wheel == 2 }, r.issues.toString())
        assertFalse(r.issues.any { it.kind == IssueKind.LOCKING })
    }

    @Test
    fun repeatedLocksAreFlaggedOnTheLockingWheel() {
        val d = TyreDiagnostics()
        drive(d, 1..6, locks = { lap -> if (lap >= 4) 4 else 0 })
        val r = d.report()
        assertEquals(12, r.wheels[0].lockEvents)
        assertTrue(r.issues.any { it.kind == IssueKind.LOCKING && it.wheel == 0 }, r.issues.toString())
    }

    @Test
    fun aRefuelOnlyStopKeepsTheStintAndRaisesNoColdTyreAlarm() {
        val d = TyreDiagnostics()
        // Arrêt au tour 4 sans changement de pneus : le registre ne change pas le relais.
        drive(d, 1..5, stopLap = 4, temps = { lap -> if (lap == 5) listOf(62f, 62f, 60f, 60f) else listOf(80f, 80f, 78f, 78f) })
        val r = d.report()
        assertEquals(1, r.stintStartLap)
        assertEquals(2, r.referenceLap, "la référence du relais est gardée")
        assertTrue(r.recovering, "tour de sortie : récupération thermique")
        assertTrue(r.issues.none { it.kind == IssueKind.TEMPERATURE }, r.issues.toString())
        assertTrue(r.history.isEmpty())
    }

    @Test
    fun aConfirmedTyreChangeOpensANewStintKeepsHistoryAndWaitsForStabilisation() {
        val d = TyreDiagnostics()
        var t = drive(d, 1..5, stopLap = 5)
        // Le pilote confirme « pneus neufs » : nouveau relais au tour 5 (tour de l'arrêt).
        d.setStint(5, certain = true)
        t = drive(d, 6..6, t0 = t, temps = { listOf(60f, 60f, 58f, 58f) })
        val early = d.report()
        assertEquals(5, early.stintStartLap)
        assertEquals(1, early.history.size)
        assertEquals(1, early.history.single().startLap)
        assertNull(early.referenceLap, "pas de référence à la sortie des stands")
        assertTrue(early.issues.none { it.kind == IssueKind.TEMPERATURE })
        drive(d, 7..8, t0 = t)
        val later = d.report()
        assertEquals(7, later.referenceLap)
    }

    @Test
    fun missingDataNeverCrashesNorInventsValues() {
        val d = TyreDiagnostics()
        assertEquals(TyreDiagnostics.Report.EMPTY, d.report())
        // Rayon des pneus absent : aucun glissement calculé ; températures à 0 : ignorées.
        var t = 0L
        repeat(700) { d.onPacket(pk(1, 150f, listOf(0f, 0f, 0f, 0f), r = 0f), t); t += 50 }
        repeat(700) { d.onPacket(pk(2, 150f, listOf(0f, 0f, 0f, 0f), r = 0f), t); t += 50 }
        val r = d.report()
        assertTrue(r.wheels.all { it.lapMeanC == null && it.slipPct == null && it.spinEvents == 0 })
        assertEquals(TyreDiagnostics.DrivenAxle.UNKNOWN, r.drivenAxle)
    }

    @Test
    fun aUdpInterruptionIsCountedAndNoEventSpansIt() {
        val d = TyreDiagnostics()
        var t = drive(d, 1..2, format = PacketFormat.C, surface = Surface.TARMAC)
        // Patinage juste avant et juste après une coupure de 3 s : deux événements distincts.
        repeat(5) { d.onPacket(pk(3, 180f, listOf(80f, 80f, 78f, 78f), listOf(1f, 1f, 1.3f, 1.014f), format = PacketFormat.C, surface = Surface.TARMAC), t); t += 50 }
        t += 3_000
        repeat(5) { d.onPacket(pk(3, 180f, listOf(80f, 80f, 78f, 78f), listOf(1f, 1f, 1.3f, 1.014f), format = PacketFormat.C, surface = Surface.TARMAC), t); t += 50 }
        val r = d.report()
        assertEquals(1, r.gaps)
        assertEquals(2, r.wheels[2].spinEvents)
        assertTrue(r.confidenceWhy.any { "coupure" in it })
        assertTrue(r.confidence < TyreDiagnostics.Confidence.HIGH)
    }

    @Test
    fun gripLossIsOnlyClaimedWhenTheLapTimesShowIt() {
        val d = TyreDiagnostics()
        drive(d, 1..3)
        assertTrue(d.report(GripReport(GripLevel.POSSIBLE)).issues.single().kind == IssueKind.DEGRADATION_SUSPECTED)
        assertTrue(d.report(GripReport(GripLevel.PERSISTENT)).issues.single().kind == IssueKind.GRIP_LOSS)
        assertTrue(d.report(GripReport(GripLevel.STABLE)).issues.isEmpty())
        assertNotNull(d.report().wheels[0].nowC)
    }
}
