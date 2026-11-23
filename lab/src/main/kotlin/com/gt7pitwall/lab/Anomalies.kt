package com.gt7pitwall.lab

import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pitwall Lab — anomalies et événements suspects d'une trace brute, lus dans les paquets GT7 (MESURE uniquement ; aucun
 * moteur, aucune interprétation de pilotage).
 *
 * Chaque anomalie : type, instant dans la trace (ms), numéro de paquet GT7 (0x70), détail, gravité :
 * - `info`    : transition normale de GT7 qu'il faut savoir lire (menu, « 8/0 » après l'arrivée, nouvelle session) ;
 * - `suspect` : incohérence à examiner (trou de flux, 0x84 incohérent en course, carburant qui monte en roulant…).
 * La gravité est une règle du Lab, pas une preuve de défaut : la PS5 et le HUD restent la référence.
 */
object Anomalies {
    /** Au plus autant d'exemples gardés par type (le total reste compté). */
    const val MAX_PER_TYPE = 50
    const val GAP_MS = 500L
    const val FROZEN_MS = 1_000L

    private class Acc {
        val list = JSONArray()
        val counts = sortedMapOf<String, Int>()
        val severity = mutableMapOf<String, String>()
        /** Contexte mesuré du paquet courant (tour, nombre de tours, vitesse), joint à chaque anomalie. */
        var ctx: TelemetryPacket? = null
        fun describe(p: TelemetryPacket) = "tour ${p.currentLap}/${p.totalLaps}, ${p.speedKmh.toInt()} km/h" +
            (if (p.isPaused) ", pause" else "") + (if (p.isLoading) ", chargement" else "")

        fun add(type: String, sev: String, tMs: Long, packet: Int, detail: String, context: String? = null) {
            counts.merge(type, 1, Int::plus)
            severity[type] = sev
            if (counts.getValue(type) <= MAX_PER_TYPE) {
                val o = JSONObject().put("type", type).put("gravite", sev).put("t_ms", tMs).put("paquet", packet).put("detail", detail)
                (context ?: ctx?.let(::describe))?.let { o.put("contexte", it) }
                list.put(o)
            }
        }
    }

    private fun inRace(p: TelemetryPacket) = p.totalLaps > 0 && p.currentLap >= 1 && p.currentLap <= p.totalLaps && !p.isLoading && !p.isPaused

    fun scan(f: File): JSONObject {
        require(f.isFile) { "trace introuvable : ${f.name}" }
        val a = Acc()
        val clock = TelemetryTrace.VirtualClock(0)
        var prev: TelemetryPacket? = null
        var prevMs = 0L
        val idSteps = ArrayList<Int>()
        var badPosSince: Long? = null
        var badPosPacket = 0
        var badPosCtx: String? = null
        var frozenCtx: String? = null
        var frozenSince: Long? = null
        var frozenPacket = 0
        var finishSeenLap: Int? = null
        val r = TelemetryTrace.replay(f.inputStream(), clock) { p ->
            val t = clock.nowMs
            a.ctx = p
            val q = prev
            if (q != null) {
                val dt = t - prevMs
                // Flux : trou entre deux échantillons, numéros de paquet qui reculent ou sautent.
                if (dt > GAP_MS) a.add("TROU_FLUX", "suspect", prevMs, q.packetId, "aucun échantillon pendant $dt ms (pause, menu, Wi-Fi ou enregistrement) ; reprise : ${a.describe(p)}", "avant : ${a.describe(q)}")
                val step = p.packetId - q.packetId
                if (step <= 0) {
                    a.add("PAQUET_RECUL", "suspect", t, p.packetId, "numéro GT7 ${q.packetId} → ${p.packetId} (redémarrage de la télémétrie ?)")
                } else {
                    if (idSteps.size < 2_000) idSteps += step
                    val typical = idSteps.sorted().let { it[it.size / 2] }
                    if (idSteps.size >= 20 && dt <= GAP_MS && step > maxOf(10 * typical, typical + 60)) {
                        a.add("SAUT_PAQUETS", "suspect", t, p.packetId, "$step numéros d'écart (habituel : $typical) sans trou de temps")
                    }
                }
                // Session et tours.
                if (p.carCode != q.carCode && p.carCode > 0 && q.carCode > 0) a.add("VOITURE_CHANGE", "info", t, p.packetId, "voiture ${q.carCode} → ${p.carCode}")
                if (p.format != q.format) a.add("FORMAT_CHANGE", "suspect", t, p.packetId, "format ${q.format.label} → ${p.format.label}")
                if (p.currentLap < q.currentLap) {
                    a.add("TOUR_RECUL", "info", t, p.packetId, "tour ${q.currentLap} → ${p.currentLap} (nouvelle session ou menu)")
                    finishSeenLap = null
                } else if (p.currentLap > q.currentLap + 1 && q.currentLap >= 0) {
                    a.add("TOUR_SAUT", "suspect", t, p.packetId, "tour ${q.currentLap} → ${p.currentLap}")
                }
                if (q.totalLaps > 0 && p.currentLap > q.totalLaps && q.currentLap <= q.totalLaps) finishSeenLap = p.currentLap
                if (p.totalLaps != q.totalLaps && p.currentLap >= 1 && p.currentLap == q.currentLap) {
                    val afterFinish = finishSeenLap == p.currentLap
                    a.add(
                        "NB_TOURS_CHANGE", if (afterFinish) "info" else "suspect", t, p.packetId,
                        "${q.currentLap}/${q.totalLaps} → ${p.currentLap}/${p.totalLaps}" + if (afterFinish) " (après l'arrivée)" else " (en course)",
                    )
                }
                if (p.currentLap >= 2 && p.currentLap == q.currentLap + 1 && p.lastLapMs <= 0) {
                    a.add("CHRONO_ABSENT", "suspect", t, p.packetId, "tour ${q.currentLap} bouclé sans dernier temps (0x7C = ${p.lastLapMs})")
                }
                // Position (0x84 / 0x86) en course.
                if (inRace(p) && inRace(q) && p.preRaceCarCount > q.preRaceCarCount && q.preRaceCarCount > 0) {
                    a.add("PELOTON_EN_HAUSSE", "suspect", t, p.packetId, "0x86 ${q.preRaceCarCount} → ${p.preRaceCarCount} en course")
                }
                // Carburant : hausse en roulant (un plein se fait à l'arrêt ou dans la voie des stands).
                if (p.fuelCapacity > 0f && p.fuelLevel - q.fuelLevel > 1f && p.speedKmh > 90f) {
                    a.add("CARBURANT_HAUSSE_EN_ROULANT", "suspect", t, p.packetId,
                        "carburant ${"%.1f".format(q.fuelLevel)} → ${"%.1f".format(p.fuelLevel)} à ${p.speedKmh.toInt()} km/h")
                }
            }
            val valid = p.preRaceCarCount in 1..40 && p.preRacePosition in 1..p.preRaceCarCount
            if (inRace(p) && !valid) {
                if (badPosSince == null) { badPosSince = t; badPosPacket = p.packetId; badPosCtx = a.describe(p) }
            } else {
                badPosSince?.let { s -> a.add("POSITION_INCOHERENTE", "suspect", s, badPosPacket, "0x84/0x86 incohérent en course pendant ${t - s} ms", "début : $badPosCtx") }
                badPosSince = null
            }
            // Télémétrie figée : position X/Z identique alors que la vitesse dit que la voiture roule.
            val moving = p.speedKmh > 5f && !p.isPaused && !p.isLoading
            val same = q != null && p.positionX == q.positionX && p.positionZ == q.positionZ
            if (moving && same) {
                if (frozenSince == null) { frozenSince = prevMs; frozenPacket = q!!.packetId; frozenCtx = a.describe(q) }
            } else {
                frozenSince?.let { s ->
                    // Observé sur les traces réelles (06/11, 19/11, 22/11) : GT7 fige la position quelques secondes avant de
                    // passer au menu (0/0), en sortie de séance libre ou après l'arrivée. Transition connue : « info ».
                    val exit = p.currentLap == 0 && p.totalLaps == 0
                    if (t - s >= FROZEN_MS) a.add(
                        if (exit) "TELEMETRIE_FIGEE_SORTIE" else "TELEMETRIE_FIGEE", if (exit) "info" else "suspect", s, frozenPacket,
                        "position X/Z immobile pendant ${t - s} ms à vitesse non nulle ; fin : ${a.describe(p)}", "début : $frozenCtx",
                    )
                }
                frozenSince = null
            }
            prev = p
            prevMs = t
        }
        badPosSince?.let { s -> a.add("POSITION_INCOHERENTE", "suspect", s, badPosPacket, "0x84/0x86 incohérent jusqu'à la fin de la trace") }
        val summary = JSONObject()
        a.counts.forEach { (k, v) -> summary.put(k, JSONObject().put("nombre", v).put("gravite", a.severity[k])) }
        return JSONObject()
            .put("trace", f.name)
            .put("nature", "Anomalies lues dans les paquets GT7 (MESURE). La gravité est une règle du Lab, pas une preuve.")
            .put("echantillons", r.frames)
            .put("illisibles", r.frames - r.decoded)
            .put("resume", summary)
            .put("suspects", a.counts.filter { a.severity[it.key] == "suspect" }.values.sum())
            .put("anomalies", a.list)
            .put("limites", JSONArray(listOf(
                "Une trace échantillonnée (20/30 Hz) saute des numéros de paquet par construction : seul un saut anormal est signalé.",
                "Les seuils (trou > 500 ms, figée > 1 s, carburant > 90 km/h) sont des règles du Lab, à ajuster avec les essais.",
            )))
            .also { if (Gabarit.MARK in f.name) it.put("avertissement", "Trace GABARIT fabriquée pour les tests : aucune valeur n'est une mesure réelle de GT7.") }
    }
}
