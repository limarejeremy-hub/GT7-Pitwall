package com.gt7pitwall.lab

import com.gt7pitwall.core.telemetry.TelemetryParser
import com.gt7pitwall.core.telemetry.TelemetryTrace
import com.gt7pitwall.core.tyres.TyreDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pitwall Lab — `tyre-diag <trace...>` : le diagnostic des pneus du MOTEUR (V514-A, [TyreDiagnostics]) rejoué sur des
 * traces RÉELLES. Sert à vérifier, sans PS5 : roues motrices identifiées, patinage chiffré, anomalies relevées tour par
 * tour (fausses alertes ?), coupures UDP. Aucun changement de pneus n'est confirmé dans une trace brute : comme en course
 * sans réponse du pilote, le relais n'est jamais remis à zéro par un arrêt.
 */
object TyreDiag {
    fun run(traces: List<File>): JSONObject {
        val out = JSONArray()
        for (f in traces) {
            var diag: TyreDiagnostics? = null
            var lastLap = -1
            var race = 0
            val perLap = mutableListOf<JSONObject>()
            fun flush() {
                val d = diag ?: return
                val r = d.report()
                out.put(JSONObject().put("trace", f.name).put("course", race)
                    .put("roues_motrices", r.drivenAxle.label)
                    .put("surface_connue", r.surfacesKnown)
                    .put("tours_mesures", r.measuredLaps)
                    .put("tour_reference", r.referenceLap ?: JSONObject.NULL)
                    .put("coupures_udp", r.gaps)
                    .put("confiance", r.confidence.label)
                    .put("raisons", JSONArray(r.confidenceWhy))
                    .put("roues", JSONArray(r.wheels.map { w ->
                        JSONObject().put("roue", w.label).put("moy_dernier_tour_C", w.lapMeanC?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
                            .put("max_dernier_tour_C", w.lapMaxC?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
                            .put("depuis_reference_C", w.sinceReferenceC?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
                            .put("ecart_essieu_C", w.axleDeltaC?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
                            .put("patinage_pct", w.slipPct?.let { Math.round(it * 100) / 100.0 } ?: JSONObject.NULL)
                            .put("patinages_forts", JSONObject().put("echantillons", w.spinSamples).put("evenements", w.spinEvents))
                            .put("blocages", JSONObject().put("echantillons", w.lockSamples).put("evenements", w.lockEvents))
                    }))
                    .put("anomalies_fin", JSONArray(r.issues.map { "${it.kind.label} : ${it.text}" }))
                    .put("anomalies_par_tour", JSONArray(perLap.toList())))
                perLap.clear()
            }
            TelemetryTrace.stream(f.inputStream()) { fr ->
                val p = TelemetryParser.parseDecrypted(fr.plain) ?: return@stream true
                val inRace = p.totalLaps > 0 && p.currentLap in 1..p.totalLaps && !p.isLoading && !p.isPaused
                if (!inRace) return@stream true
                if (p.currentLap == 1 && lastLap != 1 && lastLap != -1 || diag == null) {
                    if (diag != null && lastLap > 1) flush()
                    if (diag == null || lastLap > 1) { diag = TyreDiagnostics(); race++ }
                }
                val d = diag!!
                if (p.currentLap != lastLap && lastLap >= 1) {
                    // Fin d'un tour : ce que la carte afficherait à ce moment.
                    d.report().let { r -> perLap += JSONObject().put("tour", lastLap).put("recuperation", r.recovering).put("anomalies", JSONArray(r.issues.map { "${it.kind.name}:${it.wheel ?: "train"}" })) }
                }
                lastLap = p.currentLap
                d.onPacket(p, fr.atMs.toLong())
                true
            }
            flush()
        }
        return JSONObject()
            .put("nature", "Diagnostic pneus du moteur (V514-A) rejoué sur traces RÉELLES : MESURE (températures, rotation, rayon) et CALCUL. Aucune usure.")
            .put("courses", out)
    }
}
