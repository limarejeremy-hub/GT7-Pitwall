package com.gt7pitwall.core

import com.gt7pitwall.core.analysis.LapSample
import com.gt7pitwall.core.telemetry.TyreSurfaces
import com.gt7pitwall.core.telemetry.Wheels
import com.gt7pitwall.core.tyres.TractionMonitor
import com.gt7pitwall.core.tyres.TractionMonitor.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issues #35/#36 (Sprint B) : motricité à commande comparable. Exemples synthétiques (valeurs choisies pour le test,
 * pas des mesures GT7) : 50 % d'accélérateur, 3e, 80 km/h ; pneus frais glissement arrière 1,02 et 4,0 m/s², fin de
 * relais 1,22 et 3,0 m/s². Non validé physiquement tant qu'un essai PS5 ne l'a pas comparé au ressenti et au HUD.
 */
class TractionMonitorTest {
    private val tarmac = TyreSurfaces.parse("TTTT".toByteArray())
    private val kerbRR = TyreSurfaces.parse("TTTC".toByteArray())

    private fun sample(
        rearSlip: Float = 1.02f, accel: Float = 4.0f, tcs: Boolean = false, gear: Int = 3, kmh: Float = 80f,
        throttle: Int = 128, temp: Float = 80f, surfaces: TyreSurfaces? = tarmac, rpm: Float = 6000f, asm: Boolean = false,
    ) = LapSample(
        tick = 0, x = 0f, z = 0f, speedMps = kmh / 3.6f, throttle = throttle, brake = 0, yawRate = 0.05f, wheelAngle = null,
        frontSlip = 1.0f, rearSlip = rearSlip, tcs = tcs, offTrack = false, surfaces = surfaces,
        slips = Wheels(1.0f, 1.0f, rearSlip, rearSlip), gear = gear, rpm = rpm, longAccel = accel,
        tyreTemps = Wheels(temp, temp, temp, temp), asm = asm,
    )

    /** Quatre virages, 30 paquets de réaccélération chacun. */
    private fun lap(corners: Int = 4, each: (corner: Int) -> List<LapSample>) = (1..corners).associateWith { each(it) }
    private fun fresh() = lap { List(30) { sample() } }
    private fun worn() = lap { List(30) { sample(rearSlip = 1.22f, accel = 3.0f) } }

    private fun run(m: TractionMonitor, laps: Map<Int, Map<Int, List<LapSample>>>, stintStart: Int = 1): TractionMonitor.Report {
        var r: TractionMonitor.Report? = null
        for ((n, exits) in laps) {
            m.addLap(n, exits)
            r = m.evaluate(stintStart, emptySet())
        }
        return r!!
    }

    @Test
    fun sameCommandMoreWheelspinAndLessAccelerationIsDetectedThenRepeated() {
        val m = TractionMonitor()
        val r = run(m, mapOf(2 to fresh(), 3 to fresh(), 4 to fresh(), 5 to worn(), 6 to worn()))
        assertEquals(Level.DEGRADATION_SUSPECTED, r.level, r.text())
        assertEquals(listOf(1, 2, 3, 4), r.flaggedCorners)
        assertEquals(TractionMonitor.Axle.REAR, r.axle)
        val again = run(m, mapOf(7 to worn()))
        assertEquals(Level.DEGRADATION_REPEATED, again.level, again.text())
        assertTrue(again.text().contains("glissement +20,0 points, accélération −1,00 m/s²"), again.text())
        assertFalse(again.text().contains("%"), "aucun pourcentage d'usure")
    }

    @Test
    fun tcsThatMasksTheWheelspinStillShowsTheLoss() {
        // Le TCS coupe : le glissement ne monte pas, mais l'accélération obtenue baisse et le TCS intervient.
        val masked = lap { List(30) { sample(rearSlip = 1.03f, accel = 3.0f, tcs = it % 2 == 0) } }
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to masked, 5 to masked))
        assertEquals(Level.DEGRADATION_SUSPECTED, r.level, r.text())
    }

    @Test
    fun aRearRightOnTheKerbNeverRaisesATyreAlarm() {
        // Fin de relais : patinage fort, mais toujours avec la roue arrière droite sur le vibreur ; le reste est normal.
        val kerb = lap { List(15) { sample(rearSlip = 1.30f, accel = 2.5f, surfaces = kerbRR) } + List(15) { sample() } }
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to kerb, 5 to kerb))
        assertEquals(Level.STABLE, r.level, r.text())
        assertTrue(r.text().contains("mesures sur vibreur ou hors asphalte écartées"), r.text())
    }

    @Test
    fun anUnknownSurfaceIsNotUsedAsAReference() {
        val unknown = TyreSurfaces.parse("TTTX".toByteArray())
        val odd = lap { List(30) { sample(rearSlip = 1.30f, accel = 2.5f, surfaces = unknown) } }
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to odd, 5 to odd))
        assertEquals(Level.INSUFFICIENT, r.level, r.text())
    }

    @Test
    fun anotherGearOrSpeedIsNotComparedSoNoFalseDiagnosis() {
        // Rapport et vitesse différents (autre trajectoire, autre réglage) : rien de comparable, aucune conclusion.
        val other = lap { List(30) { sample(rearSlip = 1.25f, accel = 2.8f, gear = 4, kmh = 110f) } }
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to other, 5 to other))
        assertEquals(Level.INSUFFICIENT, r.level, r.text())
    }

    @Test
    fun oneMissedExitIsNotATrend() {
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to fresh(), 5 to fresh(), 6 to worn(), 7 to fresh()))
        assertEquals(Level.STABLE, r.level, r.text())
    }

    @Test
    fun coldTyresSuspendTheComparison() {
        val cold = lap { List(30) { sample(rearSlip = 1.22f, accel = 3.0f, temp = 60f) } }
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to cold, 5 to cold))
        assertEquals(Level.INSUFFICIENT, r.level, r.text())
        assertTrue(r.text().contains("plus froids"), r.text())
    }

    @Test
    fun lessAccelerationWithoutSlipOrTcsIsNotATractionLoss() {
        // Moins d'accélération à glissement et TCS égaux (aspiration perdue, moteur, trajectoire) : pas les pneus.
        val slower = lap { List(30) { sample(accel = 3.0f) } }
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to slower, 5 to slower))
        assertEquals(Level.STABLE, r.level, r.text())
        assertTrue(r.text().contains("pas une perte de motricité"), r.text())
    }

    @Test
    fun theFirstLapOfTheStintAndANewStintStartFromScratch() {
        val m = TractionMonitor()
        // Le premier tour du relais (tour 2 ici) est écarté : 3 tours restants, insuffisant.
        assertEquals(Level.INSUFFICIENT, run(m, mapOf(2 to worn(), 3 to fresh(), 4 to fresh(), 5 to worn()), stintStart = 2).level)
        run(m, mapOf(6 to worn(), 7 to worn(), 8 to worn()), stintStart = 1)
        m.newStint()
        assertEquals(Level.INSUFFICIENT, run(m, mapOf(10 to worn()), stintStart = 9).level)
    }

    @Test
    fun withoutSurfaceDataTheQualityIsSaidToBeLower() {
        val freshA = lap { List(30) { sample(surfaces = null) } }
        val wornA = lap { List(30) { sample(rearSlip = 1.22f, accel = 3.0f, surfaces = null) } }
        val r = run(TractionMonitor(), mapOf(2 to freshA, 3 to freshA, 4 to wornA, 5 to wornA))
        assertEquals(Level.DEGRADATION_SUSPECTED, r.level)
        assertTrue(r.text().contains("Surface des roues inconnue"), r.text())
    }

    @Test
    fun wheelspinThatRaisesTheRevsIsStillCompared() {
        // Même rapport et même vitesse : le patinage fait monter le régime. Il ne doit pas sortir de la comparaison.
        val spinning = lap { List(30) { sample(rearSlip = 1.22f, accel = 3.0f, rpm = 7400f) } }
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to spinning, 5 to spinning))
        assertEquals(Level.DEGRADATION_SUSPECTED, r.level, r.text())
    }

    @Test
    fun stabilityControlCorrectionsAreExcluded() {
        val asm = lap { List(30) { sample(rearSlip = 1.30f, accel = 2.5f, asm = true) } }
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to asm, 5 to asm))
        assertEquals(Level.INSUFFICIENT, r.level, r.text())
        assertTrue(r.text().contains("ASM"), r.text())
    }

    @Test
    fun declaredRainSuspendsTheComparison() {
        val m = TractionMonitor()
        listOf(2 to fresh(), 3 to fresh(), 4 to worn(), 5 to worn()).forEach { (n, l) -> m.addLap(n, l) }
        val r = m.evaluate(1, emptySet(), raining = true)
        assertEquals(Level.INSUFFICIENT, r.level)
        assertTrue(r.text().contains("Pluie déclarée"), r.text())
    }

    @Test
    fun overheatingIsReportedAsAPossibleCauseNotHidden() {
        val hot = lap { List(30) { sample(rearSlip = 1.22f, accel = 3.0f, temp = 100f) } }
        val r = run(TractionMonitor(), mapOf(2 to fresh(), 3 to fresh(), 4 to hot, 5 to hot))
        assertEquals(Level.DEGRADATION_SUSPECTED, r.level)
        assertTrue(r.text().contains("20 °C plus chauds") && r.text().contains("surchauffe possible"), r.text())
    }

    @Test
    fun aLapProfileIsCheapToCompute() {
        // Coût mesuré sur le PC de développement (pas sur le Fold) : un tour de 90 s à 60 Hz, 12 virages.
        val m = TractionMonitor()
        val exits = (1..12).associateWith { c -> List(450) { sample(kmh = 60f + (it % 80), rearSlip = 1.0f + (it % 7) / 100f) } }
        repeat(5) { m.profile(it, exits) }
        val t0 = System.nanoTime()
        repeat(20) { m.profile(it, exits) }
        val ms = (System.nanoTime() - t0) / 20 / 1_000_000.0
        println("TractionMonitor.profile : %.2f ms par tour (5 400 paquets, PC)".format(ms))
        assertTrue(ms < 50.0, "$ms ms")
    }
}
