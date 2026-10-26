package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Course
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.sport.SportNames
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regressions from the user's Android screenshots of week 44, 26 October 2026. */
class SportNamesTest {
    private val week = LocalDate.of(2026, 10, 26)
    private val db = Gt7Database(emptyMap(), listOf(
        Course(100, "Willow Springs International Raceway: Big Willow", "track", 3950, null, 1, false, false),
        Course(101, "Willow Springs International Raceway: Streets of Willow", "track", 2500, null, 1, false, false),
        Course(102, "Willow Springs International Raceway: Big Willow", "track", 3950, null, 1, true, false),
    ))
    private fun info(source: String, series: Series, track: String, car: String?) =
        DailyRaceInfo(series, SourceRef(source, null, 1L), trackName = track, cars = car,
            laps = if (series == Series.DAILY_C) 14 else if (series == Series.DAILY_B) 7 else 5)

    @Test fun aliasesOfBigWillowAndGr4AreNotConflicts() {
        val merged = ScheduleBuilder.merge(Series.DAILY_B, week, listOf(
            info("GTPlanet", Series.DAILY_B, "Willow Springs International Raceway: Big Willow", "Gr.4"),
            info("Victory", Series.DAILY_B, "Willow Springs – Big Willow", "Gr.4 Cars"),
            info("DG EDGE", Series.DAILY_B, "Willow Springs International Raceway - Big Willow", "GR.4"),
        ), db)
        assertEquals(FieldStatus.CONFIRMED, merged.trackName.status)
        assertEquals(100, merged.trackId)
        assertEquals(FieldStatus.CONFIRMED, merged.cars.status)
        assertEquals("Gr.4", merged.cars.value)
    }

    @Test fun classAliasesAreSafeButARealCategoryConflictIsStillBlocked() {
        val valid = ScheduleBuilder.merge(Series.DAILY_C, week, listOf(
            info("Victory", Series.DAILY_C, "SUGO International Racing Course", "Gr.3"),
            info("GTPlanet", Series.DAILY_C, "SUGO International Racing Course", "Group 3"),
            info("DG EDGE", Series.DAILY_C, "SUGO International Racing Course", "GR.3 cars"),
        ), db)
        assertEquals(FieldStatus.CONFIRMED, valid.cars.status)
        assertEquals("Gr.3", valid.cars.value)
        assertNull(valid.trackId) // SUGO absent until the database learns it
        assertEquals(FieldStatus.CONFIRMED, valid.trackName.status)

        val invalid = ScheduleBuilder.merge(Series.DAILY_C, week, listOf(
            info("Victory", Series.DAILY_C, "SUGO International Racing Course", "Gr.3"),
            info("Other", Series.DAILY_C, "SUGO International Racing Course", "Gr.4"),
        ), db)
        assertEquals(FieldStatus.CONFLICT, invalid.cars.status)
        assertNull(invalid.cars.value)
    }

    @Test fun genericCarTextDoesNotPoisonRealOneMakeDescriptions() {
        val a = ScheduleBuilder.merge(Series.DAILY_A, week, listOf(
            info("GTPlanet", Series.DAILY_A, "SUGO International Racing Course", "Nissan Fairlady Z (Z34) '08"),
            info("Victory", Series.DAILY_A, "SUGO International Racing Course", "Nissan Fairlady Z (Z34) 2008"),
            info("DG EDGE", Series.DAILY_A, "SUGO International Racing Course", "the race"),
        ), db)
        assertEquals(FieldStatus.CONFIRMED, a.cars.status)
        assertTrue(a.cars.value!!.contains("Nissan"))
        assertTrue(!SportNames.meaningfulCar("the race"))
        assertTrue(SportNames.meaningfulCar("Gr.3"))
    }

    @Test fun distinctLayoutsAndModelYearsMustStayConflicts() {
        val trackConflict = ScheduleBuilder.merge(Series.DAILY_B, week, listOf(
            info("Victory", Series.DAILY_B, "Willow Springs – Big Willow", "Gr.4"),
            info("Other", Series.DAILY_B, "Willow Springs – Streets of Willow", "Gr.4"),
        ), db)
        assertEquals(FieldStatus.CONFLICT, trackConflict.trackName.status)
        assertNull(trackConflict.trackId)
        val modelConflict = ScheduleBuilder.merge(Series.DAILY_A, week, listOf(
            info("Victory", Series.DAILY_A, "SUGO International Racing Course", "Nissan Fairlady Z (Z34) '08"),
            info("Other", Series.DAILY_A, "SUGO International Racing Course", "Nissan Fairlady Z (Z34) '23"),
        ), db)
        assertEquals(FieldStatus.CONFLICT, modelConflict.cars.status)
    }
}
