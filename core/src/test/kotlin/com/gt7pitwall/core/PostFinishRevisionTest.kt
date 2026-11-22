package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.PositionTracker
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * V508 révisé — classement révisé par GT7 APRÈS la ligne d'arrivée.
 *
 * Fait réel (trace du 22/11, témoignage du pilote) : P12/16 sur le paquet de la ligne, puis P13/16 567 ms plus tard ;
 * le pilote avait reçu une pénalité de 2 s appliquée après la ligne. La trace brute n'est pas dans le dépôt : seules ces
 * deux valeurs et ce délai viennent du réel ; les tours avant l'arrivée sont un gabarit.
 */
class PostFinishRevisionTest {
    private val onTrack = TelemetryPacket.FLAG_ON_TRACK
    private val loading = TelemetryPacket.FLAG_LOADING
    private var id = 0

    private data class Seg(val ms: Long, val lap: Int, val total: Int, val pos: Int, val cars: Int, val flags: Int = TelemetryPacket.FLAG_ON_TRACK)

    private class Run(val c: RaceController, val storage: FakeStorage) {
        val said = mutableListOf<EngineerMessage>()
        val changes = mutableListOf<Pair<Long, PositionTracker.Change>>()
        fun texts() = said.filter { it.category != MessageCategory.DRIVER }.map { it.text }
    }

    private fun packet(s: Seg): TelemetryPacket {
        val plain = TestPackets.plain(lap = s.lap, totalLaps = s.total, flags = s.flags, packetId = ++id, speedMps = if (s.lap >= 1) 50f else 0f)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x84, s.pos.toShort())
            putShort(0x86, s.cars.toShort())
        }
        return TelemetryParser.parseDecrypted(plain)!!
    }

    /** Rejoue à 50 paquets/s (20 ms), en passant exactement par l'instant de chaque segment. */
    private fun replay(segs: List<Seg>, untilMs: Long, onTime: (Long, Run) -> Unit = { _, _ -> }): Run {
        var now = 0L
        val storage = FakeStorage()
        val c = RaceController(storage = storage, clock = { now })
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        val r = Run(c, storage)
        var t = segs.first().ms
        var i = 0
        while (t <= untilMs) {
            while (i + 1 < segs.size && segs[i + 1].ms <= t) i++
            now = t
            val before = c.positionTracker.snapshot(now)
            r.said += c.onPacket(packet(segs[i]))
            val after = c.positionTracker.snapshot(now)
            if (after.postFinishChanges.size > before.postFinishChanges.size) r.changes += now to PositionTracker.Change.Revised(after.postFinishChanges.last())
            if (after.finalConsolidated && !before.finalConsolidated) {
                r.changes += now to PositionTracker.Change.Consolidated(after.finalPosition!!, after.finalCars!!, after.linePosition!!, after.lineCars!!, "")
            }
            onTime(now, r)
            val next = segs.getOrNull(i + 1)?.ms
            t = if (next != null && next < t + 20) next else t + 20
        }
        return r
    }

    /** Course de 3 tours (gabarit), puis la fin réelle du 22/11 : ligne P12/16, P13/16 à +567 ms. */
    private fun race22(line: Long = 300_000, after: List<Seg> = listOf(Seg(line + 567, 4, 3, 13, 16))): List<Seg> = listOf(
        Seg(0, 0, 3, 12, 16),
        Seg(10_000, 1, 3, 12, 16),
        Seg(100_000, 2, 3, 12, 16),
        Seg(200_000, 3, 3, 12, 16),
        Seg(line, 4, 3, 12, 16),
    ) + after

    @Test
    fun november22LineP12ThenRevisedP13IsArchivedAsP13() {
        val line = 300_000L
        val answers = mutableMapOf<String, String>()
        val r = replay(race22(line), untilMs = line + 40_000) { now, run ->
            fun ask() = run.c.engineer.answer(DriverQuery.POSITION, run.c.snapshot, run.c.fuel).text
            when (now) {
                in line + 200 until line + 220 -> answers["ligne"] = ask()
                in line + 5_000 until line + 5_020 -> answers["consolidation"] = ask()
                in line + 35_000 until line + 35_020 -> answers["consolidé"] = ask()
            }
        }
        val m = r.c.engineer.measured
        // Trois valeurs distinctes : ligne, après la ligne, archivée.
        assertEquals(12 to 16, m.linePosition to m.lineCars, "position au franchissement de la ligne")
        assertEquals(13 to 16, m.finalPosition to m.finalCars, "position après l'arrivée")
        assertTrue(m.finalConsolidated)
        assertEquals(listOf(PositionTracker.PostFinishChange(12, 16, 13, 16, 567)), m.postFinishChanges, "historique des changements")
        // Révision retenue après 0,5 s de stabilité ; consolidation 30 s après la révision.
        val revisedAt = r.changes.first { it.second is PositionTracker.Change.Revised }.first
        assertEquals(line + 567 + 500, revisedAt)
        val consolidatedAt = r.changes.first { it.second is PositionTracker.Change.Consolidated }.first
        assertEquals(revisedAt + 30_000, consolidatedAt)

        // Archive (relue en JSON) : résultat P13/16, ligne P12/16, historique gardé.
        val rec = r.storage.sessions.single()
        assertEquals(13, rec.finishPosition)
        assertEquals(16, rec.finishCars)
        assertEquals(12, rec.lineFinishPosition)
        assertEquals(16, rec.lineFinishCars)
        assertTrue(rec.finishConsolidated)
        assertEquals(1, rec.postFinishChanges.size)

        // Radio : la ligne une fois, la révision une fois, un seul bilan ; aucune cause attribuée.
        val said = r.texts()
        assertEquals(1, said.count { it.startsWith("Course terminée") }, said.toString())
        assertEquals(1, said.count { "Tu passes la ligne P12 sur 16." in it }, said.toString())
        assertEquals(1, said.count { it == "Classement GT7 révisé après l'arrivée : P13 sur 16." }, said.toString())
        assertFalse(said.any { "Tu termines P12" in it }, "jamais « tu termines P12 » puis P13 : $said")
        assertEquals(1, r.said.count { it.dedupKey?.startsWith("bilan:") == true }, "un seul bilan")
        val bilanAt = r.said.indexOfFirst { it.dedupKey?.startsWith("bilan:") == true }
        val revisionAt = r.said.indexOfFirst { it.text.startsWith("Classement GT7 révisé") }
        assertTrue(bilanAt > revisionAt, "le bilan suit le classement consolidé")
        assertFalse((said + r.c.journal.recent(2000).map { it.text }).any { "pénalit" in it.lowercase() }, "aucune pénalité attribuée")

        assertEquals("Tu as passé la ligne P12 sur 16. Classement GT7 pas encore consolidé.", answers["ligne"])
        assertEquals("Tu as passé la ligne P12 sur 16 ; classement GT7 provisoire : P13 sur 16.", answers["consolidation"])
        assertEquals("Tu termines P13 sur 16, d'après GT7. P12 à la ligne, classement révisé ensuite.", answers["consolidé"])
    }

    @Test
    fun eachRevisionRestartsTheConsolidationDelay() {
        val line = 300_000L
        val r = replay(
            race22(line, after = listOf(Seg(line + 567, 4, 3, 13, 16), Seg(line + 20_000, 4, 3, 14, 16))),
            untilMs = line + 60_000,
        )
        val m = r.c.engineer.measured
        assertEquals(12, m.linePosition)
        assertEquals(14, m.finalPosition)
        assertEquals(listOf(13, 14), m.postFinishChanges.map { it.toPosition })
        val consolidatedAt = r.changes.first { it.second is PositionTracker.Change.Consolidated }.first
        assertEquals(line + 20_000 + 500 + 30_000, consolidatedAt, "délai repris à la deuxième révision")
        val said = r.texts()
        assertEquals(listOf("Classement GT7 révisé après l'arrivée : P14 sur 16."), said.filter { it.startsWith("Classement GT7 révisé") }, "dit une fois, avec le classement consolidé")
    }

    @Test
    fun aFlickerShorterThanHalfASecondIsNotARevision() {
        val line = 300_000L
        val r = replay(race22(line, after = listOf(Seg(line + 567, 4, 3, 13, 16), Seg(line + 867, 4, 3, 12, 16))), untilMs = line + 35_000)
        val m = r.c.engineer.measured
        assertEquals(12, m.finalPosition)
        assertTrue(m.postFinishChanges.isEmpty())
        assertTrue(m.finalConsolidated)
        assertFalse(r.texts().any { it.startsWith("Classement GT7 révisé") })
    }

    @Test
    fun eightSlashZeroDoesNotStopTheConsolidationAndLobbyValuesEndIt() {
        val line = 300_000L
        // « 4/0 » (tours remis à 0) juste après la ligne : la révision qui suit compte encore ; puis le salon (16 → 18 voitures).
        val r = replay(
            race22(line, after = listOf(Seg(line + 200, 4, 0, 12, 16), Seg(line + 567, 4, 0, 13, 16), Seg(line + 6_000, 4, 0, 18, 18))),
            untilMs = line + 10_000,
        )
        val m = r.c.engineer.measured
        assertEquals(13 to 16, m.finalPosition to m.finalCars)
        assertTrue(m.finalConsolidated, "le salon termine la consolidation")
        val consolidatedAt = r.changes.first { it.second is PositionTracker.Change.Consolidated }.first
        assertEquals(line + 6_000, consolidatedAt)
        assertEquals(13, r.storage.sessions.single().finishPosition)
    }

    @Test
    fun aNewRaceDuringConsolidationArchivesTheRevisedResultOnce() {
        val line = 300_000L
        val r = replay(
            race22(line, after = listOf(Seg(line + 567, 4, 3, 13, 16), Seg(line + 5_000, 0, 0, 1, 12, loading), Seg(line + 7_000, 0, 5, 6, 12))),
            untilMs = line + 15_000,
        )
        val finished = r.storage.sessions.filter { it.lineFinishPosition != null }
        assertEquals(1, finished.size)
        val rec = finished.single()
        assertEquals(13, rec.finishPosition)
        assertEquals(12, rec.lineFinishPosition)
        assertTrue(rec.finishConsolidated)
        val said = r.texts()
        assertEquals(1, said.count { it == "Classement GT7 révisé après l'arrivée : P13 sur 16." }, said.toString())
        assertEquals(1, r.said.count { it.dedupKey?.startsWith("bilan:") == true }, "un seul bilan")
        val m = r.c.engineer.measured
        assertEquals(null, m.linePosition, "la nouvelle course repart sans classement d'arrivée")
    }
}
