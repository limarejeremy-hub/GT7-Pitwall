package com.gt7pitwall.core.knowledge

import com.gt7pitwall.core.session.LapRecord

/** Ce que l'application a mesuré pour une voiture sur un circuit (sert à planifier avant le départ). */
data class CarTrackRecord(
    val carCode: Int,
    val trackId: Int,
    val bestLapMs: Int?,
    /** Rythme typique : moyenne des tours à moins de 7 % du meilleur. */
    val typicalLapMs: Int?,
    /** Consommation par tour ramenée à un multiplicateur ×1 (litres). */
    val fuelPerLapX1: Float?,
    /** Nombre de tours mesurés ayant servi à la consommation. */
    val fuelSamples: Int,
) {
    val key: String get() = key(carCode, trackId)

    companion object {
        fun key(carCode: Int, trackId: Int) = "$carCode@$trackId"
    }
}

object CarTrackMemory {
    /** Fusionne les tours d'une session dans la mémoire existante. */
    fun update(old: CarTrackRecord?, carCode: Int, trackId: Int, laps: List<LapRecord>, fuelMultiplier: Int): CarTrackRecord {
        val timed = laps.filter { !it.refuelled && (it.timeMs ?: 0) > 0 }.map { it.timeMs!! }
        val best = (timed + listOfNotNull(old?.bestLapMs)).minOrNull()
        val typical = best?.let { b -> timed.filter { it <= b * 1.07 }.takeIf { it.isNotEmpty() }?.average()?.toInt() }
            ?: old?.typicalLapMs
        val mult = fuelMultiplier.coerceAtLeast(1)
        val samples = laps.mapNotNull { it.fuelUsed }.filter { it > 0.01f }.map { it / mult }
        val fuel = when {
            samples.isEmpty() -> old?.fuelPerLapX1
            old?.fuelPerLapX1 == null -> samples.average().toFloat()
            else -> ((old.fuelPerLapX1 * old.fuelSamples + samples.sum()) / (old.fuelSamples + samples.size))
        }
        return CarTrackRecord(carCode, trackId, best, typical, fuel, (old?.fuelSamples ?: 0) + samples.size)
    }
}
