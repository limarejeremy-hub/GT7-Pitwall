package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.telemetry.PositionProbe
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneOffset

/** Diagnostic des champs 0x84 / 0x86 : il observe et journalise, sans rien supposer ni rien annoncer. */
class PositionProbeTest {

    private fun packet(lap: Int, raw84: Int, raw86: Int, totalLaps: Int = 5, flags: Int = TelemetryPacket.FLAG_ON_TRACK, id: Int = 1): TelemetryPacket {
        val plain = TestPackets.plain(lap = lap, totalLaps = totalLaps, flags = flags, packetId = id)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x84, raw84.toShort())
            putShort(0x86, raw86.toShort())
        }
        return TelemetryParser.parseDecrypted(plain)!!
    }

    @Test
    fun rawValuesAreReadSigned() {
        val p = packet(lap = 1, raw84 = -1, raw86 = -1)
        assertEquals(-1, p.preRacePosition)
        assertEquals(-1, p.preRaceCarCount)
        val q = packet(lap = 1, raw84 = 0x7FFF, raw86 = -2)
        assertEquals(32767, q.preRacePosition)
        assertEquals(-2, q.preRaceCarCount)
    }

    @Test
    fun logsChangesStatesLapsAndShares() {
        val probe = PositionProbe()
        var t = 1_000L
        // Grille : position 7 sur 16, puis départ (-1 / -1), puis un éventuel 0x84 évolutif, puis l'arrivée.
        repeat(10) { probe.onPacket(packet(0, 7, 16), t++) }
        repeat(30) { probe.onPacket(packet(1, -1, -1), t++) }
        repeat(30) { probe.onPacket(packet(2, -1, -1), t++) }
        repeat(20) { probe.onPacket(packet(2, 5, 16), t++) }
        repeat(10) { probe.onPacket(packet(6, 5, 16), t++) }

        val s = probe.summary(recent = 20)
        assertEquals(100, s.packets)
        assertEquals(5, s.raw84)
        assertEquals("arrivée", s.state)
        assertEquals(listOf("premier paquet", "changement 0x84/0x86", "tour 2", "changement 0x84/0x86", "état : arrivée"), s.events.map { it.reason })
        assertEquals("avant départ", s.events.first().state)
        assertEquals(7, s.events.first().raw84)
        assertEquals(1, s.events[1].lap)
        assertEquals(40, s.counts84.share(s.counts84.valid))
        assertEquals(60, s.counts84.share(s.counts84.minusOne))
    }

    @Test
    fun pausesAndLoadingAreSessionStates() {
        val probe = PositionProbe()
        probe.onPacket(packet(1, -1, -1), 1)
        probe.onPacket(packet(1, -1, -1, flags = TelemetryPacket.FLAG_ON_TRACK or TelemetryPacket.FLAG_PAUSED), 2)
        probe.onPacket(packet(1, -1, -1, flags = TelemetryPacket.FLAG_LOADING), 3)
        probe.onPacket(packet(0, 3, 12, flags = 0), 4)
        assertEquals(listOf("en course", "pause", "chargement", "hors piste"), probe.summary().events.map { it.state })
    }

    @Test
    fun markersAndExport() {
        val probe = PositionProbe()
        probe.mark("dépassement", 0)
        assertEquals("aucun paquet", probe.summary().events.single().state)
        probe.onPacket(packet(3, -1, -1), 3_600_000)
        probe.mark("HUD P4", 3_600_500)
        val text = probe.export(ZoneOffset.UTC)
        assertTrue("heure;tour;tours;0x84;0x86;état;motif" in text, text)
        assertTrue("01:00:00.500;3;5;-1;-1;en course;repère : HUD P4" in text, text)
        assertTrue("0x84 : 0 % positifs, 100 % à -1" in text, text)
        probe.reset()
        assertEquals(0, probe.summary().packets)
        assertNull(probe.summary().raw84)
    }

    @Test
    fun oldLinesAreDroppedButCounted() {
        val probe = PositionProbe(maxEvents = 5)
        for (i in 1..20) probe.onPacket(packet(1, i, 16), i.toLong())
        val s = probe.summary(recent = 100)
        assertEquals(5, s.events.size)
        assertEquals(15, s.dropped)
        assertEquals(20, s.events.last().raw84)
    }

    @Test
    fun aPositionAppearingDuringTheRaceIsNeverAnnounced() {
        val t = SessionTracker()
        val e = RaceEngineer()
        // Départ sans valeur de grille, puis 0x84 positif en course : aucune annonce, aucune position retenue.
        e.onUpdate(t.update(packet(0, -1, -1)), t.snapshot, null)
        val said = e.onUpdate(t.update(packet(1, 4, 16, id = 2)), t.snapshot, null) +
            e.onUpdate(t.update(packet(2, 3, 16, id = 3)), t.snapshot, null)
        assertFalse(said.any { "position" in it.text.lowercase() || Regex("P\\d").containsMatchIn(it.text) }, said.toString())
        assertNull(e.driverPosition)
    }
}
