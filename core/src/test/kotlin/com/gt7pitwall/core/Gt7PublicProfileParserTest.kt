package com.gt7pitwall.core

import com.gt7pitwall.core.profile.Gt7PublicProfileParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class Gt7PublicProfileParserTest {
    private val richPage = """
        GT GridStats Home Drivers New Search # GT Ahmed GT_Ahmed_GT Share Copied!
        Sync with Sony Syncing...
        Driver Rating 13% A+ / S 62,838 pts Next Rank: S 87162 pts to go
        Total Entries 145 Victories 31 Poles 20 Other Points 0
        DR Points History 62,838 pts
        Sport Mode Telemetry
        Daily Races 121 Entries Wins 21 Top 5 82 Avg Start 12 Avg Finish 5
        World Series 24 Entries Wins 10 Top 5 22 Avg Start 2 Avg Finish 2
        Collector Level 70 License IB Distance 168,478 KM Seat Time 2,121 HR
        Garage 199 CARS Total Credits 295,873,970
        Daily Race History Track / Race Vehicle Date Rank Best Time
    """.trimIndent()

    @Test fun readsOnlyCorroboratedPublicCareerTotals() {
        val s = Gt7PublicProfileParser.parse(richPage, "GT_Ahmed_GT")
        assertEquals("A+", s.dr)
        assertEquals("S", s.sr)
        assertEquals(62838, s.drPoints)
        assertEquals(145, s.races)
        assertEquals(31, s.wins)
        assertEquals(20, s.poles)
        assertEquals(104, s.top5)
        assertEquals(199, s.garageCars)
        assertEquals(70, s.collectorLevel)
        assertEquals(168478.0, s.distanceKm)
        assertNull(s.cleanRaces)
        assertNull(s.fastestLaps)
    }

    @Test fun rejectsMisleadingZeroTotalsYetRetainsIndependentlyVerifiableNumbers() {
        val text = richPage.replace("Total Entries 145 Victories 31 Poles 20",
            "Total Entries 0 Victories 0 Poles 0")
        val s = Gt7PublicProfileParser.parse(text, "GT_Ahmed_GT")
        assertEquals(62838, s.drPoints)
        assertNull(s.races)
        assertNull(s.wins)
        assertNull(s.poles)
        assertNull(s.top5)
        assertEquals(199, s.garageCars)
    }

    @Test fun noRaceSectionMeansUnknownNotZero() {
        val text = "GT GridStats Ptijey27 Sync with Sony Driver Rating C / S " +
            "Total Entries 0 Victories 0 Poles 0 Other Points 0 Daily Race History"
        val s = Gt7PublicProfileParser.parse(text, "Ptijey27")
        assertEquals("C", s.dr)
        assertEquals("S", s.sr)
        assertNull(s.drPoints)
        assertNull(s.races)
        assertNull(s.wins)
        assertNull(s.top5)
    }

    @Test fun pageWithoutPercentageStillAccepted() {
        val s = Gt7PublicProfileParser.parse(
            "GT GridStats Driver_K_05 Driver Rating B / A Total Entries 0 Daily Race History",
            "Driver_K_05"
        )
        assertEquals("B", s.dr)
        assertEquals("A", s.sr)
    }

    @Test fun mismatchedHeadlinesCannotCorruptCareer() {
        val text = richPage.replace("Victories 31", "Victories 30")
        val s = Gt7PublicProfileParser.parse(text, "GT_Ahmed_GT")
        assertNull(s.races)
        assertNull(s.wins)
        assertNull(s.top5)
    }

    @Test fun refusesDifferentDriverAndLoadingPages() {
        assertFailsWith<IllegalArgumentException> {
            Gt7PublicProfileParser.parse(
                "OtherPilot Driver Rating A / S Total Entries 88", "Ptijey27")
        }
        assertFailsWith<IllegalArgumentException> {
            Gt7PublicProfileParser.parse(
                "GT GridStats Ptijey27 Loading Driver Rating...", "Ptijey27")
        }
        assertFailsWith<IllegalArgumentException> {
            Gt7PublicProfileParser.parse(
                "GT GridStats Ptijey270 Driver Rating A / S", "Ptijey27")
        }
    }
}
