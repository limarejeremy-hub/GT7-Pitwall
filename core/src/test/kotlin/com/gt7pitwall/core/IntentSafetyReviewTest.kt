package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.ai.ToolCapable
import com.gt7pitwall.core.ai.tools.PitwallDataTools
import com.gt7pitwall.core.ai.tools.ToolSnapshot
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.CommandGate
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Revue #74 (06/11, 14h58) : même SORTE de commande ≠ même VALEUR ; chemins d'entrée qui contournaient l'arbitre ;
 * confirmation qui doit répéter la valeur exacte ; route rattachée à chaque phrase. Fournisseurs SIMULÉS : rien ici
 * n'est un appel GPT réseau ni une course PS5.
 */
class IntentSafetyReviewTest {

    private fun race(stopAndAsk: Boolean = false): Pair<RaceSim, RadioConversation> {
        val c = RaceController(storage = FakeStorage())
        c.setRules(RaceRules(totalLaps = 10, mandatoryStops = 1, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM, TyreCompound.RS), startTyre = TyreCompound.RS))
        c.confirmRules("2026-11-06")
        val s = RaceSim(c, totalLaps = 10, perLap = 9f, fuel = 100f)
        s.start()
        s.driveLaps(3)
        if (stopAndAsk) {
            s.pitStop(fuel = 30f)
            s.say("oui")
            assertTrue("quelle gomme" in s.lastAnswer, s.lastAnswer)
        }
        return s to RadioConversation(c, Mutex())
    }

    private fun say(conv: RadioConversation, vocab: String, free: String?) = runBlocking { conv.onUtterance(vocab, free, model = null) }

    // ---------------------------------------------------------------- Valeurs contradictoires : rien appliqué

    @Test fun sameKindDifferentValueIsAConflict() {
        for ((vocab, free) in listOf(
            "je pars en durs" to "je pars en mediums",
            "j'ai fait un arret et monte les durs" to "j'ai fait un arret et monte les mediums",
            "je suis p8" to "je suis p11",
            "course de 11 tours" to "course de 10 tours",
        )) {
            val v = DriverCommandParser.parse(vocab)
            val f = DriverCommandParser.parse(free)
            assertNotNull(v, vocab)
            assertNotNull(f, free)
            assertEquals(v::class, f::class, "même sorte de commande : $vocab / $free")
            val d = IntentArbiter.decide(vocab, free, v)
            assertIs<IntentArbiter.Decision.Conflict>(d, "$vocab / $free → $d")
        }
        // Mêmes valeurs dites autrement : accord.
        assertEquals(IntentArbiter.Decision.Proceed, IntentArbiter.decide("je pars en durs", "je pars sur les durs", DriverCommandParser.parse("je pars en durs")))
    }

    @Test fun aConflictLeavesTheRaceUntouchedAndPreparesNoConfirmation() {
        val (sim, conv) = race(stopAndAsk = true)
        val before = sim.controller.ledger.changes.size
        val out = say(conv, "j'ai mis les durs", "j'ai mis les mediums")
        assertEquals(before, sim.controller.ledger.changes.size, "aucune gomme enregistrée")
        assertTrue(out.any { "deux" in it.text && "Je ne change rien" in it.text || "deux gommes différentes" in it.text }, out.map { it.text }.toString())
        // Un « oui » ensuite ne doit rien appliquer (aucune confirmation n'était en attente).
        say(conv, "oui", "oui")
        assertEquals(before, sim.controller.ledger.changes.size)
    }

    @Test fun aConfirmationRepeatsTheExactValue() {
        val q = CommandGate.confirmation(DriverCommandParser.parse("j'ai mis les durs")!!)
        assertTrue("durs" in q, q)
        val stop = CommandGate.confirmation(DriverCommandParser.parse("j'ai fait un arret et monte les mediums")!!)
        assertTrue("mediums" in stop, stop)
    }

    // ---------------------------------------------------------------- Chemins d'entrée

    @Test fun aSingleTranscriptQuestionIsNeverADeclaration() {
        for (t in listOf("est ce que je pars en durs", "est-ce que je dois mettre les tendres", "si je mets les durs")) {
            val cmd = DriverCommandParser.parse(t)
            if (cmd != null && CommandGate.sensitive(cmd)) {
                assertIs<IntentArbiter.Decision.Analysis>(IntentArbiter.decide(t, null, cmd), t)
                assertIs<IntentArbiter.Decision.Analysis>(IntentArbiter.decide(t, t, cmd), "deux transcriptions identiques : $t")
            }
        }
        val (sim, conv) = race()
        val before = sim.controller.ledger.changes.size
        say(conv, "est ce que je pars en durs", null)
        assertEquals(before, sim.controller.ledger.changes.size)
    }

    @Test fun aQuestionAskedWhileATyreAnswerIsExpectedIsNotTheAnswer() {
        val (sim, conv) = race(stopAndAsk = true)
        val ledger = sim.controller.ledger
        fun declared() = ledger.changes.lastOrNull()?.compound
        val before = declared()
        say(conv, "medium", "est ce que medium c'est mieux")
        assertEquals(before, declared(), "une question n'est pas la réponse attendue")
        assertTrue(ledger.awaitingTyreDeclaration, "la question reste en attente")
        // Deux réponses différentes : rien appliqué, la question reste posée.
        say(conv, "durs", "mediums")
        assertEquals(before, declared(), "deux gommes différentes : rien")
        assertTrue(ledger.awaitingTyreDeclaration)
        // La réponse simple reste rapide et appliquée.
        say(conv, "medium", "medium")
        assertEquals(TyreCompound.RM, declared())
        assertFalse(ledger.awaitingTyreDeclaration)
    }

    @Test fun aNegationNeverDeclaresTyres() {
        val (sim, conv) = race()
        val before = sim.controller.ledger.changes.size
        say(conv, "j'ai mis les durs", "je n'ai pas mis les durs")
        assertEquals(before, sim.controller.ledger.changes.size)
    }

    // ---------------------------------------------------------------- Route de chaque phrase

    @Test fun everyPhraseGetsItsOwnRoute() {
        val (_, conv) = race()
        for (p in listOf("carburant", "merci", "radio check", "je perds du temps donc un virage")) {
            say(conv, p, p)
            val r = conv.lastRoute
            assertNotNull(r, p)
            assertTrue(r.phrase == p, "route rattachée à « $p » : ${r.text}")
        }
    }

    // ---------------------------------------------------------------- Mode GPT : les outils de télémétrie sont fournis

    private class ToolGpt : AiProvider, ToolCapable {
        var snapshots = mutableListOf<ToolSnapshot>()
        override val backend = AiBackend.CHATGPT
        override val modelId = "gpt-6.1-sol"
        override val ready = true
        override val status = "simulé"
        override val toolsEnabled = true
        override suspend fun complete(systemInstruction: String, prompt: String) = AiResult.Text("non")
        override suspend fun completeWithTools(systemInstruction: String, prompt: String, snapshot: ToolSnapshot): AiResult {
            snapshots += snapshot
            return AiResult.Text("Regarde tes derniers tours au calme.", snapshot.result(PitwallDataTools.Tool.LAPS).evidenceText)
        }
    }

    @Test fun inGptModeACoachingQuestionGetsTheTelemetryTools() {
        val (_, conv) = race()
        val gpt = ToolGpt()
        conv.cloud = AiRouter(mapOf(AiBackend.CHATGPT to gpt), AiUsage(), AiMode.CHATGPT_PLAN, totalTimeoutMs = 12_000L)
        say(conv, "je perdues tendres aucun virages", "je perds du temps donc un virage")
        assertEquals(1, gpt.snapshots.size, "question de pilotage → GPT avec outils (fournisseur simulé)")
        val laps = gpt.snapshots.single().result(PitwallDataTools.Tool.LAPS)
        assertTrue(laps.facts.isNotEmpty(), "les tours mesurés sont accessibles par l'outil")
        assertFalse(conv.lastRoute!!.provider.startsWith("moteur"), conv.lastRoute!!.text)
    }
}
