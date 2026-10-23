package com.gt7pitwall.core

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.race.StrategyDialogue.Ask
import com.gt7pitwall.core.race.StrategyDialogue.ObservationKind
import com.gt7pitwall.core.radio.RadioPacer
import com.gt7pitwall.core.strategy.TyreCompound
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D5 : questions pneus et endurance à la radio, réponses du moteur, aucune intervention enregistrée par une question. */
class TyreRadioTest {

    @Test
    fun naturalTyreQuestionsAreRecognised() {
        val expected = mapOf(
            "Radio, comment vont mes pneus ?" to Ask.TyreStatus,
            "Ils sont encore bons ?" to Ask.TyreStatus,
            "Il me reste combien de tours avec ces gommes ?" to Ask.TyreLife,
            "Je perds l'avant, tu vois quelque chose ?" to Ask.GripQuestion(ObservationKind.GRIP_FRONT),
            "Je patine beaucoup en sortie, pourquoi ?" to Ask.GripQuestion(ObservationKind.WHEELSPIN),
            "Est-ce qu'on doit changer les pneus ?" to Ask.TyreChange,
            "Est-ce qu'on gagnerait du temps en rentrant maintenant ?" to Ask.GainPitNow,
            "On peut prolonger de deux tours ?" to Ask.Extend(2),
            "Pourquoi tu veux me faire rentrer alors que j'ai assez d'essence ?" to Ask.WhyPitWithFuel,
            "Je change seulement les pneus." to Ask.TyresOnlyIntent,
        )
        for ((phrase, ask) in expected) {
            for (s in listOf(phrase, phrase.replace(Regex("[?!.,]"), "").trim())) {
                assertEquals(ask, StrategyDialogue.classify(s), s)
            }
        }
        // « Je viens de monter des mediums » est une déclaration, pas une question : elle suit le chemin des commandes.
        assertEquals(null, StrategyDialogue.classify("je viens de monter des mediums"))
    }

    private fun live(): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = FakeStorage())
        val sim = RaceSim(c, totalLaps = 15, perLap = 4f)
        sim.start()
        sim.driveLaps(5)
        return c to sim
    }

    private fun ask(conv: RadioConversation, s: String) =
        runBlocking { conv.onUtterance(s, s, null) }.filter { it.category != MessageCategory.DRIVER }

    @Test
    fun questionsNeverChangeTheRaceState() {
        val (c, _) = live()
        val conv = RadioConversation(c)
        val before = Triple(c.ledger.stopCount, c.ledger.changes.size, c.rules)
        val all = mutableListOf<String>()
        for (s in listOf(
            "radio comment vont mes pneus", "ils sont encore bons", "il me reste combien de tours avec ces gommes",
            "je perds l'avant tu vois quelque chose", "je patine beaucoup en sortie pourquoi", "est-ce qu'on doit changer les pneus",
            "est-ce qu'on gagnerait du temps en rentrant maintenant", "on peut prolonger de deux tours",
            "pourquoi tu veux me faire rentrer alors que j'ai assez d'essence", "je change seulement les pneus",
        )) {
            val out = ask(conv, s)
            assertTrue(out.isNotEmpty(), s)
            all += out.map { it.text }
        }
        assertEquals(before, Triple(c.ledger.stopCount, c.ledger.changes.size, c.rules), "aucune question ni intention n'enregistre une intervention")
        assertFalse(all.any { Regex("\\d+ ?% d'usure|usés? à \\d+|usure de \\d+ ?%").containsMatchIn(it) }, all.toString())
        assertFalse(all.any { it.startsWith("Gemini") }, "aucune réponse pneus calculée par Gemini")
        // Réponses courtes pour la conduite.
        assertTrue(all.all { it.length <= 300 }, all.filter { it.length > 300 }.toString())
    }

    @Test
    fun answersSayWhatIsMeasuredDeclaredOrUnknown() {
        val (c, _) = live()
        val conv = RadioConversation(c)
        val status = ask(conv, "comment vont mes pneus").single().text
        assertTrue(status.startsWith("Pneus de "), status)
        assertTrue("gomme non déclarée" in status, status)
        val life = ask(conv, "il me reste combien de tours avec ces gommes").single().text
        assertTrue(life.startsWith("Je ne peux pas le dire"), "aucune durée de vie inventée sans historique : $life")
        val grip = ask(conv, "je perds l'avant tu vois quelque chose").single().text
        assertTrue(grip.startsWith("Noté, d'après toi."), grip)
        assertTrue(c.activeObservations.any { it.kind == ObservationKind.GRIP_FRONT })
        assertTrue(c.aiFacts().lines.any { it.startsWith("Déclaré par le pilote") && "avant" in it })
        val why = ask(conv, "pourquoi").single().text
        assertTrue(why.startsWith("Je n'ai pas encore de mesure nette") || why.startsWith("Je me base sur"), why)
        val change = ask(conv, "est-ce qu'on doit changer les pneus").single().text
        assertTrue(change.startsWith("Non") || "dégradation" in change, change)
    }

    @Test
    fun declaredTyreLifeIsAnsweredAsDeclared() {
        val (c, sim) = live()
        sim.say("les pneus tiennent dix tours")
        val life = ask(RadioConversation(c), "il me reste combien de tours avec ces gommes").single().text
        assertTrue(life.startsWith("Tu m'as dit qu'ils tiennent 10 tours (déclaré)"), life)
    }

    @Test
    fun fittingTyresIsADeclarationNotAQuestion() {
        val (c, sim) = live()
        sim.say("je viens de monter des mediums course")
        assertEquals(TyreCompound.RM, c.ledger.changes.single().compound)
        assertEquals(sim.lap, c.engineer.stintContext.startLap, "nouveau relais pneus")
    }

    @Test
    fun nonUrgentMessagesWaitForTheStraightButAlertsNever() {
        val pacer = RadioPacer(maxHoldMs = 6_000)
        val info = EngineerMessage("Températures des pneus stabilisées, 85 degrés en moyenne.", Priority.INFO, MessageCategory.LAP)
        val lap = EngineerMessage("Tour 4 bouclé.", Priority.NORMAL, MessageCategory.LAP)
        val box = EngineerMessage("Box, box !", Priority.CRITICAL, MessageCategory.ALERT)
        val answer = EngineerMessage("Pneus de 4 tours.", Priority.HIGH, MessageCategory.ANSWER)
        assertEquals(listOf(box, answer), pacer.pace(listOf(info, lap, box, answer), busy = true, nowMs = 0))
        assertEquals(emptyList<EngineerMessage>(), pacer.pace(emptyList(), busy = true, nowMs = 2_000))
        assertEquals(listOf(info, lap), pacer.pace(emptyList(), busy = false, nowMs = 2_500), "dans la ligne droite")
        pacer.pace(listOf(info), busy = true, nowMs = 10_000)
        assertEquals(listOf(info), pacer.pace(emptyList(), busy = true, nowMs = 16_000), "jamais plus de 6 s")
    }
}
