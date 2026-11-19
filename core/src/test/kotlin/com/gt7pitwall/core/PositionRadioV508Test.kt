package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.FrenchFormat
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.PositionTracker
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.telemetry.ConnectionStatus
import com.gt7pitwall.core.telemetry.FormatLog
import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.PositionProbe
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryRecorder
import com.gt7pitwall.core.telemetry.TelemetryTrace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * V508 — « Position ? » et classement à l'arrivée, rejoués à partir de la capture Course Sport du pilote.
 *
 * Données brutes (trace GT7PWTR1) de cette course : NON disponibles dans le dépôt. Les transitions de l'export texte
 * sont donc écrites dans une trace brute synthétique (paquets format C, 20 Hz) puis relues par le moteur de replay
 * ([TelemetryTrace.replay]) et son horloge virtuelle, comme une vraie trace.
 *
 * Valeurs issues de l'export (marquées [export]) : 19:32:10.746 P10/15 au départ ; 19:33:44.241 P9/15 à la fin du
 * tour 1 ; 19:34:43.717 P8/14 (0x86 15 → 14, rang inchangé) ; 19:42:55.781 « 8/7 » P5/14 ; puis « 8/0 ».
 * Tout le reste (heures des autres passages de ligne, instant du passage P9 → P8, durée avant « 8/0 ») est un GABARIT
 * de test, pas une mesure : la relecture de la trace brute réelle devra le confirmer.
 */
class PositionRadioV508Test {

    private val onTrack = TelemetryPacket.FLAG_ON_TRACK
    private val loading = TelemetryPacket.FLAG_LOADING

    private data class Seg(val at: Long, val lap: Int, val total: Int, val pos: Int, val cars: Int, val flags: Int)

    private fun at(hms: String): Long {
        val (h, m, rest) = hms.split(":")
        val (s, ms) = rest.split(".")
        return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
    }

    /** La course du pilote ; [afterFlagMs] : délai entre la ligne d'arrivée et le « 8/0 ». */
    private fun race(afterFlagMs: Long = 2_200): List<Seg> {
        val flag = at("19:42:55.781")
        return listOf(
            Seg(at("19:31:40.000"), -1, 0, 12, 12, onTrack), // gabarit : matchmaking (0x84 compte les voitures chargées)
            Seg(at("19:31:55.000"), 0, 7, 1, 15, loading), // gabarit : chargement de la grille
            Seg(at("19:32:10.746"), 0, 7, 10, 15, onTrack), // [export] P10/15 au départ
            Seg(at("19:32:20.000"), 1, 7, 10, 15, onTrack), // gabarit : feu vert
            Seg(at("19:33:44.241"), 2, 7, 9, 15, onTrack), // [export] P9/15, fin du tour 1
            Seg(at("19:34:10.000"), 2, 7, 8, 15, onTrack), // gabarit : passage P9 → P8 (instant inconnu)
            Seg(at("19:34:43.717"), 2, 7, 8, 14, onTrack), // [export] P8/14 : une voiture quitte la course
            Seg(at("19:35:10.000"), 3, 7, 8, 14, onTrack), // gabarit : passages de ligne des tours 2 à 6
            Seg(at("19:36:32.000"), 4, 7, 8, 14, onTrack),
            Seg(at("19:37:54.000"), 5, 7, 8, 14, onTrack),
            Seg(at("19:39:16.000"), 6, 7, 8, 14, onTrack),
            Seg(at("19:40:38.000"), 7, 7, 8, 14, onTrack),
            // [export] « 8/7 » P5/14. Pire cas : la remontée P8 → P5 n'apparaît QUE sur le paquet de la ligne.
            Seg(flag, 8, 7, 5, 14, onTrack),
            Seg(flag + afterFlagMs, 8, 0, 5, 14, onTrack), // [export] « 8/0 » (délai : gabarit)
            Seg(flag + afterFlagMs + 8_000, 8, 0, 5, 14, onTrack),
        )
    }

    private class Run(val c: RaceController, val clock: TelemetryTrace.VirtualClock) {
        val said = mutableListOf<EngineerMessage>()
        val changes = mutableListOf<Pair<Long, List<PositionTracker.Change>>>()
        val probe = PositionProbe()
        val shadow = PositionTracker()
        val formats = mutableMapOf<String, Int>()
        fun texts() = said.filter { it.category != MessageCategory.DRIVER }.map { it.text }
    }

    /** Écrit les segments en trace brute (20 paquets/s, format C), puis la relit avec le moteur de replay. */
    private fun replay(segs: List<Seg>, until: Long = segs.last().at + 2_000, run: Run? = null, onTime: (Long, Run) -> Unit = { _, _ -> }): Run {
        val start = segs.first().at
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            TelemetryTrace.writeHeader(out)
            var t = start
            var i = 0
            var id = (run?.c?.snapshot?.packet?.packetId ?: 0) + 1
            while (t < until) {
                while (i + 1 < segs.size && segs[i + 1].at <= t) i++
                val s = segs[i]
                val plain = TestPackets.plain(
                    lap = s.lap, totalLaps = s.total, flags = s.flags, packetId = id++,
                    speedMps = if (s.lap >= 1) 50f else 0f, size = PacketFormat.C.size,
                )
                ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putShort(0x84, s.pos.toShort())
                    putShort(0x86, s.cars.toShort())
                }
                TelemetryTrace.writeFrame(out, (t - start).toInt(), plain)
                // Les heures de l'export tombent entre deux paquets à 20 Hz : la transition exacte est rejouée aussi.
                val next = minOf(t + 50, segs.getOrNull(i + 1)?.at ?: Long.MAX_VALUE)
                t = next
            }
        }
        val clock = run?.clock ?: TelemetryTrace.VirtualClock(start)
        val r = run ?: Run(RaceController(storage = FakeStorage(), clock = clock::now), clock).also {
            it.c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        }
        val result = TelemetryTrace.replay(ByteArrayInputStream(bytes.toByteArray()), clock, start) { p ->
            val now = clock.nowMs
            r.probe.onPacket(p, now)
            r.changes += now to r.shadow.onPacket(p, now)
            r.said += r.c.onPacket(p)
            onTime(now, r)
        }
        result.formats.forEach { (k, v) -> r.formats.merge(k, v, Int::plus) }
        return r
    }

    @Test
    fun spokenOrdinalsAndCardinals() {
        assertEquals("premier", FrenchFormat.ordinalWords(1))
        assertEquals("cinquième", FrenchFormat.ordinalWords(5))
        assertEquals("neuvième", FrenchFormat.ordinalWords(9))
        assertEquals("dixième", FrenchFormat.ordinalWords(10))
        assertEquals("quatorzième", FrenchFormat.ordinalWords(14))
        assertEquals("dix-septième", FrenchFormat.ordinalWords(17))
        assertEquals("vingt et unième", FrenchFormat.ordinalWords(21))
        assertEquals("trente-quatrième", FrenchFormat.ordinalWords(34))
        assertEquals("quarantième", FrenchFormat.ordinalWords(40))
        assertEquals("quinze", FrenchFormat.cardinalWords(15))
        assertEquals("vingt et un", FrenchFormat.cardinalWords(21))
        assertEquals("trente-deux", FrenchFormat.cardinalWords(32))
    }

    @Test
    fun sportRaceFromTheCaptureIsFollowedLapByLapAndFrozenAtTheFlag() {
        val answers = mutableMapOf<String, String>()
        var verbose: String? = null
        fun ask(r: Run) = r.c.engineer.answer(DriverQuery.POSITION, r.c.snapshot, r.c.fuel).text
        val r = replay(race()) { now, run ->
            run.c.engineer.shortPositionAnswer = true
            when (now) {
                in at("19:31:45.000")..at("19:31:45.049") -> answers["matchmaking"] = ask(run)
                in at("19:32:13.000")..at("19:32:13.049") -> answers["départ"] = ask(run)
                in at("19:33:47.000")..at("19:33:47.049") -> answers["tour 2"] = ask(run)
                in at("19:34:46.000")..at("19:34:46.049") -> answers["15 → 14"] = ask(run)
                in at("19:42:56.000")..at("19:42:56.049") -> answers["arrivée"] = ask(run)
                in at("19:43:00.000")..at("19:43:00.049") -> answers["8/0"] = ask(run)
            }
            if (now in at("19:34:46.000")..at("19:34:46.049")) {
                run.c.engineer.shortPositionAnswer = false
                verbose = ask(run)
                run.c.engineer.shortPositionAnswer = true
            }
        }
        assertEquals(mapOf("C" to r.formats.values.sum()), r.formats, "trace relue en format C")

        // « Position ? » : jamais pendant le matchmaking ; rang de la session active ensuite ; rien après l'arrivée.
        assertEquals("Position indisponible.", answers["matchmaking"])
        assertEquals("Dixième sur quinze.", answers["départ"])
        assertEquals("Neuvième sur quinze.", answers["tour 2"])
        assertEquals("Huitième sur quatorze.", answers["15 → 14"])
        assertEquals("Position indisponible.", answers["arrivée"], "course finie : plus de rang live")
        assertEquals("Position indisponible.", answers["8/0"])
        assertEquals("Tu es P8 sur 14 (15 au départ).", verbose, "réponse habituelle inchangée sans l'option")

        // Départ et passages de ligne.
        val m = r.c.engineer.measured
        assertEquals(10, m.startPosition)
        assertEquals(15, m.startCars)
        val laps = r.c.positionTracker.lapPositions
        val lap1 = laps.first { it.lap == 1 }
        assertEquals(9 to 15, lap1.position to lap1.cars, "P9/15 à la fin du tour 1")
        assertEquals(at("19:33:44.241"), lap1.timeMs)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), laps.map { it.lap })
        assertEquals(8 to 14, laps.first { it.lap == 2 }.let { it.position to it.cars })
        assertEquals(5 to 14, laps.first { it.lap == 7 }.let { it.position to it.cars }, "tour 7 = ligne d'arrivée")

        // 0x86 15 → 14 en restant P8 : aucun changement de rang, ni sur ce paquet ni ensuite ; un seul changement de peloton.
        val exact = r.changes.first { it.first == at("19:34:43.717") }
        assertTrue(exact.second.none { it is PositionTracker.Change.Moved }, exact.toString())
        val around = r.changes.filter { it.first in at("19:34:43.717")..at("19:35:09.000") }.flatMap { it.second }
        assertEquals(listOf(PositionTracker.Change.CarsChanged(15, 14)), around, around.toString())

        // Arrivée : P5/14 figé au paquet de la ligne ; un seul message d'arrivée malgré le « 8/0 ».
        assertEquals(5, m.finalPosition)
        assertEquals(14, m.finalCars)
        val finished = r.changes.flatMap { it.second }.filterIsInstance<PositionTracker.Change.Finished>()
        assertEquals(listOf(PositionTracker.Change.Finished(5, 14)), finished)
        val said = r.texts()
        assertEquals(1, said.count { it.startsWith("Course terminée") }, said.toString())
        assertEquals(1, said.count { "P5 sur 14" in it }, said.toString())
        assertTrue(said.any { it.startsWith("Course terminée") && "Tu termines P5 sur 14." in it }, said.toString())
        assertFalse(said.any { "Ta position à l'arrivée" in it }, said.toString())
        assertFalse(said.any { "Nouvelle session" in it }, "le 8/0 ne rouvre pas de session : $said")
        assertTrue(r.c.engineer.raceOver)

        // Diagnostic : le « 8/0 » n'est plus « en course », et chaque ligne garde son numéro de paquet et son format.
        val probeAfter = r.probe.summary(recent = 100).events
        val flip = probeAfter.last { it.totalLaps == 0 && it.lap == 8 }
        assertEquals(PositionProbe.AFTER_FINISH, flip.state)
        assertTrue(probeAfter.none { it.lap == 8 && it.totalLaps == 0 && it.state == "en course" })
        assertTrue(probeAfter.all { it.format == "C" && it.packetId > 0 }, probeAfter.toString())
    }

    @Test
    fun eightSlashZeroRightAfterTheLineStillGivesOneFinishAndNoReopening() {
        val r = replay(race(afterFlagMs = 200))
        assertEquals(5, r.c.engineer.measured.finalPosition)
        assertEquals(14, r.c.engineer.measured.finalCars)
        val said = r.texts()
        assertEquals(1, said.count { it.startsWith("Course terminée") }, said.toString())
        assertEquals(1, said.count { "P5 sur 14" in it }, said.toString())
        assertEquals(1, r.changes.flatMap { it.second }.count { it is PositionTracker.Change.Finished })
    }

    @Test
    fun aNewGridStartsWithItsOwnStateAndNothingIsInherited() {
        val r = replay(race())
        r.c.engineer.shortPositionAnswer = true
        val t0 = r.clock.nowMs + 1_000
        val answers = mutableMapOf<String, String>()
        val next = listOf(
            Seg(t0, -1, 0, 9, 9, onTrack), // menu / matchmaking suivant
            Seg(t0 + 10_000, 0, 5, 1, 12, loading),
            Seg(t0 + 12_000, 0, 5, 3, 12, onTrack), // nouvelle grille : P3/12
            Seg(t0 + 20_000, 1, 5, 3, 12, onTrack),
        )
        replay(next, until = t0 + 25_000, run = r) { now, run ->
            when (now) {
                in t0 + 5_000 until t0 + 5_050 -> answers["menu"] = run.c.engineer.shortPosition()
                in t0 + 13_000 until t0 + 13_050 -> answers["grille < 2 s"] = run.c.engineer.shortPosition()
                in t0 + 15_000 until t0 + 15_050 -> answers["grille"] = run.c.engineer.shortPosition()
            }
        }
        assertEquals("Position indisponible.", answers["menu"])
        assertEquals("Position indisponible.", answers["grille < 2 s"], "rien hérité de P5/14 avant que la grille soit stable")
        assertEquals("Troisième sur douze.", answers["grille"])
        val m = r.c.engineer.measured
        assertNull(m.finalPosition, "le classement de la course précédente n'est pas repris")
        assertEquals(3, m.startPosition)
        assertTrue(r.c.positionTracker.lapPositions.none { it.position == 5 }, r.c.positionTracker.lapPositions.toString())
    }

    @Test
    fun udpFreezeAndMenuReturnMidRaceGiveNoLivePosition() {
        val segs = race().take(5) // jusqu'à P9/15 au tour 2
        val r = replay(segs, until = at("19:33:50.000"))
        r.c.engineer.shortPositionAnswer = true
        assertEquals("Neuvième sur quinze.", r.c.engineer.shortPosition())
        // Gel UDP : plus aucun paquet ; la question arrive 1,6 s plus tard.
        r.clock.nowMs += 1_600
        assertEquals("Position indisponible.", r.c.engineer.shortPosition())
        // Reprise du flux, puis retour au menu en pleine course (tours inconnus).
        val t = r.clock.nowMs
        replay(listOf(Seg(t, 2, 7, 9, 15, onTrack), Seg(t + 3_000, 2, 0, 9, 15, onTrack)), until = t + 4_000, run = r)
        assertEquals("Position indisponible.", r.c.engineer.shortPosition())
    }

    @Test
    fun formatLogSeparatesHeartbeatReceivedFormatAndResponse() {
        val log = FormatLog()
        val a = PacketFormat.A
        val c = PacketFormat.C
        val t = PacketFormat.TILDE
        // Capture Sport : C et ~ notés sans réponse pendant la négociation, A reçu, puis 90 482 paquets C alors que le
        // dernier heartbeat envoyé est A.
        log.onStatus(ConnectionStatus.Receiving("x", 60, a, requested = a, unanswered = listOf(c, t)), "Course Sport B", 0)
        repeat(100) { log.onPacket(it * 16L, packetId = 1 + it) }
        log.onStatus(ConnectionStatus.Receiving("x", 60, c, requested = a, unanswered = listOf(c, t)), "Course Sport B", 2_000)
        repeat(90_482) { log.onPacket(2_000L + it * 16L, packetId = 101 + it) }
        val s = log.summary().single()
        assertTrue(s.startsWith("Course Sport B — A (296 octets) : 100 paquets"), s)
        assertTrue("C (368 octets) : 90482 paquets" in s, s)
        assertFalse("sans réponse : C" in s, "C reçu : jamais « sans réponse » — $s")
        assertTrue("sans réponse : ~ (344 octets)" in s, s)
        assertTrue("reçu sans être le dernier heartbeat : C (368 octets)" in s, s)
        val e = log.export()
        assertTrue("dernier heartbeat envoyé : A (296 octets) ; reçu : C (368 octets)" in e.replace(',', ';'), e)
        assertTrue(";A;C;EARLIER_HEARTBEAT;90482;101;90582" in e, e)
        // Un événement de position (numéro de paquet GT7) se rattache au format reçu à ce moment-là.
        assertEquals(a, log.receivedAt(packetId = 50))
        assertEquals(c, log.receivedAt(packetId = 40_000))
        assertNull(log.receivedAt(packetId = 999_999))
    }
}
