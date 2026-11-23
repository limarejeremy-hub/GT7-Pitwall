package com.gt7pitwall.lab

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `endurance` sur la trace GABARIT d'endurance (fabriquée, jamais une mesure) et le règlement Le Mans du dépôt. */
class EnduranceTest {
    private val dir = Files.createTempDirectory("endurance").toFile()
    private val trace = Gabarit.writeEndurance(dir)
    private val rules = File("le-mans/regles-sport-c-2026-11-23.json").let { if (it.isFile) it else File("lab/le-mans/regles-sport-c-2026-11-23.json") }

    @Test
    fun lapsStopRefuelAndFreshTyreIndice() {
        val r = Endurance.run(trace)
        assertTrue(r.has("avertissement"), "gabarit signalé")
        val c = r.getJSONArray("courses").also { assertEquals(1, it.length()) }.getJSONObject(0)
        assertTrue(c.getBoolean("terminee"))
        assertEquals(4, c.getInt("tours_boucles"))
        assertEquals(3, c.getJSONObject("position_ligne_0x84").getInt("valeur"))
        val stops = c.getJSONArray("arrets")
        assertEquals(1, stops.length(), stops.toString())
        val s = stops.getJSONObject(0)
        assertEquals(2, s.getInt("tour"))
        assertTrue(s.getBoolean("plein"))
        assertEquals("INDICE", s.getJSONObject("signature_pneus_neufs").getString("nature"))
        assertTrue(s.getJSONObject("signature_pneus_neufs").getBoolean("valeur"))
        assertTrue(s.getJSONObject("duree_immobile_ms").getLong("valeur") in 19_000L..20_100L)
        // Tour 1 : 20 s (MESURE 0x7C) ; consommation des tours sans arrêt ≈ 2 L (CALCUL).
        val lap1 = c.getJSONArray("tours").getJSONObject(0)
        assertEquals(20_000, lap1.getJSONObject("chrono_ms").getInt("valeur"))
        val fuel = c.getJSONObject("carburant")
        assertEquals(2.0, fuel.getJSONObject("consommation_mediane_par_tour").getDouble("valeur"), 0.15)
        assertEquals("CALCUL", fuel.getJSONObject("tours_par_plein").getString("nature"))
        // Rien de mesuré sur la météo ou l'usure : déclaré non mesurable.
        assertTrue(r.getJSONArray("non_mesurable").toString().contains("météo", ignoreCase = true))
    }

    @Test
    fun publishedRulesCheck() {
        assertTrue(rules.isFile, "règlement Le Mans présent dans le dépôt")
        val c = Endurance.run(trace, rules).getJSONArray("courses").getJSONObject(0)
        val checks = c.getJSONArray("controle_reglement").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("regle") } }
        // Le gabarit fait 4 tours : écart attendu face aux 14 tours du Mans.
        assertEquals("ECART", checks.getValue("tours").getString("verdict"))
        assertEquals("CONFORME", checks.getValue("arrets_obligatoires").getString("verdict"))
        // Un arrêt obligatoire n'implique pas un changement de pneus.
        val tyres = checks.getValue("changement_pneus_impose")
        assertEquals(false, tyres.getBoolean("attendu_REGLEMENT"))
        assertTrue(tyres.getString("note").contains("n'implique pas"))
        assertEquals("NON_EVALUABLE", checks.getValue("usure_multiplicateur").getString("verdict"))
    }

    @Test
    fun leMansVegaScenariosDeclareRules() {
        val sc = File("vega-golden/le-mans.json").let { if (it.isFile) it else File("lab/vega-golden/le-mans.json") }
        val spec = JSONObject(sc.readText())
        assertEquals(14, spec.getJSONObject("regles").getInt("tours"))
        val r = VegaGolden.run(trace, sc)
        assertTrue(r.getString("profil").startsWith("DAILY_C"))
        assertTrue(r.getString("regles").contains("mandatoryStops=1"))
        val byId = (0 until r.getJSONArray("scenarios").length()).map { r.getJSONArray("scenarios").getJSONObject(it) }.associateBy { it.getString("id") }
        // Le gabarit n'a que 4 tours : les points au-delà ne sont pas atteints, jamais réussis par défaut.
        assertEquals("NON_EVALUABLE", byId.getValue("lm-tours-restants").getString("verdict"))
        // Aucune question n'impose des pneus neufs ni ne modifie la course.
        val tyres = byId.getValue("lm-pneus-non-imposes")
        assertTrue(tyres.getString("verdict") in setOf("REUSSI", "ECHOUE"), tyres.toString())
        println("lm-pneus-non-imposes : " + tyres.getJSONArray("echanges").getJSONObject(0).getString("reponse"))
        println("lm-arret-obligatoire : " + byId.getValue("lm-arret-obligatoire").optJSONArray("echanges")?.getJSONObject(0)?.getString("reponse"))
    }
}
