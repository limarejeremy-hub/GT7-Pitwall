package com.gt7pitwall.core

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.ai.LocalReplies
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.CommandGate
import com.gt7pitwall.core.radio.CommandSource
import com.gt7pitwall.core.radio.ConversationWindow
import com.gt7pitwall.core.radio.ConversationWindow.Effect
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.GateContext
import com.gt7pitwall.core.radio.RadioTone
import com.gt7pitwall.core.radio.RadioTones
import com.gt7pitwall.core.radio.SpeechFallbackPolicy
import com.gt7pitwall.core.radio.Vetting
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Radio : bips PCM, conversation continue après « Radio », et sécurité des phrases dites sans mot de réveil. */
class VoiceConversationTest {

    // ------------------------------------------------------------------ Signaux

    @Test
    fun readyAndClosedTonesAreRealDistinctPcm() {
        for (rate in listOf(48_000, 16_000)) {
            val ready = RadioTones.pcm(RadioTone.READY, rate)
            val closed = RadioTones.pcm(RadioTone.CLOSED, rate)
            assertEquals(rate * 90 / 1000, ready.size)
            assertEquals(rate * (70 + 70 + 90) / 1000, closed.size)
            assertNotEquals(ready.size, closed.size)
            // Volume modéré, attaque et extinction douces (pas de clic).
            val max = (RadioTones.AMPLITUDE * Short.MAX_VALUE).toInt() + 1
            assertTrue(ready.all { abs(it.toInt()) <= max })
            assertTrue(abs(ready.first().toInt()) < 200 && abs(ready.last().toInt()) < 200)
            // Double bip : un vrai silence entre les deux notes.
            val gap = closed.copyOfRange(rate * 75 / 1000, rate * 135 / 1000)
            assertTrue(gap.all { it.toInt() == 0 })
            assertTrue(ready.any { abs(it.toInt()) > max / 2 }, "audible")
        }
    }

    // ------------------------------------------------------------------ Conversation continue

    @Test
    fun oneWakeWordThenSuccessiveQuestionsWithASingleBeep() {
        val w = ConversationWindow(idleMs = 15_000)
        w.wake(0)
        assertTrue(w.open)
        assertEquals(Effect.READY_BEEP, w.micReady(), "bip quand le micro écoute")
        assertTrue(w.addressedByWake)
        assertNull(w.utterance("quelle est notre meilleure stratégie", 2_000))
        assertFalse(w.addressedByWake, "les phrases suivantes viennent sans « radio »")
        // L'ingénieur répond : la fenêtre est suspendue pendant qu'il parle.
        w.engineerSpeaking(true, 3_000)
        assertNull(w.tick(30_000), "jamais fermée pendant que l'ingénieur parle")
        w.engineerSpeaking(false, 30_000)
        assertNull(w.micReady(), "pas de bip à chaque phrase")
        assertNull(w.tick(44_000))
        assertNull(w.utterance("et le carburant pour finir", 44_000))
        assertNull(w.tick(58_000))
        // Silence de 15 s après le dernier échange : double bip.
        assertEquals(Effect.CLOSED_BEEP, w.tick(59_000))
        assertFalse(w.open)
        assertNull(w.tick(70_000))
    }

    @Test
    fun closingWordsCloseImmediately() {
        for (s in listOf("terminé", "Terminé.", "fin de communication", "Fin de transmission", "merci terminé", "Merci, terminé", "c'est tout merci")) {
            val w = ConversationWindow()
            w.wake(0)
            assertEquals(Effect.CLOSED_BEEP, w.utterance(s, 1_000), s)
            assertFalse(w.open, s)
        }
        // Une phrase qui contient « terminé » n'est pas une fin de conversation.
        for (s in listOf("c'est terminé pour les pneus", "course terminée dans combien de tours", "j'ai terminé mon arrêt")) {
            assertFalse(ConversationWindow.isClosing(s), s)
        }
    }

    @Test
    fun unintelligibleNoiseDoesNotExtendTheWindow() {
        val w = ConversationWindow(idleMs = 10_000)
        w.wake(0)
        w.utterance("[unk] [unk]", 8_000)
        w.utterance("hé", 9_000)
        assertEquals(Effect.CLOSED_BEEP, w.tick(10_000))
        assertFalse(ConversationWindow.isIntelligible("[unk] euh"))
        assertTrue(ConversationWindow.isIntelligible("combien d'essence"))
    }

    @Test
    fun automaticMessagesNeverOpenAConversation() {
        val w = ConversationWindow()
        // Un message de course passe (l'ingénieur parle), sans « radio » : la conversation reste fermée.
        w.engineerSpeaking(true, 0)
        w.engineerSpeaking(false, 5_000)
        assertFalse(w.open)
        assertNull(w.micReady())
        assertNull(w.utterance("arrêt fait", 6_000))
        assertNull(w.tick(60_000))
    }

    @Test
    fun aResetClosesSilently() {
        val w = ConversationWindow()
        w.wake(0)
        w.reset()
        assertFalse(w.open)
        assertNull(w.tick(100_000), "écoute arrêtée, Discord perdu : pas de double bip")
    }

    // ------------------------------------------------------------------ Sécurité

    @Test
    fun withoutTheWakeWordRaceChangesNeedAConfirmation() {
        val ctx = GateContext(telemetryLive = true)
        for (s in listOf("arrêt fait", "j'ai fait mon arrêt", "j'ai mis les durs course", "pneus changés", "2 arrêts obligatoires", "il pleut")) {
            val cmd = DriverCommandParser.parse(s) ?: continue
            val v = CommandGate.vet(cmd, s, CommandSource.CONVERSATION, ctx)
            assertTrue(v !is Vetting.Execute, "$s → $v")
        }
        // Questions et réponses passent sans confirmation.
        for (c in listOf(DriverCommand.Yes, DriverCommand.No, DriverCommand.Repeat)) {
            assertTrue(CommandGate.vet(c, "oui", CommandSource.CONVERSATION, ctx) is Vetting.Execute)
        }
    }

    private fun live(): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = FakeStorage())
        val sim = RaceSim(c, totalLaps = 15, perLap = 4f)
        sim.start()
        sim.driveLaps(3)
        return c to sim
    }

    private fun say(conv: RadioConversation, text: String, addressed: Boolean) =
        runBlocking { conv.onUtterance(text, text, null, addressed = addressed) }.filter { it.category != MessageCategory.DRIVER }

    @Test
    fun gameNoiseOrAnotherVoiceCannotRecordAStop() {
        val (c, _) = live()
        val conv = RadioConversation(c)
        // « Arrêt fait » entendu pendant la conversation, sans « radio » : une question, rien n'est noté.
        val out = say(conv, "arrêt fait", addressed = false)
        assertEquals(0, c.ledger.stopCount)
        assertTrue(out.single().expectsReply, out.toString())
        // Le pilote confirme : l'arrêt est noté par la validation habituelle.
        say(conv, "oui", addressed = false)
        assertEquals(1, c.ledger.stopCount)
        // Avec « radio » devant, la règle d'origine s'applique.
        val (c2, _) = live()
        say(RadioConversation(c2), "arrêt fait", addressed = true)
        assertEquals(1, c2.ledger.stopCount)
    }

    @Test
    fun successiveQuestionsInAConversationAreAnsweredAndChangeNothing() {
        val (c, _) = live()
        val conv = RadioConversation(c)
        val before = Triple(c.ledger.stopCount, c.ledger.changes.size, c.rules)
        for (s in listOf("quelle est notre meilleure stratégie", "combien de carburant pour finir", "comment vont mes pneus", "pourquoi")) {
            assertTrue(say(conv, s, addressed = false).isNotEmpty(), s)
        }
        assertEquals(before, Triple(c.ledger.stopCount, c.ledger.changes.size, c.rules))
    }

    @Test
    fun theRadioNoLongerSaysItIsListening() {
        val notice = SpeechFallbackPolicy().let { p ->
            val s = p.start()
            p.finish(s, com.gt7pitwall.core.radio.GoogleOutcome.SPEECH_TIMEOUT, 6)?.notice
        }
        assertFalse(notice?.contains("je t'écoute", ignoreCase = true) ?: false, notice)
        assertFalse(LocalReplies.text(com.gt7pitwall.core.ai.Intent.PRESENCE, true).contains("je t'écoute", ignoreCase = true))
    }
}
