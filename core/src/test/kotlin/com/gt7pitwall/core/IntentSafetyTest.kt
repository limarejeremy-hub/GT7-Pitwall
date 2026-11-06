package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.IntentArbiter
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #73, P0-A : paires (vocabulaire radio → phrase libre) relevées dans le journal de la course Sport C du 06/11,
 * anonymisées (seules les transcriptions, aucune donnée du compte). La phrase audio d'origine est inconnue : on teste
 * ce que Pitwall a reçu. Règle : la phrase LIBRE décide du sens ; une question, une hypothèse ou un sujet de pilotage
 * ne modifient jamais la course ; un désaccord demande « oui ». Aucun essai PS5.
 */
class IntentSafetyTest {

    private fun race(): Pair<RaceSim, RadioConversation> {
        val rules = RaceRules(totalLaps = 11, fuelMultiplier = 3, mandatoryStops = 0, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM), startTyre = TyreCompound.RM)
        val sim = RaceSim(RaceController(storage = FakeStorage(rules = rules)), totalLaps = 11, perLap = 9f).also { it.start(); it.driveLaps(3) }
        return sim to RadioConversation(sim.controller, Mutex())
    }

    /** Aucune modification enregistrée pour cette phrase (journal du moteur). */
    private fun nothingApplied(sim: RaceSim) {
        val applied = sim.controller.journal.recent().filter { it.decision == "appliquée" }
        assertTrue(applied.isEmpty(), "appliqué à tort : ${applied.map { it.text }}")
    }

    private fun say(conv: RadioConversation, vocab: String, free: String) = runBlocking { conv.onUtterance(vocab, free, model = null) }

    // ---------------------------------------------------------------- Les quatre fautes du 06/11

    @Test fun case1AComparisonAboutTyresNeverDeclaresTyres() {
        val vocab = "depasse faire depart tiennent durent bons mediums"
        val free = "il etait preferable de partir en dur pour medium"
        assertIs<IntentArbiter.Decision.Analysis>(IntentArbiter.decide(vocab, free, DriverCommandParser.parse(vocab)))
        val (sim, conv) = race()
        val out = say(conv, vocab, free)
        nothingApplied(sim)
        assertTrue(out.none { it.category == MessageCategory.ANSWER && it.text.contains("confirmes") }, "pas même une confirmation : c'est une question")
        assertEquals("locale", conv.lastRoute?.route, conv.lastRoute?.text)
    }

    @Test fun case2LosingTimeInACornerIsCoachingNeverAnOvertake() {
        val vocab = "je perdues tendres aucun virages"
        val free = "je perds du temps donc un virage"
        val d = IntentArbiter.decide(vocab, free, DriverCommand.Overtaken)
        assertIs<IntentArbiter.Decision.Analysis>(d)
        assertEquals(IntentArbiter.Topic.COACHING, d.topic)
        val (sim, conv) = race()
        val out = say(conv, vocab, free)
        nothingApplied(sim)
        assertTrue(out.none { "doublé" in it.text || "perdu une place" in it.text }, out.map { it.text }.toString())
    }

    @Test fun case3KeepingTheStrategyRelativeToMyPositionIsAQuestion() {
        val vocab = "pas pourquoi moi position bons garde memes strategie"
        val free = "par rapport a ma position garde la meme strategie"
        assertIs<IntentArbiter.Decision.Analysis>(IntentArbiter.decide(vocab, free, DriverCommandParser.parse(vocab)))
        val (sim, conv) = race()
        val out = say(conv, vocab, free)
        nothingApplied(sim)
        assertTrue(out.none { "depuis le tour 0" in it.text }, out.map { it.text }.toString())
    }

    @Test fun case4ATimedTyreChangeIsAPlanNotATemperatureAnswer() {
        val vocab = "changes mes pneu aux depart dit tours sept"
        val free = "changer mes pneus au debut du tour sept"
        assertIs<IntentArbiter.Decision.Analysis>(IntentArbiter.decide(vocab, free, DriverCommandParser.parse(vocab)))
        val (sim, conv) = race()
        say(conv, vocab, free)
        nothingApplied(sim)
    }

    // ---------------------------------------------------------------- Ce qui doit continuer à marcher

    @Test fun anExplicitDeclarationOnBothTranscriptsStillApplies() {
        assertEquals(IntentArbiter.Decision.Proceed, IntentArbiter.decide("j'ai mis les durs", "j'ai mis les durs", DriverCommandParser.parse("j'ai mis les durs")))
        assertEquals(IntentArbiter.Decision.Proceed, IntentArbiter.decide("box", null, DriverCommandParser.parse("box")), "sans phrase libre : chemin habituel")
    }

    @Test fun aMutationTheFreeTextDoesNotConfirmNeedsAYes() {
        val vocab = "j'ai mis les durs"
        val free = "on verra bien ce que ça donne"
        val d = IntentArbiter.decide(vocab, free, DriverCommandParser.parse(vocab))
        assertIs<IntentArbiter.Decision.Clarify>(d)
        val (sim, conv) = race()
        val out = say(conv, vocab, free)
        nothingApplied(sim)
        assertTrue(out.any { it.text.contains("?") }, "une question courte de confirmation : ${out.map { it.text }}")
        assertEquals("confirmation demandée", conv.lastRoute?.route)
    }

    @Test fun negationsAndHypothesesNeverChangeTheTyres() {
        for ((vocab, free) in listOf(
            "j'ai mis les durs" to "si j'avais mis les durs je serais plus rapide",
            "mis les tendres" to "est ce que je dois mettre les tendres",
            "tendres" to "il aurait fallu partir en tendres",
        )) {
            val d = IntentArbiter.decide(vocab, free, DriverCommandParser.parse(vocab))
            assertFalse(d is IntentArbiter.Decision.Proceed && CommandGateHelpers.sensitive(DriverCommandParser.parse(vocab)), "$free → $d")
        }
    }

    // ---------------------------------------------------------------- Mode ChatGPT : l'analyse va vraiment à GPT

    private class FakeGpt : AiProvider {
        var calls = 0
        override val backend = AiBackend.CHATGPT
        override val modelId = "gpt-6.1-sol"
        override val ready = true
        override val status = "simulé"
        override suspend fun complete(systemInstruction: String, prompt: String): AiResult { calls++; return AiResult.Text("On regarde ça ensemble au calme.") }
    }

    @Test fun inGptModeAnAnalysisQuestionReachesGptAndTheRouteSaysSo() {
        val (sim, conv) = race()
        val gpt = FakeGpt()
        conv.cloud = AiRouter(mapOf(AiBackend.CHATGPT to gpt), AiUsage(), AiMode.CHATGPT_PLAN, totalTimeoutMs = 12_000L)
        say(conv, "pas pourquoi moi position bons garde memes strategie", "par rapport a ma position garde la meme strategie")
        assertEquals(1, gpt.calls, "la question d'analyse est passée par GPT")
        assertEquals("IA", conv.lastRoute?.route, conv.lastRoute?.text)
        assertTrue(conv.lastRoute!!.mode == "CHATGPT_PLAN")
        nothingApplied(sim)
        // Question d'état : instantanée, locale, même en mode GPT (pas 8 s pour le carburant).
        runBlocking { conv.onUtterance("carburant", "carburant", model = null) }
        assertEquals(1, gpt.calls)
        assertEquals("locale instantanée", conv.lastRoute?.route)
        assertTrue(sim.controller.journal.recent().any { it.text.startsWith("Route radio : ") }, "route journalisée")
    }

    @Test fun inLocalModeTheRouteExplainsWhyGptWasNotCalled() {
        val (_, conv) = race()
        say(conv, "depasse faire depart tiennent durent bons mediums", "il etait preferable de partir en dur pour medium")
        assertTrue(conv.lastRoute!!.reason.startsWith("GPT non appelé"), conv.lastRoute!!.text)
    }
}

/** Accès de test à la liste des commandes sensibles. */
private object CommandGateHelpers {
    fun sensitive(c: DriverCommand?) = c != null && com.gt7pitwall.core.radio.CommandGate.sensitive(c)
}
