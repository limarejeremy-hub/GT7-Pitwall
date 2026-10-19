package com.gt7pitwall.core

import com.gt7pitwall.core.radio.SpeechEndpointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Essai réel : micro du casque PS5, « radio » → « Je t'écoute » → demande → « délai dépassé » 12 s plus tard. Google
 * lisait le flux sans fin. Le flux doit se fermer à la fin de la phrase.
 */
class SpeechEndpointerTest {
    private val block = 3_200 // 200 ms à 16 kHz

    private fun voice() = ShortArray(block) { i -> if (i % 2 == 0) 3_000 else -3_000 }
    private fun silence() = ShortArray(block)

    private fun feed(e: SpeechEndpointer, vararg parts: Pair<Int, ShortArray>): SpeechEndpointer.End? {
        var end: SpeechEndpointer.End? = null
        for ((count, b) in parts) repeat(count) { e.accept(b, b.size)?.let { end = it } }
        return end
    }

    @Test
    fun theStreamEndsAfterTheSentenceAndASilence() {
        val e = SpeechEndpointer()
        // 0,4 s de silence (le temps de commencer), 2 s de voix, puis le silence de fin.
        assertNull(feed(e, 2 to silence(), 10 to voice(), 5 to silence()), "1 s de silence : la phrase n'est pas finie")
        assertEquals(SpeechEndpointer.End.AFTER_SPEECH, feed(e, 1 to silence()), "1,2 s : fin de phrase")
        assertNull(e.accept(silence(), block), "la fin n'est signalée qu'une fois")
    }

    @Test
    fun aShortPauseInsideTheSentenceDoesNotCutIt() {
        val e = SpeechEndpointer()
        assertNull(feed(e, 5 to voice(), 3 to silence(), 5 to voice(), 2 to silence()))
        assertTrue(e.heardSpeech)
    }

    @Test
    fun noVoiceAtAllClosesTheStreamInsteadOfWaitingForTheTimeout() {
        val e = SpeechEndpointer()
        assertNull(feed(e, 34 to silence()), "6,8 s")
        assertEquals(SpeechEndpointer.End.NO_SPEECH, feed(e, 1 to silence()), "7 s sans voix")
    }

    @Test
    fun aVeryLongSentenceIsHandedOverAnyway() {
        val e = SpeechEndpointer()
        assertEquals(SpeechEndpointer.End.TOO_LONG, feed(e, 80 to voice()), "10 s de parole ou de bruit continu, avant le délai de Google")
    }
}
