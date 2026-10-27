package com.gt7pitwall.core.telemetry

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import kotlin.math.roundToInt

/**
 * Mesures préalables à une « Black Box » (enregistrement complet de la télémétrie), sur le téléphone réel, AVANT de
 * l'écrire : rien n'est conservé. Pendant une session, la sonde mesure :
 * - le débit réel des paquets GT7 et les pertes (trous dans les numéros de paquet) ;
 * - le temps de calcul de Pitwall par paquet (moyenne, 95e centile, maximum) ;
 * - la taille qu'aurait la trace : un échantillon compact sur trois paquets (20 Hz) est réellement encodé puis
 *   compressé en mémoire (Deflate), et seuls les octets produits sont comptés ;
 * Appels depuis le fil de la télémétrie et depuis l'écran : méthodes synchronisées.
 * - la batterie et sa température, relevées par l'application (le chiffre inclut l'écran, la voix et le Wi-Fi :
 *   c'est la consommation du téléphone pendant la course, pas celle de la Black Box seule).
 */
class BlackBoxProbe(private val clock: () -> Long = System::currentTimeMillis) {
    private var startMs = 0L
    private var packets = 0L
    private var lost = 0L
    private var lastPacketId = -1
    private var samples = 0L
    private var rawBytes = 0L
    private var compressedBytes = 0L
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    private val outBuf = ByteArray(8_192)
    private val sampleBuf = ByteArrayOutputStream(64)
    /** Histogramme des temps de calcul par paquet, en dixièmes de milliseconde (0 à 50 ms). */
    private val timeHisto = LongArray(501)
    private var timeSumNs = 0L
    private var timeMaxNs = 0L
    private var battery: Pair<Long, Int>? = null
    private var batteryNow: Pair<Long, Int>? = null
    private var temperatureMaxC: Float? = null

    val running: Boolean get() = startMs > 0

    @Synchronized
    fun start() {
        reset()
        startMs = clock()
    }

    @Synchronized
    fun stop() {
        startMs = 0L
    }

    private fun reset() {
        packets = 0; lost = 0; lastPacketId = -1; samples = 0; rawBytes = 0; compressedBytes = 0
        deflater.reset()
        timeHisto.fill(0); timeSumNs = 0; timeMaxNs = 0
        battery = null; batteryNow = null; temperatureMaxC = null
    }

    /** Un paquet reçu et le temps (ns) que Pitwall a mis à le traiter. */
    @Synchronized
    fun onPacket(p: TelemetryPacket, processingNs: Long) {
        if (!running) return
        packets++
        if (lastPacketId >= 0 && p.packetId > lastPacketId + 1 && p.packetId - lastPacketId < 10_000) lost += p.packetId - lastPacketId - 1
        lastPacketId = p.packetId
        timeSumNs += processingNs
        if (processingNs > timeMaxNs) timeMaxNs = processingNs
        timeHisto[(processingNs / 100_000).toInt().coerceIn(0, 500)]++
        if (packets % 3 == 0L) encode(p)
    }

    /** Échantillon compact (36 octets) : ce qu'une Black Box garderait à 20 Hz. */
    private fun encode(p: TelemetryPacket) {
        sampleBuf.reset()
        DataOutputStream(sampleBuf).run {
            writeInt(p.packetId)
            writeFloat(p.positionX); writeFloat(p.positionZ)
            writeShort((p.speedKmh * 10).roundToInt().coerceIn(0, 32_767))
            writeShort(p.engineRpm.roundToInt().coerceIn(0, 32_767))
            writeByte(p.currentGear); writeByte(p.throttle); writeByte(p.brake); writeByte(p.currentLap.coerceIn(0, 255))
            writeShort((p.fuelLevel * 100).roundToInt().coerceIn(0, 32_767))
            writeShort((p.tyreTempFrontLeft * 10).roundToInt().coerceIn(0, 32_767))
            writeShort((p.tyreTempFrontRight * 10).roundToInt().coerceIn(0, 32_767))
            writeShort((p.tyreTempRearLeft * 10).roundToInt().coerceIn(0, 32_767))
            writeShort((p.tyreTempRearRight * 10).roundToInt().coerceIn(0, 32_767))
            writeInt(p.currentLapTimeMs ?: -1)
            writeShort(0) // drapeaux réservés
        }
        val bytes = sampleBuf.toByteArray()
        samples++
        rawBytes += bytes.size
        deflater.setInput(bytes)
        drain(Deflater.NO_FLUSH)
        // Vidage régulier : la taille compressée suit la session, comme un fichier écrit par blocs.
        if (samples % FLUSH_SAMPLES == 0L) drain(Deflater.SYNC_FLUSH)
    }

    private fun drain(flush: Int) {
        while (true) {
            val n = deflater.deflate(outBuf, 0, outBuf.size, flush)
            compressedBytes += n
            if (n < outBuf.size) break
        }
    }

    /** Relevé de batterie (pourcentage) et de température (°C) par l'application. */
    @Synchronized
    fun battery(levelPct: Int, temperatureC: Float?) {
        if (!running) return
        val now = clock() to levelPct
        if (battery == null) battery = now
        batteryNow = now
        temperatureC?.let { t -> temperatureMaxC = maxOf(temperatureMaxC ?: t, t) }
    }

    /** Compte rendu lisible (Atelier, journal). */
    @Synchronized
    fun report(): List<String> {
        if (startMs == 0L && packets == 0L) return listOf("Mesure arrêtée.")
        val elapsedS = ((clock() - startMs) / 1000.0).coerceAtLeast(1.0)
        val hours = elapsedS / 3600.0
        fun mb(b: Double) = String.format(java.util.Locale.FRANCE, "%.1f Mo", b / 1_000_000.0)
        fun ms(ns: Double) = String.format(java.util.Locale.FRANCE, "%.2f ms", ns / 1_000_000.0)
        val out = mutableListOf<String>()
        out += "Durée mesurée : ${(elapsedS / 60).roundToInt()} min, ${packets} paquets (${(packets / elapsedS).roundToInt()}/s), " +
            "${lost} perdus (${if (packets + lost > 0) String.format(java.util.Locale.FRANCE, "%.2f", 100.0 * lost / (packets + lost)) else "0"} %)."
        if (packets > 0) {
            out += "Calcul Pitwall par paquet : ${ms(timeSumNs.toDouble() / packets)} en moyenne, ${percentile95()} au 95e centile, ${ms(timeMaxNs.toDouble())} au maximum."
        }
        if (samples > 0 && hours > 0) {
            out += "Trace 20 Hz : ${mb(rawBytes / hours)} brut et ${mb(compressedBytes / hours)} compressé par heure de course " +
                "(${samples} échantillons, ${String.format(java.util.Locale.FRANCE, "%.0f", if (compressedBytes > 0) rawBytes.toDouble() / compressedBytes else 0.0)}:1)."
        }
        val b0 = battery
        val b1 = batteryNow
        if (b0 != null && b1 != null && b1.first - b0.first >= 10 * 60_000L) {
            val perHour = (b0.second - b1.second) / ((b1.first - b0.first) / 3_600_000.0)
            out += "Batterie : ${b0.second} % → ${b1.second} %, soit ${String.format(java.util.Locale.FRANCE, "%.0f", perHour)} % par heure (téléphone entier)" +
                (temperatureMaxC?.let { ", ${String.format(java.util.Locale.FRANCE, "%.0f", it)} °C au maximum" } ?: "") + "."
        } else {
            out += "Batterie : il faut au moins 10 minutes de mesure pour une consommation fiable."
        }
        return out
    }

    private fun percentile95(): String {
        val target = (packets * 0.95).toLong()
        var acc = 0L
        for (i in timeHisto.indices) {
            acc += timeHisto[i]
            if (acc >= target) {
                if (i == 500) return "plus de 50 ms"
                // Borne haute de la classe, jamais au-delà du maximum mesuré.
                val ms = minOf((i + 1) / 10.0, timeMaxNs / 1_000_000.0)
                return String.format(java.util.Locale.FRANCE, "%.2f ms", ms)
            }
        }
        return "?"
    }

    companion object {
        const val FLUSH_SAMPLES = 1_200L
    }
}
