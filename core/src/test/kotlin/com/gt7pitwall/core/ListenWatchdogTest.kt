package com.gt7pitwall.core

import com.gt7pitwall.core.radio.ListenWatchdog
import com.gt7pitwall.core.radio.ListenWatchdog.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #32 (APK #426, SUGO 27/10) : « Radio » muet pendant 14 tours. Chaque façon connue de bloquer l'écoute sans
 * signe visible est reprise, avec sa raison, sans boucle de redémarrage. Ces tests ne valent pas un essai Fold + PS5.
 */
class ListenWatchdogTest {
    private fun input(
        now: Long,
        running: Boolean = true,
        error: Boolean = false,
        paused: Boolean = false,
        speaking: Boolean = false,
        micReleased: Boolean = false,
        googleListening: Boolean = false,
        lastBlockMs: Long = now,
        lastSoundMs: Long = now,
        remoteAvailable: Boolean = true,
        usingRemote: Boolean = true,
        startedMs: Long = 1L,
        wanted: Boolean = true,
    ) = ListenWatchdog.Input(now, wanted, running, error, paused, speaking, micReleased, googleListening, lastBlockMs, lastSoundMs, remoteAvailable, usingRemote, startedMs)

    @Test
    fun aHealthyListenerIsLeftAlone() {
        val w = ListenWatchdog()
        for (t in 1_000L..120_000L step 1_000L) assertNull(w.check(input(t)))
        assertEquals(0, w.restarts)
    }

    @Test
    fun anErroredListenerIsRestartedWithBackoffNotInALoop() {
        // Bascule Discord → Fold au départ : micro indisponible, écoute en erreur. Avant : plus jamais relancée.
        val w = ListenWatchdog()
        val restarts = (0L..60_000L step 1_000L).mapNotNull { t -> w.check(input(t, running = false, error = true))?.let { t } }
        assertEquals(listOf(0L, 5_000L, 20_000L, 50_000L), restarts, "délais 0, 5, 15, 30 s")
        assertIs<Action.Restart>(w.check(input(110_000L, running = false, error = true)))
        assertNull(ListenWatchdog().check(input(0L, running = false, wanted = false)), "écoute coupée par le pilote : rien")
    }

    @Test
    fun aLostEndOfSpeechNoLongerMutesTheRadioForTheWholeRace() {
        val w = ListenWatchdog()
        // Annonces normales : pauses courtes, rien à faire.
        assertNull(w.check(input(1_000L, paused = true, speaking = true)))
        assertNull(w.check(input(9_000L, paused = true, speaking = true, lastBlockMs = 1_000L)))
        assertNull(w.check(input(10_000L)))
        // Fin de lecture perdue : la voix « parle » depuis une minute.
        assertNull(w.check(input(20_000L, paused = true, speaking = true, lastBlockMs = 10_000L)))
        val a = w.check(input(80_000L, paused = true, speaking = true, lastBlockMs = 10_000L))
        assertIs<Action.Unpause>(a)
        assertTrue(a.reason.contains("n'a pas signalé sa fin"), a.reason)
    }

    @Test
    fun aMicLentToGoogleIsTakenBackWhenGoogleStopped() {
        val w = ListenWatchdog()
        assertNull(w.check(input(1_000L, micReleased = true, googleListening = true, lastBlockMs = 0L)))
        assertNull(w.check(input(10_000L, micReleased = true, googleListening = false, lastBlockMs = 0L)))
        assertIs<Action.ReclaimMic>(w.check(input(13_000L, micReleased = true, googleListening = false, lastBlockMs = 0L)))
    }

    @Test
    fun aMissedHeadsetSwitchIsCorrected() {
        val w = ListenWatchdog()
        assertNull(w.check(input(1_000L, remoteAvailable = true, usingRemote = false)))
        val a = w.check(input(4_000L, remoteAvailable = true, usingRemote = false))
        assertIs<Action.Restart>(a)
        assertEquals("casque PS5 disponible, écoute restée sur le Fold", a.reason)
    }

    @Test
    fun aStalledAudioLoopIsRestartedAndAMutedFoldMicIsReportedOnce() {
        val w = ListenWatchdog()
        val a = w.check(input(30_000L, lastBlockMs = 24_000L))
        assertIs<Action.Restart>(a)
        assertTrue(a.reason.startsWith("aucun bloc audio depuis 6 s"), a.reason)
        val f = ListenWatchdog()
        val muted = (1_000L..40_000L step 1_000L).mapNotNull { t ->
            f.check(input(t, remoteAvailable = false, usingRemote = false, lastSoundMs = 1_000L))
        }
        assertEquals(1, muted.size, "signalé une fois")
        assertIs<Action.Report>(muted.single())
    }
}
