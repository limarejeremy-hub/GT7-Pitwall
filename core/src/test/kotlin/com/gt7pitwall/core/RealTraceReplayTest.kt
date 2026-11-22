package com.gt7pitwall.core

import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.telemetry.TelemetryTrace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Rejeu des traces brutes RÉELLES (V508 révisé), avec le moteur de replay et son horloge virtuelle.
 *
 * Les traces sont privées : jamais dans le dépôt. Pour lancer ce test, placer les fichiers enregistrés par Pitwall
 * (`gt7-pitwall-trace-20261119-*.gt7trace`, `gt7-pitwall-trace-20261122-*.gt7trace`) dans un dossier local et
 * définir `PITWALL_TRACES=<dossier>`. Sans dossier, le test est ignoré (rien n'est simulé à leur place).
 *
 * Résultats archivés attendus (d'après le pilote) : 19/11 → P7/13 (sous réserve du classement officiel), 22/11 → P13/16
 * (P12/16 à la ligne, révisé 567 ms après).
 */
class RealTraceReplayTest {
    private val expected = mapOf("20261119" to (7 to 13), "20261122" to (13 to 16))

    private fun traces(): List<File> {
        val dir = System.getenv("PITWALL_TRACES")?.let(::File)?.takeIf { it.isDirectory } ?: return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".gt7trace") && expected.keys.any { it in f.name } }?.sortedBy { it.name }.orEmpty()
    }

    @Test
    fun archivedResultsOfNovember19And22() {
        val files = traces()
        assumeTrue(files.isNotEmpty(), "PITWALL_TRACES absent : traces réelles non fournies, rejeu ignoré")
        for (f in files) {
            val clock = TelemetryTrace.VirtualClock(0)
            val c = RaceController(storage = FakeStorage(), clock = clock::now)
            c.selectProfile(ProfileId(Series.DAILY_B, SessionKind.RACE))
            val replay = f.inputStream().use { input -> TelemetryTrace.replay(input, clock) { p -> c.onPacket(p) } }
            // Fin de trace pendant la consolidation : le dernier classement reçu est retenu.
            c.positionTracker.consolidate("fin de la trace")
            val t = c.positionTracker
            println(
                "${f.name} : ${replay.lines.joinToString(" ")} | ligne P${t.linePosition}/${t.lineCars}, " +
                    "archivé P${t.finalPosition}/${t.finalCars}, après la ligne : ${t.postFinishChanges.joinToString { it.text }.ifEmpty { "aucun changement" }}",
            )
            val want = expected.entries.first { it.key in f.name }.value
            assertEquals(want, t.finalPosition to t.finalCars, f.name)
        }
    }
}
