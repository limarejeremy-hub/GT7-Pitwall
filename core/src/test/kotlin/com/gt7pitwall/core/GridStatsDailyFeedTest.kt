package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.GridStatsDailyFeed
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contrat de l'API de races de la semaine, sans dépendre d'un compte ou du réseau dans les tests. */
class GridStatsDailyFeedTest {
    private val sample = """
    {
      "status":"success", "count":3, "data":[
        {
          "race_type":"A","track_name":"SUGO International Racing Course","laps":5,
          "begin_date":"2026-10-26 00:00:00","car_class":"Road",
          "meta_car":{"name":"Nissan Fairlady Z (Z34) '08"},"other_cars":[],
          "raw_payload":{"regulation":{"tire":"Sport","compounds":["Hard"],"required_compounds":[]},
          "race":{"consume_fuel":1,"consume_tire":1,"pit_constraint":0,"start_type":"StartGrid"}}
        },
        {
          "race_type":"B","track_name":"Willow Springs International Raceway: Big Willow","laps":7,
          "begin_date":"2026-10-26 00:00:00","car_class":"Gr.4",
          "raw_payload":{"regulation":{"tire":"Racing","compounds":["Hard"],"required_compounds":[]},
          "race":{"consume_fuel":1,"consume_tire":1,"pit_constraint":0,"start_type":"StartFormationRolling"}}
        },
        {
          "race_type":"C","track_name":"SUGO International Racing Course","laps":14,
          "begin_date":"2026-10-26 00:00:00","car_class":"Gr.3",
          "raw_payload":{"regulation":{"tire":"Racing","compounds":["Medium","Soft"],
          "required_compounds":["Medium","Soft"]},
          "race":{"consume_fuel":2,"consume_tire":4,"pit_constraint":0,"start_type":"StartFormationRolling"}}
        }
      ]
    }
    """.trimIndent()

    @Test
    fun currentWeekRacesAndMandatoryCompoundsAreNeverGuessed() {
        val posts = GridStatsDailyFeed.parse(sample, 100)
        assertEquals(1, posts.size)
        assertEquals(LocalDate.of(2026, 10, 26), posts.single().validFrom)
        assertEquals(3, posts.single().races.size)
        val a = posts.single().races.getValue(Series.DAILY_A)
        assertEquals(5, a.laps)
        assertEquals("Nissan Fairlady Z (Z34) '08", a.cars)
        assertEquals(setOf(TyreCompound.SH), a.allowedTyres)
        assertEquals(emptySet(), a.requiredTyres)
        val b = posts.single().races.getValue(Series.DAILY_B)
        assertEquals("Gr.4", b.cars)
        assertEquals(setOf(TyreCompound.RH), b.allowedTyres)
        val c = posts.single().races.getValue(Series.DAILY_C)
        assertEquals(14, c.laps)
        assertEquals(0, c.mandatoryStops) // le changement de gomme vient des deux composés imposés
        assertEquals(setOf(TyreCompound.RM, TyreCompound.RS), c.requiredTyres)
        assertEquals(2, c.fuelMultiplier)
        assertEquals(4, c.tyreMultiplier)
        val state = ScheduleBuilder.build(posts, Gt7Database.EMPTY, LocalDate.of(2026, 10, 26), 100, null)
        assertFalse(state.stale)
        assertEquals(FieldStatus.PROBABLE, state.editions.getValue(Series.DAILY_C).laps.status)
        assertTrue(state.editions.getValue(Series.DAILY_C).trackId == null) // attendre l'ajout de SUGO dans le catalogue
        assertTrue(ScheduleBuilder.build(posts, Gt7Database.EMPTY, LocalDate.of(2026, 11, 2), 100, null).stale)
    }

    @Test
    fun malformedFutureOrIncompleteRacesCannotOverwriteValidSchedule() {
        assertTrue(GridStatsDailyFeed.parse("not json", 1).isEmpty())
        assertTrue(GridStatsDailyFeed.parse("""{"status":"error","data":[]}""", 1).isEmpty())
        val wrong = sample.replace("2026-10-26 00:00:00", "2026-10-27 00:00:00")
        assertTrue(GridStatsDailyFeed.parse(wrong, 1).isEmpty())
        val missing = sample.replace("\"required_compounds\":[\"Medium\",\"Soft\"]", "\"no_required_field\":null")
        val c = GridStatsDailyFeed.parse(missing, 1).single().races.getValue(Series.DAILY_C)
        assertNull(c.requiredTyres) // absent != aucun
    }

    @Test
    fun sourceConflictsCannotSilentlyReplacePilotRules() {
        val source1 = GridStatsDailyFeed.parse(sample, 1).single().races.getValue(Series.DAILY_C)
        val source2 = source1.copy(
            source = com.gt7pitwall.core.sport.SourceRef("GTPlanet", null, 1),
            laps = 13,
        )
        val merged = ScheduleBuilder.merge(Series.DAILY_C, LocalDate.of(2026, 10, 26),
            listOf(source1, source2), Gt7Database.EMPTY)
        assertEquals(FieldStatus.CONFLICT, merged.laps.status)
        assertNull(merged.laps.value)
        assertEquals(FieldStatus.CONFIRMED, merged.requiredTyres.status)
    }

    /** Domaine officiel « gt-gridstats.com » (avec tiret) : la clé du pilote n'est jamais envoyée ailleurs. */
    @org.junit.jupiter.api.Test
    fun theKeyOnlyGoesToTheOfficialDomain() {
        val url = java.net.URI(com.gt7pitwall.core.sport.GridStatsDailyFeed.URL)
        kotlin.test.assertEquals("https", url.scheme)
        kotlin.test.assertEquals("gt-gridstats.com", url.host)
    }
}
