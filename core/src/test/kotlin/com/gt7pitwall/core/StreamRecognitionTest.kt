package com.gt7pitwall.core

import com.gt7pitwall.core.radio.GoogleEngine
import com.gt7pitwall.core.radio.GoogleOutcome
import com.gt7pitwall.core.radio.RequestArbiter
import com.gt7pitwall.core.radio.RequestPick
import com.gt7pitwall.core.radio.SpeechEndpointer
import com.gt7pitwall.core.radio.SpeechFallbackPolicy
import com.gt7pitwall.core.radio.StreamAttempt
import com.gt7pitwall.core.radio.StreamEnginePolicy
import com.gt7pitwall.core.radio.StreamRead
import com.gt7pitwall.core.radio.StreamStats
import com.gt7pitwall.core.radio.StreamVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamRecognitionTest {
    /** 3 s de flux (96 000 octets), voix présente. */
    private fun stats(written: Long = 96_000, unread: Long? = 0, voice: Boolean = true) = StreamStats(
        offeredBytes = written, writtenBytes = written, unreadBytes = unread, firstByteMs = 30,
        audioMs = written * 1000 / 32_000, endLabel = "fin de phrase détectée", voiceHeard = voice,
    )

    // --- Preuve de lecture du flux ---

    @Test
    fun aStreamDrainedByTheRecognizerIsRead() {
        assertEquals(StreamRead.READ, stats(unread = 0).read)
        assertEquals(96_000L, stats(unread = 0).consumedBytes)
    }

    @Test
    fun aFullPipeNobodyReadIsProofTheStreamWasIgnored() {
        // Le tube s'est rempli (64 Ko) et rien n'en est sorti.
        val s = stats(written = 65_536, unread = 65_536)
        assertEquals(StreamRead.NOT_READ, s.read)
        assertEquals(0L, s.consumedBytes)
    }

    @Test
    fun beingReadyOrHearingSoundIsNotProof_onlyTheUnreadCountMatters() {
        assertEquals(StreamRead.UNKNOWN, stats(unread = null).read)
        // Trop peu d'audio pour conclure dans un sens ou dans l'autre.
        assertEquals(StreamRead.UNKNOWN, stats(written = 8_000, unread = 8_000).read)
        assertEquals(StreamRead.UNKNOWN, stats(written = 8_000, unread = 0).read)
    }

    @Test
    fun partiallyReadStreamCountsAsReadAboveHalfASecond() {
        assertEquals(StreamRead.READ, stats(written = 96_000, unread = 60_000).read)
        assertEquals(StreamRead.NOT_READ, stats(written = 96_000, unread = 90_000).read)
    }

    @Test
    fun summaryGivesBytesDurationAndClosing() {
        val text = stats(unread = 0).summary()
        assertTrue("93 Ko envoyés" in text, text)
        assertTrue("3.0 s" in text || "3,0 s" in text, text)
        assertTrue("lus par la reconnaissance" in text, text)
        assertTrue("fin de phrase" in text, text)
    }

    // --- Conclusion d'un essai ---

    @Test
    fun emptyResultWithVoiceAndNothingReadMeansTheEngineIgnoresTheStream() {
        val (v, why) = StreamAttempt.assess(GoogleOutcome.NO_MATCH, 7, stats(written = 65_536, unread = 65_536))
        assertEquals(StreamVerdict.IGNORES_STREAM, v)
        assertTrue("NON lu" in why, why)
    }

    @Test
    fun emptyResultAfterReadingAVoiceIsAStrikeNotAProof() {
        assertEquals(StreamVerdict.READS_BUT_EMPTY, StreamAttempt.assess(GoogleOutcome.NO_MATCH, 7, stats()).first)
        assertEquals(StreamVerdict.READS_BUT_EMPTY, StreamAttempt.assess(GoogleOutcome.SPEECH_TIMEOUT, 6, stats(unread = null)).first)
    }

    @Test
    fun emptyResultWithoutVoiceInTheStreamProvesNothing() {
        assertEquals(StreamVerdict.INCONCLUSIVE, StreamAttempt.assess(GoogleOutcome.NO_MATCH, 7, stats(voice = false)).first)
    }

    @Test
    fun aResultFromAnEngineThatDidNotReadTheStreamCameFromTheFoldMic() {
        assertEquals(StreamVerdict.IGNORES_STREAM, StreamAttempt.assess(GoogleOutcome.RESULT, null, stats(written = 65_536, unread = 65_536)).first)
        assertEquals(StreamVerdict.WORKS, StreamAttempt.assess(GoogleOutcome.RESULT, null, stats()).first)
    }

    @Test
    fun networkErrorsAndCancellationsSayNothingAboutTheStream() {
        for (code in listOf(1, 2, 4, 8, 10)) {
            assertEquals(StreamVerdict.INCONCLUSIVE, StreamAttempt.assess(GoogleOutcome.ERROR, code, stats()).first, "code $code")
        }
        assertEquals(StreamVerdict.INCONCLUSIVE, StreamAttempt.assess(GoogleOutcome.CANCELLED, null, stats()).first)
    }

    @Test
    fun audioErrorsNeverReadyAndNoAnswerAreRefusals() {
        assertEquals(StreamVerdict.REFUSED, StreamAttempt.assess(GoogleOutcome.ERROR, 3, stats()).first)
        assertEquals(StreamVerdict.REFUSED, StreamAttempt.assess(GoogleOutcome.ERROR, 5, stats()).first)
        assertEquals(StreamVerdict.REFUSED, StreamAttempt.assess(GoogleOutcome.NOT_READY, null, stats(unread = null)).first)
        assertEquals(StreamVerdict.REFUSED, StreamAttempt.assess(GoogleOutcome.TIMEOUT, null, stats()).first)
        assertEquals(StreamVerdict.IGNORES_STREAM, StreamAttempt.assess(GoogleOutcome.TIMEOUT, null, stats(written = 65_536, unread = 65_536)).first)
    }

    // --- Choix du moteur pour le casque ---

    @Test
    fun onDeviceFirstThenStandardService() {
        val p = StreamEnginePolicy()
        assertEquals(GoogleEngine.ON_DEVICE, p.next(onDeviceAvailable = true))
        assertEquals(GoogleEngine.STANDARD, p.next(onDeviceAvailable = false))
    }

    @Test
    fun proofOfIgnoredStreamSwitchesEngineAtOnce() {
        val p = StreamEnginePolicy()
        val switch = p.record(GoogleEngine.ON_DEVICE, StreamVerdict.IGNORES_STREAM, "flux NON lu", true)
        assertNotNull(switch)
        assertTrue("Google sur l'appareil écarté" in switch, switch)
        assertTrue("Prochain essai : service Google" in switch, switch)
        assertEquals(GoogleEngine.STANDARD, p.next(true))
    }

    @Test
    fun readButEmptyNeedsTwoStrikes() {
        val p = StreamEnginePolicy()
        assertNull(p.record(GoogleEngine.ON_DEVICE, StreamVerdict.READS_BUT_EMPTY, "rien compris", true))
        assertEquals(GoogleEngine.ON_DEVICE, p.next(true))
        assertNotNull(p.record(GoogleEngine.ON_DEVICE, StreamVerdict.READS_BUT_EMPTY, "rien compris", true))
        assertEquals(GoogleEngine.STANDARD, p.next(true))
    }

    @Test
    fun aSuccessClearsStrikes() {
        val p = StreamEnginePolicy()
        p.record(GoogleEngine.ON_DEVICE, StreamVerdict.READS_BUT_EMPTY, "rien compris", true)
        p.record(GoogleEngine.ON_DEVICE, StreamVerdict.WORKS, "ok", true)
        assertTrue(p.works(GoogleEngine.ON_DEVICE))
        assertNull(p.record(GoogleEngine.ON_DEVICE, StreamVerdict.READS_BUT_EMPTY, "rien compris", true))
        assertEquals(GoogleEngine.ON_DEVICE, p.next(true))
    }

    @Test
    fun inconclusiveAttemptsChangeNothing() {
        val p = StreamEnginePolicy()
        repeat(5) { assertNull(p.record(GoogleEngine.ON_DEVICE, StreamVerdict.INCONCLUSIVE, "annulée", true)) }
        assertEquals(GoogleEngine.ON_DEVICE, p.next(true))
    }

    @Test
    fun bothEnginesFailingCutsGoogleForTheHeadsetOnly() {
        val p = StreamEnginePolicy()
        p.record(GoogleEngine.ON_DEVICE, StreamVerdict.IGNORES_STREAM, "flux NON lu", true)
        assertFalse(p.googleOff(true))
        p.record(GoogleEngine.STANDARD, StreamVerdict.REFUSED, "erreur audio", true)
        val last = p.record(GoogleEngine.STANDARD, StreamVerdict.REFUSED, "erreur audio", true)
        assertNotNull(last)
        assertTrue("Google est coupé pour le casque : Vosk seul" in last, last)
        assertTrue("le micro du Fold garde Google" in last, last)
        assertTrue(p.googleOff(true))
        assertNull(p.next(true))
        assertEquals(setOf(GoogleEngine.ON_DEVICE, GoogleEngine.STANDARD), p.disabledReasons().keys)
    }

    @Test
    fun withoutOnDeviceEngineOneStandardFailureIsEnoughToCut() {
        val p = StreamEnginePolicy()
        val switch = p.record(GoogleEngine.STANDARD, StreamVerdict.IGNORES_STREAM, "flux NON lu", onDeviceAvailable = false)
        assertNotNull(switch)
        assertTrue("Vosk seul" in switch, switch)
    }

    @Test
    fun remembredChoiceAndResetForRetry() {
        val p = StreamEnginePolicy()
        p.disable(GoogleEngine.ON_DEVICE, "flux NON lu")
        p.disable(GoogleEngine.STANDARD, "flux NON lu")
        assertTrue(p.googleOff(true))
        p.reset()
        assertEquals(GoogleEngine.ON_DEVICE, p.next(true))
    }

    @Test
    fun aSecondSwitchOnAnAlreadyDisabledEngineIsNotRepeated() {
        val p = StreamEnginePolicy()
        assertNotNull(p.record(GoogleEngine.ON_DEVICE, StreamVerdict.IGNORES_STREAM, "x", true))
        assertNull(p.record(GoogleEngine.ON_DEVICE, StreamVerdict.IGNORES_STREAM, "x", true))
    }

    /** Le cas réel observé sur le Fold, du premier essai à Vosk seul : aucune demande n'est perdue en route. */
    @Test
    fun realCase_onDeviceEmptyThenStandardEmpty_endsOnVoskOnlyForTheHeadset() {
        val engines = StreamEnginePolicy()
        val headset = SpeechFallbackPolicy()
        val fold = SpeechFallbackPolicy()
        val ignored = stats(written = 65_536, unread = 65_536)
        val readEmpty = stats(unread = 0)
        val attempts = listOf(GoogleEngine.ON_DEVICE to ignored, GoogleEngine.STANDARD to readEmpty, GoogleEngine.STANDARD to readEmpty)
        for ((expected, s) in attempts) {
            assertTrue(headset.useGoogle(true, phoneMic = false, externalAudioSupported = if (engines.googleOff(true)) false else null))
            assertEquals(expected, engines.next(true))
            val session = headset.start()
            val (verdict, why) = StreamAttempt.assess(GoogleOutcome.NO_MATCH, 7, s)
            engines.record(expected, verdict, why, true)
            val decision = headset.finish(session, GoogleOutcome.NO_MATCH, 7)
            assertNotNull(decision)
            assertTrue(decision.fallbackToVosk)
            // Vosk avait la même phrase : elle est utilisée, Google sera retenté à la suivante.
            val pick = RequestArbiter.pick(null, "est ce que tu m'entends", "est-ce que tu m'entends", "radio")
            assertTrue(pick is RequestPick.Vosk)
            headset.voskHandledRequest()
        }
        assertTrue(engines.googleOff(true))
        assertFalse(headset.useGoogle(true, phoneMic = false, externalAudioSupported = false))
        // Le micro du Fold n'a rien perdu.
        assertTrue(fold.useGoogle(true, phoneMic = true, externalAudioSupported = false))
        assertEquals(0, fold.consecutiveFailures)
    }

    @Test
    fun headsetFailuresDoNotSuspendGoogleOnTheFold() {
        val headset = SpeechFallbackPolicy()
        val fold = SpeechFallbackPolicy()
        repeat(3) { headset.finish(headset.start(), GoogleOutcome.NO_MATCH, 7) }
        assertTrue(headset.suspended)
        assertFalse(fold.suspended)
        assertTrue(fold.useGoogle(true, phoneMic = true, externalAudioSupported = null))
    }

    // --- Quelle phrase pour l'ingénieur ---

    @Test
    fun googleTextWinsWhenThereIsOne() {
        assertEquals(RequestPick.Google("combien d'essence"), RequestArbiter.pick("Combien d'essence ?".replace(" ?", ""), "combien essence", "", "radio"))
    }

    @Test
    fun emptyGoogleFallsBackToTheSameSentenceFromVosk() {
        val pick = RequestArbiter.pick(null, "radio est ce que tu m'entends", "radio est-ce que tu m'entends", "radio")
        assertEquals(RequestPick.Vosk("est ce que tu m'entends", "est ce que tu m'entends"), pick)
    }

    @Test
    fun googleHearingOnlyTheWakeWordAlsoFallsBackToVosk() {
        val pick = RequestArbiter.pick("Radio", "combien de tours", "", "radio")
        assertEquals(RequestPick.Vosk("combien de tours", null), pick)
    }

    @Test
    fun noiseAloneIsNotARequest() {
        assertEquals(RequestPick.Repeat, RequestArbiter.pick(null, "[unk]", "", "radio"))
        assertEquals(RequestPick.Repeat, RequestArbiter.pick(null, "", "", "radio"))
        assertEquals(RequestPick.Repeat, RequestArbiter.pick("", "radio", "radio", "radio"))
    }

    // --- Flux : fin de phrase et compteurs cohérents ---

    @Test
    fun endpointerClosesTheStreamAfterTwoSecondsOfVoiceThenSilence() {
        val e = SpeechEndpointer()
        val voice = ShortArray(4_000) { if (it % 2 == 0) 3_000 else -3_000 }
        val silence = ShortArray(4_000)
        var bytes = 0L
        var end: SpeechEndpointer.End? = null
        repeat(8) { bytes += 8_000; end = end ?: e.accept(voice, 4_000) }
        var blocks = 0
        while (end == null && blocks < 20) {
            bytes += 8_000
            end = e.accept(silence, 4_000)
            blocks++
        }
        assertEquals(SpeechEndpointer.End.AFTER_SPEECH, end)
        assertEquals(5, blocks) // 1,25 s de silence (blocs de 0,25 s) pour 1,2 s demandées
        // 3,25 s envoyées : bien au-delà du demi-seconde nécessaire pour prouver une lecture ou non.
        assertTrue(bytes > StreamStats.MIN_PROOF_BYTES * 6)
    }
}
