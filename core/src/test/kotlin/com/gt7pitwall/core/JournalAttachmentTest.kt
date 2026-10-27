package com.gt7pitwall.core

import com.gt7pitwall.core.race.JournalExport
import com.gt7pitwall.core.race.JournalSource
import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.SessionJournal
import com.gt7pitwall.core.strategy.RaceRules
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Chaque événement appartient à la course qu'il décrit : le bilan de fin de course est écrit avant la clôture du
 * journal, et un événement tardif nommé (SessionJournal.recordFor) reste dans sa course, jamais dans la suivante.
 */
class JournalAttachmentTest {
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
    fun theRaceReviewBelongsToTheFinishedRaceAndPrecedesItsClosing() {
        val store = FakeStorage()
        val c = controller(store)
        val first = race(c)
        val e = events(store, first)
        val review = e.filter { it.text.startsWith("Bilan : ") }
        assertTrue(review.isNotEmpty(), e.map { it.text }.toString())
        assertTrue(review.all { it.sessionId == first })
        // Le bilan précède la ligne de fin de session ; les numéros se suivent sans trou ni doublon.
        assertTrue(e.last().text.startsWith("Fin de session"), e.last().text)
        assertEquals((1..e.size).toList(), e.map { it.seq })
        // La course suivante ne reçoit aucun bilan de la précédente.
        val second = race(c)
        assertNotEquals(first, second)
        val e2 = events(store, second)
        assertFalse(e2.any { it.text.startsWith("Bilan : ") && it.details["session"] == first }, e2.map { it.text }.toString())
        assertFalse(e2.any { it.details["avant_session"] == "oui" && it.text.startsWith("Bilan") })
    }

    @Test
    fun theJsonExportContainsTheReviewInOrder() {
        val store = FakeStorage()
        val c = controller(store)
        val id = race(c)
        val json = JSONObject(JournalExport.json(id, store.sessions.firstOrNull { it.id == id }, events(store, id), now))
        val list = json.getJSONArray("events")
        val texts = (0 until list.length()).map { list.getJSONObject(it).getString("text") }
        val lastReview = texts.indexOfLast { it.startsWith("Bilan : ") }
        assertTrue(lastReview >= 0, texts.toString())
        assertEquals(texts.size - 1, texts.indexOfFirst { it.startsWith("Fin de session") }, texts.toString())
        assertTrue(lastReview < texts.size - 1)
        assertTrue((0 until list.length()).all { list.getJSONObject(it).getString("session") == id })
    }

    @Test
    fun eventsAfterClosingNeverSlideIntoTheNextRaceAnymore() {
        // Avant la correction : un événement écrit après la clôture attendait en mémoire et rejoignait la course
        // suivante (marqué « avant_session »). recordFor l'écrit dans la course nommée, sans attente.
        val store = FakeStorage()
        val c = controller(store)
        val first = race(c)
        c.journal.recordFor(first, JournalType.DECISION, JournalSource.ENGINE, "Événement tardif")
        val second = race(c)
        assertTrue(events(store, first).any { it.text == "Événement tardif" })
        assertFalse(events(store, second).any { it.text == "Événement tardif" })
    }
}
