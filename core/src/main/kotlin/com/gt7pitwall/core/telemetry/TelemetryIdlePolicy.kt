package com.gt7pitwall.core.telemetry

/**
 * Issue #63 : mise en veille de Pitwall quand le flux GT7 s'arrête durablement (GT7 fermé, PS5 éteinte, Wi-Fi coupé).
 * La télémétrie ne dit jamais « PS5 éteinte » ni « GT7 quitté » : seulement que le flux est interrompu.
 *
 * Deux délais, mesurés sur une horloge monotone (jamais l'heure murale, qui peut sauter) :
 * - jamais connecté : [neverConnectedMs] sans premier paquet vivant depuis le démarrage → veille ;
 * - déjà connecté : [silentMs] sans paquet vivant → veille. Une coupure brève (statut « silence » après 2 s, Wi-Fi qui
 *   décroche, chargement, menus) reste bien en dessous.
 *
 * « Vivant » : un paquet GT7 déchiffré dont l'identifiant de paquet avance. Une voiture immobile aux stands ou un menu
 * qui envoie encore des paquets numérotés restent vivants ; un instantané figé répété avec le même identifiant, ou
 * des datagrammes illisibles (jamais transmis ici), ne le sont pas. La veille ne dépend ni de la position ni de la
 * vitesse, ni de l'écran ou du cycle de vie de l'application.
 */
class TelemetryIdlePolicy(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    val neverConnectedMs: Long = NEVER_CONNECTED_MS,
    val silentMs: Long = SILENT_MS,
) {
    enum class Reason(val label: String) {
        NEVER_CONNECTED("aucun paquet GT7 depuis le démarrage"),
        SILENT("flux GT7 interrompu"),
    }

    private var startedMs: Long? = null
    private var lastAliveMs: Long? = null
    private var lastPacketId: Int? = null

    /** Un paquet vivant a été reçu depuis le démarrage. */
    val connected: Boolean get() = lastAliveMs != null

    fun start() {
        startedMs = clock()
        lastAliveMs = null
        lastPacketId = null
    }

    fun stop() {
        startedMs = null
    }

    /** Paquet GT7 déchiffré. Vrai s'il compte comme signe de vie (identifiant nouveau). */
    fun onPacket(packetId: Int): Boolean {
        if (startedMs == null) return false
        if (packetId == lastPacketId) return false
        lastPacketId = packetId
        lastAliveMs = clock()
        return true
    }

    /** Raison de mettre Pitwall en veille maintenant, ou null. À appeler périodiquement (quelques secondes). */
    fun check(): Reason? {
        val start = startedMs ?: return null
        val now = clock()
        val alive = lastAliveMs
        return when {
            alive == null && now - start >= neverConnectedMs -> Reason.NEVER_CONNECTED
            alive != null && now - alive >= silentMs -> Reason.SILENT
            else -> null
        }
    }

    /** Silence depuis le dernier signe de vie (ou depuis le démarrage), ms ; null si arrêté. */
    fun silenceMs(): Long? {
        val start = startedMs ?: return null
        return clock() - (lastAliveMs ?: start)
    }

    companion object {
        const val NEVER_CONNECTED_MS = 5L * 60 * 1000
        const val SILENT_MS = 120_000L
    }
}
