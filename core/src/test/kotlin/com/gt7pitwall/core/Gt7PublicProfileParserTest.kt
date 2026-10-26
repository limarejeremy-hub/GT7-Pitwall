package com.gt7pitwall.core

import com.gt7pitwall.core.profile.Gt7PublicProfileParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class Gt7PublicProfileParserTest {
    @Test fun parsesRatingsButNotMisleadingZeroCounters() {
        val t = "GT GridStats Levly Ptijey27 New Search Sync with Sony Driver Rating 100% A+ / S " +
            "Total Entries 0 Victories 0 Poles 0 Other Points 0 Daily Race History"
        val s = Gt7PublicProfileParser.parse(t, "Ptijey27")
        assertEquals("A+", s.dr)
        assertEquals("S", s.sr)
        assertNull(s.races)
        assertNull(s.wins)
        assertNull(s.drPoints)
    }

    @Test fun acceptsRatingWithoutPercentage() {
        val s = Gt7PublicProfileParser.parse(
            "GT GridStats Driver_K_05 Driver Rating B / A Total Entries 0", "Driver_K_05"
        )
        assertEquals("B", s.dr)
        assertEquals("A", s.sr)
    }

    @Test fun rejectsOtherUserAndIncompletePages() {
        assertFailsWith<IllegalArgumentException> {
            Gt7PublicProfileParser.parse("OtherPilot Driver Rating A / S", "Ptijey27")
        }
        assertFailsWith<IllegalArgumentException> {
            Gt7PublicProfileParser.parse("Ptijey27 Driver Rating Loading...", "Ptijey27")
        }
        assertFailsWith<IllegalArgumentException> {
            Gt7PublicProfileParser.parse("Ptijey270 Driver Rating A / S", "Ptijey27")
        }
    }
}
