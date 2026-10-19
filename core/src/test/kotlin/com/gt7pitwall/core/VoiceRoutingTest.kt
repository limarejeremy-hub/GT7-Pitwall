package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.radio.RemoteVoice
import com.gt7pitwall.core.radio.RoutedVoiceOutput
import com.gt7pitwall.core.radio.SpeechQueue
import com.gt7pitwall.core.radio.VoiceOutput
import com.gt7pitwall.core.radio.VoiceRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Radio Discord : une seule sortie à la fois, repli sur le Fold, alertes critiques prioritaires. */
class VoiceRoutingTest {

    private class Phone : VoiceOutput {
        val said = mutableListOf<String>()
        var stopped = false
        override fun speak(message: EngineerMessage) { said += message.text }
        override fun stop() { stopped = true }
    }

    private class Discord(override var live: Boolean) : RemoteVoice {
        val said = mutableListOf<String>()
        val failures = mutableListOf<Pair<EngineerMessage, (EngineerMessage) -> Unit>>()
        var stopped = false
        override fun speak(message: EngineerMessage, onFailed: (EngineerMessage) -> Unit) {
            said += message.text
            failures += message to onFailed
        }
        override fun stop() { stopped = true }
        fun failAll() = failures.forEach { (m, f) -> f(m) }
    }

    private fun msg(text: String, priority: Priority = Priority.NORMAL, at: Long = 0) =
        EngineerMessage(text, priority, MessageCategory.RADIO, timestampMs = at)

    @Test
    fun discordInTheChannelReplacesThePhoneSpeaker() {
        val phone = Phone()
        val discord = Discord(live = true)
        val out = RoutedVoiceOutput(phone, discord) { 0 }
        out.speak(msg("Essence pour 4 tours."))
        assertEquals(VoiceRoute.REMOTE, out.route)
        assertEquals(listOf("Essence pour 4 tours."), discord.said)
        assertTrue(phone.said.isEmpty(), "pas de double voix sans réglage explicite")
    }

    @Test
    fun phoneSpeaksWhenDiscordIsNotInTheChannel() {
        val phone = Phone()
        val discord = Discord(live = false)
        val out = RoutedVoiceOutput(phone, discord) { 0 }
        out.speak(msg("Box ce tour."))
        assertEquals(VoiceRoute.PHONE, out.route)
        assertEquals(listOf("Box ce tour."), phone.said)
        assertTrue(discord.said.isEmpty())
    }

    @Test
    fun mirrorSettingSpeaksOnBoth() {
        val phone = Phone()
        val discord = Discord(live = true)
        val out = RoutedVoiceOutput(phone, discord) { 0 }.apply { mirrorOnPhone = true }
        out.speak(msg("Meilleur tour."))
        assertEquals(VoiceRoute.BOTH, out.route)
        assertEquals(listOf("Meilleur tour."), phone.said)
        assertEquals(listOf("Meilleur tour."), discord.said)
        // Déjà dit sur le Fold : un échec Discord ne le répète pas.
        discord.failAll()
        assertEquals(listOf("Meilleur tour."), phone.said)
    }

    @Test
    fun undeliveredMessageFallsBackToThePhoneWhileStillRelevant() {
        val phone = Phone()
        val discord = Discord(live = true)
        var now = 1_000L
        val out = RoutedVoiceOutput(phone, discord) { now }
        out.speak(msg("Carburant critique, box !", Priority.CRITICAL, at = 1_000))
        out.speak(msg("Écart +0,4.", at = 1_000))
        now = 1_000 + RoutedVoiceOutput.STALE_MS + 1
        out.speak(msg("Il reste 2 tours.", at = now))
        // La connexion tombe : seul le message encore d'actualité est rejoué sur le Fold.
        discord.failAll()
        assertEquals(listOf("Il reste 2 tours."), phone.said)
    }

    @Test
    fun stopSilencesBothOutputs() {
        val phone = Phone()
        val discord = Discord(live = true)
        RoutedVoiceOutput(phone, discord).stop()
        assertTrue(phone.stopped && discord.stopped)
    }

    @Test
    fun criticalAlertFlushesTheQueueAndInterrupts() {
        val q = SpeechQueue<EngineerMessage> { it.priority }
        assertNull(q.offer(msg("Tour 3 : 1:37.9")))
        assertNull(q.offer(msg("Secteur 2 plus lent.")))
        val gen = q.generation
        val dropped = q.offer(msg("Carburant critique, box !", Priority.CRITICAL))
        assertEquals(listOf("Tour 3 : 1:37.9", "Secteur 2 plus lent."), dropped?.map { it.text })
        assertTrue(q.generation > gen, "la phrase en cours est abandonnée")
        assertEquals("Carburant critique, box !", q.poll()?.text)
        assertNull(q.poll())
    }

    @Test
    fun messagesKeepTheirOrderAndDrainReturnsTheRest() {
        val q = SpeechQueue<EngineerMessage> { it.priority }
        q.offer(msg("Un."))
        q.offer(msg("Deux.", Priority.HIGH))
        q.offer(msg("Trois."))
        assertEquals("Un.", q.poll()?.text)
        val gen = q.generation
        assertEquals(listOf("Deux.", "Trois."), q.drain().map { it.text })
        assertTrue(q.generation > gen)
        assertNull(q.poll())
    }
}
