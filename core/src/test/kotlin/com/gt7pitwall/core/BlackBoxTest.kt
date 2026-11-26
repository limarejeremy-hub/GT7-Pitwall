package com.gt7pitwall.core

import com.gt7pitwall.core.blackbox.BlackBox
import com.gt7pitwall.core.blackbox.BlackBoxPack
import com.gt7pitwall.core.race.JournalEvent
import com.gt7pitwall.core.race.JournalSource
import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryRecorder
import com.gt7pitwall.core.telemetry.TelemetryTrace
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mission Black Box : enregistrement automatique par session, pack ZIP unique, vérification par réouverture. */
class BlackBoxTest {
    private var now = 1_764_100_000_000L
    private val direct = Executor { it.run() }
    private fun tmp(): File = Files.createTempDirectory("blackbox").toFile()

    /** Paquet format C (déchiffré), avec position 0x84/0x86. */
    private fun packet(id: Int, lap: Int = 1, speedKmh: Float = 150f, fuel: Float = 80f, pos: Int = 5, cars: Int = 8, temps: Float = 80f): ByteArray {
        val b = TestPackets.plain(
            lap = lap, totalLaps = 5, fuel = fuel, speedMps = speedKmh / 3.6f, packetId = id, size = PacketFormat.C.size,
            tyreTemps = floatArrayOf(temps, temps, temps, temps),
        )
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(0x84, pos.toShort()).putShort(0x86, cars.toShort())
        return b
    }

    private fun event(seq: Int, type: JournalType, source: JournalSource, text: String, lap: Int? = 1, at: Long = now, details: Map<String, String> = emptyMap()) =
        JournalEvent(seq, at, "S1", lap, type, source, text, details = details)

    private val device = BlackBoxPack.Device("0.15.0 test", "42", "SM-F956B", "15", mapOf("voix" to "oui"), mapOf("gpt_live" to "désactivé"))

    // -------------------------------------------------------------------------------------------- recorder

    @Test fun allPacketsModeKeepsWifiBurstsThatTwentyHertzWouldDrop() {
        val sink = ByteArrayOutputStream()
        val rec = TelemetryRecorder(clock = { now })
        rec.start(sink, TelemetryRecorder.ALL_PACKETS)
        repeat(300) { i -> now += if (i % 2 == 0) 1 else 32; rec.onFrame(packet(i + 1), now) }
        rec.stop()
        val st = rec.stats()
        assertEquals(300, st.kept)
        assertEquals(0, st.decimated)
        assertEquals(300, TelemetryTrace.read(sink.toByteArray().inputStream()).size)
        // Mode 60 Hz historique : les paquets rapprochés sont écartés, et désormais comptés.
        val rec60 = TelemetryRecorder(clock = { now })
        rec60.start(ByteArrayOutputStream(), 60)
        repeat(300) { i -> now += if (i % 2 == 0) 1 else 32; rec60.onFrame(packet(i + 1), now) }
        rec60.stop()
        assertTrue(rec60.stats().decimated in 149L..150L, "écartés : ${rec60.stats().decimated}")
    }

    @Test fun segmentsConcatenateIntoOneValidTrace() {
        val dir = tmp()
        val rec = TelemetryRecorder(clock = { now }, maxBytes = Long.MAX_VALUE)
        // Paquets de contenu varié (gzip ne les écrase pas) pour franchir le seuil de 64 Ko plusieurs fois.
        val rnd = java.util.Random(7)
        rec.start(File(dir, BlackBox.segmentName(1)).outputStream(), TelemetryRecorder.ALL_PACKETS,
            rotate = { n -> File(dir, BlackBox.segmentName(n)).outputStream() }, segmentBytes = 64 * 1024)
        repeat(3_000) { i ->
            now += 16
            val p = packet(i + 1)
            for (k in 0x150 until 0x160) p[k] = rnd.nextInt().toByte()
            rec.onFrame(p, now)
            if (i % 500 == 0) Thread.sleep(30)
        }
        rec.stop()
        val segs = BlackBox.segmentFiles(dir)
        assertTrue(segs.size >= 2, "segments : ${segs.size}")
        assertEquals(segs.size, rec.stats().segments)
        val all = java.io.SequenceInputStream(java.util.Collections.enumeration(segs.map { it.inputStream() }))
        val frames = TelemetryTrace.read(all)
        assertEquals(3_000, frames.size)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.atMs >= a.atMs })
    }

    @Test fun abruptCloseKeepsEverythingUpToTheLastPeriodicFlush() {
        val f = File(tmp(), "crash.gt7trace")
        val rec = TelemetryRecorder()
        rec.start(f.outputStream(), TelemetryRecorder.ALL_PACKETS)
        val t = System.currentTimeMillis()
        repeat(200) { i -> rec.onFrame(packet(i + 1), t + i * 16L) }
        Thread.sleep(TelemetryRecorder.FLUSH_MS + 700)
        // Pas d'arrêt : le téléphone « s'éteint ». Le fichier sur disque est lu tel quel.
        val frames = TelemetryTrace.read(f.readBytes().inputStream())
        assertEquals(200, frames.size)
        rec.stop()
    }

    // -------------------------------------------------------------------------------------------- cycle de vie

    @Test fun sessionLifecycleRecordsWithPreRollAndFinalizes() {
        val root = tmp()
        val bb = BlackBox(root, clock = { now }, io = direct)
        val said = mutableListOf<String>()
        bb.listener = BlackBox.Listener { _, text, _ -> said += text }
        // Inactive : rien n'est gardé.
        bb.onFrame(packet(1), now)
        bb.onSessionBegin("S0")
        assertTrue(bb.sessions().isEmpty())
        bb.auto = true
        repeat(50) { i -> now += 16; bb.onFrame(packet(i + 2), now) } // grille, avant la session
        bb.onSessionBegin("S1")
        repeat(100) { i -> now += 16; bb.onFrame(packet(i + 100), now) }
        bb.onSessionEnd("S1", finished = true)
        val m = assertNotNull(bb.meta("S1"))
        assertEquals(BlackBox.State.FINISHED, m.state)
        val frames = TelemetryTrace.read(m.segments.single().inputStream())
        assertEquals(150, frames.size, "50 de pré-enregistrement + 100")
        assertTrue(said.any { "démarré" in it } && said.any { "finalisé" in it })
        assertEquals(150L, m.stats!!.getLong("kept"))
    }

    @Test fun manualTestSessionWithoutAutoMode() {
        val bb = BlackBox(tmp(), clock = { now }, io = direct)
        bb.armManual(liveSessionId = null)
        bb.onSessionBegin("ESSAI")
        repeat(10) { i -> now += 16; bb.onFrame(packet(i + 1), now) }
        bb.onSessionEnd("ESSAI", finished = false)
        val m = assertNotNull(bb.meta("ESSAI"))
        assertTrue(m.manual)
        assertFalse(bb.manualArmed, "un essai demandé n'arme qu'une session")
    }

    @Test fun abruptCloseIsRecoveredAtNextLaunch() {
        val root = tmp()
        val bb = BlackBox(root, clock = { now }, io = direct)
        bb.auto = true
        bb.onSessionBegin("S1")
        repeat(20) { i -> now += 16; bb.onFrame(packet(i + 1), now) }
        // Pas de fin : application tuée. Au lancement suivant :
        val again = BlackBox(root, clock = { now }, io = direct)
        val m = assertNotNull(again.meta("S1"))
        assertEquals(BlackBox.State.RECOVERED, m.state)
        assertTrue("brutale" in m.stopReason!!)
        bb.recorder.stop()
    }

    @Test fun retentionKeepsTheLastSessionsOnly() {
        val bb = BlackBox(tmp(), BlackBox.Limits(keepSessions = 2), clock = { now }, io = direct)
        bb.auto = true
        for (s in 1..4) {
            now += 60_000
            bb.onSessionBegin("S$s")
            repeat(5) { i -> now += 16; bb.onFrame(packet(i + 1), now) }
            bb.onSessionEnd("S$s", finished = true)
        }
        assertEquals(listOf("S4", "S3"), bb.sessions().map { it.sessionId })
    }

    @Test fun sessionBudgetStopIsNeverSilent() {
        val bb = BlackBox(tmp(), BlackBox.Limits(maxSessionBytes = 1), io = direct)
        val abnormal = mutableListOf<String>()
        bb.listener = BlackBox.Listener { _, text, bad -> if (bad) synchronized(abnormal) { abnormal += text } }
        bb.auto = true
        bb.onSessionBegin("S1")
        val t = System.currentTimeMillis()
        repeat(500) { i -> bb.onFrame(packet(i + 1), t + i * 16L) }
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && synchronized(abnormal) { abnormal.isEmpty() }) Thread.sleep(100)
        assertTrue(synchronized(abnormal) { abnormal.any { "budget" in it } }, "arrêt signalé : $abnormal")
        assertEquals(BlackBox.State.STOPPED, bb.meta("S1")!!.state)
    }

    // -------------------------------------------------------------------------------------------- pack

    /** Course de 3 tours : arrêt au tour 2 (immobile, plein 20 → 60), P5 → P4 pendant l'arrêt, P4 → P3 en roulant. */
    private fun recordRace(root: File, sessionId: String = "S1"): BlackBox.Meta {
        val bb = BlackBox(root, clock = { now }, io = direct)
        bb.auto = true
        bb.onSessionBegin(sessionId)
        var id = 0
        var fuel = 40f
        fun frame(lap: Int, speed: Float, pos: Int, temps: Float = 80f) {
            now += 16
            bb.onFrame(packet(++id, lap, speed, fuel, pos, 8, temps), now)
            if (id % 200 == 0) Thread.sleep(5) // rythme du Wi-Fi : la file bornée (1 024) n'est jamais pleine en course
        }
        repeat(600) { frame(1, 150f, 5); fuel -= 0.01f }
        repeat(300) { frame(2, 0f, if (it < 100) 5 else 4, 60f); if (it in 50 until 250) fuel += 0.2f }
        repeat(300) { frame(2, 150f, if (it < 150) 4 else 3, 70f) }
        repeat(300) { frame(3, 150f, 3) }
        bb.onSessionEnd(sessionId, finished = true)
        return bb.meta(sessionId)!!
    }

    private fun journal(t0: Long) = listOf(
        event(1, JournalType.SESSION, JournalSource.SYSTEM, "Début de session : Porsche 963 — Circuit de la Sarthe — Course Sport C, course", at = t0),
        event(2, JournalType.DRIVER, JournalSource.TRANSCRIPT, "Combien d'essence ?", at = t0 + 2_000),
        event(3, JournalType.ENGINEER, JournalSource.ENGINE, "Il te reste 3,8 tours d'essence.", at = t0 + 2_200),
        event(4, JournalType.STRATEGY, JournalSource.ENGINE, "Stratégie V515 : approuvée", at = t0 + 5_000, details = mapOf("plan_retenu" to "1 arrêt T2", "plan_propose" to "2 arrêts T2,T3", "controles" to "carburant=ok")),
        event(5, JournalType.ALERT, JournalSource.ENGINE, "Box ce tour !", at = t0 + 8_000),
        event(6, JournalType.DRIVER, JournalSource.TRANSCRIPT, "J'ai pris une pénalité de 2 secondes, ma clé 192.168.1.20", at = t0 + 9_000),
        event(7, JournalType.PIT, JournalSource.TELEMETRY, "Passage au stand : ravitaillement", lap = 2, at = t0 + 12_000),
        event(8, JournalType.DRIVER, JournalSource.TRANSCRIPT, "Je viens de le dépasser", at = t0 + 20_000),
        event(9, JournalType.SESSION, JournalSource.SYSTEM, "Fin de session : course terminée", at = t0 + 25_000, details = mapOf("issue" to "finished")),
    )

    @Test fun singleCoursePackHasEverythingAndVerifiesByReopening() {
        val root = tmp()
        val t0 = now
        val meta = recordRace(root)
        val zip = File(root, "out/" + BlackBoxPack.fileName(t0, "Circuit de la Sarthe", "Course", 1, java.time.ZoneOffset.UTC))
        val res = BlackBoxPack.write(zip, listOf(BlackBoxPack.Course("S1", null, journal(t0), meta.segments, meta, "Porsche 963", "Circuit de la Sarthe")), device, now)
        assertTrue(res.verification.ok, res.verification.status)
        assertTrue(zip.name.matches(Regex("Pitwall_\\d{4}-\\d{2}-\\d{2}_CircuitDeLaSarthe_Course1\\.zip")), zip.name)
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            for (f in listOf("README.md", "manifest.json", "telemetry.gt7trace", "telemetry_10hz.csv", "race.json", "timeline.jsonl", "timeline.csv", "strategy.jsonl", "radio.jsonl", "diagnostics.json")) {
                assertTrue(f in names, "absent : $f")
            }
            fun text(n: String) = z.getInputStream(z.getEntry(n)).readBytes().toString(Charsets.UTF_8)
            val manifest = JSONObject(text("manifest.json"))
            assertEquals(BlackBoxPack.FORMAT, manifest.getString("format"))
            assertEquals(1500, manifest.getJSONArray("sessions").getJSONObject(0).getInt("frames"))
            // Télémétrie rejouable telle quelle.
            assertEquals(1500, TelemetryTrace.replay(z.getInputStream(z.getEntry("telemetry.gt7trace"))).decoded)
            // Lisible sans décoder le binaire : première ligne au moins 100 ms après la précédente (ici tous les 7 paquets de 16 ms).
            assertTrue(text("telemetry_10hz.csv").lines().size in 200..260)
            // C. Le gain pendant l'arrêt n'est pas un dépassement ; le gain en roulant n'est pas prouvé.
            val race = JSONObject(text("race.json"))
            val changes = race.getJSONObject("changements_de_position").getJSONArray("liste")
            assertEquals(2, changes.length())
            assertTrue(changes.getJSONObject(0).getBoolean("pendant_arret"))
            assertTrue("pas un dépassement" in changes.getJSONObject(0).getString("cause"))
            assertTrue("NON prouvé" in changes.getJSONObject(1).getString("cause"))
            assertEquals("Je viens de le dépasser", changes.getJSONObject(1).getJSONArray("declarations_pilote").getString(0))
            // D. Ravitaillement mesuré, carburant avant/après.
            val refuel = race.getJSONObject("stands").getJSONArray("ravitaillements_mesures").getJSONObject(0)
            assertTrue(refuel.getDouble("carburant_apres") > refuel.getDouble("carburant_avant") + 30)
            // Pénalité : déclaration du pilote, adresse masquée.
            val pen = race.getJSONObject("penalites").getJSONArray("declarations").getJSONObject(0).getString("texte")
            assertTrue("pénalité" in pen && "192.168" !in pen)
            // E. Températures par tour mesurées dans la trace.
            assertTrue(race.getJSONObject("pneus").getJSONArray("par_tour_trace").length() >= 3)
            // 4. Chronologie triée, natures présentes, position sur le circuit attachée aux événements du journal.
            val tl = text("timeline.jsonl").lines().filter { it.isNotBlank() }.map(::JSONObject)
            assertTrue(tl.zipWithNext().all { (a, b) -> a.getLong("t_ms") <= b.getLong("t_ms") })
            assertTrue(tl.map { it.getString("nature") }.containsAll(listOf("MESURE", "CALCUL", "DECLARATION")))
            assertTrue(tl.any { it.getString("type") == "RAVITAILLEMENT" } && tl.any { it.getString("type") == "POSITION_MESUREE" })
            assertTrue(tl.first { it.optInt("seq") == 2 }.opt("x") != JSONObject.NULL)
            assertTrue(tl.all { it.getString("origine") == "EN_COURSE" })
            // F. Stratégie : verdict, stratégie rejetée, rappel Box.
            val st = text("strategy.jsonl").lines().filter { it.isNotBlank() }.map(::JSONObject)
            assertTrue(st.any { it.getString("categorie") == "VERDICT_STRATEGY_ASSURANCE" && it.getString("strategie_rejetee") == "2 arrêts T2,T3" })
            assertTrue(st.any { it.getString("categorie") == "RAPPEL_BOX" })
            val radio = text("radio.jsonl").lines().filter { it.isNotBlank() }.map(::JSONObject)
            assertEquals(listOf("pilote", "Véga"), radio.take(2).map { it.getString("role") })
            // README : résumé et manquants explicites.
            val readme = text("README.md")
            assertTrue("Données manquantes" in readme && "secteurs" in readme && "aucun son" in readme)
            assertTrue("192.168" !in text("timeline.jsonl"))
            val diag = JSONObject(text("diagnostics.json"))
            assertEquals("FINISHED", diag.getJSONObject("boite_noire").getString("etat"))
            assertEquals(0, diag.getJSONObject("trace").getLong("paquets_gt7_manquants"))
        }
    }

    @Test fun packWithoutTraceAudioOrVideoSaysWhatIsMissing() {
        val root = tmp()
        val res = BlackBoxPack.write(File(root, "p.zip"), listOf(BlackBoxPack.Course("S1", null, journal(now))), device, now)
        assertTrue(res.verification.ok, res.verification.status)
        assertTrue(res.missing.any { "télémétrie brute absente" in it })
        assertTrue(res.missing.any { "aucun son ni vidéo" in it })
        ZipFile(res.file).use { assertEquals(null, it.getEntry("telemetry.gt7trace")) }
    }

    @Test fun truncatedTraceIsPackedUpToItsLastCompleteSample() {
        val root = tmp()
        val meta = recordRace(root)
        val seg = meta.segments.single()
        val cut = seg.readBytes().let { it.copyOf(it.size * 2 / 3) }
        seg.writeBytes(cut)
        val res = BlackBoxPack.write(File(root, "t.zip"), listOf(BlackBoxPack.Course("S1", null, journal(now - 30_000), meta.segments, meta)), device, now)
        assertTrue(res.verification.ok, res.verification.status)
        val frames = res.verification.frames.values.single()
        assertTrue(frames in 1 until 1500, "échantillons : $frames")
    }

    @Test fun seanceOfSeveralCoursesInOneFile() {
        val root = tmp()
        val a = recordRace(root, "A")
        now += 120_000
        val b = recordRace(root, "B")
        val res = BlackBoxPack.write(File(root, BlackBoxPack.seanceName(now, 2)), listOf(
            BlackBoxPack.Course("A", null, journal(now - 200_000), a.segments, a),
            BlackBoxPack.Course("B", null, journal(now - 30_000), b.segments, b),
        ), device, now)
        assertTrue(res.verification.ok, res.verification.status)
        ZipFile(res.file).use { z ->
            assertNotNull(z.getEntry("course-01_A/telemetry.gt7trace"))
            assertNotNull(z.getEntry("course-02_B/race.json"))
            assertEquals("seance", JSONObject(z.getInputStream(z.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)).getString("kind"))
        }
    }

    @Test fun tamperedPackFailsVerification() {
        val root = tmp()
        val res = BlackBoxPack.write(File(root, "p.zip"), listOf(BlackBoxPack.Course("S1", null, journal(now))), device, now)
        val bad = File(root, "bad.zip")
        ZipFile(res.file).use { z ->
            ZipOutputStream(bad.outputStream()).use { out ->
                for (e in z.entries()) {
                    out.putNextEntry(ZipEntry(e.name))
                    val data = z.getInputStream(e).readBytes()
                    out.write(if (e.name == "radio.jsonl") data + "{\"x\":1}\n".toByteArray() else data)
                    out.closeEntry()
                }
            }
        }
        val v = BlackBoxPack.verify(bad)
        assertFalse(v.ok)
        assertTrue(v.problems.any { "radio.jsonl" in it })
    }

    @Test fun oneHourAtSixtyHertzIsPackedInStreaming() {
        val root = tmp()
        val bb = BlackBox(root, clock = { now }, io = direct)
        bb.auto = true
        bb.onSessionBegin("LONG")
        val n = 216_000
        for (i in 1..n) {
            now += 16
            bb.onFrame(packet(i, 1 + i / 6_000, 150f, 100f - i / 3_000f, 5), now)
            if (i % 500 == 0) Thread.sleep(3) // rythme réaliste : le fil d'écriture suit (file bornée à 1 024)
        }
        bb.onSessionEnd("LONG", finished = true)
        val m = bb.meta("LONG")!!
        assertEquals(0L, m.stats!!.getLong("dropped"))
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val res = BlackBoxPack.write(File(root, "long.zip"), listOf(BlackBoxPack.Course("LONG", null, emptyList(), m.segments, m)), device, now)
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue(res.verification.ok, res.verification.status)
        assertEquals(n, res.verification.frames.values.single())
        assertTrue(after - before < 64L * 1_048_576, "mémoire retenue : ${(after - before) / 1_048_576} Mo")
    }

    @Test fun fileNamesAreReadableAndAscii() {
        val t = java.time.ZonedDateTime.of(2026, 11, 26, 20, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("Pitwall_2026-11-26_LeMans_Course1.zip", BlackBoxPack.fileName(t, "Le Mans", "Course", 1, java.time.ZoneOffset.UTC))
        assertEquals("Pitwall_2026-11-26_NurburgringNordschleife_Essai2.zip", BlackBoxPack.fileName(t, "Nürburgring Nordschleife", "Essai", 2, java.time.ZoneOffset.UTC))
    }
}
