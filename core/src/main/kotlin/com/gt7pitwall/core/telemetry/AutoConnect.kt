package com.gt7pitwall.core.telemetry

/**
 * Issue #55 : démarrer la télémétrie à l'ouverture de Pitwall, sans bouton, si le pilote l'a activé.
 *
 * Une seule demande de démarrage par ouverture réelle : un pliage/dépliage du Fold, une rotation ou un retour au premier
 * plan rappellent [onOpen] sans jamais relancer une réception déjà active (relancer le moteur clôturerait la session en
 * cours). Un « Arrêter » volontaire tient jusqu'à un démarrage explicite du pilote ; rien ne reconnecte derrière lui.
 *
 * Ne concerne que la réception UDP : le bot Discord garde son entrée automatique au salon, et le pilote PS5 rejoint
 * lui-même le salon (aucune API ne le permet depuis Android).
 */
class AutoConnect(private val clock: () -> Long = System::currentTimeMillis) {
    /** Le pilote a arrêté la réception : pas de reconnexion automatique avant son prochain « Démarrer ». */
    var stoppedByUser = false
        private set

    private var requestedAt: Long? = null

    /**
     * Ouverture ou retour au premier plan. Vrai : il faut demander le démarrage maintenant (et c'est noté comme demandé).
     * [running] : la réception tourne déjà.
     */
    fun onOpen(enabled: Boolean, running: Boolean): Boolean {
        if (!enabled || running || stoppedByUser) return false
        // Une demande vient de partir et le service ne l'a pas encore traitée : on ne la double pas.
        val pending = requestedAt?.let { clock() - it < PENDING_MS } == true
        if (pending) return false
        requestedAt = clock()
        return true
    }

    /** « Démarrer la télémétrie » touché : la reconnexion automatique redevient permise. */
    fun onUserStart() {
        stoppedByUser = false
        requestedAt = clock()
    }

    /** « Arrêter » (écran ou notification). */
    fun onUserStop() {
        stoppedByUser = true
        requestedAt = null
    }

    companion object {
        /** Délai laissé au service pour démarrer avant qu'une nouvelle ouverture puisse redemander. */
        const val PENDING_MS = 10_000L
    }
}
