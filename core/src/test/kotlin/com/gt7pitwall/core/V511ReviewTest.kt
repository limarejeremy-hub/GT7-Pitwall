package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.race.PositionTracker
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.radio.TtsPreemption
import com.gt7pitwall.core.radio.TtsPreemption.Action
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** V511 — points de la revue de la PR #86. */
class V511ReviewTest {

    // ------------------------------------------------------------------ 1. Alertes critiques sur la synthèse Android

    private fun msg(text: String, p: Priority) = EngineerMessage(text, p, MessageCategory.RADIO)

    /** Énoncés que `TtsVoiceOutput` place pour un message : phrase puis silence de fin. */
    private fun place(t: TtsPreemption, m: EngineerMessage, id: String): List<String> =
        listOf("$id-phrase", "$id-fin").onEach { t.queued(it, m.priority) }

    @Test
    fun twoCriticalAlertsArrivingImmediatelyNeverFlushTheFirst() {
        val t = TtsPreemption()
        val fuel = msg("Box, box ! Carburant critique.", Priority.CRITICAL)
        val pit = msg("Arrêt obligatoire ce tour-ci.", Priority.CRITICAL)
        // Enchaînement exact de la revue : la 1re alerte est placée, `onStart` n'a PAS encore été appelé…
        assertEquals(Action.FLUSH, t.decide(fuel))
        t.flushed()
        place(t, fuel, "c1")
        // … et la 2e arrive aussitôt. L'ancienne règle (`current == null`) aurait demandé QUEUE_FLUSH et effacé la 1re.
        assertEquals(Action.ADD, t.decide(pit), "la 2e alerte passe après la 1re, sans QUEUE_FLUSH")
    }

    @Test
    fun aCriticalAlertStillPreemptsNonCriticalAnnouncements() {
        val t = TtsPreemption()
        val lap = msg("Tour 4 bouclé.", Priority.NORMAL)
        assertEquals(Action.ADD, t.decide(lap))
        place(t, lap, "n1")
        assertEquals(Action.FLUSH, t.decide(msg("Box, box !", Priority.CRITICAL)), "préemption des annonces non critiques conservée")
    }

    @Test
    fun aNonCriticalAnnouncementBetweenTwoAlertsWaitsForTheLastOne() {
        val t = TtsPreemption()
        val c1 = msg("Box, box !", Priority.CRITICAL)
        val n = msg("P6.", Priority.NORMAL)
        val c2 = msg("Arrêt obligatoire.", Priority.CRITICAL)
        assertEquals(Action.FLUSH, t.decide(c1)); t.flushed()
        val ids1 = place(t, c1, "c1")
        assertEquals(Action.DEFER, t.decide(n), "jamais intercalée entre deux alertes")
        assertEquals(Action.ADD, t.decide(c2))
        val ids2 = place(t, c2, "c2")
        ids1.forEach { assertTrue(t.finished(it).isEmpty(), "une alerte est encore en file") }
        val released = ids2.flatMap { t.finished(it) }
        assertEquals(listOf(n), released, "rendue après la dernière alerte")
        assertEquals(0, t.deferredCount)
    }

    @Test
    fun stopForgetsEverything() {
        val t = TtsPreemption()
        val c1 = msg("Box, box !", Priority.CRITICAL)
        t.decide(c1); t.flushed(); place(t, c1, "c1")
        t.decide(msg("P6.", Priority.NORMAL))
        t.stopped()
        assertEquals(0, t.deferredCount)
        assertEquals(Action.FLUSH, t.decide(msg("Box !", Priority.CRITICAL)))
    }

    // ------------------------------------------------------------------ 3. Position invalide pendant la course

    private var id = 0

    private data class Seg(val ms: Long, val lap: Int, val total: Int, val pos: Int, val cars: Int)

    private fun packet(s: Seg): TelemetryPacket {
        val plain = TestPackets.plain(lap = s.lap, totalLaps = s.total, flags = TelemetryPacket.FLAG_ON_TRACK, packetId = ++id, speedMps = if (s.lap >= 1) 50f else 0f)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x84, s.pos.toShort())
            putShort(0x86, s.cars.toShort())
        }
        return TelemetryParser.parseDecrypted(plain)!!
    }

    private class Run(val c: RaceController, val said: MutableList<EngineerMessage> = mutableListOf()) {
        fun texts() = said.filter { it.category != MessageCategory.DRIVER }.map { it.text }
    }

    /** 50 paquets/s, en passant par l'instant exact de chaque segment ; les paquets ne s'arrêtent jamais. */
    private fun replay(segs: List<Seg>, untilMs: Long, onTime: (Long, Run) -> Unit = { _, _ -> }): Run {
        var now = 0L
        val c = RaceController(storage = FakeStorage(), clock = { now })
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        val r = Run(c)
        var t = segs.first().ms
        var i = 0
        while (t <= untilMs) {
            while (i + 1 < segs.size && segs[i + 1].ms <= t) i++
            now = t
            r.said += c.onPacket(packet(segs[i]))
            onTime(now, r)
            val next = segs.getOrNull(i + 1)?.ms
            t = if (next != null && next < t + 20) next else t + 20
        }
        return r
    }

    private fun ask(r: Run): String = r.c.engineer.answer(DriverQuery.POSITION, r.c.snapshot, r.c.fuel).text

    @Test
    fun minusOneDuringTheRaceNeverPresentsTheOldRankAsCurrent() {
        val short = mutableMapOf<String, String>()
        val verbose = mutableMapOf<String, String>()
        val r = replay(
            listOf(
                Seg(0, 0, 5, 8, 14),
                Seg(10_000, 1, 5, 8, 14),
                Seg(30_000, 2, 5, -1, 14), // 0x84 = -1, paquets toujours reçus
                Seg(33_000, 2, 5, 7, 14),
            ),
            untilMs = 40_000,
        ) { now, run ->
            fun both(key: String) {
                run.c.engineer.shortPositionAnswer = true
                short[key] = ask(run)
                run.c.engineer.shortPositionAnswer = false
                verbose[key] = ask(run)
            }
            when (now) {
                in 29_000 until 29_020 -> both("avant")
                in 30_020 until 30_040 -> both("-1 depuis 20 ms")
                in 32_000 until 32_020 -> both("-1 depuis 2 s")
                in 33_500 until 33_520 -> both("retour, pas encore stable")
                in 36_000 until 36_020 -> both("retour stable")
            }
        }
        assertEquals("Huitième sur quatorze.", short["avant"])
        assertEquals("Tu es P8 sur 14.", verbose["avant"])
        for (k in listOf("-1 depuis 20 ms", "-1 depuis 2 s")) {
            assertEquals("Position indisponible.", short[k], k)
            assertFalse(verbose[k]!!.contains("P8"), "« $k » : jamais l'ancien P8 comme actuel : ${verbose[k]}")
            assertTrue(verbose[k]!!.startsWith("Je ne reçois plus ta position de GT7"), verbose[k])
        }
        // Retour d'un rang cohérent : le nouveau rang est dit une fois stable (2 s), jamais l'ancien entre-temps.
        assertEquals("Position indisponible.", short["retour, pas encore stable"])
        assertEquals("Septième sur quatorze.", short["retour stable"])
        assertFalse(r.texts().any { it == "P8." }, r.texts().toString())
    }

    @Test
    fun anInvalidFirstFinishPacketUsesTheLastRankJustBeforeTheLine() {
        val line = 60_000L
        val r = replay(
            listOf(
                Seg(0, 0, 3, 6, 14),
                Seg(10_000, 1, 3, 6, 14),
                Seg(30_000, 2, 3, 5, 14),
                Seg(45_000, 3, 3, 5, 14),
                Seg(line, 4, 3, -1, 14), // paquet de la ligne : rang incohérent
                Seg(line + 300, 4, 3, 5, 14),
            ),
            untilMs = line + 35_000,
        )
        val m = r.c.engineer.measured
        assertEquals(5 to 14, m.linePosition to m.lineCars)
        assertEquals(PositionTracker.LAST_VALID_BEFORE_LINE, r.c.positionTracker.lineSource)
        assertEquals(5, m.finalPosition)
        assertTrue(m.postFinishChanges.isEmpty(), "le rang cohérent qui suit est le même : aucune révision")
        assertTrue(m.finalConsolidated)
        val said = r.texts()
        assertEquals(1, said.count { it.startsWith("Course terminée") && "Tu passes la ligne P5 sur 14." in it }, said.toString())
        assertTrue(r.c.journal.recent(2000).any { "paquet de la ligne sans rang cohérent" in it.text })
    }

    @Test
    fun anInvalidLinePacketWithoutRecentRankTakesTheFirstValidRankAfterTheLine() {
        val line = 60_000L
        // 0x84 = -1 depuis 3 s avant la ligne (aucun rang récent, place établie perdue) ; P9/14 arrive 300 ms après.
        val r = replay(
            listOf(
                Seg(0, 0, 3, 6, 14),
                Seg(10_000, 1, 3, 6, 14),
                Seg(52_000, 3, 3, -1, 14),
                Seg(line, 4, 3, -1, 14),
                Seg(line + 300, 4, 3, 9, 14),
            ),
            untilMs = line + 35_000,
        )
        val m = r.c.engineer.measured
        assertEquals(9 to 14, m.linePosition to m.lineCars)
        assertEquals(PositionTracker.FIRST_VALID_AFTER_LINE, r.c.positionTracker.lineSource)
        assertTrue(m.finalConsolidated)
        assertEquals(1, r.texts().count { it.startsWith("Course terminée") }, r.texts().toString())
    }
}
