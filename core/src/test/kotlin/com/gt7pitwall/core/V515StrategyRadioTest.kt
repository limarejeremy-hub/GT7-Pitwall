package com.gt7pitwall.core

import com.gt7pitwall.core.display.StrategyBoard
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.race.StrategyDialogue.Ask
import com.gt7pitwall.core.strategy.RaceRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V515-C : Véga ingénieur stratégie (course SIMULÉE : vérifie le logiciel, pas une course PS5). Les réponses viennent
 * de la décision du contrôleur ; aucune n'invente d'écart adverse ni d'usure.
 */
class V515StrategyRadioTest {
    private var now = 0L

    private fun race(): RaceSim {
        val c = RaceController(storage = FakeStorage(), clock = { now })
        c.setRules(RaceRules(totalLaps = 14, mandatoryStops = 1))
        return RaceSim(c, totalLaps = 14, perLap = 9.8f).also { s -> s.tap = { now += 17 } }
    }

    private fun ask(s: RaceSim, q: String): String {
        val a = StrategyDialogue.classify(q) ?: error("non reconnue : $q")
        return s.controller.strategyAnswer(a, q).filterNot { it.text.startsWith("Toi :") }.joinToString(" ") { it.text }
    }

    @Test
    fun theTenQuestionsAreUnderstood() {
        val expected = mapOf(
            "Véga, quelle est notre meilleure stratégie ?" to Ask.Best,
            "On tente l'undercut ?" to Ask.Undercut,
            "Et si on reste en piste un tour de plus ?" to Ask.Extend(1),
            "Est-ce qu'on peut faire l'overcut ?" to Ask.Overcut,
            "Si je change les pneus maintenant, combien de carburant faut-il ?" to Ask.FuelIfTyresNow,
            "Mes pneus peuvent tenir jusqu'au dernier tour ?" to Ask.TyreLife,
            "Peut-on éviter le prochain arrêt ?" to Ask.AvoidStop,
            "Est-ce que perdre 25 secondes aux stands vaut le coup ?" to Ask.WorthPitLoss(25),
            "Qu'est-ce qui a changé dans notre stratégie ?" to Ask.WhatChanged,
            "Quelle est la stratégie la moins risquée ?" to Ask.LeastRisk,
        )
        for ((q, a) in expected) assertEquals(a, StrategyDialogue.classify(q), q)
    }

    @Test
    fun answersComeFromTheControllerDecisionAndInventNothing() {
        val s = race()
        s.start()
        while (s.lap < 4) s.step(80f)
        val cmd = assertNotNull(s.controller.strategyCommand, "commande V515 calculée au recalcul du plan")
        assertTrue(cmd.report.evaluated > 0)
        // Les rappels « Box » et le plan ne changent pas : la commande ne fait que conseiller.
        assertEquals(s.controller.plan!!.stops.map { it.endOfLap }, cmd.report.reference!!.stopLaps)
        val answers = listOf(
            "Véga, quelle est notre meilleure stratégie ?", "On tente l'undercut ?", "Est-ce qu'on peut faire l'overcut ?",
            "Si je change les pneus maintenant, combien de carburant faut-il ?", "Peut-on éviter le prochain arrêt ?",
            "Est-ce que perdre 25 secondes aux stands vaut le coup ?", "Quelle est la stratégie la moins risquée ?",
        ).associateWith { ask(s, it) }
        for ((q, a) in answers) {
            assertTrue(a.isNotBlank(), q)
            assertFalse(Regex("usure|\\d+[,.]?\\d* ?s (devant|derrière)").containsMatchIn(a), "$q → $a")
            assertTrue(a.length < 400, "réponse trop longue pour la conduite : $a")
        }
        assertTrue(answers.getValue("On tente l'undercut ?").contains("ndercut"), answers.toString())
        // Sans dégradation démontrée, le gain de pneus neufs n'est pas inventé.
        assertTrue("dégradation" in answers.getValue("Est-ce que perdre 25 secondes aux stands vaut le coup ?"), answers.toString())
        // Le tableau est construit et affiche le contrôleur.
        val b = StrategyBoard.of(cmd.report, cmd.decision)
        assertTrue(b.checks.isNotEmpty() && b.alternatives.isNotEmpty())
        assertTrue(b.alternatives.none { it.status == StrategyBoard.Status.IMPOSSIBLE && it.delta.startsWith("inconnu") })
    }

    @Test
    fun noRadioAvalancheFromTheNewEngine() {
        val s = race()
        s.start()
        val before = s.said.size
        while (s.lap < 6) s.step(80f)
        val spoken = s.said.drop(before).map { it.text }
        assertTrue(spoken.none { "Stratégie V515" in it || "undercut" in it.lowercase() || "overcut" in it.lowercase() }, spoken.toString())
    }
}
