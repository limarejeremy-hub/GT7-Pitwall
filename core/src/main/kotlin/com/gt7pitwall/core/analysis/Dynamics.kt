package com.gt7pitwall.core.analysis

import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.abs

/** Accélérations en g, calculées à partir des données GT7 (vitesse et vitesse de rotation). */
data class GForce(
    /** Positif = accélération, négatif = freinage. */
    val longitudinal: Float,
    /** Accélération latérale (vitesse × vitesse de lacet). Le sens gauche/droite reste à confirmer en jeu. */
    val lateral: Float,
)

/**
 * G longitudinal : variation de la vitesse entre deux paquets (60 paquets/s d'après l'identifiant de paquet).
 * G latéral : vitesse × vitesse de rotation autour de l'axe vertical (physique du virage : a = v·ω).
 * Lissage exponentiel pour un affichage lisible.
 */
class GForceMeter(private val smoothing: Float = 0.2f) {
    private var last: TelemetryPacket? = null
    private var long = 0f
    private var lat = 0f

    fun update(p: TelemetryPacket): GForce? {
        val prev = last
        last = p
        if (prev == null || p.isPaused) return null
        val dId = p.packetId - prev.packetId
        if (dId !in 1..10) return null
        val dt = dId / PACKETS_PER_SECOND
        val rawLong = (p.speedMps - prev.speedMps) / dt / G
        val rawLat = p.speedMps * p.angularVelocityY / G
        if (abs(rawLong) > 6f || abs(rawLat) > 6f) return GForce(long, lat) // valeur aberrante (choc, replacement)
        long += smoothing * (rawLong - long)
        lat += smoothing * (rawLat - lat)
        return GForce(long, lat)
    }

    companion object {
        const val G = 9.81f
        const val PACKETS_PER_SECOND = 60f
    }
}

/** Tracé d'un tour complet (vue de dessus, coordonnées GT7 en mètres) pour la carte du circuit. */
class TrackOutline(
    val xs: FloatArray,
    val zs: FloatArray,
    /** Indices des points où commencent les secteurs 2, 3… */
    val sectorStarts: IntArray,
    val lapNumber: Int,
)
