package com.gt7pitwall.core

import com.gt7pitwall.core.race.JournalExport
import com.gt7pitwall.core.race.JournalSource
import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.SessionJournal
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.telemetry.BlackBoxRuns
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Le compte rendu Black Box appartient à la course mesurée : arrêté depuis le menu après la clôture, il est écrit dans
 * cette course (à la suite de ses événements), jamais dans la course suivante. Même logique que PitwallEngine :
 * BlackBoxRuns note la course du journal à chaque paquet mesuré, SessionJournal.recordFor écrit le compte rendu.
 */
class BlackBoxJournalTest {
    private var now = 1_800_000_000_000L
    private fun controller(store: FakeStorage) = RaceController(storage = store, clock = { now += 5; now }).also {
        it.setRules(RaceRules(totalLaps = 3))
    }

    private fun events(store: FakeStorage, id: String) = SessionJournal.load(store, id)

    /** Course complète de 3 tours, puis retour au menu (télémétrie arrêtée). Renvoie l'identifiant de la course. */
    private fun race(c: RaceController): String {
        val s = RaceSim(c, totalLaps = 3, perLap = 3f)
        s.start()
        s.driveLaps(4)
        val id = c.journal.sessionId!!
        c.endSession()
        return id
    }

    @Test
    fun aMeasureStoppedFromTheMenuIsAttachedToTheMeasuredRaceNotTheNextOne() {
        val store = FakeStorage()
        val c = controller(store)
        val runs = BlackBoxRuns()
        // Pendant la course, l'application note la course du journal à chaque paquet mesuré.
        val s = RaceSim(c, totalLaps = 3, perLap = 3f)
        s.start()
        repeat(3) { s.driveLaps(1); runs.seen(c.journal.sessionId) }
        s.driveLaps(1)
        val measured = c.journal.sessionId!!
        runs.seen(measured)
        c.endSession()
        runs.seen(c.journal.sessionId) // au menu : aucune course ouverte, rien n'est ajouté
        assertEquals(listOf(measured), runs.all())
        // Le pilote coupe la mesure au menu, après la clôture.
        c.journal.recordFor(runs.last()!!, JournalType.DECISION, JournalSource.SYSTEM, "Mesure Black Box terminée", details = mapOf("mesure_1" to "Durée mesurée : 4 min"))
        val e = events(store, measured)
        val m = e.last()
        assertEquals("Mesure Black Box terminée", m.text)
        assertEquals(measured, m.sessionId)
        assertEquals("oui", m.details["apres_cloture"])
        assertEquals((1..e.size).toList(), e.map { it.seq }, "à la suite des événements de la course, sans doublon")
        // La course suivante démarre : elle ne reçoit pas la mesure.
        val next = race(c)
        assertFalse(events(store, next).any { "Mesure Black Box" in it.text })
        // L'export JSON de la course mesurée la contient.
        val json = JournalExport.json(measured, null, events(store, measured), now)
        assertTrue("Mesure Black Box terminée" in json && "apres_cloture" in json)
    }

    @Test
    fun aMeasureSpanningTwoRacesReportsToTheLastMeasuredRaceAndListsBoth() {
        val store = FakeStorage()
        val c = controller(store)
        val runs = BlackBoxRuns()
        val a = RaceSim(c, totalLaps = 3, perLap = 3f).also { it.start(); it.driveLaps(2) }
        runs.seen(c.journal.sessionId)
        val first = c.journal.sessionId!!
        a.driveLaps(2)
        c.endSession()
        val b = RaceSim(c, totalLaps = 3, perLap = 3f).also { it.start(); it.driveLaps(2) }
        runs.seen(c.journal.sessionId)
        val second = c.journal.sessionId!!
        b.driveLaps(2)
        assertEquals(listOf(first, second), runs.all())
        // Arrêt pendant la seconde course (encore ouverte) : événement normal de cette course.
        val e = c.journal.recordFor(runs.last()!!, JournalType.DECISION, JournalSource.SYSTEM, "Mesure Black Box terminée",
            details = mapOf("courses_mesurees" to runs.all().joinToString(",")))
        assertEquals(second, e.sessionId)
        assertFalse(e.details.containsKey("apres_cloture"))
        assertFalse(events(store, first).any { "Mesure Black Box" in it.text })
    }
}
