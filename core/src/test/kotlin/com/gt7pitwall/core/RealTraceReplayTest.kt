package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.telemetry.PositionProbe
import com.gt7pitwall.core.telemetry.TelemetryTrace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Rejeu des traces brutes RÉELLES du pilote (V511, revue PR #86), avec le moteur de replay et son horloge virtuelle.
 *
 * Les traces sont privées : jamais dans le dépôt. Les placer dans un dossier local et définir `PITWALL_TRACES=<dossier>`
 * (fichiers `.gt7trace` enregistrés par Pitwall ; la date doit figurer dans le nom : `20261119`, `2026-11-19`…).
 *
 * - Sans `PITWALL_TRACES` : tout est IGNORÉ, avec la liste exacte de ce qui n'a pas été exécuté (aucun résultat inventé).
 * - Avec `PITWALL_TRACES` : les traces du 19/11 et du 22/11 sont OBLIGATOIRES (échec si l'une manque) ; celles du 06/11
 *   et du 28/10 sont contrôlées si présentes, sinon signalées « non exécutées ».
 *
 * Références données par le pilote : 19/11 = 74 556 échantillons C, dernière arrivée observée P7/13 (classement officiel
 * à confirmer) ; 22/11 = 29 936 échantillons C, P12/16 à la ligne puis P13/16 (pénalité de 2 s, témoignage du pilote) ;
 * 06/11 = 126 686 échantillons A, trace tronquée ; 28/10 = 22 400 échantillons C.
 */
class RealTraceReplayTest {

    private data class Expected(
        val label: String,
        val tokens: List<String>,
        val frames: Int,
        val format: String,
        val required: Boolean,
        /** Dernière arrivée attendue (position, voitures) ; null : pas de contrôle de classement. */
        val finish: Pair<Int, Int>? = null,
        /** Rang de la ligne attendu pour la dernière arrivée. */
        val line: Pair<Int, Int>? = null,
        /** Révisions après la ligne attendues pour la dernière arrivée (vide : aucune exigée). */
        val revisions: List<Pair<Pair<Int, Int>, Pair<Int, Int>>> = emptyList(),
        val truncated: Boolean = false,
    )

    private val references = listOf(
        Expected("19/11", listOf("20261119", "2026-11-19", "19-11-2026", "1911"), 74_556, "C", required = true, finish = 7 to 13),
        Expected(
            "22/11", listOf("20261122", "2026-11-22", "22-11-2026", "2211"), 29_936, "C", required = true,
            finish = 13 to 16, line = 12 to 16, revisions = listOf((12 to 16) to (13 to 16)),
        ),
        Expected("06/11", listOf("20261106", "2026-11-06", "06-11-2026", "0611"), 126_686, "A", required = false, truncated = true),
        Expected("28/10", listOf("20261028", "2026-10-28", "28-10-2026", "2810"), 22_400, "C", required = false),
    )

    private val raceChecks = listOf(
        "nombre d'échantillons et format",
        "paquets illisibles et interruptions du flux (> 0,5 s)",
        "rang à la ligne, révisions après l'arrivée et classement final (PositionTracker)",
        "archive de session (SessionArchive : finishPosition, ligne, consolidation, révisions)",
        "un seul « Course terminée », un seul bilan et au plus une révision dite par course",
        "transition « 8/0 » : pas de nouvelle session, état « après arrivée »",
    )

    private fun dir(): File? = System.getenv("PITWALL_TRACES")?.let(::File)?.takeIf { it.isDirectory }

    private fun find(dir: File, e: Expected): File? =
        dir.listFiles { f -> f.name.endsWith(".gt7trace") }?.firstOrNull { f -> e.tokens.any { it in f.name } }

    @Test
    fun replayPilotTraces() {
        val d = dir()
        if (d == null) {
            val notRun = references.joinToString("\n") { e ->
                " - ${e.label}${if (e.required) " (obligatoire)" else ""} : " +
                    (if (e.finish != null) raceChecks else raceChecks.take(2)).joinToString(" ; ")
            }
            println("RealTraceReplayTest NON EXÉCUTÉ (PITWALL_TRACES absent). Contrôles non exécutés :\n$notRun")
            assumeTrue(false, "PITWALL_TRACES absent : aucune trace réelle rejouée.\n$notRun")
            return
        }
        val missingRequired = references.filter { it.required && find(d, it) == null }
        val report = StringBuilder("RealTraceReplayTest — dossier ${d.name}\n")
        for (e in references) {
            val f = find(d, e)
            if (f == null) {
                report.append(" - ${e.label} : NON EXÉCUTÉ (trace absente)\n")
                continue
            }
            report.append(replayOne(f, e))
        }
        println(report)
        if (missingRequired.isNotEmpty()) {
            fail<Unit>("Traces obligatoires absentes : ${missingRequired.joinToString { it.label }}. Contrôles non exécutés pour elles : ${raceChecks.joinToString(" ; ")}\n$report")
        }
    }

    private fun replayOne(f: File, e: Expected): String {
        val clock = TelemetryTrace.VirtualClock(0)
        val storage = FakeStorage()
        val c = RaceController(storage = storage, clock = clock::now)
        c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
        val probe = PositionProbe(maxEvents = 100_000)
        val said = mutableListOf<EngineerMessage>()
        val replay = f.inputStream().use { input ->
            TelemetryTrace.replay(input, clock) { p ->
                probe.onPacket(p, clock.nowMs)
                said += c.onPacket(p)
            }
        }
        val out = StringBuilder(" - ${e.label} (${f.name}) : ${replay.lines.joinToString(" ")}\n")

        // 1. Échantillons et format.
        assertEquals(e.frames, replay.frames, "${e.label} : nombre d'échantillons")
        assertEquals(setOf(e.format), replay.formats.keys, "${e.label} : format des paquets")
        // 2. Paquets illisibles et interruptions : rapportés (une trace réelle peut en contenir), jamais masqués.
        out.append("   paquets illisibles : ${replay.frames - replay.decoded} ; interruptions > 0,5 s : ${replay.gapsOver500Ms}" +
            (if (e.truncated) " ; trace tronquée attendue" else "") + "\n")
        if (e.finish == null) {
            out.append("   classement : non contrôlé pour cette trace (aucune référence d'arrivée)\n")
            return out.toString()
        }

        // 3 et 4. Dernière arrivée : PositionTracker et archive.
        val records: List<SessionRecord> = storage.sessions.filter { it.lineFinishPosition != null }.sortedBy { it.startedAtMs }
        assertTrue(records.isNotEmpty(), "${e.label} : aucune arrivée archivée")
        val last = records.last()
        val t = c.positionTracker
        out.append("   arrivées archivées : ${records.size} ; dernière : ligne P${last.lineFinishPosition}/${last.lineFinishCars}, " +
            "archivé P${last.finishPosition}/${last.finishCars}, consolidé=${last.finishConsolidated}, " +
            "révisions : ${last.postFinishChanges.joinToString { it.text }.ifEmpty { "aucune" }}\n")
        assertEquals(e.finish, last.finishPosition to last.finishCars, "${e.label} : classement archivé")
        e.line?.let { assertEquals(it, last.lineFinishPosition to last.lineFinishCars, "${e.label} : rang à la ligne") }
        if (e.revisions.isNotEmpty()) {
            assertEquals(
                e.revisions, last.postFinishChanges.map { (it.fromPosition to it.fromCars) to (it.toPosition to it.toCars) },
                "${e.label} : révisions après l'arrivée",
            )
        }
        // L'état courant du tracker suit l'archive pour une session encore ouverte en fin de trace.
        if (t.linePosition != null) assertEquals(last.finishPosition, t.finalPosition, "${e.label} : PositionTracker ≠ archive")

        // 5. Résumés : un par course, jamais doublés.
        val texts = said.filter { it.category != MessageCategory.DRIVER }.map { it.text }
        val finishedMsgs = texts.count { it.startsWith("Course terminée") }
        val bilans = said.mapNotNull { it.dedupKey?.takeIf { k -> k.startsWith("bilan:") } }
        val revisionsSaid = texts.count { it.startsWith("Classement GT7 révisé") }
        out.append("   « Course terminée » : $finishedMsgs ; bilans : ${bilans.size} ; révisions dites : $revisionsSaid ; messages : ${texts.size}\n")
        assertEquals(records.size, finishedMsgs, "${e.label} : un « Course terminée » par arrivée")
        assertEquals(bilans.distinct().size, bilans.size, "${e.label} : bilan en double")
        assertTrue(bilans.size <= records.size, "${e.label} : plus de bilans que d'arrivées")
        assertTrue(revisionsSaid <= records.size, "${e.label} : révision dite plusieurs fois")

        // 6. Transition « 8/0 » : état « après arrivée », aucune session ouverte par elle.
        val events = probe.summary(recent = Int.MAX_VALUE).events
        val eightZero = events.filter { it.totalLaps == 0 && it.lap >= 1 && events.any { a -> a.state == "arrivée" && a.lap == it.lap && a.timeMs <= it.timeMs } }
        out.append("   transitions « tour/0 » après une arrivée : ${eightZero.size}\n")
        assertTrue(eightZero.none { it.state == "en course" }, "${e.label} : « 8/0 » vu comme « en course »")
        out.append("   messages de position et d'arrivée :\n")
        texts.filter { it.startsWith("Course terminée") || it.startsWith("Classement GT7") || it.startsWith("Tu passes la ligne") }
            .forEach { out.append("     « $it »\n") }
        // Consignes de virage réellement produites (garde-fou V511 : aucune perte > 1,5 s, jamais « 41 dixièmes »).
        val coaching = texts.filter { "Virage" in it || it.startsWith("Bilan") }
        out.append("   consignes de virage et bilans dits : ${coaching.size}\n")
        coaching.forEach { out.append("     « $it »\n") }
        assertTrue(coaching.none { Regex("\\b([1-9]\\d) dixièmes").containsMatchIn(it) }, "${e.label} : durée d'au moins une seconde dite en dixièmes")
        assertTrue(coaching.none { ".." in it }, "${e.label} : point doublé")
        return out.toString()
    }
}
