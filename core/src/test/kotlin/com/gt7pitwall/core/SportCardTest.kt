package com.gt7pitwall.core

import com.gt7pitwall.core.display.SportCard
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.knowledge.TrackSignature
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.sport.Sourced
import com.gt7pitwall.core.strategy.TyreCompound
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cartes Sport A/B/C du Briefing, sur la vraie base GT7 de l'APK (fiches de la semaine du 02/11 : données publiques). */
class SportCardTest {
    private val dir = File("../app/src/main/assets/gt7db")
    private val db = Gt7Database.parse(File(dir, "cars.csv").readText(), File(dir, "maker.csv").readText(), File(dir, "course.csv").readText()).also {
        it.trackSignatures = TrackSignature.parseAll(File(dir, "track_signatures.json").readText())
    }
    private val paris = ZoneId.of("Europe/Paris")
    private val monday = LocalDate.of(2026, 11, 2)
    private val wednesday = monday.plusDays(2).atTime(12, 0).atZone(paris).toInstant().toEpochMilli()
    private val gtp = SourceRef("GTPlanet", null, 0)

    private fun <T> ok(v: T) = Sourced(v, FieldStatus.CONFIRMED, listOf(gtp))

    private fun edition(series: Series, trackId: Int?, track: Sourced<String>, cars: Sourced<String>, laps: Int, required: Set<TyreCompound>? = null) = Edition(
        series, monday, monday.plusDays(7), trackName = track, trackId = trackId, laps = ok(laps), cars = cars,
        bop = Sourced.unknown(), settings = Sourced.unknown(), startType = Sourced.unknown(),
        allowedTyres = ok(setOf(TyreCompound.RH, TyreCompound.RM)), requiredTyres = required?.let { ok(it) } ?: Sourced.unknown(),
        mandatoryStops = Sourced.unknown(), fuelMultiplier = ok(3), tyreMultiplier = ok(5), sources = listOf(gtp),
    )

    @Test fun aNamedCarAndATrackOutlineAreFound() {
        val c = SportCard.of(edition(Series.DAILY_C, 1240, ok("Watkins Glen Long Course"), ok("Audi TT Cup '16"), 11, setOf(TyreCompound.RH, TyreCompound.RM)), db, wednesday)
        assertEquals("Watkins Glen Long Course", c.trackName)
        assertEquals(3298, c.carCode, "voiture précise → code GT7 pour la photo officielle")
        assertTrue(c.classes.isEmpty())
        assertTrue(c.current)
        assertTrue(c.tyreChangeNeeded)
        val o = c.outline
        assertNotNull(o, "tracé dessiné à partir des captures CC0")
        assertTrue(o.all { it in 0f..1f })
        assertEquals(11, c.laps)
    }

    @Test fun aCategoryShowsABadgeNotAPhoto() {
        val c = SportCard.of(edition(Series.DAILY_B, 1292, ok("Grand Valley - Highway 1"), ok("Gr.3"), 6), db, wednesday)
        assertEquals(listOf("Gr.3"), c.classes)
        assertNull(c.carCode, "une catégorie n'a pas de photo de voiture")
        assertEquals(listOf("N300"), SportCard.classes("N300 cars"))
        assertEquals(listOf("Gr.4", "Gr.B"), SportCard.classes("Gr.4 / Gr.B"))
    }

    @Test fun unknownOrConflictingFieldsStayEmpty() {
        val c = SportCard.of(
            edition(Series.DAILY_A, null, Sourced(null, FieldStatus.CONFLICT, listOf(gtp), "x / y"), Sourced.unknown(), 5),
            db, wednesday,
        )
        assertNull(c.trackName)
        assertEquals(FieldStatus.CONFLICT, c.trackStatus)
        assertNull(c.outline, "pas de circuit sûr : pas de tracé")
        assertNull(c.carsText)
    }

    @Test fun anOldWeekIsNotCurrentAndCardsAreOrdered() {
        val a = edition(Series.DAILY_A, 847, ok("Dragon Trail - Gardens"), ok("Gr.4"), 5)
        val c = edition(Series.DAILY_C, 1240, ok("Watkins Glen Long Course"), ok("Audi TT Cup '16"), 11)
        val state = ScheduleState(mapOf(Series.DAILY_C to c, Series.DAILY_A to a), emptyList(), null, null, stale = false)
        val cards = SportCard.all(state, db, monday.plusDays(9).atTime(10, 0).atZone(paris).toInstant().toEpochMilli())
        assertEquals(listOf('A', 'C'), cards.map { it.letter })
        assertFalse(cards.any { it.current }, "semaine terminée")
    }
}
