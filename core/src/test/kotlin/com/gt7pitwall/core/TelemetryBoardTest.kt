package com.gt7pitwall.core

import com.gt7pitwall.core.display.TelemetryBoard
import com.gt7pitwall.core.display.TelemetryBoard.SectorColor
import com.gt7pitwall.core.display.TelemetryBoard.TempBand
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreChange
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Écran 2 « Télémétrie » : données affichées, calculées sur PC. Aucune course PS5. */
class TelemetryBoardTest {

    private fun lap(n: Int, ms: Int?, vararg sectors: Int) = LapRecord(n, ms, fuelUsed = 7f, refuelled = false, sectorsMs = sectors.toList().ifEmpty { null })

    @Test fun sectorColoursFollowTheMockup() {
        val laps = listOf(
            lap(3, 120_427, 41_902, 43_115, 35_410),
            lap(4, 119_025, 41_650, 41_774, 35_601),
            lap(5, 118_886, 41_488, 42_010, 35_388),
            lap(6, 119_523, 41_530, 41_902, 35_091),
        )
        val rows = TelemetryBoard.lapRows(laps, bestLapNumber = 5)
        assertEquals(listOf(SectorColor.NONE, SectorColor.NONE, SectorColor.NONE), rows[0].sectors.map { it.color }, "premier tour : pas de tour précédent")
        assertEquals(listOf(SectorColor.IMPROVED, SectorColor.BEST, SectorColor.SLOWER), rows[1].sectors.map { it.color })
        assertEquals(listOf(SectorColor.BEST, SectorColor.SLOWER, SectorColor.IMPROVED), rows[2].sectors.map { it.color })
        assertEquals(listOf(SectorColor.SLOWER, SectorColor.IMPROVED, SectorColor.BEST), rows[3].sectors.map { it.color })
        assertTrue(rows[2].bestLap)
        assertEquals(TelemetryBoard.MAX_ROWS, TelemetryBoard.lapRows((1..9).map { lap(it, 120_000 + it, 40_000 + it, 40_000, 40_000) }, null).size)
    }

    @Test fun aLapWithoutSectorsShowsNoColour() {
        val rows = TelemetryBoard.lapRows(listOf(lap(1, 120_000, 40_000, 40_000, 40_000), lap(2, 121_000)), null)
        assertTrue(rows[1].sectors.all { it.color == SectorColor.NONE && it.ms == null })
    }

    @Test fun temperatureBandsAreGeneralLandmarks() {
        assertEquals(TempBand.UNKNOWN, TelemetryBoard.band(null))
        assertEquals(TempBand.COLD, TelemetryBoard.band(45f))
        assertEquals(TempBand.IDEAL, TelemetryBoard.band(85f))
        assertEquals(TempBand.WARM, TelemetryBoard.band(98f))
        assertEquals(TempBand.HOT, TelemetryBoard.band(110f))
    }

    @Test fun paceTrendNeedsCleanLapsInTheStint() {
        val laps = listOf(lap(7, 120_000), lap(8, 120_300), lap(9, 120_600), lap(10, 120_900))
        assertEquals(0.3, TelemetryBoard.paceTrend(laps, stintStartLap = 6, excluded = setOf(7))!!, 1e-9, "tour de sortie exclu")
        assertNull(TelemetryBoard.paceTrend(laps.take(2), 6, emptySet()), "pas assez de tours")
    }

    @Test fun stintsFollowTheRecordedChange() {
        val s = TelemetryBoard.stints(11, 8, TyreCompound.RM, listOf(TyreChange(6, TyreCompound.RH)), setOf(6))
        assertEquals(11, s.size)
        assertEquals(TyreCompound.RM, s[5].compound, "tour 6 : encore les mediums, arrêt en fin de tour")
        assertTrue(s[5].pit)
        assertEquals(TyreCompound.RH, s[6].compound)
        assertTrue(s[7].current && s[8].future && !s[6].future)
        assertTrue(TelemetryBoard.stints(null, 3, TyreCompound.RM, emptyList(), emptySet()).isEmpty(), "nombre de tours inconnu : pas de frise")
    }

    @Test fun theBoardReadsALiveSimulatedRace() {
        val rules = RaceRules(totalLaps = 11, fuelMultiplier = 3, mandatoryStops = 0, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM), startTyre = TyreCompound.RM)
        val sim = RaceSim(RaceController(storage = FakeStorage(rules = rules)), totalLaps = 11, perLap = 9f).also { it.start(); it.driveLaps(4) }
        val b = TelemetryBoard.of(sim.controller)
        assertEquals(11, b.totalLaps)
        assertNotNull(b.lap)
        assertTrue(b.laps.isNotEmpty())
        assertEquals(4, b.tyres.size)
        assertEquals(TyreCompound.RM, b.compound)
        assertNotNull(b.fuelPct)
        assertNotNull(b.fuelPerLapPct)
        assertEquals(11, b.stints.size)
        assertTrue(b.stints.take((b.lap ?: 1) - 1).all { it.compound == TyreCompound.RM })
    }

    @Test fun timesAreFormattedLikeTheMockup() {
        assertEquals("1:59.523", TelemetryBoard.lapTime(119_523))
        assertEquals("41.471", TelemetryBoard.lapTime(41_471))
        assertEquals("—", TelemetryBoard.lapTime(null))
    }
}
