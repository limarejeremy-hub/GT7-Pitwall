package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** vega-golden : la trace est un GABARIT fabriqué (jamais une mesure) ; les réponses viennent du moteur déterministe. */
class VegaGoldenTest {
    private val dir = Files.createTempDirectory("vega").toFile()
    private val trace = Gabarit.write(dir, anomalies = false)

    private fun scenarios(vararg s: JSONObject): File =
        File(dir, "s.json").also { it.writeText(JSONObject().put("scenarios", JSONArray(s.toList())).toString()) }

    private fun sc(id: String, point: String, q: List<String>, verifs: JSONObject) =
        JSONObject().put("id", id).put("categorie", "test").put("point", point).put("questions", JSONArray(q)).put("verifs", verifs)

    @Test
    fun positionSafetyAndUnreachedPoint() {
        val r = VegaGolden.run(
            trace,
            scenarios(
                sc("position", "tour:2", listOf("quelle est ma position"), JSONObject().put("non_vide", true).put("etat_inchange", true)
                    .put("nombre_proche", JSONObject().put("fait", "position").put("tolerance", 0))),
                sc("negation", "tour:2", listOf("je n'ai pas changé les pneus"), JSONObject().put("etat_inchange", true)),
                sc("absent", "tour:9", listOf("quelle est ma position"), JSONObject().put("non_vide", true)),
                sc("arrivee", "arrivee+2000", listOf("quelle est ma position"), JSONObject().put("non_vide", true)
                    .put("nombre_proche", JSONObject().put("fait", "position_archivee").put("tolerance", 0))),
            ),
        )
        assertTrue(r.has("avertissement"), "gabarit signalé")
        val byId = (0 until r.getJSONArray("scenarios").length()).map { r.getJSONArray("scenarios").getJSONObject(it) }.associateBy { it.getString("id") }
        val pos = byId.getValue("position")
        assertEquals("REUSSI", pos.getString("verdict"), pos.toString())
        assertEquals("moteur déterministe", pos.getString("origine"))
        assertEquals("Tu es P12 sur 16.", pos.getJSONArray("echanges").getJSONObject(0).getString("reponse"))
        assertEquals("REUSSI", byId.getValue("negation").getString("verdict"), byId.getValue("negation").toString())
        assertEquals("NON_EVALUABLE", byId.getValue("absent").getString("verdict"))
        val arr = byId.getValue("arrivee")
        assertEquals("REUSSI", arr.getString("verdict"), arr.toString())
        assertTrue("P13 sur 16" in arr.getJSONArray("echanges").getJSONObject(0).getString("reponse"), "classement révisé après la ligne")
        assertEquals(3, r.getJSONObject("resume").getInt("reussis"))
        assertTrue("# Pitwall Lab — vega-golden" in VegaReport.markdown(r))
    }

    @Test
    fun aFailingRuleIsReportedWithItsDetail() {
        val r = VegaGolden.run(trace, scenarios(
            sc("piege", "tour:2", listOf("quelle est ma position"), JSONObject().put("ne_contient_pas", JSONArray(listOf("P12")))),
        ))
        val s = r.getJSONArray("scenarios").getJSONObject(0)
        assertEquals("ECHOUE", s.getString("verdict"))
        assertTrue("contient « P12 »" in s.getJSONArray("verifs").getJSONObject(0).getString("detail"))
    }

    @Test
    fun chronosAndNumbersAreRead() {
        assertEquals(listOf(92.4), VegaGolden.chronos("Dernier tour : 1 minute 32 secondes 4."))
        assertEquals(listOf(90.601), VegaGolden.chronos("meilleur 1:30.601"))
        assertEquals(listOf(47.2, 2.0, 2.0, 42.3), VegaGolden.numbers("Carburant pour 47,2 tours. Consommation 2 % du réservoir par tour sur 2 tours. Marge de 42,3 tours."))
    }

    private fun report(vararg v: Pair<String, String>) = JSONObject().put("trace", "t.gt7trace")
        .put("resume", JSONObject().put("reussis", v.count { it.second == "REUSSI" }).put("echoues", v.count { it.second == "ECHOUE" }).put("non_evaluables", 0))
        .put("scenarios", JSONArray(v.map { (id, verdict) ->
            JSONObject().put("id", id).put("categorie", "c").put("verdict", verdict)
                .put("echanges", JSONArray().put(JSONObject().put("question", "q").put("reponse", "$id $verdict")))
                .put("verifs", JSONArray())
        }))

    @Test
    fun diffSeparatesImprovementsRegressionsAndChanges() {
        val a = report("x" to "ECHOUE", "y" to "REUSSI", "z" to "REUSSI", "w" to "REUSSI")
        val b = report("x" to "REUSSI", "y" to "ECHOUE", "z" to "REUSSI", "w" to "REUSSI")
        // w : même verdict mais réponse identique ; z : réponse identique aussi.
        val d = VegaReport.diff(a, b, "v510", "v511")
        val s = d.getJSONObject("resume")
        assertEquals(1, s.getInt("ameliorations"))
        assertEquals(1, s.getInt("regressions"))
        assertEquals(2, s.getInt("identiques"))
        val md = VegaReport.diffMarkdown(d)
        assertTrue("## Régressions" in md && "## Améliorations" in md, md)
        assertTrue("Aucun modèle cloud évalué" in md)
    }

    @Test
    fun repositoryScenariosCoverEveryRequiredCategoryAndAlwaysCheckSafety() {
        val spec = JSONObject(File("vega-golden/scenarios.json").let { if (it.isFile) it else File("lab/vega-golden/scenarios.json") }.readText())
        val list = spec.getJSONArray("scenarios").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val cats = list.map { it.getString("categorie") }.toSet()
        for (c in listOf("carburant", "stratégie", "stands", "pilotage", "contexte", "données absentes", "sécurité commandes")) assertTrue(c in cats, "catégorie manquante : $c")
        assertTrue(list.all { it.getJSONObject("verifs").optBoolean("etat_inchange") }, "chaque question vérifie qu'elle ne modifie pas la course")
        assertEquals(list.size, list.map { it.getString("id") }.toSet().size, "identifiants uniques")
    }
}
