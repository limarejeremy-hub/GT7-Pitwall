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
    private var tyreTempSum = 0.0
    private var lockState = 0
    private var spinState = 0
    private val wheelTempSum = DoubleArray(4)
    private val axleLock = IntArray(2)
    private val axleSpin = IntArray(2)

    /** Blocages et patinages par essieu (0 : avant, 1 : arrière), mêmes règles que [lockups] et [wheelspins]. */
    val lockupsByAxle = IntArray(2)
    val wheelspinsByAxle = IntArray(2)

    /** Température de surface moyenne de chaque pneu sur le tour (°C). */
    val avgWheelTemps: com.gt7pitwall.core.telemetry.Wheels?
        get() = if (packets > 0) com.gt7pitwall.core.telemetry.Wheels(
            (wheelTempSum[0] / packets).toFloat(), (wheelTempSum[1] / packets).toFloat(),
            (wheelTempSum[2] / packets).toFloat(), (wheelTempSum[3] / packets).toFloat(),
        ) else null

    /** Blocages de roue au freinage (épisodes d'au moins [SLIP_MIN_PACKETS] paquets). */
    var lockups = 0
        private set

    /** Patinages à l'accélération (mêmes règles). */
    var wheelspins = 0
        private set

    /** Température de surface moyenne des 4 pneus sur le tour (°C). */
    val avgTyreTemp: Float get() = if (packets > 0) (tyreTempSum / packets).toFloat() else 0f

    /** Part du tour où l'antipatinage (TCS) était actif (0..1). */
    val tcsShare: Float get() = if (packets > 0) tcsPackets.toFloat() / packets else 0f
    val asmShare: Float get() = if (packets > 0) asmPackets.toFloat() / packets else 0f

    /** Distance parcourue depuis la ligne (m), d'après les positions GT7. */
    val lapDistance: Float get() = distance

    /** Vrai si le tour a été suivi depuis la ligne sans interruption. */
    val isComplete: Boolean get() = valid

    /**
     * Format C : paquets où la surface est connue, dont avec au moins une roue sur un vibreur, et sorties de piste
     * (au moins deux roues hors asphalte et hors vibreur pendant [OFF_TRACK_MIN_PACKETS] paquets de suite). Un simple
     * contact avec un vibreur n'est jamais une sortie ; un échantillon isolé non plus.
     */
    private var surfacePackets = 0
    private var kerbPackets = 0
    private var offState = 0
    var offTrackEvents = 0
        private set

    /** Part du tour avec une roue sur un vibreur (0..1), null sans donnée de surface (formats A, B, ~). */
    val kerbShare: Float? get() = if (surfacePackets > 0) kerbPackets.toFloat() / surfacePackets else null

    /** Sorties de piste mesurées, null sans donnée de surface. */
    val offTracks: Int? get() = if (surfacePackets > 0) offTrackEvents else null
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
        tyreTempSum = 0.0
        lockState = 0
        spinState = 0
        lockups = 0
        wheelspins = 0
        wheelTempSum.fill(0.0)
        axleLock.fill(0)
        axleSpin.fill(0)
        lockupsByAxle.fill(0)
        wheelspinsByAxle.fill(0)
        surfacePackets = 0
        kerbPackets = 0
        offState = 0
        offTrackEvents = 0
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
        tyreTempSum += p.tyreTemps.toList().average()
        p.tyreTemps.toList().forEachIndexed { i, t -> wheelTempSum[i] += t }
        countSlip(p)
        countSurface(p)
    }

    private fun countSurface(p: TelemetryPacket) {
        val s = p.surfaces ?: return
        surfacePackets++
        if (s.count(com.gt7pitwall.core.telemetry.Surface.KERB) > 0) kerbPackets++
        offState = if (s.wheelsOff >= 2) offState + 1 else 0
        if (offState == OFF_TRACK_MIN_PACKETS) offTrackEvents++
    }

    /** Compte les épisodes de blocage / patinage (vitesse des roues comparée à celle de la voiture). */
    private fun countSlip(p: TelemetryPacket) {
        val slips = p.wheelSlip?.toList()
        val locking = slips != null && p.brake > 20 && slips.any { it < LOCK_RATIO }
        val spinning = slips != null && p.throttle > 20 && slips.any { it > SPIN_RATIO }
        lockState = if (locking) lockState + 1 else 0
        spinState = if (spinning) spinState + 1 else 0
        if (lockState == SLIP_MIN_PACKETS) lockups++
        if (spinState == SLIP_MIN_PACKETS) wheelspins++
        if (slips == null) return
        for (axle in 0..1) {
            val pair = slips.subList(axle * 2, axle * 2 + 2)
            axleLock[axle] = if (p.brake > 20 && pair.any { it < LOCK_RATIO }) axleLock[axle] + 1 else 0
            axleSpin[axle] = if (p.throttle > 20 && pair.any { it > SPIN_RATIO }) axleSpin[axle] + 1 else 0
            if (axleLock[axle] == SLIP_MIN_PACKETS) lockupsByAxle[axle]++
            if (axleSpin[axle] == SLIP_MIN_PACKETS) wheelspinsByAxle[axle]++
        }
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
        /** 0,25 s à au moins deux roues hors piste : en dessous, c'est un échantillon isolé ou un vibreur large. */
        const val OFF_TRACK_MIN_PACKETS = 15

        const val DEFAULT_SECTORS = 3

        /** ~600 km/h à 60 paquets/s. */
        const val MAX_STEP_PER_PACKET = 10f
        const val MAX_PACKET_GAP = 30
        const val MIN_LAP_DISTANCE = 300f
        const val LOCK_RATIO = 0.85f
        const val SPIN_RATIO = 1.15f

        /** ~1/10 s : un glissement plus bref n'est pas compté. */
        const val SLIP_MIN_PACKETS = 6
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
