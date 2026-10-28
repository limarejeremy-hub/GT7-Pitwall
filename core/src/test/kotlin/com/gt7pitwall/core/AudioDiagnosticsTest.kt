package com.gt7pitwall.core

import com.gt7pitwall.core.radio.AudioSendMonitor
import com.gt7pitwall.core.radio.OpusToc
import com.gt7pitwall.core.radio.PhraseAudio
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #48 (APK #473) : « un bruit quand il parle, comme une perte… un freeze ». Ces tests vérifient la MESURE (trames,
 * appels d'envoi, vidages), pas la cause : seul un essai d'écoute sur casque PS5 la dira.
 */
class AudioDiagnosticsTest {
    /** Paquet Opus CELT mono 20 ms (config 31 : 20 ms, une trame). */
    private val frame20 = byteArrayOf((31 shl 3).toByte(), 1, 2, 3)

    /** Paquet de 10 ms (config 30). */
    private val frame10 = byteArrayOf((30 shl 3).toByte(), 1, 2)

    private var now = 0L
    private fun monitor() = AudioSendMonitor { now }.also { it.enabled = true }

    /** Envoi simulé comme JDA : canProvide puis provide20MsAudio toutes les [stepMs] ms. */
    private class Sender(val m: AudioSendMonitor) {
        val queue = ConcurrentLinkedQueue<ByteArray>()
        var promised = false
        fun canProvide() = queue.isNotEmpty().also { promised = it }
        fun provide(): ByteArray? = queue.poll().also { m.onProvide(it != null, promised) }
        fun clear(reason: String, current: String?) {
            val n = queue.size
            queue.clear()
            m.onClear(reason, n, current)
        }
    }

    @Test fun opusFrameDurationsAreReadFromTheToc() {
        assertEquals(200, OpusToc.durationTenthMs(frame20))
        assertEquals(100, OpusToc.durationTenthMs(frame10))
        val a = PhraseAudio.of(listOf(frame20, frame20, frame10, ByteArray(0)))
        assertEquals(4, a.frames)
        assertEquals(1, a.invalidDuration)
        assertEquals(1, a.empty)
        assertEquals(50, a.durationMs)
    }

    @Test fun aRegularPhraseProducesNoEvent() {
        val m = monitor()
        val s = Sender(m)
        val frames = List(50) { frame20 }
        m.beginPhrase("Tour 3 : 2 minutes 9 secondes", frames)
        s.queue.addAll(frames)
        while (s.canProvide()) { s.provide(); now += 20 }
        m.endPhrase(cutShort = false)
        assertTrue(m.drain().isEmpty())
    }

    @Test fun lateSendCallsAndInvalidFramesAreReported() {
        val m = monitor()
        val s = Sender(m)
        val frames = List(20) { frame20 } + frame10
        m.beginPhrase("Tu es P8 sur 14", frames)
        s.queue.addAll(frames)
        var i = 0
        while (s.canProvide()) {
            s.provide()
            now += if (i++ == 7) 140 else 20 // le fil d'envoi bloqué 140 ms en pleine phrase
        }
        m.endPhrase(cutShort = false)
        val codes = m.drain().map { it.code }
        assertTrue("AUDIO_FRAME_POLL_GAP" in codes, codes.toString())
        assertTrue("AUDIO_FRAME_DURATION_INVALID" in codes, codes.toString())
    }

    @Test fun aClearBetweenCanProvideAndProvideIsAnUnderrunAndIsExplained() {
        val m = monitor()
        val s = Sender(m)
        val frames = List(30) { frame20 }
        m.beginPhrase("Stratégie : un arrêt fin du tour 6", frames)
        s.queue.addAll(frames)
        repeat(10) { s.canProvide(); s.provide(); now += 20 }
        // JDA a reçu « prête », puis une alerte prioritaire vide la file avant la trame suivante.
        s.canProvide()
        s.clear("alerte prioritaire (Carburant critique)", "Stratégie : un arrêt fin du tour 6")
        s.provide()
        m.endPhrase(cutShort = true)
        val events = m.drain()
        val codes = events.map { it.code }
        assertTrue("AUDIO_CLEAR_REASON" in codes, codes.toString())
        assertTrue("AUDIO_QUEUE_UNDERRUN" in codes, codes.toString())
        assertTrue("AUDIO_PREEMPTED" in codes, codes.toString())
        assertTrue(events.first { it.code == "AUDIO_CLEAR_REASON" }.text.startsWith("20 trame(s) abandonnée(s) : alerte prioritaire"))
    }

    @Test fun disabledByDefaultAndRateLimited() {
        val off = AudioSendMonitor { now }
        off.beginPhrase("x", listOf(frame10))
        off.endPhrase(false)
        assertTrue(off.drain().isEmpty())
        val m = monitor()
        repeat(5) {
            m.beginPhrase("p$it", listOf(frame10))
            m.endPhrase(false)
            now += 100
        }
        assertEquals(1, m.drain().count { it.code == "AUDIO_FRAME_DURATION_INVALID" }, "au plus un par 5 s")
    }
}
