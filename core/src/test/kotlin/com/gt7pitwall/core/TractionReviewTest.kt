package com.gt7pitwall.core

import com.gt7pitwall.core.analysis.LapSample
import com.gt7pitwall.core.driver.CornerIssue
import com.gt7pitwall.core.driver.DriverCoach
import com.gt7pitwall.core.driver.DriverProfile
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.telemetry.TyreSurfaces
import com.gt7pitwall.core.telemetry.Wheels
import com.gt7pitwall.core.tyres.TractionMonitor
import com.gt7pitwall.core.tyres.TractionMonitor.Level
import com.gt7pitwall.core.tyres.TyreOrDriving
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Revue technique de la PR #37 : corrections P0-A, P0-B, P0-C et P1-D, plus le couple délivré (0x154, expérimental). */
class TractionReviewTest {
    private val tarmac = TyreSurfaces.parse("TTTT".toByteArray())

    private fun sample(rearSlip: Float = 1.02f, accel: Float = 4.0f, temp: Float = 80f, surfaces: TyreSurfaces? = tarmac, torque: Float? = null, tcs: Boolean = false) =
        LapSample(
            tick = 0, x = 0f, z = 0f, speedMps = 80f / 3.6f, throttle = 128, brake = 0, yawRate = 0.05f, wheelAngle = null,
            frontSlip = 1.0f, rearSlip = rearSlip, tcs = tcs, offTrack = false, surfaces = surfaces,
            slips = Wheels(1.0f, 1.0f, rearSlip, rearSlip), gear = 3, rpm = 6000f, longAccel = accel,
            tyreTemps = Wheels(temp, temp, temp, temp), engineTorque = torque,
        )

    private fun lap(each: () -> LapSample) = (1..4).associateWith { List(30) { each() } }
    private val fresh = lap { sample() }
    private val worn = lap { sample(rearSlip = 1.22f, accel = 3.0f) }

    private fun repeated(m: TractionMonitor) {
        mapOf(2 to fresh, 3 to fresh, 4 to worn, 5 to worn, 6 to worn).forEach { (n, l) -> m.addLap(n, l); m.evaluate(1, emptySet()) }
        assertEquals(Level.DEGRADATION_REPEATED, m.last.level, m.last.text())
        assertTrue(m.last.strategyEligible)
    }

    @Test
    fun p0aASuspendedComparisonNeverStaysConfirmed() {
        // Pluie déclarée après une perte répétée.
        val rain = TractionMonitor().also(::repeated)
        rain.addLap(7, worn)
        val r = rain.evaluate(1, emptySet(), raining = true)
        assertEquals(Level.INSUFFICIENT, r.level)
        assertEquals(Level.DEGRADATION_REPEATED, r.previous)
        assertFalse(r.strategyEligible, "la stratégie ne doit plus compter « pneus dégradés »")
        assertTrue(r.text().contains("plus confirmé tant que la comparaison est suspendue"), r.text())
        // Pneus redevenus froids (neutralisation).
        val cold = TractionMonitor().also(::repeated)
        cold.addLap(7, lap { sample(rearSlip = 1.22f, accel = 3.0f, temp = 60f) })
        cold.addLap(8, lap { sample(rearSlip = 1.22f, accel = 3.0f, temp = 60f) })
        assertFalse(cold.evaluate(1, emptySet()).strategyEligible)
        // Plus de sorties comparables (autre trajectoire, données manquantes).
        val gone = TractionMonitor().also(::repeated)
        gone.addLap(7, emptyMap())
        gone.addLap(8, emptyMap())
        val g = gone.evaluate(1, emptySet())
        assertEquals(Level.INSUFFICIENT, g.level)
        assertFalse(g.strategyEligible)
    }

    @Test
    fun p0cWithoutWheelSurfacesALossIsNeverConfirmed() {
        // Format A : impossible d'exclure un vibreur. Même cinq tours de patinage : jamais « répétée ».
        val m = TractionMonitor()
        val freshA = lap { sample(surfaces = null) }
        val wornA = lap { sample(rearSlip = 1.30f, accel = 2.8f, surfaces = null) }
        mapOf(2 to freshA, 3 to freshA, 4 to wornA, 5 to wornA, 6 to wornA, 7 to wornA).forEach { (n, l) -> m.addLap(n, l); m.evaluate(1, emptySet()) }
        assertEquals(Level.DEGRADATION_SUSPECTED, m.last.level)
        assertFalse(m.last.strategyEligible)
        assertTrue(m.last.text().contains("constat jamais confirmé"), m.last.text())
    }

    @Test
    fun lessDeliveredTorqueWithoutTcsIsNotBlamedOnTheTyres() {
        // 0x154 plus bas à pédale égale, sans TCS ni patinage : moteur ou commande. Jamais « les pneus ».
        val m = TractionMonitor()
        val ref = lap { sample(torque = 0.80f) }
        val lowTorque = lap { sample(rearSlip = 1.02f, accel = 3.0f, torque = 0.60f) }
        mapOf(2 to ref, 3 to ref, 4 to lowTorque, 5 to lowTorque).forEach { (n, l) -> m.addLap(n, l); m.evaluate(1, emptySet()) }
        assertEquals(Level.STABLE, m.last.level, m.last.text())
        assertTrue(m.last.torqueExplained > 0)
        assertTrue(m.last.text().contains("couple délivré (0x154, expérimental) baisse sans TCS ni patinage"), m.last.text())
        // Couple qui baisse **avec** le TCS : coupure électronique parce que ça patine, la perte de motricité reste vue.
        val cut = TractionMonitor()
        val tcsCut = lap { sample(rearSlip = 1.03f, accel = 3.0f, torque = 0.55f, tcs = true) }
        mapOf(2 to ref, 3 to ref, 4 to tcsCut, 5 to tcsCut).forEach { (n, l) -> cut.addLap(n, l); cut.evaluate(1, emptySet()) }
        assertEquals(Level.DEGRADATION_SUSPECTED, cut.last.level, cut.last.text())
    }

    @Test
    fun p1dASpreadLossIsOnlyProbablyTheTyresWhenTractionAgrees() {
        val spread = TyreOrDriving.Report(TyreOrDriving.Verdict.TYRES, listOf("8 virages sur 8 plus lents"), "moyenne", 8)
        val stable = TractionMonitor.Report(Level.STABLE, emptyList(), comparedCorners = 4, surfaceKnown = true)
        val crossed = TyreOrDriving.crossCheck(spread, stable)
        assertEquals(TyreOrDriving.Verdict.MIXED, crossed.verdict)
        assertTrue(crossed.text.contains("pneus non démontrés"), crossed.text)
        val losing = TractionMonitor.Report(Level.DEGRADATION_SUSPECTED, emptyList(), comparedCorners = 4, flaggedCorners = listOf(1, 2))
        assertTrue(TyreOrDriving.crossCheck(spread, losing).text.contains("pneus probables"))
        // Sans comparaison de motricité : « pneus possibles », jamais « probables ».
        val alone = TyreOrDriving.crossCheck(spread, TractionMonitor.Report(Level.INSUFFICIENT, emptyList()))
        assertFalse(alone.text.contains("probables"), alone.text)
    }

    /** P0-B : le coach et la motricité voient le même tour ; pas de « dose l'accélérateur » contre une perte de motricité mesurée. */
    @Test
    fun p0bTheCoachNeverBlamesTheThrottleOnTheLapTheTractionLossIsSeen() {
        val key = DriverProfile.key(3607, "462", "RACE")
        fun run(withTraction: Boolean): Pair<DriverCoach, List<String>> {
            val coach = DriverCoach()
            coach.start("s-${System.nanoTime()}")
            if (withTraction) coach.tractionContext = { DriverCoach.TractionContext(1, emptySet(), false) }
            val said = mutableListOf<String>()
            val sim = CornerSim().also { s -> s.sink = { said += coach.onPacket(it, inPit = false).map { m -> m.text } } }
            sim.start()
            sim.lap()
            fun lap(st: CornerSim.Style) {
                sim.lap(listOf(st, st))
                said += coach.onLap(
                    LapRecord(sim.lap - 1, sim.lastLapMs, 3f, false),
                    DriverCoach.LapContext(key, race = false, totalLaps = 0, positionChangedRecently = false, pitLap = false),
                ).map { it.text }
                sim.settle()
            }
            repeat(3) { lap(CornerSim.Style()) }
            repeat(2) { lap(CornerSim.Style(exitSpin = 0.2f, exitAccel = 0.75f)) }
            return coach to said
        }
        // Sans le moteur de motricité, le coach reproche le patinage : c'est le cas que la revue signale.
        val (blind, blindSaid) = run(withTraction = false)
        assertEquals(CornerIssue.WHEELSPIN, blind.advices.lastOrNull()?.issue, blindSaid.toString())
        // Avec : la perte est évaluée avant le conseil du même tour, et le conseil contradictoire n'est pas donné.
        val (coach, said) = run(withTraction = true)
        assertTrue(coach.traction.last.level >= Level.DEGRADATION_SUSPECTED, coach.traction.last.text())
        assertNull(coach.advices.firstOrNull { it.issue == CornerIssue.WHEELSPIN }, said.toString())
        assertTrue(said.none { "dose l'accélérateur" in it }, said.toString())
    }

    /** Revue complémentaire PR #37, P0-2 : 0x154 n'est qu'une observation ; le verdict ne change pas sans lui. */
    @Test
    fun verdictsAreIdenticalWithOrWithoutTheExperimentalTorque() {
        fun run(torque: Float?, worn: LapSample): TractionMonitor.Report {
            val m = TractionMonitor()
            val ref = lap { sample(torque = torque?.let { 0.8f }) }
            val w = lap { worn.copy(engineTorque = torque) }
            mapOf(2 to ref, 3 to ref, 4 to w, 5 to w, 6 to w).forEach { (n, l) -> m.addLap(n, l); m.evaluate(1, emptySet()) }
            return m.last
        }
        for (worn in listOf(sample(rearSlip = 1.22f, accel = 3.0f), sample(rearSlip = 1.03f, accel = 3.0f, tcs = true), sample(accel = 3.0f), sample())) {
            val with = run(0.55f, worn)
            val without = run(null, worn)
            assertEquals(without.level, with.level, worn.toString())
            assertEquals(without.flaggedCorners, with.flaggedCorners)
            assertEquals(without.strategyEligible, with.strategyEligible)
        }
    }
}
