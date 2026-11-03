package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.AutoConnect
import com.gt7pitwall.core.telemetry.TelemetryIdlePolicy
import com.gt7pitwall.core.telemetry.TelemetryIdlePolicy.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #63 : Pitwall se met en veille quand le flux GT7 s'arrête durablement, jamais sur une coupure brève. */
class TelemetryIdlePolicyTest {
    private var now = 1_000L
    private fun policy() = TelemetryIdlePolicy({ now }).also { it.start() }
    private var id = 0
    private fun TelemetryIdlePolicy.live(seconds: Int) {
        repeat(seconds * 60) { onPacket(++id); now += 16 }
    }

    @Test fun silenceAfterAValidPacketOnlySuspendsAtTwoMinutes() {
        for ((silence, expected) in listOf(0 to null, 2 to null, 30 to null, 119 to null, 120 to Reason.SILENT)) {
            val p = policy()
            p.live(5)
            now += silence * 1000L
            assertEquals(expected, p.check(), "$silence s de silence")
        }
    }

    @Test fun packetsResumingAt119SecondsKeepPitwallAwake() {
        val p = policy()
        p.live(5)
        now += 119_000
        p.live(1)
        now += 119_000
        assertNull(p.check())
        now += 1_000
        assertEquals(Reason.SILENT, p.check())
    }

    @Test fun fiveMinutesWithoutEverReceivingSuspends() {
        val p = policy()
        now += 4 * 60_000
        assertNull(p.check())
        now += 60_000
        assertEquals(Reason.NEVER_CONNECTED, p.check())
        assertFalse(p.connected)
    }

    @Test fun aFrozenSnapshotWithTheSamePacketIdDoesNotKeepPitwallAwake() {
        val p = policy()
        p.live(5)
        // GT7 répète le même instantané (même identifiant) : pas un signe de vie.
        repeat(130 * 60) { assertFalse(p.onPacket(id)); now += 16 }
        assertEquals(Reason.SILENT, p.check())
    }

    @Test fun aStillButLiveFeedNeverSuspends() {
        // Voiture immobile aux stands ou menu : paquets numérotés qui avancent, même sans mouvement.
        val p = policy()
        p.live(10 * 60)
        assertNull(p.check())
    }

    @Test fun nothingIsCheckedOnceStopped() {
        val p = policy()
        p.stop()
        now += 10 * 60_000
        assertNull(p.check())
        assertFalse(p.onPacket(1))
    }

    @Test fun afterAnAutoSuspendOnlyAnIntentionalOpeningReconnects() {
        var t = 0L
        val a = AutoConnect { t }
        assertTrue(a.onOpen(enabled = true, running = false))
        t += 60_000
        a.onAutoSuspended()
        // Rotation, pliage du Fold : quelques centaines de ms en arrière-plan, pas de boucle de reconnexion.
        repeat(5) {
            a.onBackground(); t += 400; a.onReopened(freshLaunch = false)
            assertFalse(a.onOpen(enabled = true, running = false))
            t += 20_000
        }
        // Le pilote revient plus tard dans Pitwall : la connexion automatique reprend.
        a.onBackground(); t += AutoConnect.REOPEN_AFTER_MS; a.onReopened(freshLaunch = false)
        assertTrue(a.onOpen(enabled = true, running = false))
    }

    @Test fun aFreshLaunchOrStartAlsoLiftsTheSuspend() {
        var t = 0L
        val a = AutoConnect { t }
        a.onAutoSuspended()
        a.onReopened(freshLaunch = true)
        assertTrue(a.onOpen(enabled = true, running = false))
        val b = AutoConnect { t }
        b.onAutoSuspended()
        b.onUserStart()
        assertFalse(b.autoSuspended)
    }

    @Test fun aVoluntaryStopKeepsPriorityOverTheReopening() {
        var t = 0L
        val a = AutoConnect { t }
        a.onUserStop()
        a.onBackground(); t += 10 * 60_000; a.onReopened(freshLaunch = false)
        assertFalse(a.onOpen(enabled = true, running = false), "« Arrêter » tient jusqu'à « Démarrer »")
    }
}
