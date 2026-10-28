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
        val replay = TelemetryTrace.replay(ByteArrayInputStream(sink.toByteArray()))
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
        assertEquals(setOf("C"), TelemetryTrace.replay(ByteArrayInputStream(sink.toByteArray())).formats.keys)
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
        val r = TelemetryTrace.replay(ByteArrayInputStream(sink.toByteArray())) { p -> race.onPacket(p) }
        assertEquals(r.frames, r.decoded)
        assertEquals(2, race.snapshot.packet?.currentLap)
    }

    // ------------------------------------------------ Revue PR #44 : relecture en flux, horloge virtuelle, longue durée

    @Test fun liveAndReplayGiveTheSameCalculationsWithTheVirtualClock() {
        var liveNow = 1_793_000_000_000L
        val sink = ByteArrayOutputStream()
        val out = java.io.DataOutputStream(java.util.zip.GZIPOutputStream(sink))
        TelemetryTrace.writeHeader(out)
        val start = liveNow
        val sim = RaceSim(RaceController(clock = { liveNow }), totalLaps = 6, perLap = 4f)
        sim.tap = { plain ->
            liveNow += 16
            TelemetryTrace.writeFrame(out, (liveNow - start).toInt(), plain)
        }
        sim.start()
        sim.driveLaps(6)
        out.close()
        val live = sim.controller

        val clock = TelemetryTrace.VirtualClock(start)
        val replayed = RaceController(clock = clock::now)
        val r = TelemetryTrace.replay(ByteArrayInputStream(sink.toByteArray()), clock) { p -> replayed.onPacket(p) }
        assertEquals(r.frames, r.decoded)
        assertEquals(liveNow, clock.nowMs, "l'horloge virtuelle finit à l'heure du dernier paquet")

        fun laps(c: RaceController) = c.snapshot.laps.map { Triple(it.lapNumber, it.timeMs, it.fuelUsed) }
        assertTrue(laps(live).size >= 5, laps(live).toString())
        assertEquals(laps(live), laps(replayed))
        assertEquals(live.fuel?.perLap, replayed.fuel?.perLap)
        assertEquals(live.currentRecord()?.id, replayed.currentRecord()?.id, "identifiant de session daté par l'horloge")
        assertEquals(live.journal.recent().map { it.timeMs to it.text }, replayed.journal.recent().map { it.timeMs to it.text })
    }

    /** ~4 h à 20 Hz, format A, écrit directement au format de trace (sans file d'attente). */
    private fun longTrace(frames: Int, hz: Int, lapEvery: Int): java.io.File {
        val f = kotlin.io.path.createTempFile("trace", ".gt7trace").toFile().apply { deleteOnExit() }
        java.io.DataOutputStream(java.util.zip.GZIPOutputStream(f.outputStream().buffered(64 * 1024))).use { out ->
            TelemetryTrace.writeHeader(out)
            for (i in 0 until frames) {
                val lap = 1 + i / lapEvery
                val plain = TestPackets.plain(
                    lap = lap, totalLaps = 0, speedMps = 50f, packetId = i * (60 / hz) + 1,
                    lastLapMs = if (lap > 1) lapEvery * 1000 / hz else -1, posX = (i % lapEvery) * 2.5f,
                )
                TelemetryTrace.writeFrame(out, i * 1000 / hz, plain)
            }
        }
        return f
    }

    @Test fun aFourHourTraceReplaysInAStreamWithoutLoadingItInMemory() {
        val frames = 4 * 3_600 * 20 // 288 000 échantillons, ~85 Mo bruts une fois décompressés
        val file = longTrace(frames, hz = 20, lapEvery = 1_800)
        val rt = Runtime.getRuntime()
        fun used(): Long { System.gc(); return rt.totalMemory() - rt.freeMemory() }
        val before = used()
        var peak = 0L
        var seen = 0
        val r = file.inputStream().buffered().use {
            TelemetryTrace.replay(it) { _ ->
                if (++seen % 48_000 == 0) peak = maxOf(peak, used() - before)
            }
        }
        assertEquals(frames, r.frames)
        assertEquals(frames, r.decoded)
        assertEquals(0, r.gapsOver500Ms)
        assertEquals((frames - 1) * 50, r.durationMs)
        assertTrue(peak < 32L * 1_048_576, "mémoire retenue pendant la relecture : ${peak / 1_048_576} Mo")
    }

    @Test fun anHourAtSixtyHertzReplaysThroughARaceControllerOnRecordedTime() {
        val frames = 3_600 * 60 // 216 000 paquets
        val file = longTrace(frames, hz = 60, lapEvery = 5_400) // un tour toutes les 90 s
        val clock = TelemetryTrace.VirtualClock(1_793_000_000_000L)
        val race = RaceController(clock = clock::now)
        val r = file.inputStream().buffered().use { TelemetryTrace.replay(it, clock) { p -> race.onPacket(p) } }
        assertEquals(frames, r.decoded)
        // Heure virtuelle = heure enregistrée du dernier paquet, pas la durée (courte) de la relecture.
        assertEquals(1_793_000_000_000L + (frames - 1) * 1000L / 60, clock.nowMs)
        assertEquals(40, race.snapshot.packet?.currentLap)
        assertTrue(race.snapshot.laps.size >= 38, "${race.snapshot.laps.size} tours")
    }
}
