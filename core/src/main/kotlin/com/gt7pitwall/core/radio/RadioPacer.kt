package com.gt7pitwall.core.radio

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.abs

/**
 * Radio tactique : une annonce non urgente attend un moment où le pilote est libre (ligne droite, voiture stable),
 * jamais pendant un gros freinage, une forte rotation ou une instabilité. Elle est annulée si elle devient périmée
 * (trop ancienne, tour dépassé, ou remplacée par une annonce plus récente sur le même sujet). Les alertes, les décisions
 * de course importantes, les réponses et les questions passent toujours tout de suite.
 *
 * Correction (V3) : l'ancienne version relâchait une annonce différée après 6 s, même si le pilote restait occupé.
 */
class RadioPacer(
    /** Temps libre continu avant de parler : une ligne droite, pas un simple relâché entre deux virages. */
    private val freeMs: Long = FREE_MS,
) {
    private class Held(val atMs: Long, val message: EngineerMessage)

    private val held = ArrayList<Held>()
    private var freeSinceMs: Long? = null
    private var lastBusyMs: Long? = null
    private var previous: TelemetryPacket? = null

    /** Temps écoulé d'après les paquets reçus (ms) : à passer à [pace]. */
    var packetClockMs = 0L
        private set

    /** Annonces différées, la plus ancienne d'abord (diagnostic, tests). */
    val pending: List<EngineerMessage> get() = held.map { it.message }

    /**
     * Le pilote est occupé : gros freinage, forte décélération, volant ou roues bien braqués, voiture qui tourne vite
     * (lacet), patinage ou blocage, roues hors piste. Chaque critère ne s'appuie que sur des valeurs transmises par GT7
     * dans le format reçu ; un champ absent ne compte pas.
     */
    fun busy(p: TelemetryPacket?): Boolean {
        val prev = previous
        previous = p
        // Horloge des paquets (60 par seconde) : le temps de la course, pas celui du téléphone.
        if (p != null) {
            val dId = prev?.let { p.packetId - it.packetId } ?: 1
            packetClockMs += (if (dId in 1..MAX_TICK_PACKETS) dId else 1) * 1000L / 60L
        }
        if (p == null || p.isPaused || !p.isOnTrack || p.speedKmh < 20f) return false
        if (p.brake >= BRAKE_BUSY) return true
        if (p.steeringRad?.let { abs(it) >= STEER_BUSY_RAD } == true) return true
        val wheel = listOfNotNull(p.frontWheelAngleLeftRad, p.frontWheelAngleRightRad).maxOfOrNull { abs(it) }
        if (wheel != null && wheel >= WHEEL_BUSY_RAD) return true
        // Lacet (GT7 : axe Y vertical) et accélération latérale qui en découle.
        val yaw = abs(p.angularVelocityY)
        if (yaw >= YAW_BUSY_RAD_S || yaw * p.speedMps >= LATERAL_BUSY_MS2) return true
        // Forte décélération sans frein (rétrogradage, sortie de piste) : d'après deux paquets consécutifs.
        if (prev != null) {
            val dId = p.packetId - prev.packetId
            if (dId in 1..5) {
                val decel = (prev.speedMps - p.speedMps) * 60f / dId
                if (decel >= DECEL_BUSY_MS2) return true
            }
        }
        // Instabilité : roue qui bloque ou patine nettement.
        p.wheelSlip?.toList()?.let { s -> if (s.any { it < LOCK_SLIP || it > SPIN_SLIP }) return true }
        p.surfaces?.let { if (it.wheelsOff >= 2) return true }
        return false
    }

    fun deferrable(m: EngineerMessage): Boolean =
        m.priority <= Priority.NORMAL && m.category != MessageCategory.ANSWER && m.category != MessageCategory.ALERT &&
            m.category != MessageCategory.DRIVER && !m.expectsReply

    /** Sujet d'une annonce : une annonce plus récente sur le même sujet remplace l'ancienne (jamais deux avis contradictoires). */
    private fun topic(m: EngineerMessage): String? = m.topic ?: m.dedupKey

    /**
     * Messages à dire maintenant. [busy] : état du pilote à cet instant ; [lap] : tour en cours (une annonce valable
     * jusqu'à un tour donné est annulée au-delà).
     */
    fun pace(messages: List<EngineerMessage>, busy: Boolean, nowMs: Long, lap: Int? = null): List<EngineerMessage> {
        if (busy) {
            lastBusyMs = nowMs
            freeSinceMs = null
        } else if (freeSinceMs == null) {
            freeSinceMs = nowMs
        }
        // Dans une même salve, seule la dernière annonce d'un sujet compte (plan recalculé deux fois de suite…).
        val batch = messages.filterIndexed { i, m ->
            val t = topic(m) ?: return@filterIndexed true
            m.category == MessageCategory.ANSWER || messages.drop(i + 1).none { topic(it) == t && it.category != MessageCategory.ANSWER }
        }
        val out = mutableListOf<EngineerMessage>()
        for (m in batch) {
            val t = topic(m)
            if (t != null) held.removeAll { topic(it.message) == t } // périmée : remplacée par la nouvelle
            if (deferrable(m)) held += Held(nowMs, m) else out += m
        }
        // Annonces devenues périmées : trop anciennes, ou tour dépassé.
        held.removeAll { h -> nowMs - h.atMs > ttl(h.message) || (lap != null && h.message.validUntilLap?.let { lap > it } == true) }
        val free = freeSinceMs?.let { nowMs - it >= freeMs } == true
        if (free && held.isNotEmpty()) {
            // Les plus importantes d'abord, au plus [MAX_RELEASE] d'un coup : le reste attend la ligne droite suivante.
            val release = held.sortedWith(compareByDescending<Held> { it.message.priority }.thenBy { it.atMs }).take(MAX_RELEASE)
            held.removeAll(release.toSet())
            out += release.map { it.message }
            // La suite attend encore [RELEASE_GAP_MS] de calme : deux annonces au plus, puis le pilote respire.
            freeSinceMs = nowMs + RELEASE_GAP_MS - freeMs
        }
        return out
    }

    private fun ttl(m: EngineerMessage): Long = m.ttlMs ?: when (m.priority) {
        Priority.INFO -> INFO_TTL_MS
        else -> NORMAL_TTL_MS
    }

    fun clear() {
        held.clear()
        freeSinceMs = null
        lastBusyMs = null
        previous = null
    }

    companion object {
        /**
         * Annonce à ne pas mettre dans la file de la voix : une place dite derrière une autre phrase arrive déjà fausse
         * (essai PS5). Elle reste affichée ; la prochaine place stable sera annoncée.
         */
        fun dropBeforeSpeech(m: EngineerMessage, voiceBusy: Boolean): Boolean =
            voiceBusy && m.topic == com.gt7pitwall.core.engineer.RaceEngineer.POSITION_TOPIC

        /** Frein à plus de 25 % de la course de la pédale. */
        const val BRAKE_BUSY = 64
        const val STEER_BUSY_RAD = 0.35f
        /** Angle des roues avant (format C) : environ 7°. */
        const val WHEEL_BUSY_RAD = 0.12f
        const val YAW_BUSY_RAD_S = 0.45f
        /** Environ 0,8 g en virage. */
        const val LATERAL_BUSY_MS2 = 8f
        const val DECEL_BUSY_MS2 = 7f
        const val LOCK_SLIP = 0.85f
        const val SPIN_SLIP = 1.2f
        const val FREE_MS = 1_200L
        const val MAX_TICK_PACKETS = 60
        const val RELEASE_GAP_MS = 3_000L
        const val MAX_RELEASE = 2
        const val INFO_TTL_MS = 20_000L
        const val NORMAL_TTL_MS = 40_000L
    }
}
