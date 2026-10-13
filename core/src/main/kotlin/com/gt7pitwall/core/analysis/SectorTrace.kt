package com.gt7pitwall.core.analysis

import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Secteurs "Pitwall".
 *
 * GT7 ne transmet pas de temps de secteurs. On les reconstruit à partir de données réelles :
 * la position de la voiture (~60 fois/s) donne la distance parcourue, le numéro de paquet donne
 * l'écoulement du temps, et le chrono officiel du tour sert d'étalon. Le tour est découpé en
 * [sectorCount] parts de distance égale : ce ne sont pas les secteurs officiels du circuit.
 */
class SectorTrace(private val sectorCount: Int = DEFAULT_SECTORS) {

    private val distances = FloatArrayList()
    private val ticks = IntArrayList()
    private var distance = 0f
    private var tick = 0
    private var last: TelemetryPacket? = null
    private var valid = false

    /** Démarre l'enregistrement au passage de ligne. */
    fun startLap(p: TelemetryPacket) {
        distances.clear()
        ticks.clear()
        distance = 0f
        tick = 0
        last = p
        valid = true
        distances.add(0f)
        ticks.add(0)
    }

    /** Le tour en cours ne pourra pas être découpé (pause, téléportation, connexion en cours de tour). */
    fun invalidate() {
        valid = false
    }

    fun add(p: TelemetryPacket) {
        val prev = last ?: return
        if (!valid) return
        if (p.isPaused || p.isLoading) {
            valid = false
            return
        }
        val dId = p.packetId - prev.packetId
        if (dId <= 0) return // doublon ou paquet en retard
        val dx = p.positionX - prev.positionX
        val dy = p.positionY - prev.positionY
        val dz = p.positionZ - prev.positionZ
        val step = sqrt(dx * dx + dy * dy + dz * dz)
        // Au-delà, la voiture a été replacée (retour piste, rembobinage) : découpage impossible.
        if (step > MAX_STEP_PER_PACKET * dId || dId > MAX_PACKET_GAP) {
            valid = false
            return
        }
        distance += step
        tick += dId
        distances.add(distance)
        ticks.add(tick)
        last = p
    }

    /**
     * Termine le tour avec le chrono officiel GT7 et renvoie la durée de chaque secteur en ms,
     * ou null si le tour n'a pas été suivi entièrement.
     */
    fun finish(officialLapMs: Int): List<Int>? {
        if (!valid || tick <= 0 || distance < MIN_LAP_DISTANCE || officialLapMs <= 0) return null
        val msPerTick = officialLapMs.toDouble() / tick
        val boundaries = (1 until sectorCount).map { k -> tickAt(distance * k / sectorCount) * msPerTick }
        val result = mutableListOf<Int>()
        var previous = 0.0
        for (b in boundaries) {
            result += (b - previous).roundToInt()
            previous = b
        }
        result += officialLapMs - result.sum()
        valid = false
        return result
    }

    private fun tickAt(target: Float): Double {
        var i = 1
        while (i < distances.size && distances[i] < target) i++
        if (i >= distances.size) return ticks[ticks.size - 1].toDouble()
        val d0 = distances[i - 1]
        val d1 = distances[i]
        val t0 = ticks[i - 1]
        val t1 = ticks[i]
        val f = if (d1 > d0) (target - d0) / (d1 - d0) else 0f
        return t0 + (t1 - t0) * f.toDouble()
    }

    companion object {
        const val DEFAULT_SECTORS = 3

        /** ~600 km/h à 60 paquets/s. */
        const val MAX_STEP_PER_PACKET = 10f
        const val MAX_PACKET_GAP = 30
        const val MIN_LAP_DISTANCE = 300f
    }
}

/** Compare les secteurs d'un tour à ceux d'une référence (meilleur tour). */
object SectorComparison {
    /** Écart par secteur en ms (positif = temps perdu). null si l'une des listes manque. */
    fun deltas(lap: List<Int>?, reference: List<Int>?): List<Int>? {
        if (lap == null || reference == null || lap.size != reference.size) return null
        return lap.zip(reference) { a, b -> a - b }
    }
}

/** Petites listes de primitives sans boxing (60 échantillons/s pendant un tour). */
internal class FloatArrayList {
    private var data = FloatArray(4096)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Float = data[i]

    fun clear() {
        size = 0
    }
}

internal class IntArrayList {
    private var data = IntArray(4096)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]

    fun clear() {
        size = 0
    }
}
