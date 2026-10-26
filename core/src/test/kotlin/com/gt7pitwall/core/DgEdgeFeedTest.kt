package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.DgEdgeFeed
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real-week examples of publicly visible text; no network in unit tests. */
class DgEdgeFeedTest {
    private val week = LocalDate.of(2026, 10, 26)
    private val urls = mapOf(
        "A" to "https://www.dg-edge.com/events/dailies/week-44-2026-race-a-sugo/681",
        "B" to "https://www.dg-edge.com/events/dailies/week-44-2026-race-b-willow/682",
        "C" to "https://www.dg-edge.com/events/dailies/week-44-2026-race-c-sugo/683",
    )
    private fun html(letter: String, summary: String, numbers: String): String = """
        <html><head><meta content="$summary" name="description" /></head>
        <body><nav>Menu</nav><h1>Daily Race $letter</h1>
        <div>Daily Race · Week 44/2026</div><section>26 October 2026 - 2 November 2026
        $numbers 100% </section> <h2>Event stats</h2></body></html>
    """.trimIndent()

    private val a = html("A",
        "GT7 Daily Race A for Week 44 2026 runs Nissan Fairlady Z (Z34) '08 over 5 laps at SUGO International Racing Course on Sports Hard tyres. Race settings: in-game time 15:15, BoP, grid start, no tuning, slipstream: simulation. Live leaderboard.",
        "SH 5 x1 x1")
    private val b = html("B",
        "GT7 Daily Race B for Week 44 2026 runs Gr.4 over 7 laps at Willow Springs International Raceway - Big Willow on Racing Hard tyres. Race settings: in-game time 17:40, BoP, medium damage, rolling start, tuning allowed. Live leaderboard.",
        "RH 7 x1 x1")
    private val c = html("C",
        "GT7 Daily Race C for Week 44 2026 runs Gr.3 over 14 laps at SUGO International Racing Course on Racing Medium and Racing Soft tyres. Race settings: in-game time 16:34, BoP, medium damage, rolling start, tuning allowed, Required: RM, RS. Live leaderboard.",
        "RM RS 14 x2 x4")

    @Test fun thePublicIndexProvidesThisWeeksThreeTrustedPaths() {
        val index = """<a href="/events/dailies/week-43-2026-race-a-old/670">old</a>
            <a href="${urls.getValue("A").removePrefix("https://www.dg-edge.com")}">A</a>
            <a href="${urls.getValue("B").removePrefix("https://www.dg-edge.com")}">B</a>
            <a href="${urls.getValue("C").removePrefix("https://www.dg-edge.com")}">C</a>"""
        val links = DgEdgeFeed.links(index, week)
        assertEquals(3, links.size)
        assertEquals(urls.getValue("C"), links.getValue(Series.DAILY_C))
        assertTrue(DgEdgeFeed.links(index, LocalDate.of(2026, 11, 2)).isEmpty())
        assertEquals(2, DgEdgeFeed.links(index + """<a href="/events/dailies/week-44-2026-race-a-other/999">other</a>""", week).size)
    }

    @Test fun week44ExamplesGiveCompoundsMultipliersAndNoInventedPitStop() {
        val pages = mapOf(urls.getValue("A") to a, urls.getValue("B") to b, urls.getValue("C") to c)
        val post = assertNotNull(DgEdgeFeed.parseWeek(pages, week, 1L))
        assertEquals(3, post.races.size)
        val ra = post.races.getValue(Series.DAILY_A)
        assertEquals(5, ra.laps)
        assertEquals(setOf(TyreCompound.SH), ra.allowedTyres)
        assertEquals("Nissan Fairlady Z (Z34) '08", ra.cars)
        val rb = post.races.getValue(Series.DAILY_B)
        assertEquals(7, rb.laps)
        assertEquals(setOf(TyreCompound.RH), rb.allowedTyres)
        val rc = post.races.getValue(Series.DAILY_C)
        assertEquals(14, rc.laps)
        assertEquals(2, rc.fuelMultiplier)
        assertEquals(4, rc.tyreMultiplier)
        assertEquals(setOf(TyreCompound.RM, TyreCompound.RS), rc.allowedTyres)
        assertEquals(setOf(TyreCompound.RM, TyreCompound.RS), rc.requiredTyres)
        assertNull(rc.mandatoryStops)
        val state = ScheduleBuilder.build(listOf(post), Gt7Database.EMPTY, week, 1, null)
        assertEquals(3, state.editions.size)
        assertEquals(FieldStatus.PROBABLE, state.editions.getValue(Series.DAILY_C).laps.status)
    }

    @Test fun malformedPagesCannotOverwriteKnownRegulations() {
        assertNull(DgEdgeFeed.parseDetail(c.replace("Week 44 2026", "Week 43 2026"), urls.getValue("C"), 1))
        assertNull(DgEdgeFeed.parseDetail(c, "https://attacker.invalid/events/dailies/week-44-2026-race-c-sugo/683", 1))
        assertNull(DgEdgeFeed.parseWeek(mapOf(urls.getValue("A") to a, urls.getValue("C") to c), week, 1L))
        assertNull(DgEdgeFeed.parseWeek(mapOf(urls.getValue("A") to a, urls.getValue("B") to b,
            urls.getValue("C") to c.replace("14 x2 x4", "unknown")), week, 1L))
    }
}
