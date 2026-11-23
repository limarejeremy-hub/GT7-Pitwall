package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Les traces utilisées ici sont des GABARITS fabriqués (jamais des mesures). */
class AnomaliesCompareTest {
    private fun gabarit(anomalies: Boolean) = Gabarit.write(Files.createTempDirectory("lab").toFile(), anomalies)

    @Test
    fun anomaliesFindsTheThreePlantedIncidentsAndOnlyThem() {
        val r = Anomalies.scan(gabarit(anomalies = true))
        val s = r.getJSONObject("resume")
        assertTrue(r.has("avertissement"), "gabarit signalé")
        assertEquals(1, s.getJSONObject("TROU_FLUX").getInt("nombre"))
        assertEquals(1, s.getJSONObject("POSITION_INCOHERENTE").getInt("nombre"))
        assertEquals(1, s.getJSONObject("CARBURANT_HAUSSE_EN_ROULANT").getInt("nombre"))
        val gap = (0 until r.getJSONArray("anomalies").length()).map { r.getJSONArray("anomalies").getJSONObject(it) }.first { it.getString("type") == "TROU_FLUX" }
        assertEquals(20_000, gap.getLong("t_ms"))
        assertTrue("2000 ms" in gap.getString("detail"))
        val pos = (0 until r.getJSONArray("anomalies").length()).map { r.getJSONArray("anomalies").getJSONObject(it) }.first { it.getString("type") == "POSITION_INCOHERENTE" }
        assertTrue("1000 ms" in pos.getString("detail"), pos.toString())
        assertFalse(s.has("TELEMETRIE_FIGEE"))
        assertFalse(s.has("PAQUET_RECUL"))
    }

    @Test
    fun aCleanTraceHasNoSuspectAnomaly() {
        val r = Anomalies.scan(gabarit(anomalies = false))
        assertEquals(0, r.getInt("suspects"), r.getJSONObject("resume").toString())
    }

    @Test
    fun sequenceDiffKeepsOrderAndDuplicates() {
        val d = Compare.seqDiff(listOf("a", "b", "c", "b"), listOf("a", "c", "b", "d"))
        assertEquals(listOf("b"), d.removed)
        assertEquals(listOf("d"), d.added)
        assertEquals(Compare.SeqDiff(emptyList(), emptyList()), Compare.seqDiff(listOf("x", "x"), listOf("x", "x")))
    }

    private fun report(result: String, msgs: List<Pair<String, String>>, journal: List<Pair<String, String>>) = JSONObject()
        .put("trace", "pitwall-trace-${Gabarit.MARK}.gt7trace")
        .put("sessions", JSONArray().put(JSONObject()
            .put("issue", "FINISHED")
            .put("tours", JSONObject().put("valeur", JSONArray(listOf(1, 2, 3))))
            .put("ligne", JSONObject().put("valeur", "P12/16"))
            .put("resultat_archive", JSONObject().put("valeur", result).put("consolide", true))))
        .put("messages", JSONObject().put("valeur", JSONArray(msgs.map { JSONObject().put("priorite", it.first).put("texte", it.second) })))
        .put("journal", JSONObject().put("valeur", JSONArray(journal.map { JSONObject().put("type", it.first).put("texte", it.second).put("tour", 4) })))
        .put("compteurs", JSONObject().put("valeur", JSONObject().put("course_terminee", 1).put("bilans", 1)))

    @Test
    fun compareReportsResultsAlertsMessagesDecisionsAndPositions() {
        val a = report("P12/16", listOf("HIGH" to "Tu termines P12 sur 16.", "NORMAL" to "P12."), listOf("POSITION" to "Classement final : P12 sur 16", "DECISION" to "Plan : aucun arrêt"))
        val b = report("P13/16", listOf("HIGH" to "Tu passes la ligne P12 sur 16.", "NORMAL" to "P12.", "HIGH" to "Classement GT7 révisé après l'arrivée : P13 sur 16."),
            listOf("POSITION" to "Ligne d'arrivée : P12 sur 16", "POSITION" to "Classement révisé", "DECISION" to "Plan : aucun arrêt"))
        val d = Compare.diff(a, b, "v510", "v511")
        assertFalse(d.getBoolean("identique"))
        val r = d.getJSONObject("resume")
        assertEquals(1, r.getInt("resultats"))
        assertEquals(3, r.getInt("alertes"))
        assertEquals(3, r.getInt("messages"), "« P12. » commun n'est pas une différence")
        assertEquals(0, r.getInt("decisions"))
        assertEquals(3, r.getInt("positions"))
        val res = d.getJSONArray("resultats").getJSONObject(0)
        assertEquals("resultat_archive", res.getString("champ"))
        assertEquals("P12/16", res.getString("v510"))
        assertEquals("P13/16", res.getString("v511"))
        val md = Compare.markdown(d)
        assertTrue("# Pitwall Lab — comparaison `v510` → `v511`" in md)
        assertTrue("➕ (v511) [HIGH] Classement GT7 révisé après l'arrivée : P13 sur 16." in md, md)
        assertTrue("GABARIT" in md, "l'avertissement gabarit est repris")
    }

    @Test
    fun identicalReportsAreSaidIdentical() {
        val a = report("P13/16", listOf("HIGH" to "x"), listOf("DECISION" to "y"))
        val d = Compare.diff(a, JSONObject(a.toString()), "A", "B")
        assertTrue(d.getBoolean("identique"))
        assertTrue("Aucune différence" in Compare.markdown(d))
    }
}
