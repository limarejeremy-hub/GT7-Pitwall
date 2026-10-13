package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.strategy.FuelStrategy
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SectorsAndRadioTest {

    /** Circuit simulé : ligne droite de 3 km parcourue à vitesse donnée par tiers, 60 paquets/s. */
    private class Track {
        val tracker = SessionTracker()
        val engineer = RaceEngineer()
        var x = 0f
        var id = 0
        var lastLap = -1
        val said = mutableListOf<String>()

        fun send(lap: Int, lastLapMs: Int, paused: Boolean = false) {
            val flags = 1 or (if (paused) 2 else 0)
            val plain = TestPackets.plain(lap = lap, totalLaps = 10, packetId = ++id, lastLapMs = lastLapMs, flags = flags, posX = x)
            val p = TelemetryParser.parseDecrypted(plain)!!
            val events = tracker.update(p)
            said += engineer.onUpdate(events, tracker.snapshot, FuelStrategy.compute(tracker.snapshot)).map { it.text }
        }

        /** Un tour : 3 tiers de 1000 m aux vitesses données (m/s). Renvoie le chrono "officiel". */
        fun lap(lap: Int, speeds: List<Float>, pauseMidLap: Boolean = false): Int {
            var ms = 0.0
            for ((s, v) in speeds.withIndex()) {
                val packets = (1000f / v * 60f).toInt()
                repeat(packets) { i ->
                    x += v / 60f
                    send(lap, lastLap, paused = pauseMidLap && s == 1 && i == 10)
                }
                ms += packets * 1000.0 / 60.0
            }
            lastLap = ms.toInt()
            send(lap + 1, lastLap)
            return lastLap
        }
    }

    @Test
    fun sectorsAreReconstructedAndComparedToBestLap() {
        val t = Track()
        t.send(0, -1)
        t.send(1, -1)
        t.lap(1, listOf(50f, 50f, 50f)) // 3 × 20 s
        val ref = t.tracker.snapshot.laps.last()
        val s1 = assertNotNull(ref.sectorsMs)
        assertEquals(3, s1.size)
        s1.forEach { assertEquals(20_000.0, it.toDouble(), 30.0) }
        assertEquals(ref.timeMs, s1.sum())

        t.lap(2, listOf(50f, 40f, 50f)) // secteur 2 : 25 s
        val lap2 = t.tracker.snapshot.laps.last()
        val s2 = assertNotNull(lap2.sectorsMs)
        assertEquals(25_000.0, s2[1].toDouble(), 30.0)
        val msg = t.said.last { it.startsWith("Tour 2 :") }
        assertTrue("Tu perds surtout dans le secteur 2, 5 secondes" in msg, msg)

        val report = t.engineer.handle(DriverCommand.Ask(DriverQuery.SECTORS), t.tracker.snapshot, null).text
        assertTrue(report.startsWith("Tour 2 contre ton meilleur : secteur 1"), report)
        assertTrue("secteur 2 plus 5 secondes" in report, report)
    }

    @Test
    fun lapWithPauseOrJoinedMidLapHasNoSectors() {
        val t = Track()
        t.send(1, -1) // connexion en cours de tour 1
        // Vitesses différentes : GT7 ne change le "dernier tour" que si le chrono diffère.
        t.lap(1, listOf(50f, 50f, 50f))
        assertEquals(1, t.tracker.snapshot.laps.last().lapNumber)
        assertNull(t.tracker.snapshot.laps.last().sectorsMs)
        t.lap(2, listOf(49f, 49f, 49f), pauseMidLap = true)
        assertEquals(2, t.tracker.snapshot.laps.last().lapNumber)
        assertNull(t.tracker.snapshot.laps.last().sectorsMs)
        t.lap(3, listOf(48f, 48f, 48f))
        assertEquals(3, t.tracker.snapshot.laps.last().lapNumber)
        assertNotNull(t.tracker.snapshot.laps.last().sectorsMs)
    }

    @Test
    fun parsesDriverSpeech() {
        assertEquals(DriverCommand.ReportPosition(3), DriverCommandParser.parse("je suis troisième"))
        assertEquals(DriverCommand.ReportPosition(5), DriverCommandParser.parse("en position cinq"))
        assertEquals(DriverCommand.ReportPosition(12), DriverCommandParser.parse("je suis douzième"))
        assertEquals(DriverCommand.Overtook, DriverCommandParser.parse("j'ai doublé la Porsche"))
        assertEquals(DriverCommand.Overtaken, DriverCommandParser.parse("je me suis fait doubler"))
        assertEquals(DriverCommand.Overtaken, DriverCommandParser.parse("il m'a passé"))
        assertEquals(DriverCommand.Ask(DriverQuery.FUEL), DriverCommandParser.parse("combien d'essence"))
        assertEquals(DriverCommand.Ask(DriverQuery.SECTORS), DriverCommandParser.parse("mes secteurs"))
        assertEquals(DriverCommand.Ask(DriverQuery.POSITION), DriverCommandParser.parse("quelle position"))
        assertEquals(DriverCommand.Quiet, DriverCommandParser.parse("silence"))
        assertEquals(DriverCommand.Repeat, DriverCommandParser.parse("tu peux répéter"))
        assertNull(DriverCommandParser.parse("bonjour"))
        // Phrases réellement entendues lors du test du 13/10.
        assertEquals(DriverCommand.ReportPosition(17), DriverCommandParser.parse("je suis dix-septieme"))
        assertEquals(DriverCommand.Overtaken, DriverCommandParser.parse("adieu une place"))
        assertEquals(DriverCommand.ReportLast, DriverCommandParser.parse("dernier"))
        assertEquals(DriverCommand.ReportLast, DriverCommandParser.parse("je suis dernière"))
        assertEquals(DriverCommand.Ask(DriverQuery.LAST_LAP), DriverCommandParser.parse("mon dernier tour"))
        assertEquals(DriverCommand.ReportPosition(20), DriverCommandParser.parse("je suis vingtième"))
        assertEquals(DriverCommand.ReportPosition(22), DriverCommandParser.parse("je suis vingt-deuxième"))
        assertEquals(DriverCommand.ReportPosition(21), DriverCommandParser.parse("je suis vingt et unième"))
        assertEquals(DriverCommand.ReportPosition(18), DriverCommandParser.parse("position dix-huit"))
        assertEquals(DriverCommand.Overtook, DriverCommandParser.parse("j'ai gagné une place"))
        assertTrue("radio" in DriverCommandParser.vocabulary("radio"))
    }

    @Test
    fun positionFromGridThenDriverReports() {
        val t = SessionTracker()
        val e = RaceEngineer()
        val plain = TestPackets.plain(lap = 0, totalLaps = 5)
        java.nio.ByteBuffer.wrap(plain).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x84, 4)
            putShort(0x86, 12)
        }
        e.onUpdate(t.update(TelemetryParser.parseDecrypted(plain)!!), t.snapshot, null)
        assertEquals(4, e.driverPosition)
        assertEquals("Bien joué ! Tu es P3.", e.handle(DriverCommand.Overtook, t.snapshot, null).text)
        assertEquals("Compris, tu es P4. On reste concentré.", e.handle(DriverCommand.Overtaken, t.snapshot, null).text)
        assertEquals("Bien reçu, P2.", e.handle(DriverCommand.ReportPosition(2), t.snapshot, null).text)
        assertTrue("P2" in e.answer(DriverQuery.POSITION, t.snapshot, null).text)
        assertEquals("Compris, dernier, P12. On remonte.", e.handle(DriverCommand.ReportLast, t.snapshot, null).text)
        // On ne peut pas être plus loin que dernier.
        assertEquals("Compris, tu es P12. On reste concentré.", e.handle(DriverCommand.Overtaken, t.snapshot, null).text)
        e.handle(DriverCommand.ReportPosition(2), t.snapshot, null)
        // Le tour suivant rappelle la position.
        e.onUpdate(t.update(TestPackets.packet(lap = 1, totalLaps = 5)), t.snapshot, null)
        val lapMsgs = e.onUpdate(t.update(TestPackets.packet(lap = 2, totalLaps = 5, lastLapMs = 80_000)), t.snapshot, null)
        assertTrue(lapMsgs.any { "Tu es P2." in it.text }, lapMsgs.toString())
    }

    @Test
    fun quietModeKeepsOnlyAlertsAndRepeatWorks() {
        val t = SessionTracker()
        val e = RaceEngineer()
        e.onUpdate(t.update(TestPackets.packet(lap = 1, totalLaps = 5)), t.snapshot, null)
        e.handle(DriverCommand.Quiet, t.snapshot, null)
        val lap = e.onUpdate(t.update(TestPackets.packet(lap = 2, totalLaps = 5, lastLapMs = 80_000)), t.snapshot, null)
        assertTrue(lap.isEmpty(), lap.toString())
        e.handle(DriverCommand.Talk, t.snapshot, null)
        val answer = e.handle(DriverCommand.Ask(DriverQuery.LAST_LAP), t.snapshot, null)
        assertEquals(answer.text, e.handle(DriverCommand.Repeat, t.snapshot, null).text)
    }
}
