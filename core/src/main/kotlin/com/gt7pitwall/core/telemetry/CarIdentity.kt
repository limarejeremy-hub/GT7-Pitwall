package com.gt7pitwall.core.telemetry

/**
 * Voiture conduite d'après le code 0x124 (issue #40). Lit TOUS les paquets, y compris ceux des menus (tour négatif)
 * que le suivi de session ignore : essai du 28/10, la voiture changée dans GT7 restait affichée « confirmée par la PS5 »
 * parce que la connexion était vivante alors que le code n'était relu qu'en piste.
 *
 * - Un nouveau code n'est retenu qu'après [STABLE_PACKETS] paquets consécutifs sur au moins [STABLE_MS] : un code nul,
 *   un chargement, une télémétrie figée ou une valeur isolée ne changent rien.
 * - « En direct » seulement si un paquet non figé de cette voiture est arrivé depuis moins de [LIVE_MS] ; sinon c'est la
 *   dernière voiture détectée, jamais une confirmation.
 * - Les changements sont gardés (au plus [MAX_TRANSITIONS]) avec le format et l'état de la session.
 */
class CarIdentity {
    data class Transition(val from: Int?, val to: Int, val atMs: Long, val format: PacketFormat, val state: String) {
        val text: String get() = (from?.let { "voiture $it → $to" } ?: "voiture $to identifiée") + " ($state, format ${format.label})"
    }

    @Volatile
    var current: Int? = null
        private set
    @Volatile
    private var lastLiveMs = Long.MIN_VALUE / 2
    private var candidate: Int? = null
    private var candidateCount = 0
    private var candidateSinceMs = 0L
    private var prev: TelemetryPacket? = null
    @Volatile
    private var frozen = 0
    private val log = ArrayDeque<Transition>()

    /** Lu par l'écran pendant que la télémétrie écrit : copie sous verrou. */
    val transitions: List<Transition> get() = synchronized(log) { log.toList() }

    /** En direct : paquet récent de cette voiture, et pas d'instantané figé en cours (même dans le délai de [LIVE_MS]). */
    fun live(nowMs: Long): Boolean = current != null && nowMs - lastLiveMs <= LIVE_MS && frozen < FROZEN_PACKETS

    fun onPacket(p: TelemetryPacket, nowMs: Long): Transition? {
        val before = prev
        prev = p
        frozen = if (before != null && isRepeat(before, p)) frozen + 1 else 0
        val code = p.carCode
        if (code <= 0 || p.isLoading || frozen >= FROZEN_PACKETS) return null
        if (code == current) {
            lastLiveMs = nowMs
            candidate = null
            candidateCount = 0
            return null
        }
        if (candidate != code) {
            candidate = code
            candidateCount = 0
            candidateSinceMs = nowMs
        }
        candidateCount++
        if (candidateCount < STABLE_PACKETS || nowMs - candidateSinceMs < STABLE_MS) return null
        val t = Transition(current, code, nowMs, p.format, state(p))
        current = code
        lastLiveMs = nowMs
        candidate = null
        candidateCount = 0
        synchronized(log) {
            log.addLast(t)
            while (log.size > MAX_TRANSITIONS) log.removeFirst()
        }
        return t
    }

    /**
     * Même instantané renvoyé en boucle : la voiture n'est pas « en direct ».
     * - Numéro de paquet (0x70) qui n'avance plus : instantané répété, **y compris à 0 km/h** (revue PR #42 : au
     *   garage, un ancien instantané à l'arrêt entretenait « Confirmée par la PS5 »). Une voiture vraiment arrêtée
     *   continue d'envoyer des paquets numérotés à la suite.
     * - Numéro qui recule : reconnexion ou remise à zéro du compteur par GT7. Ce n'est pas un gel ; le compte repart.
     * - Numéro qui avance mais même position et même vitesse non nulle : instantané figé (constat du 21/10).
     * Limite : un instantané à 0 km/h renvoyé avec des numéros qui avancent reste indiscernable d'une voiture arrêtée.
     */
    private fun isRepeat(before: TelemetryPacket, p: TelemetryPacket): Boolean = when {
        p.packetId == before.packetId -> true
        p.packetId < before.packetId -> false
        else -> p.speedKmh > 1f && p.positionX == before.positionX && p.positionZ == before.positionZ && p.speedMps == before.speedMps
    }

    private fun state(p: TelemetryPacket): String = when {
        p.isPaused -> "pause"
        p.currentLap < 0 -> "menu"
        p.currentLap == 0 -> "avant le départ"
        else -> "en piste, tour ${p.currentLap}"
    }

    companion object {
        const val STABLE_PACKETS = 30
        const val STABLE_MS = 300L
        const val LIVE_MS = 2_000L
        const val FROZEN_PACKETS = 30
        const val MAX_TRANSITIONS = 20
    }
}
