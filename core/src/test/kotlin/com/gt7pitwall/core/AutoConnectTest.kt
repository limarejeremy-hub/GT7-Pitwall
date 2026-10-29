package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.AutoConnect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #55 : démarrage automatique à l'ouverture, une seule fois, jamais contre un « Arrêter » du pilote. */
class AutoConnectTest {
    private var now = 0L
    private fun policy() = AutoConnect { now }

    @Test fun offByDefaultNothingStarts() {
        val a = policy()
        assertFalse(a.onOpen(enabled = false, running = false))
    }

    @Test fun startsOnceThenFoldRotationAndBackgroundNeverRestart() {
        val a = policy()
        assertTrue(a.onOpen(enabled = true, running = false))
        // Pliage/dépliage, rotation : l'activité est recréée avant que le service ait démarré le moteur.
        now += 300
        assertFalse(a.onOpen(enabled = true, running = false))
        // Moteur démarré : retours au premier plan, navigation, notifications.
        repeat(5) { now += 60_000; assertFalse(a.onOpen(enabled = true, running = true)) }
    }

    @Test fun aVoluntaryStopHoldsUntilTheDriverStartsAgain() {
        val a = policy()
        assertTrue(a.onOpen(enabled = true, running = false))
        a.onUserStop()
        repeat(3) { now += 60_000; assertFalse(a.onOpen(enabled = true, running = false)) }
        a.onUserStart()
        // Démarrage manuel en cours : pas de seconde demande.
        assertFalse(a.onOpen(enabled = true, running = false))
        now += 60_000
        assertFalse(a.onOpen(enabled = true, running = true))
    }

    @Test fun aServiceKilledByAndroidIsRequestedAgainAtTheNextOpening() {
        val a = policy()
        assertTrue(a.onOpen(enabled = true, running = false))
        // Le système a arrêté le service sans action du pilote ; l'ouverture suivante redemande, une fois.
        now += AutoConnect.PENDING_MS + 1
        assertTrue(a.onOpen(enabled = true, running = false))
        assertFalse(a.onOpen(enabled = true, running = false))
    }
}
