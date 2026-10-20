package com.gt7pitwall.core

import com.gt7pitwall.core.opponents.NoOpponentsSource
import com.gt7pitwall.core.opponents.OpponentInfo
import com.gt7pitwall.core.telemetry.GapHunter
import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fonctionnement du module Gap Hunter avec des DONNÉES SYNTHÉTIQUES. Ces tests ne prouvent rien sur GT7 : ils
 * vérifient seulement que le module repère une corrélation plantée exprès, ignore le bruit et reste borné.
 */
class GapHunterTest {

    private fun frame(position: Int, planted: Int, noise: Int, lap: Int = 2, size: Int = PacketFormat.C.size): ByteArray {
        // Drapeaux comme en course réelle : en prise, sans le bit « en piste ».
        val plain = TestPackets.plain(lap = lap, totalLaps = 10, size = size, flags = TelemetryPacket.FLAG_IN_GEAR)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x84, position.toShort())
            putShort(0x86, 12)
            putInt(0xD4, planted) // mot « réservé » : change seulement autour des dépassements
            putInt(0xD8, noise) // mot « réservé » : change sans arrêt
        }
        return plain
    }

    /** 80 s de course synthétique à 60 Hz ; la place change à 10, 25, 40 et 55 s. */
    private fun race(hunter: GapHunter) {
        var pos = 8
        var planted = 0
        val changes = listOf(10_000L, 25_000L, 40_000L, 55_000L)
        var t = 0L
        while (t < 80_000) {
            if (changes.any { t >= it && t - 16 < it }) pos--
            if (changes.any { t >= it + 200 && t - 16 < it + 200 }) planted++
            hunter.onFrame(frame(pos, planted, noise = (t / 16).toInt()), PacketFormat.C.size, 1_000_000 + t)
            t += 16
        }
    }

    @Test
    fun staysSilentAndEmptyWhileDisabled() {
        val hunter = GapHunter()
        hunter.onFrame(frame(5, 0, 0), PacketFormat.C.size, 1)
        hunter.onFrame(null, 400, 2)
        val s = hunter.summary()
        assertFalse(s.enabled)
        assertEquals(0, s.frames)
        assertEquals(0, s.invalid)
        assertEquals(0, s.lines)
    }

    @Test
    fun findsAPlantedCorrelationButNotNoiseOrKnownFields() {
        val hunter = GapHunter().apply { setEnabled(true) }
        race(hunter)
        val s = hunter.summary()
        assertEquals(4, s.events[GapHunter.EventKind.POSITION])
        val pos = s.candidates.filter { it.kind == GapHunter.EventKind.POSITION }
        assertEquals(listOf(0xD4), pos.map { it.field.offset }) // ni le bruit (0xD8), ni 0x84 lui-même (connu)
        assertEquals(4, pos.single().eventsWithChange)
        val words = hunter.stats().associateBy { it.field.offset }
        assertEquals(GapHunter.Shape.CONSTANT, words.getValue(0x58).shape)
        assertEquals(GapHunter.Shape.COUNTER, words.getValue(0xD8).shape)
        assertEquals(4, words.getValue(0xD4).changes)
    }

    @Test
    fun countsReadableAndUnreadableSizes() {
        val hunter = GapHunter().apply { setEnabled(true) }
        hunter.onFrame(frame(5, 0, 0), PacketFormat.C.size, 1)
        hunter.onFrame(frame(5, 0, 0, size = PacketFormat.A.size), PacketFormat.A.size, 2)
        hunter.onFrame(null, 400, 3)
        val s = hunter.summary()
        assertEquals(mapOf(296 to 1L, 368 to 1L), s.bySize)
        assertEquals(mapOf(400 to 1L), s.invalidBySize)
    }

    @Test
    fun bytesBeyondFormatCAreReportedAsNew() {
        val hunter = GapHunter().apply { setEnabled(true) }
        hunter.onFrame(frame(5, 0, 0, size = 400), 400, 1)
        val beyond = hunter.stats().filter { it.field.offset >= PacketFormat.C.size }
        assertEquals(8, beyond.size)
        assertTrue(beyond.all { it.field.status == GapHunter.Status.UNKNOWN && it.field.label == "au-delà du format C" })
    }

    @Test
    fun journalIsBoundedAndExportHasNoNetworkData() {
        val hunter = GapHunter(maxLines = 50).apply { setEnabled(true) }
        race(hunter)
        hunter.mark("écart HUD devant 1,2", 1_090_000)
        val s = hunter.summary()
        assertEquals(50, s.lines)
        assertTrue(s.dropped > 0)
        val text = hunter.export()
        assertTrue("Une corrélation n'est pas une preuve" in text)
        assertTrue("repère : écart HUD devant 1,2" in text)
        assertTrue("0x0D4" in text)
        assertFalse(Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b").containsMatchIn(text))
        hunter.reset()
        assertEquals(0, hunter.summary().lines)
    }

    @Test
    fun opponentGapsNeedAFreshReliableMeasurementToBeAnnounced() {
        assertEquals(emptyList(), NoOpponentsSource.current())
        val now = 100_000L
        val measured = OpponentInfo(7, 1_200, "test", OpponentInfo.Kind.MEASURED, now - 1_000, 0.95, OpponentInfo.Relation.AHEAD)
        assertTrue(measured.announceable(now))
        assertFalse(measured.copy(measuredAtMs = now - 10_000).announceable(now)) // trop ancienne
        assertFalse(measured.copy(kind = OpponentInfo.Kind.ESTIMATED).announceable(now))
        assertFalse(measured.copy(reliability = 0.5).announceable(now))
        assertEquals(OpponentInfo.Kind.UNAVAILABLE, OpponentInfo(7, null, "test").kind)
    }
}
