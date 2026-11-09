package com.gt7pitwall.core.session

import com.gt7pitwall.core.analysis.TrackOutline
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.abs
import kotlin.math.hypot

/** Nature d'un passage où la voiture a ravitaillé ou s'est immobilisée. */
enum class PitAssessment {
    /** Le niveau d'essence a augmenté : passage aux stands certain. */
    REFUEL,

    /** Immobilisation qui ressemble à un arrêt aux stands (approche au limiteur, près de la ligne, hors trajectoire). */
    PIT_LIKE_STOP,

    /** Immobilisation sur la piste (accident, tête-à-queue, arrêt en piste) : ce n'est pas un arrêt aux stands. */
    TRACK_STOP,
}

/**
 * Détection des passages aux stands, sans jamais compter un simple arrêt à 0 km/h comme preuve.
 *
 * Un "passage" s'ouvre au premier signe (ravitaillement, ou immobilisation de 2 s en course) et se ferme
 * quand la voiture repart vite : un même passage n'est donc jamais compté deux fois, même s'il
 * chevauche la ligne d'arrivée. GT7 ne transmet pas l'entrée dans la voie des stands : les indices
 * utilisés sont l'approche à vitesse limitée, l'absence de freinage violent, et — si la trajectoire
 * d'un tour complet est connue — la proximité de la ligne de départ et l'écart avec la trajectoire de course.
 */
class PitDetector(private val refuelThreshold: Float = 0.05f) {

    /**
     * [trackReason] : pour un [PitAssessment.TRACK_STOP], l'indice qui a écarté l'hypothèse du stand. Seul un choc
     * ([TrackReason.VIOLENT]) est un signe fort ; les autres indices se trompent parfois (#73 : vrai arrêt au stand du
     * tour 7 classé « en piste »), le contrôleur les rapproche alors du plan et du règlement.
     */
    data class Visit(
        val lap: Int,
        val refuelAmount: Float,
        val stoppedSeconds: Float,
        val assessment: PitAssessment,
        val locationKnown: Boolean,
        val trackReason: TrackReason? = null,
    )

    /** Pourquoi une immobilisation n'a pas été jugée « stand ». */
    enum class TrackReason(val label: String) {
        VIOLENT("décélération violente avant l'arrêt (choc)"),
        NO_LIMITER("pas d'approche à vitesse limitée"),
        FAR_FROM_LINE("loin de la ligne de départ"),
        ON_RACING_LINE("sur la trajectoire de course"),
    }

    private val speeds = FloatArray(HISTORY)
    private var count = 0
    private var head = 0

    private var open = false
    private val rise = FuelRise()

    /** Un passage est en cours (ravitaillement ou immobilisation), jusqu'à la sortie des stands. */
    val inVisit: Boolean get() = open
    private var visitLap = 0
    private var fuel = 0f
    private var stoppedPackets = 0
    private var stillPackets = 0
    private var fastPackets = 0
    private var pitLike = false
    private var trackReason: TrackReason? = null
    private var locationKnown = false

    fun reset() {
        count = 0
        head = 0
        open = false
        rise.reset()
        fuel = 0f
        stoppedPackets = 0
        stillPackets = 0
        fastPackets = 0
    }

    /**
     * À chaque paquet en course. [raceStarted] : le départ a eu lieu (pas d'arrêt compté sur la grille).
     * Renvoie le passage terminé, le cas échéant.
     */
    fun update(p: TelemetryPacket, prev: TelemetryPacket, raceStarted: Boolean, outline: TrackOutline?): Visit? {
        push(p.speedKmh)
        if (!raceStarted || p.currentLap < 1 || p.isPaused || p.isLoading) return null

        val fuelUp = p.fuelLevel - prev.fuelLevel
        // Un ravitaillement lent monte de moins de [refuelThreshold] par paquet : on cumule la hausse.
        val risen = rise.update(fuelUp)
        val stationary = p.speedKmh < STATIONARY_KMH
        stillPackets = if (stationary) stillPackets + 1 else 0

        val opened = !open
        if (!open) {
            when {
                risen > refuelThreshold -> {
                    openVisit(p, outline, assess = false)
                    pitLike = true
                    fuel = risen
                }
                stillPackets >= OPEN_STOPPED_PACKETS -> openVisit(p, outline, assess = true)
                else -> return null
            }
        }

        if (!opened && fuelUp > FuelRise.NOISE) fuel += fuelUp
        if (stationary) stoppedPackets++
        fastPackets = if (p.speedKmh >= CLOSE_KMH) fastPackets + 1 else 0
        if (fastPackets < CLOSE_FAST_PACKETS) return null

        // La voiture est repartie franchement : on clôt le passage.
        open = false
        rise.reset()
        val assessment = when {
            fuel >= MIN_REFUEL -> PitAssessment.REFUEL
            pitLike -> PitAssessment.PIT_LIKE_STOP
            else -> PitAssessment.TRACK_STOP
        }
        return Visit(visitLap, fuel, stoppedPackets / 60f, assessment, locationKnown, trackReason.takeIf { assessment == PitAssessment.TRACK_STOP })
    }

    private fun openVisit(p: TelemetryPacket, outline: TrackOutline?, assess: Boolean) {
        open = true
        visitLap = p.currentLap
        fuel = 0f
        // Immobilisation déjà écoulée avant l'ouverture (2 s) : comptée dans la durée (le paquet courant l'est ensuite).
        stoppedPackets = if (assess) (stillPackets - 1).coerceAtLeast(0) else 0
        fastPackets = 0
        locationKnown = outline != null && outline.xs.size >= 2
        trackReason = if (assess) whyNotPit(p, outline) else null
        pitLike = trackReason == null
    }

    /** Indices d'un arrêt aux stands, calculés au moment où la voiture s'immobilise : null s'ils concordent, sinon le premier qui manque. */
    private fun whyNotPit(p: TelemetryPacket, outline: TrackOutline?): TrackReason? {
        // 1. Pas de décélération violente juste avant l'arrêt (choc). Le passage s'ouvre après 2 s d'immobilité : la
        //    fenêtre remonte avant ces 2 s (sinon elle ne contient que des 0 km/h et un choc n'est jamais vu).
        val recent = lastSpeeds(VIOLENT_WINDOW + stillPackets)
        for (i in 1 until recent.size) {
            val g = abs(recent[i] - recent[i - 1]) / 3.6f * 60f / 9.81f
            if (g > VIOLENT_G) return TrackReason.VIOLENT
        }
        // 2. Approche à vitesse limitée (limiteur de la voie des stands) : quelques secondes entre 20 et 105 km/h,
        //    sans dépasser 110 km/h dans les 5 dernières secondes.
        val approach = lastSpeeds(HISTORY)
        val limited = approach.count { it in 20f..105f }
        val maxRecent = lastSpeeds(300).maxOrNull() ?: 0f
        if (limited < LIMITER_PACKETS || maxRecent > 110f) return TrackReason.NO_LIMITER
        // 3. Position, si la trajectoire d'un tour complet est connue.
        if (outline == null || outline.xs.size < 2) return null
        val toStartLine = hypot(p.positionX - outline.xs[0], p.positionZ - outline.zs[0])
        if (toStartLine > MAX_DISTANCE_TO_START_M) return TrackReason.FAR_FROM_LINE
        if (distanceToLine(p.positionX, p.positionZ, outline) < MIN_OFF_LINE_M) return TrackReason.ON_RACING_LINE
        return null
    }

    private fun distanceToLine(x: Float, z: Float, o: TrackOutline): Float {
        var best = Float.MAX_VALUE
        for (i in 1 until o.xs.size) {
            val ax = o.xs[i - 1]
            val az = o.zs[i - 1]
            val dx = o.xs[i] - ax
            val dz = o.zs[i] - az
            val len2 = dx * dx + dz * dz
            val t = if (len2 > 0f) (((x - ax) * dx + (z - az) * dz) / len2).coerceIn(0f, 1f) else 0f
            val d = hypot(x - (ax + t * dx), z - (az + t * dz))
            if (d < best) best = d
        }
        return best
    }

    private fun push(v: Float) {
        speeds[head] = v
        head = (head + 1) % HISTORY
        if (count < HISTORY) count++
    }

    private fun lastSpeeds(n: Int): FloatArray {
        val k = minOf(n, count)
        return FloatArray(k) { i -> speeds[(head - k + i + HISTORY) % HISTORY] }
    }

    companion object {
        const val HISTORY = 600 // 10 s
        const val STATIONARY_KMH = 1f
        const val OPEN_STOPPED_PACKETS = 120 // 2 s immobile
        const val CLOSE_KMH = 120f
        const val CLOSE_FAST_PACKETS = 60 // 1 s au-dessus de 120 km/h : sortie des stands
        const val MIN_REFUEL = 0.5f
        const val VIOLENT_WINDOW = 120
        /**
         * Choc : au-delà de tout freinage (une voiture de course freine à 4-5 g au plus). Jusqu'au 09/11 la fenêtre ne
         * voyait que les 2 s d'immobilité et le contrôle ne servait jamais ; à 2,5 g il aurait pris un freinage appuyé
         * dans la voie des stands pour un accident.
         */
        const val VIOLENT_G = 8f
        const val LIMITER_PACKETS = 180 // 3 s à vitesse limitée
        const val MAX_DISTANCE_TO_START_M = 800f
        const val MIN_OFF_LINE_M = 4f
    }
}

/**
 * Hausse cumulée du niveau d'essence sur des paquets consécutifs : GT7 ravitaille progressivement, parfois de
 * moins de 0,05 L par paquet. Toute baisse (consommation) remet le cumul à zéro.
 */
class FuelRise {
    private var total = 0f

    fun update(delta: Float): Float {
        when {
            delta > NOISE -> total += delta
            delta < -NOISE -> total = 0f
        }
        return total
    }

    fun reset() {
        total = 0f
    }

    companion object {
        const val NOISE = 0.0005f
    }
}
