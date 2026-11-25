package com.gt7pitwall.core

import com.gt7pitwall.core.display.TyreBoard
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.race.StrategyDialogue.Ask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V514-A : questions de Véga sur les pneus et les stands, carte PNEUS de bout en bout (course SIMULÉE : vérifie le
 * logiciel, pas la justesse d'un modèle sur GT7). Aucune réponse ni aucune carte ne donne de pourcentage d'usure.
 */
class V514TyreRadioTest {
    private var now = 0L

    private fun race(): RaceSim {
        val c = RaceController(storage = FakeStorage(), clock = { now })
        return RaceSim(c, totalLaps = 12, perLap = 5f).also { s -> s.tap = { now += 17 } }
    }

    private fun ask(s: RaceSim, q: String): String {
        val a = StrategyDialogue.classify(q) ?: error("non reconnue : $q")
        return s.controller.strategyAnswer(a, q).filterNot { it.text.startsWith("Toi :") }.joinToString(" ") { it.text }
    }

    @Test
    fun theQuestionsOfThePilotAreRecognised() {
        val expected = mapOf(
            "Véga, comment sont mes pneus ?" to Ask.TyreStatus,
            "Quels pneus souffrent le plus ?" to Ask.TyreWorst,
            "Quel pneu souffre le plus ?" to Ask.TyreWorst,
            "Mes pneus avant chauffent ?" to Ask.TyreHeat(true),
            "Est-ce que l'arrière chauffe ?" to Ask.TyreHeat(false),
            "Combien de tours peuvent encore tenir mes pneus ?" to Ask.TyreLife,
            "Est-ce rentable de changer maintenant ?" to Ask.GainPitNow,
            "Je peux finir avec mon carburant ?" to Ask.FuelToFinish,
            "Combien dois-je économiser ?" to Ask.FuelSave,
            "Combien de temps vais-je perdre aux stands ?" to Ask.PitLoss,
        )
        for ((q, a) in expected) assertEquals(a, StrategyDialogue.classify(q), q)
        // « Je perds de l'adhérence ? » : question sur un constat, la mesure répond (existant, inchangé).
        assertTrue(StrategyDialogue.classify("Je perds de l'adhérence ?") is Ask.GripQuestion)
    }

    @Test
    fun answersAreShortHonestAndNeverAWearPercentage() {
        val s = race()
        s.start()
        s.tyreTemps = floatArrayOf(84f, 84f, 78f, 79f)
        s.driveLaps(3)
        // L'avant gauche chauffe nettement plus que l'avant droit sur deux tours complets.
        s.tyreTemps = floatArrayOf(99f, 84f, 78f, 79f)
        s.driveLaps(3)
        val worst = ask(s, "Quels pneus souffrent le plus ?")
        assertTrue("avant gauche" in worst, worst)
        assertTrue("pas une usure" in worst, worst)
        val front = ask(s, "Mes pneus avant chauffent ?")
        assertTrue(front.startsWith("Oui"), front)
        assertTrue("plage idéale" in front, front)
        val status = ask(s, "Véga, comment sont mes pneus ?")
        assertTrue("Température" in status, status)
        for (a in listOf(worst, front, status)) assertFalse(Regex("\\d+ ?%").containsMatchIn(a), "aucun pourcentage : $a")
        assertTrue(worst.length < 220 && front.length < 260, "réponses courtes")
    }

    @Test
    fun pitLossIsNeverInventedWithoutData() {
        val s = race()
        s.start()
        s.driveLaps(2)
        val a = ask(s, "Combien de temps vais-je perdre aux stands ?")
        assertTrue("ne peux pas le chiffrer" in a || "ESTIMATION" in a || "d'après" in a, a)
    }

    @Test
    fun theTyreCardIsBuiltFromTheEngineAndHasNoWearGauge() {
        val s = race()
        s.start()
        s.driveLaps(4)
        val board = TyreBoard.of(s.controller.tyreDiagnostics(), s.controller.tyreTrain())
        assertEquals(4, board.cells.size)
        assertTrue(board.details.any { "Usure : non transmise par GT7" in it.text })
        assertTrue(board.cells.all { it.nowC != null && it.lapMeanC != null })
        val all = (board.messages + board.details).joinToString(" ") { it.text } + board.headline
        assertFalse(Regex("usure \\d|\\d+ ?% d'usure|reste \\d+ ?%").containsMatchIn(all), all)
        // Pas de nouvelle annonce radio spontanée pour une variation de température.
        assertTrue(s.texts.none { "°C" in it })
    }

    @Test
    fun aRefuelStopWithoutTyreChangeKeepsTheTyreAgeAndRaisesNoColdAlarm() {
        val s = race()
        s.start()
        s.driveLaps(3)
        s.pitStop(fuel = 20f, stopSeconds = 8f)
        s.driveTo(1.0f)
        val r = s.controller.tyreDiagnostics()
        assertEquals(1, r.stintStartLap, "un plein seul ne remet pas l'âge des pneus à zéro")
        assertTrue(r.issues.none { it.kind == com.gt7pitwall.core.tyres.TyreDiagnostics.IssueKind.TEMPERATURE }, r.issues.toString())
    }
}
