package com.gt7pitwall.core.engineer

import com.gt7pitwall.core.engineer.FrenchFormat.deltaSpeech
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.roundToInt

/**
 * Pneus et adhérence, uniquement à partir de mesures GT7 :
 * température de surface des 4 pneus, TCS, blocages / patinages (vitesse des roues vs voiture), chronos.
 *
 * GT7 ne transmet ni l'usure des pneus, ni la météo, ni le type de gomme : l'ingénieur parle donc
 * de ce qu'il mesure (températures qui se stabilisent, adhérence qui baisse, chronos qui se dégradent).
 * Les seuils sont volontairement prudents et regroupés ici pour être ajustés après les essais.
 */
class TyreCoach {

    // --- Montée en température (échantillon d'une seconde) ---
    private val window = ArrayDeque<Float>()
    private var lastSampleId: Int? = null
    private var startTemp: Float? = null
    private var warmAnnounced = false

    /** Température moyenne quand les pneus se sont stabilisés (référence de relais). */
    var stableTemp: Float? = null
        private set
    private var overheatWarned = false

    // --- Adhérence par tour ---
    private var gripAlertRef: LapRecord? = null
    private var severeActive = false
    private var lastDegradationLap = -NEVER

    /** Tour où une usure des pneus a été constatée (chronos qui se dégradent avec plus de glisse), depuis le dernier arrêt. */
    var wearSuspectedLap: Int? = null
        private set

    fun reset() {
        window.clear()
        lastSampleId = null
        startTemp = null
        warmAnnounced = false
        stableTemp = null
        overheatWarned = false
        gripAlertRef = null
        severeActive = false
        lastDegradationLap = -NEVER
        wearSuspectedLap = null
    }

    /** Arrêt au stand : les pneus repartent pour une nouvelle montée en température. */
    fun pitStop() {
        window.clear()
        startTemp = null
        warmAnnounced = false
        overheatWarned = false
        wearSuspectedLap = null
    }

    /** À chaque paquet. Renvoie une phrase quand il y a quelque chose à dire. */
    fun onPacket(p: TelemetryPacket): String? {
        if (!p.isOnTrack || p.isPaused || p.speedKmh < MIN_SPEED_KMH) return null
        lastSampleId?.let { if (p.packetId - it in 0 until SAMPLE_PACKETS) return null }
        lastSampleId = p.packetId
        val t = p.tyreTemps.toList().average().toFloat()
        if (startTemp == null) startTemp = t
        window.addLast(t)
        if (window.size > WINDOW_SAMPLES) window.removeFirst()

        val stable = stableTemp
        if (warmAnnounced && stable != null) {
            // Gros refroidissement (pluie, longue neutralisation) : nouvelle montée en température à suivre.
            if (t < stable - COOL_DOWN_DROP) {
                pitStop()
                startTemp = t
                window.addLast(t)
                return null
            }
            if (!overheatWarned && t >= stable + OVERHEAT_RISE) {
                overheatWarned = true
                return "Tes pneus chauffent : ${t.roundToInt()} degrés, contre ${stable.roundToInt()} quand ils étaient bien. Ménage-les un peu."
            }
            if (overheatWarned && t < stable + OVERHEAT_REARM) overheatWarned = false
            return null
        }

        if (window.size < WINDOW_SAMPLES) return null
        val first = window.take(EDGE_SAMPLES).average()
        val last = window.takeLast(EDGE_SAMPLES).average()
        val rise = t - (startTemp ?: t)
        if (rise >= MIN_WARMUP_RISE && last - first < STABLE_SLOPE) {
            warmAnnounced = true
            stableTemp = last.toFloat()
            return "Pneus en température, ${last.roundToInt()} degrés en moyenne. Tu peux attaquer !"
        }
        return null
    }

    /** En fin de tour : adhérence et dégradation. Une seule phrase, la plus importante. */
    fun onLap(laps: List<LapRecord>, raining: Boolean): String? {
        val cur = laps.lastOrNull()?.takeIf { it.hasStats() && !it.refuelled } ?: return null
        val prev = laps.dropLast(1).lastOrNull { it.hasStats() && !it.refuelled }

        // 1. Vraiment la galère.
        val curEvents = events(cur)
        val severe = (cur.tcsShare ?: 0f) >= SEVERE_TCS || curEvents >= SEVERE_EVENTS
        if (severe && !severeActive) {
            severeActive = true
            return "C'est la galère : TCS actif ${pct(cur.tcsShare)} du tour, ${cur.lockups} blocages et ${cur.wheelspins} patinages. " +
                "Freine plus tôt et sois progressif sur l'accélérateur."
        }
        if (!severe) severeActive = false

        // 2. L'adhérence baisse par rapport au tour précédent (au moins deux signaux).
        if (prev != null && gripAlertRef == null) {
            val signals = mutableListOf<String>()
            val drop = (prev.avgTyreTemp ?: 0f) - (cur.avgTyreTemp ?: 0f)
            if (drop >= GRIP_TEMP_DROP) signals += "pneus ${drop.roundToInt()} degrés plus froids"
            val tcsPrev = prev.tcsShare ?: 0f
            val tcsCur = cur.tcsShare ?: 0f
            if (tcsCur - tcsPrev >= GRIP_TCS_RISE && tcsCur >= tcsPrev * 2) signals += "TCS passé de ${pct(tcsPrev)} à ${pct(tcsCur)}"
            val evPrev = events(prev)
            if (curEvents - evPrev >= GRIP_EVENTS_RISE) signals += "$curEvents blocages ou patinages contre $evPrev"
            if (signals.size >= 2) {
                gripAlertRef = prev
                return "Attention, l'adhérence baisse : ${signals.joinToString(", ")}. Sois doux sur les pédales."
            }
        }

        // 3. L'adhérence revient.
        gripAlertRef?.let { ref ->
            val back = (cur.avgTyreTemp ?: 0f) >= (ref.avgTyreTemp ?: 0f) - RECOVERY_TEMP_MARGIN &&
                (cur.tcsShare ?: 0f) <= (ref.tcsShare ?: 0f) + RECOVERY_TCS_MARGIN
            if (back) {
                gripAlertRef = null
                return "L'adhérence revient : pneus à ${cur.avgTyreTemp?.roundToInt()} degrés, TCS à ${pct(cur.tcsShare)}. Tu peux remettre du rythme."
            }
        }

        // 4. Dégradation sur le relais (chronos qui baissent + plus de glisse) — pas sous la pluie.
        if (!raining) degradation(laps, cur)?.let { return it }
        return null
    }

    private fun degradation(laps: List<LapRecord>, cur: LapRecord): String? {
        if (cur.lapNumber - lastDegradationLap < DEGRADATION_REPEAT_LAPS) return null
        // Relais en cours : après le dernier ravitaillement, sans le tour de sortie des stands.
        val lastPit = laps.indexOfLast { it.refuelled }
        val stint = laps.drop(lastPit + 2).filter { it.hasStats() && (it.timeMs ?: 0) > 0 }
        if (stint.size < DEGRADATION_MIN_LAPS) return null
        val best = stint.sortedBy { it.timeMs }.take(2)
        val recent = stint.takeLast(2)
        if (recent.any { it in best }) return null
        val loss = recent.map { it.timeMs!! }.average() - best.map { it.timeMs!! }.average()
        val tcsRise = recent.map { it.tcsShare ?: 0f }.average() - best.map { it.tcsShare ?: 0f }.average()
        val eventsRise = recent.map { events(it) }.average() - best.map { events(it) }.average()
        if (loss < DEGRADATION_LOSS_MS || (tcsRise < DEGRADATION_TCS_RISE && eventsRise < DEGRADATION_EVENTS_RISE)) return null
        lastDegradationLap = cur.lapNumber
        wearSuspectedLap = cur.lapNumber
        val why = buildList {
            if (tcsRise >= DEGRADATION_TCS_RISE) add("le TCS travaille plus")
            if (eventsRise >= DEGRADATION_EVENTS_RISE) add("ça glisse plus")
        }.joinToString(" et ")
        return "Tes chronos se dégradent : plus ${deltaSpeech(loss.toInt())} sur tes meilleurs tours du relais, et $why. " +
            "Tes pneus fatiguent sûrement, ménage-les."
    }

    private fun LapRecord.hasStats() = avgTyreTemp != null && tcsShare != null && lockups != null && wheelspins != null

    private fun events(l: LapRecord) = (l.lockups ?: 0) + (l.wheelspins ?: 0)

    private fun pct(v: Float?) = "${((v ?: 0f) * 100).roundToInt()} pour cent"

    companion object {
        const val MIN_SPEED_KMH = 30f
        private const val NEVER = 1_000
        const val SAMPLE_PACKETS = 60
        const val WINDOW_SAMPLES = 20
        const val EDGE_SAMPLES = 5
        const val MIN_WARMUP_RISE = 8f
        const val STABLE_SLOPE = 1.5
        const val COOL_DOWN_DROP = 15f
        const val OVERHEAT_RISE = 15f
        const val OVERHEAT_REARM = 8f

        const val SEVERE_TCS = 0.30f
        const val SEVERE_EVENTS = 10
        const val GRIP_TEMP_DROP = 10f
        const val GRIP_TCS_RISE = 0.10f
        const val GRIP_EVENTS_RISE = 4
        const val RECOVERY_TEMP_MARGIN = 5f
        const val RECOVERY_TCS_MARGIN = 0.05f

        const val DEGRADATION_MIN_LAPS = 4
        const val DEGRADATION_LOSS_MS = 1_000.0
        const val DEGRADATION_TCS_RISE = 0.03
        const val DEGRADATION_EVENTS_RISE = 2.0
        const val DEGRADATION_REPEAT_LAPS = 3
    }
}
