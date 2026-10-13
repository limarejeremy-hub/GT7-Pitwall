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
    private val xs = FloatArrayList()
    private val zs = FloatArrayList()

    /** Vitesse maximale du tour (km/h). */
    var maxSpeedKmh = 0f
        private set
    private var packets = 0
    private var tcsPackets = 0
    private var asmPackets = 0

    /** Part du tour où l'antipatinage (TCS) était actif (0..1). */
    val tcsShare: Float get() = if (packets > 0) tcsPackets.toFloat() / packets else 0f
    val asmShare: Float get() = if (packets > 0) asmPackets.toFloat() / packets else 0f

    /** Vrai si le tour a été suivi depuis la ligne sans interruption. */
    val isComplete: Boolean get() = valid
    private var distance = 0f
    private var tick = 0
    private var last: TelemetryPacket? = null
    private var valid = false

    /** Démarre l'enregistrement au passage de ligne. */
    fun startLap(p: TelemetryPacket) {
        distances.clear()
        ticks.clear()
        xs.clear()
        zs.clear()
        distance = 0f
        tick = 0
        last = p
        valid = true
        maxSpeedKmh = p.speedKmh
        packets = 0
        tcsPackets = 0
        asmPackets = 0
        record(p)
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
        record(p)
        last = p
        if (p.speedKmh > maxSpeedKmh) maxSpeedKmh = p.speedKmh
        packets++
        if (p.isTcsActive) tcsPackets++
        if (p.isAsmActive) asmPackets++
    }

    private fun record(p: TelemetryPacket) {
        distances.add(distance)
        ticks.add(tick)
        xs.add(p.positionX)
        zs.add(p.positionZ)
    }

    /** Tracé du tour (un point sur [step]) avec le début des secteurs, si le tour est complet. */
    fun outline(lapNumber: Int, step: Int = 4): TrackOutline? {
        if (!valid || distance < MIN_LAP_DISTANCE) return null
        val n = (distances.size + step - 1) / step
        val ox = FloatArray(n) { xs[minOf(it * step, xs.size - 1)] }
        val oz = FloatArray(n) { zs[minOf(it * step, zs.size - 1)] }
        val starts = IntArray(sectorCount - 1) { k ->
            val target = distance * (k + 1) / sectorCount
            var i = 0
            while (i < distances.size && distances[i] < target) i++
            minOf(i / step, n - 1)
        }
        return TrackOutline(ox, oz, starts, lapNumber)
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
