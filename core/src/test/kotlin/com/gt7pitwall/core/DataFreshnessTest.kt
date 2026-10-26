package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.sport.DataFreshness
import com.gt7pitwall.core.sport.DataFreshness.Level
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.ScheduleBuilder
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataFreshnessTest {
    private val feed = javaClass.getResource("/gtplanet-daily-2026-10-19.xml")!!.readText()
    private val db: Gt7Database = run {
        val dir = File("../app/src/main/assets/gt7db")
        Gt7Database.parse(File(dir, "cars.csv").readText(), File(dir, "maker.csv").readText(), File(dir, "course.csv").readText())
    }
    private val h = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun state(today: LocalDate, error: String? = null) =
        ScheduleBuilder.build(GtPlanetFeed.parse(feed, 1), db, today, now - 2 * h, error)

    @Test
    fun theWeeksRacesAndAFreshBaseAreGreen() {
        val f = DataFreshness.of(state(LocalDate.of(2026, 10, 21)), LocalDate.of(2026, 10, 21), now, now - 3 * h, null)
        assertEquals(Level.OK, f.level)
        assertEquals("Sport 19/10 · Base 3 h", f.text)
    }

    @Test
    fun mondayBeforeTheFactSheetIsWaitingNotStale() {
        val monday = LocalDate.of(2026, 10, 26)
        val f = DataFreshness.of(state(monday), monday, now, now - 3 * h, null)
        assertEquals(Level.WAITING, f.level)
        assertTrue(f.text.startsWith("Sport en attente"), f.text)
    }

    @Test
    fun aMissingWeekLaterIsAProblemAndErrorsAreShown() {
        val thursday = LocalDate.of(2026, 10, 29)
        val f = DataFreshness.of(state(thursday, error = "GTPlanet : HTTP 503"), thursday, now, null, null)
        assertEquals(Level.PROBLEM, f.level)
        assertTrue("HTTP 503" in f.detail && "version livrée" in f.detail, f.detail)
    }
}
