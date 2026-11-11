package com.gt7pitwall.core

import com.gt7pitwall.core.display.RaceCommandBoard
import com.gt7pitwall.core.display.RaceCommandBoard.AlertKind
import com.gt7pitwall.core.display.RaceCommandBoard.FuelRisk
import com.gt7pitwall.core.display.RaceCommandBoard.Link
import com.gt7pitwall.core.display.RaceCommandBoard.PitState
import com.gt7pitwall.core.display.RaceCommandBoard.Tone
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.FuelAdvice
import com.gt7pitwall.core.strategy.FuelReport
import com.gt7pitwall.core.strategy.PlannedStop
import com.gt7pitwall.core.strategy.RacePlan
import com.gt7pitwall.core.strategy.StopCandidate
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreStint
import com.gt7pitwall.core.telemetry.ConnectionStatus
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import com.gt7pitwall.core.tyres.GripLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Écran RACE (refonte V506) : états, alertes et valeurs « indisponibles », sans rien inventer. Données synthétiques. */
class RaceCommandBoardTest {
    private val liveLink = ConnectionStatus.Receiving("192.0.2.1", 60)

    private fun packet(lap: Int = 7, total: Int = 11, fuel: Float = 46f, temps: FloatArray = floatArrayOf(78f, 81f, 97f, 99f)): TelemetryPacket =
        TelemetryParser.parseDecrypted(TestPackets.plain(lap = lap, totalLaps = total, fuel = fuel, lastLapMs = 119_882, bestLapMs = 119_523, tyreTemps = temps))!!

    private fun report(advice: FuelAdvice, lapsOfFuel: Float? = 5.1f, toGo: Float? = 5f) =
        FuelReport(fuelLevel = 46f, fuelCapacity = 100f, perLap = 8.9f, samples = 3, lapsOfFuel = lapsOfFuel, raceLapsRemaining = toGo, fuelToFinish = null, advice = advice)

    private fun plan(vararg stops: PlannedStop, required: Set<TyreCompound> = emptySet()) =
        RacePlan(11, DataSource.GAME, stops.toList(), 8.9f, DataSource.MEASURED, null, required)

    private fun board(
        connection: ConnectionStatus = liveLink,
        packet: TelemetryPacket? = packet(),
        fuel: FuelReport? = report(FuelAdvice.Comfortable(1.2f)),
        plan: RacePlan? = plan(),
        stint: TyreStint? = TyreStint(1, 6, TyreCompound.RM, certain = true, fromStart = true),
        grip: GripLevel = GripLevel.STABLE,
        finished: Boolean = false,
        pending: StopCandidate? = null,
        missing: Set<TyreCompound> = emptySet(),
        position: Int? = 13,
    ) = RaceCommandBoard.of(connection, packet, position, true, 16, finished, fuel, plan, stint, grip, 0, pending, missing, rulesIncomplete = false)

    @Test fun aCalmRaceShowsMeasuredValuesAndNoAlert() {
        val b = board()
        assertEquals(Link.LIVE, b.link)
        assertTrue(b.live)
        assertEquals(13, b.position); assertEquals(7, b.lap); assertEquals(11, b.totalLaps)
        assertEquals(46f, b.fuel.levelPct!!, 0.1f)
        assertEquals(8.9f, b.fuel.perLapPct!!, 0.01f)
        assertEquals(FuelRisk.SAFE, b.fuel.risk)
        assertEquals(listOf(78f, 81f, 97f, 99f), b.tyres.cells.map { it.tempC })
        assertEquals(RaceCommandBoard.CompoundStatus.DECLARED, b.tyres.compoundStatus)
        assertEquals(PitState.NONE, b.pit.state)
        assertTrue(b.alerts.isEmpty(), b.alerts.toString())
        assertFalse(RaceCommandBoard.WEAR_AVAILABLE, "GT7 ne transmet pas l'usure : jamais de pourcentage")
    }

    @Test fun unknownDataStaysUnavailableNeverZero() {
        val b = RaceCommandBoard.of(ConnectionStatus.Stopped, null, null, false, null, false, null, null, null, GripLevel.INSUFFICIENT, 0, null, emptySet(), false)
        assertEquals(Link.STOPPED, b.link)
        assertFalse(b.live)
        assertNull(b.position); assertNull(b.lap); assertNull(b.totalLaps); assertNull(b.lastLapMs)
        assertNull(b.fuel.levelPct); assertNull(b.fuel.lapsOfFuel); assertNull(b.fuel.perLapPct)
        assertEquals(FuelRisk.UNAVAILABLE, b.fuel.risk)
        assertTrue(b.tyres.cells.all { it.tempC == null })
        assertEquals(RaceCommandBoard.CompoundStatus.UNKNOWN, b.tyres.compoundStatus)
        assertNull(b.tyres.ageLaps)
        assertEquals(PitState.UNKNOWN, b.pit.state)
        assertTrue(b.alerts.isEmpty(), "application à l'arrêt : aucune alerte")
    }

    @Test fun fuelBecomesCriticalUnderOneLapOrAtTheLastChanceLap() {
        val need = FuelAdvice.PitRequired(fuelToAdd = 30f, latestPitLap = 9, stopsNeeded = 1, savingNeeded = 0.2f, targetPerLap = 7f)
        val soon = board(fuel = report(need, lapsOfFuel = 2.4f))
        assertEquals(FuelRisk.PIT_NEEDED, soon.fuel.risk)
        assertEquals(AlertKind.FUEL_PIT_NEEDED, soon.topAlert?.kind)
        assertTrue("fin du tour 9" in soon.topAlert!!.detail)
        val dry = board(fuel = report(need, lapsOfFuel = 0.8f))
        assertEquals(FuelRisk.CRITICAL, dry.fuel.risk)
        assertEquals(AlertKind.FUEL_CRITICAL, dry.topAlert?.kind)
        assertEquals(Tone.CRITICAL, dry.topAlert?.tone)
        val lastChance = board(packet = packet(lap = 9), fuel = report(need, lapsOfFuel = 1.6f))
        assertEquals(FuelRisk.CRITICAL, lastChance.fuel.risk, "dernier tour possible pour rentrer")
        assertEquals(AlertKind.FUEL_TIGHT, board(fuel = report(FuelAdvice.Tight(0.1f, 8.5f))).topAlert?.kind)
    }

    @Test fun thePitWindowIsSignalledOneLapAheadThenThisLap() {
        val stop = PlannedStop(8, null, forFuel = false, mandatory = false, tyreToFit = TyreCompound.RH, changeTyres = true)
        val planned = board(packet = packet(lap = 5), plan = plan(stop, required = setOf(TyreCompound.RM, TyreCompound.RH)))
        assertEquals(PitState.PLANNED, planned.pit.state); assertEquals(3, planned.pit.lapsUntil)
        assertTrue(planned.alerts.none { it.kind == AlertKind.BOX_NEXT_LAP || it.kind == AlertKind.BOX_THIS_LAP })
        val next = board(packet = packet(lap = 7), plan = plan(stop))
        assertEquals(PitState.NEXT_LAP, next.pit.state)
        assertEquals(AlertKind.BOX_NEXT_LAP, next.topAlert?.kind)
        val now = board(packet = packet(lap = 8), plan = plan(stop))
        assertEquals(PitState.THIS_LAP, now.pit.state)
        assertEquals(AlertKind.BOX_THIS_LAP, now.topAlert?.kind)
        assertEquals("Monter les course durs", now.topAlert?.detail)
        assertEquals("Ravitailler · Monter les course durs", RaceCommandBoard.pitAction(now.pit.copy(refuel = true)))
    }

    @Test fun lostTelemetryIsExplicitAndSilencesRaceAlerts() {
        val need = FuelAdvice.PitRequired(30f, 7, 1, 0.2f, 7f)
        val b = board(connection = ConnectionStatus.NoData("192.0.2.1", 12_400), fuel = report(need, lapsOfFuel = 0.5f))
        assertEquals(Link.SILENT, b.link)
        assertEquals(12, b.silenceS)
        assertFalse(b.live)
        assertEquals(listOf(AlertKind.TELEMETRY_LOST), b.alerts.map { it.kind }, "valeurs figées : pas d'alerte carburant sur une donnée périmée")
        assertTrue("12 s" in b.topAlert!!.detail)
        assertEquals(FuelRisk.CRITICAL, b.fuel.risk, "la dernière valeur connue reste affichée, dite figée par l'écran")
        assertEquals(AlertKind.TELEMETRY_ERROR, board(connection = ConnectionStatus.Error("Port occupé")).topAlert?.kind)
    }

    @Test fun aContestedStopAsksForConfirmationAndTyreHeatIsMeasured() {
        val b = board(pending = StopCandidate(7, locationKnown = true, contested = true), packet = packet(temps = floatArrayOf(80f, 82f, 106f, 111f)))
        assertEquals(PitState.CONFIRM, b.pit.state)
        assertEquals(AlertKind.CONFIRM_STOP, b.topAlert?.kind)
        val hot = b.alerts.single { it.kind == AlertKind.TYRES_HOT }
        assertEquals("ARG 106 °C, ARD 111 °C (température mesurée).", hot.detail)
        assertTrue(b.tyres.overheating)
    }

    @Test fun criticalAlertsComeFirstAndObligationsOnlyWhenKnown() {
        val stop = PlannedStop(7, null, forFuel = true, mandatory = false)
        val b = board(plan = plan(stop), fuel = report(FuelAdvice.Tight(0.1f, 8f)), grip = GripLevel.PERSISTENT)
        assertEquals(listOf(AlertKind.BOX_THIS_LAP, AlertKind.GRIP_LOSS, AlertKind.FUEL_TIGHT), b.alerts.map { it.kind })
        val missing = board(missing = setOf(TyreCompound.RH))
        assertEquals(AlertKind.TYRES_MISSING, missing.topAlert?.kind)
        assertTrue(board().pit.missingTyres.isEmpty() && board().alerts.none { it.kind == AlertKind.TYRES_MISSING }, "aucune obligation inventée")
    }

    @Test fun aFinishedRaceRaisesNoAlertAndUndeclaredTyresAreSaidSo() {
        val need = FuelAdvice.PitRequired(30f, 7, 1, 0.2f, 7f)
        val b = board(finished = true, fuel = report(need, lapsOfFuel = 0.3f), stint = TyreStint(1, 6, TyreCompound.RM, certain = false, fromStart = true))
        assertEquals(PitState.FINISHED, b.pit.state)
        assertEquals(FuelRisk.FINISHED, b.fuel.risk)
        assertTrue(b.alerts.isEmpty())
        assertEquals(RaceCommandBoard.CompoundStatus.UNCERTAIN, b.tyres.compoundStatus)
    }
}
