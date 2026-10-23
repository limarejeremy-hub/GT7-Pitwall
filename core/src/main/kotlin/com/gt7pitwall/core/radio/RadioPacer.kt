package com.gt7pitwall.core.radio

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.abs

/**
 * Diffère les messages non urgents pendant un gros freinage ou un virage serré, au plus [maxHoldMs] : le pilote les
 * entend dans la ligne droite suivante. Les alertes, les appels au stand, les messages importants et les réponses à
 * ses questions passent toujours tout de suite.
 */
class RadioPacer(private val maxHoldMs: Long = 6_000L) {
    private val held = ArrayDeque<Pair<Long, EngineerMessage>>()

    /** Le pilote est occupé : freinage marqué, ou volant bien braqué (formats de paquet qui le transmettent). */
    fun busy(p: TelemetryPacket?): Boolean {
        if (p == null || p.isPaused || !p.isOnTrack) return false
        return p.brake >= BRAKE_BUSY || (p.steeringRad?.let { abs(it) >= STEER_BUSY_RAD } == true)
    }

    fun deferrable(m: EngineerMessage): Boolean =
        m.priority <= Priority.NORMAL && m.category != MessageCategory.ANSWER && m.category != MessageCategory.ALERT &&
            m.category != MessageCategory.DRIVER && !m.expectsReply

    /** Messages à dire maintenant : ceux qui ne peuvent pas attendre, plus ceux mis de côté dès que le pilote est libre. */
    fun pace(messages: List<EngineerMessage>, busy: Boolean, nowMs: Long): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        for (m in messages) {
            if (busy && deferrable(m)) held.addLast(nowMs to m) else out += m
        }
        while (held.isNotEmpty() && (!busy || nowMs - held.first().first >= maxHoldMs)) out += held.removeFirst().second
        return out
    }

    fun clear() = held.clear()

    companion object {
        /** Frein à plus de 25 % de la course de la pédale. */
        const val BRAKE_BUSY = 64
        const val STEER_BUSY_RAD = 0.35f
    }
}
