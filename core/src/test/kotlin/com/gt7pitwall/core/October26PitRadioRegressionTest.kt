package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.PitLedger
import com.gt7pitwall.core.strategy.PlannedStop
import com.gt7pitwall.core.strategy.RacePlan
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Régressions constatées sur la course personnalisée du 26/10 : Porsche 963, Mans, 7 tours. */
class October26PitRadioRegressionTest {
    @Test
    fun pitCallSaysExactlyWhichLapIsTheInLap() {
        val e = RaceEngineer()
        val plan = RacePlan(
            totalLaps = 7,
            lapsSource = DataSource.GAME,
            stops = listOf(PlannedStop(4, 44, forFuel = true, mandatory = true)),
            perLap = 19.6f,
            fuelSource = DataSource.MEASURED,
            pitLaneDeltaS = null,
            requiredTyres = emptySet(),
        )
        e.onPlan(plan, currentLap = 2)
        val t3 = assertNotNull(e.boxCall(3, null))
        assertTrue("tour 4" in t3, t3)
        assertTrue("Reste en piste" in t3, t3)
        assertTrue("ne rentre pas" in t3, t3)
        val t4 = assertNotNull(e.boxCall(4, null))
        assertTrue(t4.startsWith("Box, box !"), t4)
        assertTrue("à la fin de ce tour (tour 4)" in t4, t4)
        assertFalse("ne rentre pas" in t4, t4)
    }

    @Test
    fun manualDeclarationAndTelemetricRefuelMergeOnlyWithSameVisitIdentity() {
        val pit = PitLedger()
        val declared = pit.declare(4) // pilote au tour 4, arrêté après la fin du tour 3
        pit.awaitingTyreChangeAnswer = true
        val measured = pit.recordRefuel(3, 55f, declaredInThisVisitNumber = declared.number)
        assertEquals(1, pit.stopCount)
        assertEquals(declared.number, measured.number)
        assertEquals(3, measured.lap)
        assertEquals(StopEvidence.REFUEL, measured.evidence)
        assertEquals(55f, measured.fuelAdded)
        assertEquals(TyreService.UNKNOWN, measured.tyres)
        assertTrue(pit.awaitingTyreChangeAnswer)
        pit.setThermal(3, "SUSPECTED")
        assertEquals("SUSPECTED", pit.stopList.single().thermal)
        // Même si deux visites sont à un tour d'écart, deux ravitaillements réels restent deux arrêts.
        pit.recordRefuel(4, 25f)
        assertEquals(2, pit.stopCount)
    }

    @Test
    fun withoutAVisitIdentityIndependentStopsMustNeverMerge() {
        val pit = PitLedger()
        pit.declare(4)
        pit.recordRefuel(3, 55f)
        assertEquals(2, pit.stopCount)
    }

    @Test
    fun aOneWordSttFragmentCannotRewriteThePositionToP1() {
        assertNull(DriverCommandParser.parse("une"))
        assertNull(DriverCommandParser.parse("un"))
        assertEquals(DriverCommand.ReportPosition(1), DriverCommandParser.parse("je suis premier"))
        assertEquals(DriverCommand.ReportPosition(12), DriverCommandParser.parse("je suis douzieme"))
    }

    @Test
    fun declarationDuringAPhysicalStopIsNotCountedTwiceAtPitExit() {
        val c = RaceController(storage = FakeStorage())
        val sim = RaceSim(c, totalLaps = 7, perLap = 10f)
        sim.start()
        sim.driveLaps(2)
        var wasInVisit = false
        sim.pitStop(fuel = 30f, stopSeconds = 12f, onStand = { tick ->
            if (tick == 300) {
                wasInVisit = c.tracker.pitVisitOpen
                sim.say("j'ai fait mon arret")
            }
        })
        assertTrue(wasInVisit, "déclaration pendant la même visite au stand")
        assertEquals(1, c.ledger.stopCount, c.ledger.stopList.toString())
        val single = c.ledger.stopList.single()
        assertEquals(StopEvidence.REFUEL, single.evidence)
        assertNotNull(single.fuelAdded)
        assertFalse(sim.texts.any { it.startsWith("Arrêt 2 enregistré") }, sim.texts.toString())
    }
}
