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
        val engineer = RaceEngineer().apply { shortLapReports = false } // messages de tour détaillés
        var x = 0f
        var id = 0
        var lastLap = -1
        val said = mutableListOf<String>()

        fun send(lap: Int, lastLapMs: Int, paused: Boolean = false, speed: Float = 0f) {
            val flags = 1 or (if (paused) 2 else 0)
            val plain = TestPackets.plain(
                lap = lap, totalLaps = 10, packetId = ++id, lastLapMs = lastLapMs, flags = flags, posX = x, speedMps = speed,
            )
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
                    send(lap, lastLap, paused = pauseMidLap && s == 1 && i == 10, speed = v)
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
        assertEquals(180f, ref.maxSpeedKmh!!, 0.5f) // 50 m/s
        assertEquals(0f, ref.tcsShare)
        val map = assertNotNull(t.tracker.snapshot.trackOutline)
        assertEquals(2, map.sectorStarts.size)
        assertTrue(map.xs.size > 100)
        assertTrue(map.sectorStarts[0] < map.sectorStarts[1])

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
        assertEquals(DriverCommand.Ask(DriverQuery.TYRES), DriverCommandParser.parse("mes pneus"))
        // Test réel du 13/10 : le "je" est avalé par la reconnaissance.
        assertEquals(DriverCommand.ReportPosition(14), DriverCommandParser.parse("suis quatorzieme"))
        // Réponses brèves à « Ta position ? ».
        assertEquals(DriverCommand.ReportPosition(8), DriverCommandParser.parse("huitième"))
        assertEquals(DriverCommand.ReportPosition(8), DriverCommandParser.parse("huit"))
        assertEquals(DriverCommand.Ask(DriverQuery.SECTORS), DriverCommandParser.parse("premier secteur"))
    }

    @Test
    fun positionFromGridThenDriverReports() {
        val t = SessionTracker()
        val e = RaceEngineer().apply { shortLapReports = false } // messages de tour détaillés
        // La grille n'est retenue qu'une fois stable une seconde (60 paquets) : jamais une valeur fugace.
        repeat(60) { i ->
            val plain = TestPackets.plain(lap = 0, totalLaps = 5, packetId = i + 1)
            java.nio.ByteBuffer.wrap(plain).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0x84, 4)
                putShort(0x86, 12)
            }
            e.onUpdate(t.update(TelemetryParser.parseDecrypted(plain)!!), t.snapshot, null)
        }
        // Sans position mesurée stable, c'est la déclaration du pilote qui compte.
        assertEquals("Bien reçu, P4.", e.handle(DriverCommand.ReportPosition(4), t.snapshot, null).text)
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
        val e = RaceEngineer().apply { shortLapReports = false } // messages de tour détaillés
        e.onUpdate(t.update(TestPackets.packet(lap = 1, totalLaps = 5)), t.snapshot, null)
        e.handle(DriverCommand.Quiet, t.snapshot, null)
        val lap = e.onUpdate(t.update(TestPackets.packet(lap = 2, totalLaps = 5, lastLapMs = 80_000)), t.snapshot, null)
        assertTrue(lap.isEmpty(), lap.toString())
        e.handle(DriverCommand.Talk, t.snapshot, null)
        val answer = e.handle(DriverCommand.Ask(DriverQuery.LAST_LAP), t.snapshot, null)
        assertEquals(answer.text, e.handle(DriverCommand.Repeat, t.snapshot, null).text)
    }

    @Test
    fun gForcesFromSpeedAndYawRate() {
        val meter = com.gt7pitwall.core.analysis.GForceMeter(smoothing = 1f)
        fun pk(id: Int, speed: Float, yaw: Float): com.gt7pitwall.core.telemetry.TelemetryPacket {
            val plain = TestPackets.plain(packetId = id, speedMps = speed)
            java.nio.ByteBuffer.wrap(plain).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(0x30, yaw)
            return TelemetryParser.parseDecrypted(plain)!!
        }
        assertNull(meter.update(pk(1, 20f, 0f)))
        val g = assertNotNull(meter.update(pk(2, 20.1f, 0.4905f)))
        assertEquals(0.6116f, g.longitudinal, 0.01f) // 0,1 m/s en 1/60 s
        assertEquals(1.0f, g.lateral, 0.01f) // 20,1 × 0,4905 / 9,81
    }

    /** Course réelle du 13/10 : 14,5 L/tour, 100 L au départ, 10 tours. */
    @Test
    fun boxCallOnTheRightLap() {
        val t = SessionTracker()
        val e = RaceEngineer().apply { shortLapReports = false } // messages de tour détaillés
        val said = mutableListOf<String>()
        fun go(lap: Int, fuel: Float, last: Int) {
            val p = TestPackets.packet(lap = lap, totalLaps = 10, fuel = fuel, lastLapMs = last)
            said += e.onUpdate(t.update(p), t.snapshot, FuelStrategy.compute(t.snapshot)).map { it.text }
        }
        go(0, 100f, -1)
        var fuel = 100f
        for (lap in 1..6) {
            go(lap, fuel, 110_000 + lap)
            fuel -= 14.5f
        }
        go(7, fuel, 120_000)
        val text = said.withIndex().joinToString("\n") { "${it.index}: ${it.value}" }
        // 100 L / 14,5 = 6,9 tours : dernier tour possible = 6 ; prévenu au début du tour 5.
        // Les appels au stand sont des messages séparés, juste avant le compte rendu du tour.
        val warn = said.indexOfFirst { it.startsWith("Stand au prochain tour") }
        assertEquals(said.indexOfFirst { it.startsWith("Tour 4 :") } - 1, warn, text)
        // Au tour suivant, l'appel au stand (et l'alerte carburant, critique aussi) précèdent le compte rendu du tour 5.
        // Libellé du 26/10 : le numéro du tour est précisé (« à la fin de ce tour (tour 6), ajoute environ… »).
        val boxIdx = said.indexOfFirst { it.startsWith("Box, box ! Rentre au stand à la fin de ce tour") && "ajoute environ" in it }
        assertTrue(boxIdx in (said.indexOfFirst { it.startsWith("Tour 4 :") } + 1) until said.indexOfFirst { it.startsWith("Tour 5 :") }, text)
        assertTrue("% du réservoir" in said[boxIdx])
        val lap5 = said.first { it.startsWith("Tour 5 :") }
        assertTrue("Il faudra ravitailler" !in lap5, lap5)
    }

    @Test
    fun asksPositionEachLapWhenEnabled() {
        val t = SessionTracker()
        val e = RaceEngineer().apply { askPositionEachLap = true; shortLapReports = false }
        e.onUpdate(t.update(TestPackets.packet(lap = 1, totalLaps = 5)), t.snapshot, null)
        val m = e.onUpdate(t.update(TestPackets.packet(lap = 2, totalLaps = 5, lastLapMs = 80_000)), t.snapshot, null).single()
        assertTrue(m.text.endsWith("Ta position ?"), m.text)
        assertTrue(m.expectsReply)
    }
}
