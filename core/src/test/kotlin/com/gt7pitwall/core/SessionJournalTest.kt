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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Journal de course : association à la session et au tour, persistance au fil de l'eau, export. Course simulée. */
class SessionJournalTest {

    private fun race(): Pair<FakeStorage, RaceSim> {
        val storage = FakeStorage(rules = RaceRules(totalLaps = 10))
        val sim = RaceSim(RaceController(storage = storage), totalLaps = 10)
        return storage to sim
    }

    @Test
    fun lapsPitStopsDecisionsAndPlansAreJournaledUnderTheArchivedSession() {
        val (storage, sim) = race()
        sim.start()
        sim.driveLaps(2)
        sim.say("arrêt prévu au tour 6") // question sur la stratégie : rien modifié
        sim.pitStop(30f)
        sim.driveLaps(1)
        sim.controller.endSession()

        val record = storage.sessions.single()
        val events = SessionJournal.load(storage, record.id)
        assertEquals(listOf(record.id), storage.journals.keys.toList()) // même identifiant que l'archive
        assertTrue(events.all { it.sessionId == record.id })
        assertEquals(events.map { it.seq }, (1..events.size).toList()) // aucun trou, écrit au fil de l'eau
        assertTrue(events.first().type == JournalType.SESSION && "Début de session" in events.first().text)
        assertTrue(events.last().type == JournalType.SESSION && "Fin de session" in events.last().text)

        val laps = events.filter { it.type == JournalType.LAP }
        assertEquals(record.laps.map { it.lap }, laps.map { it.lap })
        assertTrue(laps.all { it.source == JournalSource.TELEMETRY && "temps_ms" in it.details })
        assertTrue(events.any { it.type == JournalType.PIT && it.source == JournalSource.TELEMETRY })
        assertTrue(events.any { it.type == JournalType.STRATEGY && it.source == JournalSource.ENGINE && it.decision != null })

        // La phrase du pilote est associée au tour où elle a été dite (tour 3, après deux tours bouclés).
        val decision = events.single { it.type == JournalType.DECISION && "arrêt prévu au tour 6" in it.text }
        assertEquals(3, decision.lap)
        assertTrue("rien modifié" in decision.decision!!)
        assertEquals("voice", decision.details["origine"])
    }

    @Test
    fun screenCommandsAreJournaledWithTheirOrigin() {
        val (storage, sim) = race()
        sim.start()
        sim.driveLaps(1)
        sim.controller.command(com.gt7pitwall.core.radio.DriverCommand.ReportPosition(4))
        val id = storage.journals.keys.single()
        val e = SessionJournal.load(storage, id).single { it.type == JournalType.DECISION }
        assertEquals("écran", e.details["origine"])
        assertTrue("ReportPosition" in e.text)
    }

    @Test
    fun radioBeforeTheSessionJoinsTheNextOne() {
        val (storage, sim) = race()
        sim.controller.journal.record(JournalType.DRIVER, JournalSource.TRANSCRIPT, "radio check")
        assertTrue(storage.journals.isEmpty()) // pas encore de session : en attente, rien d'écrit
        sim.start()
        val events = SessionJournal.load(storage, storage.journals.keys.single())
        val early = events.single { it.text == "radio check" }
        assertEquals("oui", early.details["avant_session"])
        assertTrue(events.first().type == JournalType.SESSION) // la session est ouverte avant
    }

    @Test
    fun sensitiveDataIsMaskedAndBrokenLinesAreSkipped() {
        val storage = FakeStorage()
        val j = SessionJournal(storage, clock = { 1_000L })
        j.begin("s1", "Début")
        val token = "MTA" + "x".repeat(21) + ".GaBcDe." + "y".repeat(30)
        j.record(JournalType.RADIO, JournalSource.SYSTEM, "PS5 192.168.1.42 connectée, jeton $token")
        val text = storage.journals.getValue("s1").last()
        assertFalse("192.168.1.42" in text)
        assertFalse(token in text)
        assertTrue("[adresse masquée]" in text && "[masqué]" in text)
        storage.journals.getValue("s1") += "{\"seq\": 3, \"time\"" // écriture interrompue par Android
        assertEquals(2, SessionJournal.load(storage, "s1").size)
    }

    @Test
    fun exportsAreCompleteAndSayWhatEachSourceMeans() {
        val (storage, sim) = race()
        sim.start()
        sim.driveLaps(2)
        sim.controller.journal.record(
            JournalType.DRIVER, JournalSource.TRANSCRIPT, "combien d'essence",
            details = mapOf("reconnaissance" to "Vosk", "transcription_libre" to "combien d'essence il me reste"),
        )
        sim.controller.endSession()
        val record = storage.sessions.single()
        val events = SessionJournal.load(storage, record.id)
        val json = JSONObject(JournalExport.json(record.id, record, events, 5L))
        assertEquals(events.size, json.getJSONArray("events").length())
        assertEquals(record.laps.size, json.getJSONObject("archive").getJSONArray("laps").length())
        assertTrue(json.getJSONArray("legend").getString(0).contains("Aucun son"))
        val text = JournalExport.text(record.id, record, events)
        assertTrue("Chronologie :" in text && "Tours (mesures GT7) :" in text)
        assertTrue("transcription de la reconnaissance vocale : combien d'essence" in text)
        assertEquals(text.lines().count { it.startsWith("[") }, events.size) // une ligne par événement

        val sessions = SessionJournal.sessions(storage)
        assertEquals(record.id, sessions.single().id)
        assertEquals(events.size, sessions.single().events)
        assertNotNull(sessions.single().outcome)
    }
}
