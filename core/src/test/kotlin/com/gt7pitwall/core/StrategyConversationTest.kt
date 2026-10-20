package com.gt7pitwall.core

import com.gt7pitwall.core.ai.LanguageModel
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.race.StrategyDialogue.Ask
import com.gt7pitwall.core.race.StrategyDialogue.ObservationKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Jalon C : la radio reliée au moteur stratégique. Questions naturelles (avec ou sans « ? », Vosk n'en transcrit pas),
 * négations, hypothèses, observations, et courses simulées complètes avec plusieurs échanges successifs.
 */
class StrategyConversationTest {

    /** Gemini scripté : il ne doit jamais être appelé pour une question stratégique. */
    private class ScriptedModel(private val reply: String) : LanguageModel {
        val prompts = mutableListOf<String>()
        override val ready = true
        override val status = "prêt"
        override suspend fun generate(systemInstruction: String, prompt: String): String? {
            prompts += prompt
            return reply
        }
    }

    private fun reply(conv: RadioConversation, text: String, model: LanguageModel? = null): List<EngineerMessage> =
        runBlocking { conv.onUtterance(text, text, model) }.filter { it.category != MessageCategory.DRIVER }

    private fun answer(conv: RadioConversation, text: String, model: LanguageModel? = null): String =
        reply(conv, text, model).first { it.category == MessageCategory.ANSWER }.text

    /** Course de 10 tours, 14,5 % du réservoir par tour : un arrêt carburant est nécessaire. */
    private fun oneStopRace(laps: Int = 2): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = FakeStorage())
        val sim = RaceSim(c, totalLaps = 10, perLap = 14.5f)
        sim.start()
        sim.driveLaps(laps)
        return c to sim
    }

    /** Rien de ce qui compte pour la course ne doit bouger sur une question. */
    private data class Ledger(val stops: Int, val changes: Int, val rules: Any)

    private fun ledger(c: RaceController) = Ledger(c.ledger.stopCount, c.ledger.changes.size, c.rules)

    // ------------------------------------------------------------------ Classification

    @Test
    fun naturalQuestionsAreRecognisedWithAndWithoutQuestionMark() {
        val expected = mapOf(
            "Radio, quelle est notre meilleure stratégie ?" to Ask.Best,
            "tu me conseilles quoi" to Ask.Best,
            "Pourquoi on rentre au tour 5 ?" to Ask.WhyStop(5),
            "pourquoi on s'arrête" to Ask.WhyStop(null),
            "On peut prolonger d'un tour ?" to Ask.Extend(),
            "si je reste dehors un tour de plus" to Ask.Extend(),
            "on peut avancer l'arrêt" to Ask.Early,
            "Devant moi, ils commencent à rentrer. On fait quoi ?" to Ask.OthersPitting,
            "les autres vont aux stands" to Ask.OthersPitting,
            "Combien de carburant pour finir ?" to Ask.FuelToFinish,
            "j'ai assez d'essence pour finir" to Ask.FuelToFinish,
            "Qu'est-ce qui a changé depuis notre plan initial ?" to Ask.WhatChanged,
            "pourquoi tu changes la stratégie" to Ask.WhatChanged,
            "Je perds de l'adhérence à l'avant." to Ask.Observe(ObservationKind.GRIP_FRONT, false),
            "l'arrière glisse" to Ask.Observe(ObservationKind.GRIP_REAR, false),
            "mes pneus sont finis" to Ask.Observe(ObservationKind.TYRES_WORN, false),
            "je suis bloqué derrière une voiture" to Ask.Observe(ObservationKind.TRAFFIC, false),
        )
        for ((phrase, ask) in expected) {
            for (s in listOf(phrase, phrase.replace(Regex("[?!.,]"), "").trim())) {
                assertEquals(ask, StrategyDialogue.classify(s), s)
            }
        }
    }

    @Test
    fun negationsAndOtherSubjectsAreNotStrategyQuestions() {
        assertEquals(Ask.Observe(ObservationKind.GRIP_FRONT, true), StrategyDialogue.classify("je ne perds pas l'avant"))
        assertNull(StrategyDialogue.classify("je ne veux pas prolonger"))
        assertNull(StrategyDialogue.classify("j'ai fait mon arrêt"))
        assertNull(StrategyDialogue.classify("combien d'essence"), "le carburant simple reste à l'analyseur")
        assertNull(StrategyDialogue.classify("je rentre maintenant"), "une affirmation n'est pas une question d'arrêt anticipé")
        assertNull(StrategyDialogue.classify("radio tu m'entends"))
        assertNull(StrategyDialogue.classify("pourquoi"), "une relance seule n'a de sens qu'après une réponse")
        assertEquals(Ask.FollowUp, StrategyDialogue.classify("pourquoi", afterStrategyAnswer = true))
        assertEquals(Ask.FollowUp, StrategyDialogue.classify("tu es sûr", afterStrategyAnswer = true))
        assertEquals(Ask.Unclear, StrategyDialogue.classify("[unk] la stratégie [unk]"))
    }

    // ------------------------------------------------------------------ Course simulée complète

    @Test
    fun fullRaceConversationAnswersFromTheEngineAndRecordsNothing() {
        val (c, sim) = oneStopRace()
        val conv = RadioConversation(c)
        val model = ScriptedModel("Tu es P2, rentre au tour 3.")
        val start = ledger(c)
        val stop = c.plan!!.stops.first().endOfLap

        // 1. Meilleure stratégie : la recommandation du rapport, avec le tour d'arrêt.
        val best = answer(conv, "radio quelle est notre meilleure stratégie", model)
        assertTrue(c.strategyBrief!!.recommendation in best, best)
        assertTrue("tour $stop" in best, best)

        // 2. Relance « pourquoi ? » : sur quoi repose la recommandation.
        val why = answer(conv, "pourquoi", model)
        assertTrue("Je me base sur" in why, why)

        // 3. Le pilote cite un autre tour : on le corrige, sans rien changer.
        val wrongLap = stop - 2
        val whyStop = answer(conv, "pourquoi on rentre au tour $wrongLap", model)
        assertTrue("tour $stop" in whyStop && "le carburant" in whyStop, whyStop)
        assertTrue("pas le $wrongLap" in whyStop, whyStop)

        // 4. Prolonger d'un tour : ce que dit la comparaison des stratégies.
        val extend = answer(conv, "on peut prolonger d'un tour", model)
        assertTrue(extend.startsWith("Prolonger d'un tour : "), extend)
        assertTrue(c.strategyBrief!!.extendOneLap in extend, extend)

        // 5. Les autres rentrent : aucune donnée inventée sur eux, notre plan.
        val others = answer(conv, "devant moi ils commencent à rentrer on fait quoi", model)
        assertTrue("aucune information sur leurs écarts" in others, others)
        assertFalse(Regex("P\\d|seconde").containsMatchIn(others), others)

        // 6. Carburant pour finir : en % du réservoir, jamais en litres.
        val fuel = answer(conv, "combien de carburant pour finir", model)
        assertTrue("% du réservoir" in fuel, fuel)
        assertFalse("litre" in fuel.lowercase(), fuel)

        // 7. Ce qui a changé depuis le plan initial.
        val changed = answer(conv, "qu'est-ce qui a changé depuis notre plan initial", model)
        assertTrue(changed.startsWith("Rien n'a changé") || changed.startsWith("Plan initial"), changed)

        assertEquals(start, ledger(c), "aucune question n'enregistre d'arrêt, de pneus ou de règlement")
        assertTrue(model.prompts.isEmpty(), "aucune réponse stratégique ne passe par Gemini")

        // La course continue : l'arrêt réel, lui, est enregistré par le détecteur.
        sim.driveTo(0.1f)
        while (sim.lap < stop) sim.driveLaps(1)
        sim.pitStop(fuel = 60f)
        assertEquals(1, c.ledger.stopCount)
        val after = answer(conv, "combien de carburant pour finir", model)
        assertFalse("litre" in after.lowercase(), after)
        val best2 = answer(conv, "quelle est notre stratégie maintenant", model)
        assertTrue("pas d'arrêt jusqu'à l'arrivée" in best2 || "Aucun arrêt" in best2, best2)
        assertEquals(1, c.ledger.stopCount)
    }

    @Test
    fun observationsAreRememberedAsDeclarationsNotMeasurements() {
        val (c, _) = oneStopRace(laps = 4)
        val conv = RadioConversation(c)
        val start = ledger(c)

        val r = answer(conv, "je perds de l'adhérence à l'avant")
        assertTrue(r.startsWith("Noté : perte d'adhérence à l'avant, d'après toi."), r)
        assertTrue("chronos" in r, "confrontée aux mesures : $r")
        assertFalse(Regex("\\d+ ?% d'usure|usure de \\d").containsMatchIn(r), "aucune usure chiffrée : $r")
        assertEquals(start, ledger(c), "une perte d'adhérence n'est pas un changement de pneus")
        assertEquals(1, c.activeObservations.size)

        // Faits de l'IA : la déclaration est séparée des mesures.
        assertTrue(c.aiFacts().lines.any { it.startsWith("Déclaré par le pilote") && "non mesuré" in it }, c.aiFacts().lines.toString())

        // Trafic : noté, aucun écart inventé, pas de changement de plan.
        val plan = c.plan?.stops?.map { it.endOfLap }
        val traffic = answer(conv, "je suis bloqué dans le trafic")
        assertTrue("GT7 ne me donne pas les écarts" in traffic, traffic)
        assertEquals(plan, c.plan?.stops?.map { it.endOfLap })

        // Négation : rien n'est mémorisé.
        assertEquals("D'accord, rien à signaler de ce côté.", answer(conv, "je ne perds pas l'arrière"))
        assertEquals(2, c.activeObservations.size)

        // « Qu'est-ce qui a changé ? » rappelle ce que le pilote a signalé.
        val changed = answer(conv, "qu'est-ce qui a changé depuis le plan initial")
        assertTrue("Tu m'as signalé" in changed && "perte d'adhérence à l'avant" in changed, changed)
    }

    @Test
    fun hypothesesAndIntentionsNeverBecomeAStopOrATyreChange() {
        for (s in listOf(
            "si je prolonge d'un tour", "je vais prolonger d'un tour", "on peut rentrer maintenant", "si je rentre maintenant",
            "les autres rentrent", "mes pneus sont morts", "pourquoi on rentre au tour 3",
        )) {
            val (c, _) = oneStopRace()
            val before = ledger(c)
            reply(RadioConversation(c), s)
            assertEquals(before, ledger(c), s)
        }
        // L'intention est gardée pour la stratégie, et annoncée comme telle.
        val (c, _) = oneStopRace()
        val r = answer(RadioConversation(c), "je vais prolonger d'un tour")
        assertTrue(r.startsWith("Noté comme intention"), r)
        assertTrue(c.aiFacts().lines.any { it.startsWith("Intention du pilote, pas encore réalisée") })
    }

    @Test
    fun withoutTelemetryTheAdviceIsGeneralAndSaysSo() {
        val conv = RadioConversation(RaceController())
        for (s in listOf("quelle est notre meilleure stratégie", "on peut prolonger d'un tour", "devant moi ils rentrent on fait quoi", "combien de carburant pour finir")) {
            val r = answer(conv, s)
            assertTrue(r.startsWith("Conseil général, sans télémétrie"), "$s → $r")
            assertFalse(Regex("\\d").containsMatchIn(r.replace("GT7", "")), "aucun chiffre de course : $r")
        }
    }

    @Test
    fun garbledStrategySentenceAsksForClarification() {
        val (c, _) = oneStopRace()
        val out = reply(RadioConversation(c), "[unk] la stratégie [unk]")
        assertTrue(out.single().text.startsWith("Je n'ai pas bien compris"), out.toString())
        assertTrue(out.single().expectsReply)
    }

    @Test
    fun stateCommandsStillGoThroughTheGuard() {
        val (c, _) = oneStopRace()
        val conv = RadioConversation(c)
        reply(conv, "j'ai fait mon arrêt")
        assertEquals(1, c.ledger.stopCount, "commande claire : toujours enregistrée par le chemin habituel")
        reply(conv, "je n'ai pas fait mon arrêt")
        assertEquals(1, c.ledger.stopCount)
    }

    @Test
    fun answersAreShort() {
        val (c, _) = oneStopRace()
        val conv = RadioConversation(c)
        for (s in listOf(
            "quelle est notre meilleure stratégie", "pourquoi on rentre", "on peut prolonger d'un tour", "les autres rentrent on fait quoi",
            "combien de carburant pour finir", "qu'est-ce qui a changé depuis le plan initial", "on peut avancer l'arrêt",
        )) {
            val r = answer(conv, s)
            assertTrue(r.length <= 260, "$s → réponse trop longue pour la radio (${r.length}) : $r")
        }
    }
}
