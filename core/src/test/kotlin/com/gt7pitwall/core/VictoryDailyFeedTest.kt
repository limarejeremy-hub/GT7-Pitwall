package com.gt7pitwall.core

import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.VictoryDailyFeed
import com.gt7pitwall.core.strategy.TyreCompound
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract of Victory's visible JSON-LD + HTML cards (26/10/2026), no network in CI. */
class VictoryDailyFeedTest {
    private fun page(
        date: String = "2026-10-26",
        change: (String) -> String = { it },
    ): String {
        val end = if (date == "2026-10-26") "2026-11-02" else "2026-10-26"
        val names = listOf(
            Triple("A", "SUGO International Racing Course", "Nissan Fairlady Z (Z34) '08 · SH"),
            Triple("B", "Willow Springs – Big Willow", "Gr.4 · RH"),
            Triple("C", "SUGO International Racing Course", "Gr.3 · RM, RS"),
        )
        val cards = listOf("5 laps Fuel ×1 Wear ×1", "7 laps Fuel ×1 Wear ×1", "14 laps Fuel ×2 Wear ×4")
        val entries = JSONArray()
        for ((index, row) in names.withIndex()) {
            val id = "p_rt_101505" + (7 + index) + "_001"
            val race = row.first
            val event = JSONObject()
                .put("@type", "SportsEvent")
                .put("name", "GT7 Daily Race $race at " + row.second)
                .put("description", "Oct 26 – Nov 2 · " + row.third)
                .put("url", "https://victorydash.io/daily-races/$id")
                .put("startDate", "T08:05:00.000Z".let { date + it })
                .put("endDate", end + "T08:05:00.000Z")
            entries.put(JSONObject().put("item", event))
        }
        val script = JSONObject().put("@type", "ItemList").put("itemListElement", entries)
        return change(buildString {
            append("<html><main><script type=\"application/ld+json\">")
            append(script.toString())
            append("</script>")
            names.forEachIndexed { i, n ->
                val id = "p_rt_101505" + (7 + i) + "_001"
                append("<a aria-label=\"View Daily Race " + n.first + " leaderboard\" href=\"/daily-races/$id\">")
                append("<article><span>Race " + n.first + "</span><b>BoP</b><p>" + cards[i] + "</p>")
                append("<div>Race week Oct 26 – Nov 2</div></article></a>")
            }
            append("</main></html>")
        })
    }

    @Test fun parsesCurrentWeekFromPublicMarkup() {
        val posts = VictoryDailyFeed.parse(page(), 100L)
        assertEquals(1, posts.size)
        val post = posts.single()
        assertEquals(LocalDate.of(2026, 10, 26), post.validFrom)
        assertEquals(3, post.races.size)
        assertEquals(5, post.races.getValue(Series.DAILY_A).laps)
        assertEquals("Nissan Fairlady Z (Z34) '08", post.races.getValue(Series.DAILY_A).cars)
        assertEquals(setOf(TyreCompound.SH), post.races.getValue(Series.DAILY_A).allowedTyres)
        assertEquals(7, post.races.getValue(Series.DAILY_B).laps)
        val c = post.races.getValue(Series.DAILY_C)
        assertEquals(14, c.laps)
        assertEquals(2, c.fuelMultiplier)
        assertEquals(4, c.tyreMultiplier)
        assertEquals(setOf(TyreCompound.RM, TyreCompound.RS), c.allowedTyres)
        assertNull(c.requiredTyres) // Victory shows allowed tyres, not the compulsory pair.
        assertNull(c.mandatoryStops)
        assertEquals("Victory", c.source.name)
    }

    @Test fun failsClosedOnMissingCardsWrongDatesOrIncompatibleFields() {
        assertTrue(VictoryDailyFeed.parse(page(date = "2026-10-19"), 1).isNotEmpty()) // valid old week is labelled old
        assertTrue(VictoryDailyFeed.parse(page(change = { it.replace("14 laps Fuel ×2 Wear ×4", "14 laps") }), 1).isEmpty())
        assertTrue(VictoryDailyFeed.parse(page(change = { it.replace("<span>Race B</span>", "<span>UNKNOWN</span>") }), 1).isEmpty())
        assertTrue(VictoryDailyFeed.parse(page(change = { it.replace("p_rt_1015059_001", "not_an_id") }), 1).isEmpty())
    }
}
