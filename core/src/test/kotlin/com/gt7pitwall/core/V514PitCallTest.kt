package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.strategy.RaceRules
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * V514-B, rappel radio obligatoire : les deux annonces « Box » (début du tour d'arrêt, puis dernier secteur) restent
 * fiables SANS fiche Sport de la semaine (aucun profil ni édition), qu'il y ait un règlement saisi ou seulement le
 * nombre de tours transmis par GT7. Course SIMULÉE : vérifie le logiciel, pas une course PS5.
 */
class V514PitCallTest {
    private fun lapTexts(c: RaceController, s: RaceSim, stopLap: Int): List<String> {
        // De la fin du tour précédent (l'annonce « Box, box » part au passage de la ligne) à la fin du tour d'arrêt.
        while (s.lap < stopLap - 1) s.step(80f)
        val start = s.said.size
        while (s.lap <= stopLap) s.step(80f)
        return s.said.drop(start).map { it.text }
    }

    @Test
    fun bothBoxCallsWithoutAnyWeeklySheetAndPilotRules() {
        val c = RaceController(storage = FakeStorage())
        c.setRules(RaceRules(totalLaps = 13, mandatoryStops = 1))
        val s = RaceSim(c, totalLaps = 13, perLap = 9.8f)
        s.start()
        while (s.lap < 3) s.step(80f)
        val stop = c.plan!!.nextStop(s.lap)!!.endOfLap
        val texts = lapTexts(c, s, stop)
        assertTrue(texts.any { it.startsWith("Box, box") }, "annonce de début de tour : $texts")
        assertTrue(texts.any { it.startsWith("Box à la fin de ce tour") }, "annonce du dernier secteur : $texts")
        assertTrue(c.edition == null)
    }

    @Test
    fun bothBoxCallsWhenOnlyGt7GivesTheLapCount() {
        val c = RaceController(storage = FakeStorage())
        // Aucun règlement saisi : seul le carburant impose l'arrêt (14 tours à 9,8 % par tour pour un réservoir plein).
        val s = RaceSim(c, totalLaps = 14, perLap = 9.8f)
        s.start()
        while (s.lap < 3) s.step(80f)
        val stop = c.plan?.nextStop(s.lap)?.endOfLap ?: error("aucun arrêt planifié : ${s.texts.takeLast(5)}")
        val texts = lapTexts(c, s, stop)
        assertTrue(texts.any { it.startsWith("Box, box") }, "annonce de début de tour : $texts")
        assertTrue(texts.any { it.startsWith("Box à la fin de ce tour") }, "annonce du dernier secteur : $texts")
    }
}
