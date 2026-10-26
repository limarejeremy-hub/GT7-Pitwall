package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.strategy.RaceRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Bilan de pilotage de fin de course : mesuré, archivé, et honnête quand les données manquent. */
class DrivingReportTest {
    @Test
    fun aFinishedRaceProducesAShortRadioSummaryAndAFullArchivedReport() {
        val storage = FakeStorage(rules = RaceRules(totalLaps = 10, mandatoryStops = 1))
        val c = RaceController(storage = storage)
        val s = RaceSim(c, totalLaps = 10, perLap = 4f)
        s.start()
        s.driveLaps(5)
        s.pitStop(fuel = 0f, stopSeconds = 3f, changeTyres = true)
        s.say("oui")
        s.driveLaps(6)
        val r = c.lastDrivingReport
        assertNotNull(r, s.texts.toString())
        assertTrue(r.enoughData, r.lines.toString())
        assertTrue(r.lines.any { it.startsWith("Meilleur tour") }, r.lines.toString())
        assertTrue(r.lines.any { it.startsWith("Rythme exploitable") }, r.lines.toString())
        assertTrue(r.lines.any { it.startsWith("Après l'arrêt") }, r.lines.toString())
        assertTrue(s.texts.any { it.startsWith("Bilan") }, s.texts.toString())
        // Synthèse radio courte ; rapport complet dans l'archive, relu à l'identique.
        assertTrue(r.radio.length < 200, r.radio)
        val saved = storage.sessionsJson.values.single()
        assertEquals(r.lines, RaceJson.session(saved)!!.report)
        // Journal : le bilan complet.
        assertTrue(storage.journals.values.single().any { "Bilan pilotage" in it })
    }

    @Test
    fun withTooFewCleanLapsTheReportSaysItCannotJudge() {
        val c = RaceController(storage = FakeStorage(rules = RaceRules(totalLaps = 3)))
        val s = RaceSim(c, totalLaps = 3, perLap = 4f)
        s.start()
        s.driveLaps(4)
        val r = c.lastDrivingReport!!
        assertFalse(r.enoughData)
        assertTrue(r.lines.any { it.startsWith("Pilotage non jugé") }, r.lines.toString())
        assertTrue("pas assez de tours propres" in r.radio, r.radio)
    }
}
