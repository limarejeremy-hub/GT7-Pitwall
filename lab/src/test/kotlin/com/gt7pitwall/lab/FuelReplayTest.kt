package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `carburant` sur un journal GABARIT fabriqué (jamais une mesure) qui reprend la forme de la 2e course du Mans du 23/11 :
 * 14 tours à environ 10 par tour, arrêt obligatoire au tour 7 avec un plein trop court.
 */
class FuelReplayTest {
    private fun journal(added: Float): File {
        val laps = JSONArray()
        var fuel = 100f
        for (n in 1..14) {
            val refuel = n == 8
            if (refuel) fuel += added
            fuel -= 10f
            laps.put(JSONObject().put("lap", n).put("timeMs", if (n == 7 || n == 8) 260_000 else 222_000)
                .put("fuelUsed", if (refuel) -1 else 10.0).put("refuelled", refuel).put("fuelAfter", fuel.toDouble()))
        }
        val archive = JSONObject()
            .put("context", JSONObject().put("fuelCapacity", 100).put("gameTotalLaps", 14).put("rules", JSONObject().put("mandatoryStops", 1)))
            .put("outcome", "FINISHED").put("laps", laps)
            .put("stops", JSONArray().put(JSONObject().put("lap", 7).put("fuelAdded", added.toDouble())))
            .put("plans", JSONArray().put(JSONObject().put("lap", 0).put("stops", JSONArray().put(7))))
        val events = JSONArray().put(JSONObject().put("type", "PIT").put("lap", 7).put("text", "Passage au stand détecté (tour 7) : +${added} de carburant, immobile 35,0 s"))
        return Files.createTempFile("journal-GABARIT-NON-MESURE", ".json").toFile()
            .also { it.writeText(JSONObject().put("archive", archive).put("events", events).toString()) }
    }

    @Test
    fun shortRefuelLeadsToAQuantifiedDecisionAfterTheStop() {
        val r = FuelReplay.run(journal(37.4f))
        val rows = r.getJSONArray("decisions").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertTrue(rows.filter { it.getInt("apres_tour") < 8 }.all { it.getString("decision_V512") == "ARRET_OBLIGATOIRE_A_FAIRE" })
        val t8 = rows.first { it.getInt("apres_tour") == 8 }
        assertTrue(t8.getString("decision_V512") in setOf("SAVE", "STOP"), t8.toString())
        assertTrue(t8.getDouble("deficit_avec_marge_CALCUL") > 0)
        assertTrue(r.getString("nature").contains("pas un rejeu de paquets"))
    }

    @Test
    fun aFullRefuelNeedsNoDecision() {
        val rows = FuelReplay.run(journal(60f)).getJSONArray("decisions")
        val after = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getInt("apres_tour") >= 8 }
        assertTrue(after.all { it.getString("decision_V512") == "ENOUGH" }, after.toString())
        assertEquals(14, FuelReplay.run(journal(60f)).getInt("tours_gt7"))
    }
}
