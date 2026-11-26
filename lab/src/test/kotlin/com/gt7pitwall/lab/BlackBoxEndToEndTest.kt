package com.gt7pitwall.lab

import com.gt7pitwall.core.blackbox.BlackBox
import com.gt7pitwall.core.blackbox.BlackBoxPack
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.race.JournalJson
import com.gt7pitwall.core.race.JournalSource
import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.SessionJournal
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.telemetry.TelemetryParser
import com.gt7pitwall.core.telemetry.TelemetryTrace
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mission Black Box, bout en bout (trace GABARIT fabriquée, jamais une mesure) :
 * course enregistrée en direct → ZIP généré → ZIP rouvert → télémétrie rejouée → événements retrouvés → rapport produit.
 */
class BlackBoxEndToEndTest {
    private val direct = Executor { it.run() }

    /** Rejoue une trace gabarit comme si elle arrivait en direct : moteur réel, journal réel, boîte noire réelle. */
    private class Live(root: File, startMs: Long) {
        val clock = TelemetryTrace.VirtualClock(startMs)
        val storage = MemoryStorage()
        val race = RaceController(storage = storage, clock = clock::now)
        val bb = BlackBox(root, clock = clock::now, io = Executor { it.run() })
        val said = mutableListOf<EngineerMessage>()

        init {
            race.selectProfile(ProfileId(Series.DAILY_C, SessionKind.RACE))
            bb.auto = true
            bb.listener = BlackBox.Listener { id, text, abnormal ->
                race.journal.recordFor(id, JournalType.SESSION, JournalSource.SYSTEM, text, details = if (abnormal) mapOf("anomalie" to "oui") else emptyMap())
            }
            race.journal.lifecycle = SessionJournal.Lifecycle { id, began, finished -> if (began) bb.onSessionBegin(id) else bb.onSessionEnd(id, finished) }
        }

        fun play(trace: File, offsetMs: Long = 0, cutAfterMs: Int? = null) {
            val base = clock.nowMs + offsetMs
            var n = 0
            TelemetryTrace.stream(trace.inputStream()) { f ->
                if (cutAfterMs != null && f.atMs > cutAfterMs) return@stream false
                clock.nowMs = base + f.atMs
                bb.onFrame(f.plain, clock.nowMs)
                TelemetryParser.parseDecrypted(f.plain)?.let { p ->
                    // Comme PitwallEngine.journalMessage : chaque message réellement émis entre au journal.
                    race.onPacket(p).forEach { m ->
                        said += m
                        race.journal.record(JournalType.ENGINEER, JournalSource.ENGINE, m.text)
                    }
                }
                if (++n % 300 == 0) Thread.sleep(2) // rythme réaliste : la file du recorder ne déborde pas
                true
            }
        }

        fun close() {
            race.endSession()
            bb.onTelemetryStopped()
        }

        fun course(id: String) = BlackBoxPack.Course(
            id, storage.sessions().firstOrNull { it.id == id },
            storage.journal.mapNotNull(JournalJson::decode).filter { it.sessionId == id }.sortedBy { it.seq },
            bb.meta(id)?.segments.orEmpty(), bb.meta(id), "Voiture gabarit", "Circuit gabarit",
        )
    }

    private val device = BlackBoxPack.Device("test", "0", null, null)

    @Test fun recordedRaceToZipToReplayToReport() {
        val dir = Files.createTempDirectory("bb-e2e").toFile()
        val trace = Gabarit.writeEndurance(File(dir, "src"))
        val live = Live(File(dir, "bb"), 1_764_100_000_000L)
        live.play(trace)
        live.close()

        // La boîte noire a enregistré la course, rangée sous l'identifiant de la session du journal.
        val metas = live.bb.sessions()
        assertTrue(metas.isNotEmpty(), "aucune session enregistrée")
        val meta = metas.maxBy { it.bytes }
        val id = meta.sessionId
        assertTrue(live.storage.sessions().any { it.id == id }, "même identifiant que l'archive")
        val events = live.course(id).events
        assertTrue(events.any { "Boîte noire : enregistrement démarré" in it.text })
        assertTrue(events.any { "Boîte noire : enregistrement finalisé" in it.text })

        // ZIP généré puis rouvert.
        val zip = File(dir, "out/" + BlackBoxPack.fileName(1_764_100_000_000L, "Circuit gabarit", "Course", 1))
        val res = BlackBoxPack.write(zip, listOf(live.course(id)), device)
        assertTrue(res.verification.ok, res.verification.status)
        val frames = res.verification.frames.values.single()
        assertTrue(frames > 10_000, "échantillons : $frames")

        // Télémétrie rejouée et événements retrouvés par le Lab.
        val md = File(dir, "rapport.md")
        val report = ImportCourse.run(zip, md)
        assertTrue(report.getJSONObject("verification").getBoolean("ok"))
        val c = report.getJSONArray("courses").getJSONObject(0)
        val found = c.getJSONObject("evenements_retrouves")
        assertTrue(found.getInt("ravitaillements") >= 1, "ravitaillement mesuré : $found")
        assertTrue(found.getInt("immobilisations") >= 1, "arrêt mesuré : $found")
        assertTrue(found.getInt("changements_de_position") >= 1, "P5 → P3 : $found")
        assertEquals("EN_COURSE", c.getJSONObject("en_course").getString("origine"))
        val rep = c.getJSONObject("rejeu")
        assertEquals("SIMULATION", rep.getString("origine"))
        assertTrue(rep.getBoolean("fait"))
        // Le moteur actuel rejoue la même course : mêmes tours et mêmes arrêts que ceux archivés en direct.
        assertEquals(c.getJSONObject("en_course").getInt("tours"), rep.getInt("tours"))
        assertEquals(c.getJSONObject("en_course").getJSONArray("arrets").toString(), rep.getJSONArray("arrets").toString())
        // Messages émis en course retrouvés (radio.jsonl) et identiques à ceux que le moteur actuel recalcule.
        assertTrue(c.getJSONObject("en_course").getInt("messages_vega") > 0)
        val diffs = c.getJSONArray("differences")
        assertTrue((0 until diffs.length()).none { diffs.getJSONObject(it).getString("sujet") == "messages de Véga" }, diffs.toString())
        // CSV lisible : une ligne toutes les 100 ms.
        ZipFile(zip).use { z ->
            val rows = z.getInputStream(z.getEntry("telemetry_10hz.csv")).bufferedReader().readLines()
            assertTrue(rows.size > 900, "lignes CSV : ${rows.size}")
        }
        // Rapport lisible, les deux origines séparées.
        val text = md.readText()
        assertTrue("En course (`EN_COURSE`)" in text && "(`SIMULATION`)" in text)

        // Le gain P5 → P3 en roulant n'est jamais présenté comme un dépassement prouvé.
        ZipFile(zip).use { z ->
            val race = JSONObject(z.getInputStream(z.getEntry("race.json")).readBytes().toString(Charsets.UTF_8))
            val list = race.getJSONObject("changements_de_position").getJSONArray("liste")
            for (i in 0 until list.length()) assertFalse("dépassement prouvé" in list.getJSONObject(i).getString("cause"))
            assertTrue((0 until list.length()).any { "NON prouvé" in list.getJSONObject(it).getString("cause") })
        }
    }

    @Test fun severalRacesInARowAndASeancePack() {
        val dir = Files.createTempDirectory("bb-multi").toFile()
        val trace = Gabarit.writeEndurance(File(dir, "src"))
        val live = Live(File(dir, "bb"), 1_764_100_000_000L)
        live.play(trace)
        live.race.endSession()
        live.play(trace, offsetMs = 60_000)
        live.close()
        val ids = live.bb.sessions().sortedBy { it.startedAtMs }.map { it.sessionId }
        assertTrue(ids.size >= 2, "sessions : $ids")
        val res = BlackBoxPack.write(File(dir, BlackBoxPack.seanceName(1_764_100_000_000L, ids.size)), ids.map(live::course), device)
        assertTrue(res.verification.ok, res.verification.status)
        val report = ImportCourse.run(res.file)
        assertEquals(ids.size, report.getJSONArray("courses").length())
    }

    @Test fun wifiCutAndAbruptCloseStillGiveAUsablePack() {
        val dir = Files.createTempDirectory("bb-crash").toFile()
        // Gabarit « anomalies » : trou de flux de 2 s (Wi-Fi), puis fermeture brutale à 50 s (pas de fin de session).
        val trace = Gabarit.write(File(dir, "src"))
        val root = File(dir, "bb")
        val live = Live(root, 1_764_100_000_000L)
        live.play(trace, cutAfterMs = 50_000)
        Thread.sleep(com.gt7pitwall.core.telemetry.TelemetryRecorder.FLUSH_MS + 600)
        // Relance de l'application : la session est récupérée.
        val again = BlackBox(root, io = direct)
        val meta = again.sessions().first()
        assertEquals(BlackBox.State.RECOVERED, meta.state)
        val course = BlackBoxPack.Course(meta.sessionId, null, live.course(meta.sessionId).events, meta.segments, meta)
        val res = BlackBoxPack.write(File(dir, "crash.zip"), listOf(course), device)
        assertTrue(res.verification.ok, res.verification.status)
        assertTrue(res.missing.any { "fermeture brutale" in it })
        assertTrue(res.missing.any { "coupure" in it }, res.missing.toString())
        live.bb.recorder.stop()
    }
}
