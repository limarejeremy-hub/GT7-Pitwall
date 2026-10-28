package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.LanguageModel
import com.gt7pitwall.core.ai.RadioAi
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.CommandGate
import com.gt7pitwall.core.radio.CommandSource
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.GateContext
import com.gt7pitwall.core.radio.StatementGuard
import com.gt7pitwall.core.radio.StatementGuard.Mode
import com.gt7pitwall.core.radio.Vetting
import com.gt7pitwall.core.strategy.RaceRules
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Un ingénieur ne modifie jamais la course sur une interprétation incertaine : ce que le pilote a fait, ce qu'il
 * envisage, ce qu'il demande et ce que font les autres sont des choses différentes. Toutes les phrases passent par le
 * vrai chemin radio (RadioConversation), avec et sans ponctuation (Google et Vosk ne transcrivent pas pareil).
 */
class VoiceSafetyTest {

    private class Model(vararg replies: String) : LanguageModel {
        private val queue = ArrayDeque(replies.toList())
        val prompts = mutableListOf<String>()
        override val ready = true
        override val status = "prêt"
        override suspend fun generate(systemInstruction: String, prompt: String): String? {
            prompts += prompt
            return queue.removeFirstOrNull()
        }
    }

    /** Course suivie (télémétrie active), 3 tours faits. */
    private fun liveRace(rules: RaceRules = RaceRules()): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = FakeStorage(rules))
        val sim = RaceSim(c, totalLaps = 10, perLap = 2f)
        sim.start()
        sim.driveLaps(3)
        return c to sim
    }

    private fun say(c: RaceController, text: String, model: LanguageModel? = null): List<EngineerMessage> =
        runBlocking { RadioConversation(c).onUtterance(text, text, model) }.filter { it.category != MessageCategory.DRIVER }

    /** Instantané de ce qui compte pour la stratégie. */
    private data class State(val stops: Int, val tyreChanges: Int, val rules: RaceRules, val position: Int?, val plan: Any?)

    private fun state(c: RaceController) = State(c.ledger.stopCount, c.ledger.changes.size, c.rules, c.engineer.driverPosition, c.plan)

    /** Les deux transcriptions d'une même phrase : avec ponctuation (Google) et sans (Vosk). */
    private fun forms(s: String) = listOf(s, s.replace(Regex("[?!.,]"), "").trim())

    // ------------------------------------------------------------------ Le tableau des phrases

    @Test
    fun completedStopIsRecordedOnlyInAValidContext() {
        for (s in forms("J'ai fait mon arrêt.")) {
            val (c, _) = liveRace()
            say(c, s)
            assertEquals(1, c.ledger.stopCount, s)
        }
        // Sans télémétrie : aucune course suivie, aucun arrêt réel.
        val idle = RaceController()
        val out = say(idle, "J'ai fait mon arrêt.")
        assertEquals(0, idle.ledger.stopCount)
        assertTrue("Pas de télémétrie" in out.single().text, out.toString())
    }

    @Test
    fun negatedStopsRecordNothing() {
        for (phrase in listOf("Je n'ai pas fait mon arrêt.", "J'ai pas encore fait mon arrêt.", "Je n'ai pas encore fait mon arrêt aux stands")) {
            for (s in forms(phrase)) {
                val (c, _) = liveRace()
                val before = state(c)
                val out = say(c, s)
                assertEquals(before, state(c), s)
                assertTrue(out.single().text.startsWith("D'accord, je n'enregistre aucun arrêt"), "$s → $out")
            }
        }
    }

    @Test
    fun aNegationRightAfterADeclaredStopOffersToRemoveIt() {
        val (c, _) = liveRace()
        say(c, "arrêt fait")
        assertEquals(1, c.ledger.stopCount)
        val out = say(c, "non je n'ai pas fait mon arrêt")
        assertEquals("Tu veux que je retire l'arrêt 1 ?", out.single().text)
        assertTrue(out.single().expectsReply, "confirmation à la voix, sans écran")
        assertEquals(1, c.ledger.stopCount, "rien n'est retiré sans « oui »")
        say(c, "oui")
        assertEquals(0, c.ledger.stopCount)
    }

    @Test
    fun intentionsAndPreferencesAreNotEvents() {
        for (phrase in listOf("Je vais faire mon arrêt.", "Je pense rentrer maintenant.", "Je veux prolonger mon relais.", "Je vais rentrer au prochain tour")) {
            for (s in forms(phrase)) {
                val (c, _) = liveRace()
                val before = state(c)
                val model = Model("Avec ton essence, tu peux rester dehors encore un peu ; on décide au prochain passage.")
                val out = say(c, s, model)
                assertEquals(before, state(c), s)
                if ("prolonger" in phrase) {
                    // Question stratégique déguisée en intention : le moteur répond, jamais Gemini, et rien n'est enregistré.
                    assertTrue(!out.single().fromAi && "intention" in out.single().text, "$s → $out")
                } else {
                    // Discussion stratégique : Gemini répond, avec l'intention dans les faits si elle a été notée.
                    assertTrue(out.single().fromAi, "$s → $out")
                }
            }
        }
        // Sans Gemini : réponse honnête, rien d'enregistré, intention gardée pour la stratégie.
        val (c, _) = liveRace()
        val out = say(c, "je vais faire mon arrêt")
        assertEquals(0, c.ledger.stopCount)
        assertTrue("je n'enregistre rien tant que ce n'est pas fait" in out.single().text, out.toString())
        assertTrue(c.aiFacts().lines.any { it.startsWith("Intention du pilote, pas encore réalisée") }, "l'intention alimente la stratégie")
    }

    @Test
    fun hypothesesAndQuestionsAboutOthersRecordNothing() {
        for (phrase in listOf(
            "Si je fais mon arrêt au tour 5 ?", "Celui devant moi a fait son arrêt.", "On fait quoi si les autres rentrent ?",
            "Si devant moi ils vont aux stands, nous on fait quoi ?", "Est-ce qu'on doit rentrer ?",
        )) {
            for (s in forms(phrase)) {
                val (c, _) = liveRace()
                val before = state(c)
                say(c, s, Model("Reste dehors si ton essence le permet, et rentre au tour prévu."))
                assertEquals(before, state(c), s)
                val noModel = liveRace().first
                say(noModel, s)
                assertEquals(0, noModel.ledger.stopCount, "$s (sans Gemini)")
            }
        }
    }

    @Test
    fun tyreChangesNeedARealCompletedChange() {
        for (s in forms("J'ai changé les pneus.")) {
            val (c, _) = liveRace()
            say(c, s)
            assertEquals(1, c.ledger.changes.size, s)
        }
        for (phrase in listOf("Je n'ai pas changé les pneus.", "Non, je n'ai pas changé les pneus.", "Je vais mettre les pneus durs.")) {
            for (s in forms(phrase)) {
                val (c, _) = liveRace()
                val before = state(c)
                say(c, s)
                assertEquals(before, state(c), s)
            }
        }
        // Réponse à « As-tu changé les pneus ? » : la négation vaut « non ».
        val (c, sim) = liveRace(RaceRules(tyreStintLaps = 8))
        say(c, "arrêt fait")
        assertTrue(c.awaitingDriverAnswer, "l'ingénieur demande si les pneus ont changé")
        val out = say(c, "non je n'ai pas changé les pneus")
        assertEquals(0, c.ledger.changes.size)
        assertTrue(out.any { "mêmes pneus" in it.text }, out.toString())
        assertFalse(c.awaitingDriverAnswer)
        sim.driveLaps(1)
    }

    @Test
    fun rulesAreNeverChangedByAQuestionAndAskForConfirmationWhenStatedIndirectly() {
        for (s in forms("Est-ce que deux arrêts sont obligatoires ?")) {
            val (c, _) = liveRace()
            val before = state(c)
            val out = say(c, s)
            assertEquals(before, state(c), s)
            assertFalse(out.any { it.expectsReply && "confirmes" in it.text }, "une question ne demande pas de confirmation : $out")
        }
        for (s in forms("Il faut deux arrêts obligatoires.")) {
            val (c, _) = liveRace()
            val out = say(c, s)
            assertEquals(0, c.rules.mandatoryStops, "pas appliqué sans confirmation : $s")
            assertEquals("Tu confirmes : 2 arrêts obligatoires ?", out.single().text)
            say(c, "oui")
            assertEquals(2, c.rules.mandatoryStops, s)
        }
        // « non » : rien ne change.
        val (c, _) = liveRace()
        say(c, "il faut deux arrêts obligatoires")
        say(c, "non")
        assertEquals(0, c.rules.mandatoryStops)
    }

    @Test
    fun anUncertainPositionDoesNotOverwriteAConfirmedOne() {
        for (s in forms("Je suis peut-être sixième.")) {
            val (c, _) = liveRace()
            say(c, "je suis quatrième")
            assertEquals(4, c.engineer.driverPosition)
            val out = say(c, s)
            assertEquals(4, c.engineer.driverPosition, s)
            assertEquals("Tu es P6, je le note ?", out.single().text)
            say(c, "non")
            assertEquals(4, c.engineer.driverPosition)
        }
    }

    // ------------------------------------------------------------------ Gemini ne contourne pas le garde

    @Test
    fun geminiCommandsGoThroughTheSameGuard() {
        val (c, _) = liveRace()
        val f = c.aiFacts()
        // La question du pilote était une hypothèse : la commande de Gemini est écartée.
        val hypo = RadioAi.parse("COMMANDE: j'ai fait un arrêt aux stands", "si je fais mon arrêt maintenant", f)
        assertIs<AiReply.Command>(hypo)
        c.aiReply("si je fais mon arrêt maintenant", hypo)
        assertEquals(0, c.ledger.stopCount)
        // Même une déclaration claire reformulée par Gemini attend le « oui ».
        val claim = RadioAi.parse("COMMANDE: j'ai fait un arrêt aux stands", "c'est bon je sors des stands", f)
        val out = c.aiReply("c'est bon je sors des stands", claim)
        assertEquals(0, c.ledger.stopCount)
        assertTrue(out.any { it.text == "Tu confirmes que tu as effectué ton arrêt ?" && it.expectsReply }, out.toString())
        c.utterance("oui")
        assertEquals(1, c.ledger.stopCount)
    }

    @Test
    fun anotherSentenceDropsAPendingConfirmation() {
        val (c, _) = liveRace()
        say(c, "il faut deux arrêts obligatoires")
        say(c, "combien d'essence")
        say(c, "oui")
        assertEquals(0, c.rules.mandatoryStops, "le « oui » ne s'applique plus à une confirmation abandonnée")
    }

    // ------------------------------------------------------------------ Les commandes claires restent immédiates

    @Test
    fun clearCommandsStillWorkWithoutConfirmation() {
        val (c, _) = liveRace()
        val clear = listOf(
            "arrêt fait", "j'ai fait le plein", "je suis passé aux stands", "je me suis arrêté", "je suis cinquième",
            "j'ai doublé", "je me suis fait doubler", "il pleut", "piste sèche", "j'ai mis les durs", "silence", "tu peux parler",
        )
        for (s in clear) {
            val cmd = DriverCommandParser.parse(s)!!
            val v = c.vet(cmd, s)
            assertTrue(v is Vetting.Execute, "$s → $v")
        }
        // Issue #45 : un règlement dicté à la voix attend toujours le « oui » du pilote.
        for (s in listOf("course de 12 tours", "2 arrêts obligatoires", "consommation fois 2", "usure fois 3", "les pneus tiennent 8 tours")) {
            val v = c.vet(DriverCommandParser.parse(s)!!, s)
            assertTrue(v is Vetting.Confirm, "$s → $v")
        }
        say(c, "course de 12 tours")
        assertEquals(null, c.rules.totalLaps?.takeIf { it == 12 })
        say(c, "oui")
        assertEquals(12, c.rules.totalLaps)
        // Pneus de départ : déclarés avant la course, sans télémétrie.
        val idle = RaceController()
        say(idle, "je pars en mediums")
        assertTrue(idle.vet(DriverCommandParser.parse("je pars en mediums")!!, "je pars en mediums") is Vetting.Execute)
    }

    @Test
    fun statementModesAreRecognisedWithAndWithoutPunctuation() {
        val expected = mapOf(
            "J'ai fait mon arrêt." to Mode.DONE,
            "Je n'ai pas fait mon arrêt." to Mode.NEGATED,
            "J'ai pas encore fait mon arrêt." to Mode.NEGATED,
            "Je vais faire mon arrêt." to Mode.FUTURE,
            "Je pense rentrer maintenant." to Mode.FUTURE,
            "Si je fais mon arrêt au tour 5 ?" to Mode.HYPOTHETICAL,
            "Celui devant moi a fait son arrêt." to Mode.ABOUT_OTHERS,
            "On fait quoi si les autres rentrent ?" to Mode.HYPOTHETICAL,
            "Est-ce qu'on doit rentrer ?" to Mode.QUESTION,
            "Est-ce que deux arrêts sont obligatoires ?" to Mode.QUESTION,
            "Je veux prolonger mon relais." to Mode.WISH,
            "Je suis peut-être sixième." to Mode.UNCERTAIN,
            "Il pleut." to Mode.DONE,
            "Il va pleuvoir." to Mode.ABOUT_OTHERS,
        )
        for ((s, mode) in expected) for (f in forms(s)) assertEquals(mode, StatementGuard.mode(f), f)
        // « Il va pleuvoir » ne déclare jamais la pluie.
        assertIs<Vetting.Discuss>(CommandGate.vet(DriverCommand.Rain, "il va pleuvoir", CommandSource.VOICE, GateContext(true)))
    }
}
