package com.gt7pitwall.core

import com.gt7pitwall.core.radio.GoogleOutcome
import com.gt7pitwall.core.radio.SpeechFallbackPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Essai réel : avec Google, « radio » → « Je t'écoute » → demande → rien. La radio ne doit jamais rester muette :
 * un échec de Google est annoncé et la demande suivante passe par Vosk.
 */
class SpeechFallbackTest {

    private fun policy() = SpeechFallbackPolicy(maxFailures = 3)

    @Test
    fun aSuccessfulGoogleSessionKeepsGoogle() {
        val p = policy()
        assertTrue(p.useGoogle(available = true, phoneMic = true, externalAudioSupported = null))
        val d = p.finish(p.start(), GoogleOutcome.RESULT)
        assertEquals(SpeechFallbackPolicy.Decision(fallbackToVosk = false, notice = null), d)
        assertTrue(p.useGoogle(true, true, null))
    }

    @Test
    fun everyGoogleFailureIsAnnouncedAndTheNextRequestGoesToVosk() {
        for (outcome in listOf(
            GoogleOutcome.NO_MATCH, GoogleOutcome.SPEECH_TIMEOUT, GoogleOutcome.NOT_READY, GoogleOutcome.TIMEOUT, GoogleOutcome.ERROR,
        )) {
            val p = policy()
            val d = assertNotNull(p.finish(p.start(), outcome, errorCode = if (outcome == GoogleOutcome.ERROR) 3 else null), "$outcome")
            assertTrue(d.fallbackToVosk, "$outcome : Vosk reprend tout de suite")
            assertTrue(d.notice!!.startsWith("Je n'ai pas reçu ta demande") && outcome.label in d.notice!!, d.notice)
            assertFalse(p.useGoogle(true, true, null), "$outcome : la demande suivante passe par Vosk")
            // La demande traitée par Vosk, Google est réessayé.
            p.voskHandledRequest()
            assertTrue(p.useGoogle(true, true, null), "$outcome")
        }
        val p = policy()
        val d = p.finish(p.start(), GoogleOutcome.ERROR, errorCode = 3)!!
        assertTrue("code 3" in d.notice!!, "le code d'erreur Android est dit : ${d.notice}")
    }

    @Test
    fun repeatedFailuresSuspendGoogleUntilTheSettingChanges() {
        val p = policy()
        repeat(3) {
            p.finish(p.start(), GoogleOutcome.NOT_READY)
            p.voskHandledRequest()
        }
        assertTrue(p.suspended)
        assertFalse(p.useGoogle(true, true, null), "suspendu : Vosk pour toutes les demandes")
        p.reset()
        assertTrue(p.useGoogle(true, true, null), "réglage changé : Google réessayé")
    }

    @Test
    fun aSuccessResetsTheFailureCount() {
        val p = policy()
        repeat(2) {
            p.finish(p.start(), GoogleOutcome.NO_MATCH)
            p.voskHandledRequest()
        }
        p.finish(p.start(), GoogleOutcome.RESULT)
        p.finish(p.start(), GoogleOutcome.NO_MATCH)
        p.voskHandledRequest()
        assertFalse(p.suspended, "les échecs comptent d'affilée")
    }

    @Test
    fun aCancelledSessionIsNeitherAFailureNorAnnounced() {
        val p = policy()
        val s = p.start()
        p.cancelActive()
        assertNull(p.finish(s, GoogleOutcome.ERROR), "résultat d'une écoute annulée : ignoré")
        assertEquals(0, p.consecutiveFailures)
        assertTrue(p.useGoogle(true, true, null))
        val d = p.finish(p.start(), GoogleOutcome.CANCELLED)!!
        assertFalse(d.fallbackToVosk)
        assertNull(d.notice)
    }

    @Test
    fun rapidRestartsIgnoreStaleResults() {
        val p = policy()
        val first = p.start()
        val second = p.start() // « radio » redit avant la fin de la première écoute
        assertNull(p.finish(first, GoogleOutcome.NO_MATCH), "l'ancienne écoute ne compte plus")
        assertEquals(0, p.consecutiveFailures)
        assertNotNull(p.finish(second, GoogleOutcome.RESULT))
        assertNull(p.finish(second, GoogleOutcome.ERROR), "un résultat n'est traité qu'une fois")
    }

    @Test
    fun microphoneChangesFollowWhatGoogleAccepts() {
        val p = policy()
        assertTrue(p.useGoogle(available = true, phoneMic = true, externalAudioSupported = false), "micro du Fold : toujours possible")
        assertTrue(p.useGoogle(available = true, phoneMic = false, externalAudioSupported = null), "casque PS5 : essai du flux")
        assertTrue(p.useGoogle(available = true, phoneMic = false, externalAudioSupported = true))
        assertFalse(p.useGoogle(available = true, phoneMic = false, externalAudioSupported = false), "flux refusé : Vosk sur le casque")
        assertFalse(p.useGoogle(available = false, phoneMic = true, externalAudioSupported = null), "Google absent : Vosk")
    }
}
