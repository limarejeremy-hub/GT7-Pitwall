package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.telemetry.GT7Crypto
import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryRecorder
import com.gt7pitwall.core.telemetry.TelemetryTrace
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue #41 P1 : trace réelle sur demande, bornée, rejouable hors course, sans rien d'autre que les paquets GT7. */
class TelemetryTraceTest {
    private var now = 1_000_000L

    private fun record(rec: TelemetryRecorder, n: Int, idStep: Int = 1, size: Int = GT7Crypto.PACKET_SIZE_A, lap: (Int) -> Int = { 1 }) {
        var id = 1
        repeat(n) { i ->
            now += 16
            rec.onFrame(TestPackets.plain(lap = lap(i), speedMps = 40f, packetId = id, size = size), now)
            id += idStep
        }
    }

    @Test fun sixtyHertzIsDecimatedToTwentyAndReadsBackExactly() {
        val sink = ByteArrayOutputStream()
        val rec = TelemetryRecorder(clock = { now })
        rec.start(sink, hz = 20)
        record(rec, 600)
        rec.stop()
        val s = rec.stats()
        assertTrue(s.kept in 180..210, "${s.kept}")
        assertEquals(600, s.seen.toInt())
        assertEquals(0, s.dropped.toInt())
        assertEquals(0, s.lostUdp.toInt())
        val frames = TelemetryTrace.read(ByteArrayInputStream(sink.toByteArray()))
        assertEquals(s.kept.toInt(), frames.size)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.atMs > a.atMs })
        val replay = TelemetryTrace.replay(frames)
        assertEquals(frames.size, replay.decoded)
        assertEquals(mapOf("A" to frames.size), replay.formats)
        assertTrue(s.writtenBytes > 0 && s.p95Ms != null)
    }

    @Test fun formatCIsKeptWholeSoFutureDecodersCanReadIt() {
        val sink = ByteArrayOutputStream()
        val rec = TelemetryRecorder(clock = { now })
        rec.start(sink, hz = 60)
        record(rec, 60, size = PacketFormat.C.size)
        rec.stop()
        val frames = TelemetryTrace.read(ByteArrayInputStream(sink.toByteArray()))
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.plain.size == PacketFormat.C.size })
        assertEquals(setOf("C"), TelemetryTrace.replay(frames).formats.keys)
    }

    @Test fun networkLossesPausesAndUnreadableFramesAreCountedNotRecorded() {
        val rec = TelemetryRecorder(clock = { now })
        rec.start(ByteArrayOutputStream(), hz = 60)
        record(rec, 30, idStep = 2)
        rec.onFrame(null, now)
        rec.onFrame(ByteArray(10), now)
        now += 5_000
        record(rec, 30)
        rec.stop()
        val s = rec.stats()
        assertEquals(60, s.seen.toInt())
        assertEquals(29, s.lostUdp.toInt())
        assertEquals(1, s.pauses)
    }

    @Test fun aTruncatedTraceKeepsItsCompleteSamples() {
        val sink = ByteArrayOutputStream()
        val rec = TelemetryRecorder(clock = { now })
        rec.start(sink, hz = 60)
        record(rec, 300)
        rec.stop()
        val bytes = sink.toByteArray()
        val cut = TelemetryTrace.read(ByteArrayInputStream(bytes.copyOf(bytes.size * 2 / 3)))
        assertTrue(cut.isNotEmpty() && cut.size < rec.stats().kept, "${cut.size}")
    }

    @Test fun theSizeLimitStopsTheRecordingAndSaysWhy() {
        val rec = TelemetryRecorder(clock = { now }, maxBytes = 2_000)
        rec.start(ByteArrayOutputStream(), hz = 60)
        // Contenu varié : la compression ne peut pas tout absorber.
        var id = 1
        repeat(2_000) {
            now += 16
            val p = TestPackets.plain(speedMps = 40f + it, packetId = id++, posZ = it * 1.37f, tyreTemps = floatArrayOf(it * .1f, it * .2f, it * .3f, it * .4f))
            rec.onFrame(p, now)
            if (it % 100 == 0) Thread.sleep(5)
        }
        val until = System.currentTimeMillis() + 5_000
        while (rec.running && System.currentTimeMillis() < until) Thread.sleep(20)
        val s = rec.stats()
        assertTrue(!s.running, s.lines.toString())
        assertTrue(s.stopReason?.startsWith("taille maximale") == true, s.lines.toString())
    }

    @Test fun aTraceReplaysOfflineThroughANewRaceController() {
        val sink = ByteArrayOutputStream()
        val rec = TelemetryRecorder(clock = { now })
        rec.start(sink, hz = 60)
        record(rec, 400, lap = { if (it < 200) 1 else 2 })
        rec.stop()
        val race = RaceController()
        val r = TelemetryTrace.replay(TelemetryTrace.read(ByteArrayInputStream(sink.toByteArray()))) { p, _ -> race.onPacket(p) }
        assertEquals(r.frames, r.decoded)
        assertEquals(2, race.snapshot.packet?.currentLap)
    }
}
