package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.Brevity
import com.gt7pitwall.core.radio.ConversationWindow
import com.gt7pitwall.core.radio.RadioPacer
import com.gt7pitwall.core.radio.SpeechStyle
import com.gt7pitwall.core.strategy.RaceRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Défauts relevés lors de l'essai sur PS5 : double bip de fermeture absent, places annoncées en retard, ingénieur trop
 * bavard en course, réponses trop longues aux questions simples. (Les réponses négatives sur les pneus : TyresKeptTest.)
 */
class Ps5RadioRegressionTest {

    /** L'application interroge la fenêtre toutes les 250 ms, en signalant à chaque fois « pas de réflexion ». */
    private fun ConversationWindow.runTicks(from: Long, to: Long, talking: (Long) -> Boolean = { false }, thinking: (Long) -> Boolean = { false }): Long? {
        var t = from
        while (t <= to) {
            thinking(thinking(t), t)
            if (tick(t, driverTalking = talking(t)) != null) return t
            t += 250
        }
        return null
    }

    @Test
    fun theClosingDoubleBeepComesAfterTheSilenceEvenWhenPolledEveryTick() {
        val w = ConversationWindow()
        w.wake(0)
        // Avant : chaque « pas de réflexion » repoussait l'échéance de 8 s, la conversation ne se fermait jamais.
        val closedAt = w.runTicks(0, 60_000)
        assertEquals(ConversationWindow.DEFAULT_IDLE_MS, closedAt, "double bip après le silence prévu")
        assertFalse(w.open)
    }

    @Test
    fun gameNoiseHeardAsSpeechCannotKeepTheConversationOpen() {
        val w = ConversationWindow()
        w.wake(0)
        // Fragments entendus sans arrêt (son du jeu) : prolongation bornée.
        val closedAt = w.runTicks(0, 60_000, talking = { true })!!
        assertEquals(ConversationWindow.DEFAULT_IDLE_MS + ConversationWindow.MAX_TALK_GRACE_MS, closedAt)
        // Une vraie phrase en cours, juste avant l'échéance, n'est pas coupée.
        val w2 = ConversationWindow()
        w2.wake(0)
        assertNull(w2.runTicks(0, ConversationWindow.DEFAULT_IDLE_MS + 2_000, talking = { it > 7_000 }))
        w2.utterance("combien d'essence", 10_000)
        assertNull(w2.tick(10_000 + ConversationWindow.DEFAULT_IDLE_MS - 1))
        assertEquals(ConversationWindow.Effect.CLOSED_BEEP, w2.tick(10_000 + ConversationWindow.DEFAULT_IDLE_MS))
    }

    @Test
    fun aStuckSpeakingOrThinkingStateNeverBlocksTheClosingBeep() {
        val w = ConversationWindow()
        w.wake(0)
        w.engineerSpeaking(true, 1_000) // fin de lecture jamais signalée
        assertNull(w.tick(30_000), "une longue phrase n'est pas coupée")
        val closed = w.runTicks(30_000, 120_000)!!
        assertTrue(closed in 1_000 + ConversationWindow.MAX_HOLD_MS..1_000 + ConversationWindow.MAX_HOLD_MS + 250, "fermé à $closed")

        // Réflexion signalée vraie à chaque tick, sans fin.
        val w2 = ConversationWindow()
        w2.wake(0)
        val closed2 = w2.runTicks(0, 200_000, thinking = { true })!!
        assertTrue(closed2 <= ConversationWindow.MAX_HOLD_MS + 250, "fermé à $closed2")

        // Réflexion réelle de 5 s : le délai repart à la fin, puis double bip.
        val w3 = ConversationWindow()
        w3.wake(0)
        val closed3 = w3.runTicks(0, 60_000, thinking = { it in 2_000L until 7_000L })
        assertEquals(7_000 + ConversationWindow.DEFAULT_IDLE_MS, closed3)
    }

    private fun position(text: String) = EngineerMessage(
        text, Priority.NORMAL, MessageCategory.RADIO, topic = RaceEngineer.POSITION_TOPIC, ttlMs = RaceEngineer.POSITION_TTL_MS,
    )

    @Test
    fun aDelayedPositionIsReplacedOrDroppedNeverSpokenStale() {
        val pacer = RadioPacer()
        // Freinage : « P7 » attend, puis « P6 » le remplace.
        assertTrue(pacer.pace(listOf(position("P7.")), busy = true, nowMs = 0).isEmpty())
        assertTrue(pacer.pace(listOf(position("P6.")), busy = true, nowMs = 1_000).isEmpty())
        assertEquals(listOf("P6."), pacer.pending.map { it.text })
        // Ligne droite trop tard : la place a plus de 4 s, elle n'est plus dite.
        val out = pacer.pace(emptyList(), busy = false, nowMs = 6_000) + pacer.pace(emptyList(), busy = false, nowMs = 8_000)
        assertTrue(out.none { it.topic == RaceEngineer.POSITION_TOPIC }, out.toString())
        // Ligne droite à temps : dite.
        val p2 = RadioPacer()
        p2.pace(listOf(position("P5.")), busy = true, nowMs = 0)
        p2.pace(emptyList(), busy = false, nowMs = 1_000)
        assertEquals(listOf("P5."), p2.pace(emptyList(), busy = false, nowMs = 2_500).map { it.text })
        // Derrière une phrase déjà en cours, une place serait périmée : pas mise en file (elle reste affichée).
        assertTrue(RadioPacer.dropBeforeSpeech(position("P4."), voiceBusy = true))
        assertFalse(RadioPacer.dropBeforeSpeech(position("P4."), voiceBusy = false))
        val alert = EngineerMessage("Box, box !", Priority.CRITICAL, MessageCategory.ALERT)
        assertFalse(RadioPacer.dropBeforeSpeech(alert, voiceBusy = true), "les alertes passent toujours")
    }

    private fun race(quiet: Boolean): List<EngineerMessage> {
        val c = RaceController(storage = FakeStorage())
        c.engineer.quietRace = quiet
        c.setRules(RaceRules(totalLaps = 10, mandatoryStops = 1))
        val s = RaceSim(c, totalLaps = 10, perLap = 9f, fuel = 100f)
        s.start()
        s.driveLaps(4)
        s.pitStop(fuel = 30f)
        s.say("non")
        s.driveLaps(6)
        return s.said.filter { it.category != MessageCategory.DRIVER }
    }

    @Test
    fun inARaceTheEngineerStaysQuietButCriticalCallsStillPass() {
        val loud = race(quiet = false)
        val quiet = race(quiet = true)
        val routine = Regex("^Tour \\d+ : [^.]+\\.( Plus [^.]+ sur ton meilleur\\.)?$")
        assertTrue(loud.count { routine.matches(it.text) } >= 4, "référence : un chrono annoncé à chaque tour")
        assertTrue(quiet.none { routine.matches(it.text) }, quiet.joinToString("\n") { it.text })
        assertTrue(quiet.size < loud.size, "${quiet.size} messages contre ${loud.size}")
        // Ce qui compte passe toujours : appel au stand, question pneus, dernier tour, arrivée.
        assertTrue(quiet.any { it.priority == Priority.CRITICAL && "Box" in it.text })
        assertTrue(quiet.any { "As-tu changé les pneus ?" in it.text })
        assertTrue(quiet.any { "Dernier tour" in it.text })
        assertTrue(quiet.any { "Course terminée" in it.text })
        assertTrue(quiet.none { it.category == MessageCategory.LAP && Regex("\\bP\\d+\\.").containsMatchIn(it.text) }, "pas de place dans les tours")
    }

    @Test
    fun simpleQuestionsGetShortAnswersAndTheFullAnalysisOnlyOnRequest() {
        val c = RaceController(storage = FakeStorage())
        c.engineer.quietRace = true
        c.setRules(RaceRules(totalLaps = 12, mandatoryStops = 1))
        val s = RaceSim(c, totalLaps = 12, perLap = 9f, fuel = 100f)
        s.start()
        s.driveLaps(4)
        for (q in listOf("essence", "mes pneus", "stratégie", "quand est-ce que je rentre aux stands", "rythme")) {
            s.say(q)
            val n = SpeechStyle.sentences(s.lastAnswer).size
            assertTrue(n <= Brevity.SIMPLE_SENTENCES, "« $q » : $n phrases — ${s.lastAnswer}")
        }
        s.say("analyse complète")
        val full = s.said.filter { it.category == MessageCategory.ANSWER }.takeLast(3).joinToString(" ") { it.text }
        assertTrue("Consommation" in full && "Pneus" in full && "Stratégie" in full, full)
        assertTrue(Brevity.wantsDetail("pourquoi tu veux que je rentre ?"))
        assertTrue(Brevity.wantsDetail("fais-moi un bilan complet"))
        assertFalse(Brevity.wantsDetail("combien d'essence ?"))
    }
}

