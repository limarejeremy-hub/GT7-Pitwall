package com.gt7pitwall.lab

import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

/**
 * Pitwall Lab — `endurance` : bilan d'une course longue (Le Mans 14 tours…) lu directement dans les paquets GT7, sans le
 * moteur. Chaque valeur porte sa nature :
 * - MESURE      : champ du paquet (tour, chrono 0x7C, carburant, vitesse, températures, 0x84) ;
 * - CALCUL      : opération du Lab sur ces mesures (consommation par différence, tours par plein, arrêts nécessaires) ;
 * - INDICE      : signature observée sur une course réelle, qui n'est PAS une mesure (pneus neufs) ;
 * - REGLEMENT   : valeur publiée de l'épreuve (fichier `regles`), jamais transmise par GT7 ;
 * - NON_MESURABLE : ce que GT7 ne transmet pas (météo, usure, gomme, arrêt validé par GT7).
 */
object Endurance {
    /** Arrêt : vitesse sous ce seuil en course pendant au moins [STOP_MIN_MS]. */
    const val STOP_KMH = 1f
    const val STOP_MIN_MS = 3_000L
    const val GAP_MS = 500L
    /** Signature « pneus neufs » observée le 25/10 (Porsche 963, Le Mans) : les 4 températures retombent ensemble à 60,0. */
    const val FRESH_TYRE_C = 60.5f
    const val FRESH_SPREAD_C = 0.6f

    private class Lap(val n: Int, val startMs: Long, val fuelStart: Float, val pos: Int?) {
        var endMs: Long? = null
        var fuelEnd: Float? = null
        var timeMs: Int? = null
        var maxGapMs = 0L
        var gaps = 0
        var refuelled = false
        var stopped = false
    }

    private class Stop(val lap: Int, val startMs: Long, val fuelBefore: Float, val tempsBefore: List<Float>) {
        var endMs = startMs
        var fuelMax = fuelBefore
        var freshSignatureMs: Long? = null
    }

    private class Race(var totalLaps: Int, val carCode: Int, val capacity: Float, val startMs: Long) {
        val laps = ArrayList<Lap>()
        val stops = ArrayList<Stop>()
        var finishMs: Long? = null
        var finishPos: Int? = null
        var lastMs = startMs
        var fuelAtFinish: Float? = null
    }

    private fun inRace(p: TelemetryPacket) = p.totalLaps > 0 && p.currentLap in 1..p.totalLaps && !p.isLoading && !p.isPaused
    private fun pos(p: TelemetryPacket) = p.preRacePosition.takeIf { p.preRaceCarCount in 1..40 && it in 1..p.preRaceCarCount }
    private fun r1(x: Float) = Math.round(x * 10f) / 10.0
    private fun tag(nature: String, v: Any?) = JSONObject().put("nature", nature).put("valeur", v ?: JSONObject.NULL)

    fun run(trace: File, rulesFile: File? = null): JSONObject {
        require(trace.isFile) { "trace introuvable : ${trace.name}" }
        val rules = rulesFile?.let { JSONObject(it.readText()) }
        val races = ArrayList<Race>()
        var race: Race? = null
        var prev: TelemetryPacket? = null
        var prevMs = 0L
        var stop: Stop? = null
        var slowSince: Long? = null
        var slowFuel = 0f
        var slowTemps = emptyList<Float>()
        val clock = TelemetryTrace.VirtualClock(0)
        val r = TelemetryTrace.replay(trace.inputStream(), clock) { p ->
            val t = clock.nowMs
            val q = prev
            // Nouvelle course : le tour 1 apparaît (départ, redémarrage) ou le nombre de tours change.
            val sameStart = race?.let { it.finishMs == null && it.laps.size == 1 && it.laps[0].endMs == null } == true &&
                q != null && q.currentLap == 1
            if (sameStart && p.totalLaps > 0 && p.currentLap == 1 && q!!.totalLaps != p.totalLaps) {
                // Nombre de tours qui apparaît ou change pendant le tour 1 (observé le 28/10 : « 1/0 → 1/10 ») : même course.
                race!!.totalLaps = p.totalLaps
            } else if (p.totalLaps > 0 && p.currentLap == 1 && (q == null || q.currentLap != 1 || q.totalLaps != p.totalLaps)) {
                race = Race(p.totalLaps, p.carCode, p.fuelCapacity, t).also { races += it }
                race!!.laps += Lap(1, t, p.fuelLevel, pos(p))
                stop = null; slowSince = null
            }
            val cur = race
            if (cur != null && cur.finishMs == null && q != null) {
                cur.lastMs = t
                val open = cur.laps.lastOrNull()
                val dt = t - prevMs
                if (open != null && inRace(p) && inRace(q)) {
                    if (dt > GAP_MS) { open.gaps++; open.maxGapMs = maxOf(open.maxGapMs, dt) } else open.maxGapMs = maxOf(open.maxGapMs, dt)
                }
                // Passage de ligne : le chrono 0x7C du paquet suivant est celui du tour bouclé (MESURE).
                if (p.currentLap == q.currentLap + 1 && q.currentLap >= 1 && p.totalLaps == cur.totalLaps) {
                    open?.let { l -> l.endMs = t; l.fuelEnd = q.fuelLevel; l.timeMs = p.lastLapMs.takeIf { it > 0 } }
                    if (p.currentLap > cur.totalLaps) {
                        cur.finishMs = t; cur.finishPos = pos(p); cur.fuelAtFinish = p.fuelLevel
                    } else {
                        cur.laps += Lap(p.currentLap, t, p.fuelLevel, pos(p))
                    }
                }
                // Arrêt : immobile en course (pas de grille : départ lancé ; la vitesse nulle avant le tour 1 est ignorée).
                if (inRace(p) && cur.finishMs == null) {
                    if (p.speedKmh < STOP_KMH) {
                        if (slowSince == null) { slowSince = t; slowFuel = q.fuelLevel; slowTemps = q.tyreTemps.toList() }
                        if (stop == null && t - slowSince!! >= STOP_MIN_MS) {
                            stop = Stop(p.currentLap, slowSince!!, slowFuel, slowTemps).also { cur.stops += it }
                        }
                        stop?.let { s ->
                            s.endMs = t
                            s.fuelMax = maxOf(s.fuelMax, p.fuelLevel)
                            val temps = p.tyreTemps.toList()
                            if (s.freshSignatureMs == null && temps.all { it in 1f..FRESH_TYRE_C } && temps.max() - temps.min() <= FRESH_SPREAD_C &&
                                s.tempsBefore.min() > FRESH_TYRE_C + 3f
                            ) s.freshSignatureMs = t
                        }
                    } else {
                        stop?.let { s -> cur.laps.lastOrNull { it.n == s.lap }?.stopped = true }
                        stop = null; slowSince = null
                    }
                }
                if (p.fuelLevel - q.fuelLevel > 0.5f) open?.refuelled = true
            }
            prev = p
            prevMs = t
        }
        val out = JSONArray()
        // Fragment sans aucun tour bouclé ni arrivée et de moins d'une minute (fin d'une session précédente en début de
        // trace, observé le 28/10) : écarté du bilan, mais compté.
        val (kept, dropped) = races.partition { it.finishMs != null || it.laps.any { l -> l.endMs != null } || it.lastMs - it.startMs >= 60_000 }
        kept.forEach { out.put(report(it, rules)) }
        return JSONObject()
            .put("trace", trace.name)
            .put("nature", "Bilan d'endurance lu dans les paquets GT7 (MESURE), avec calculs du Lab (CALCUL) et indices (INDICE). Aucun moteur.")
            .put("echantillons", r.frames)
            .put("courses", out)
            .put("fragments_ecartes", dropped.size)
            .put("non_mesurable", JSONArray(listOf(
                "Météo et pluie : le paquet GT7 n'a aucun champ météo. Seules la déclaration du pilote et ses observations comptent ; une hypothèse doit être présentée comme telle.",
                "Usure et gomme des pneus : non transmises par GT7. La température n'est pas l'usure.",
                "Validation de l'arrêt obligatoire par GT7 : non transmise ; seul le résultat officiel dans le jeu fait foi.",
                "Écarts avec les autres voitures : non transmis.",
            )))
            .put("limites", JSONArray(listOf(
                "Arrêt = vitesse < 1 km/h en course pendant ≥ 3 s : un tête-à-queue immobile serait compté (le carburant permet de le distinguer).",
                "La signature « pneus neufs » (4 températures à 60 °C ensemble) vient d'une seule course réelle (25/10) : c'est un indice, pas une preuve.",
                "Trace échantillonnée (souvent 20 Hz) : instants à ±50 ms.",
            )))
            .also { if (Gabarit.MARK in trace.name) it.put("avertissement", "Trace GABARIT fabriquée pour les tests : aucune valeur n'est une mesure réelle de GT7.") }
    }

    private fun report(c: Race, rules: JSONObject?): JSONObject {
        val laps = JSONArray()
        val clean = ArrayList<Float>()
        c.laps.forEach { l ->
            val used = l.fuelEnd?.let { l.fuelStart - it }
            if (used != null && !l.refuelled && !l.stopped && used > 0f && l.n >= 2) clean += used
            laps.put(JSONObject()
                .put("tour", l.n)
                .put("chrono_ms", tag("MESURE", l.timeMs))
                .put("carburant_debut", tag("MESURE", r1(l.fuelStart)))
                .put("carburant_fin", tag("MESURE", l.fuelEnd?.let(::r1)))
                .put("consommation", tag("CALCUL", if (l.refuelled) "plein pendant le tour" else used?.let(::r1)))
                .put("position_debut", tag("MESURE", l.pos))
                .put("arret", l.stopped)
                .put("trous_flux", l.gaps)
                .put("plus_long_trou_ms", l.maxGapMs))
        }
        val stops = JSONArray()
        c.stops.forEach { s ->
            val added = s.fuelMax - s.fuelBefore
            stops.put(JSONObject()
                .put("tour", s.lap)
                .put("duree_immobile_ms", tag("MESURE", s.endMs - s.startMs))
                .put("carburant_avant", tag("MESURE", r1(s.fuelBefore)))
                .put("carburant_ajoute", tag("CALCUL", r1(maxOf(0f, added))))
                .put("plein", added > 0.5f)
                .put("temperatures_avant", tag("MESURE", JSONArray(s.tempsBefore.map(::r1))))
                .put("signature_pneus_neufs", tag("INDICE", s.freshSignatureMs != null))
                .put("lecture", when {
                    s.freshSignatureMs != null && added > 0.5f -> "plein et indice de pneus neufs (à confirmer par le pilote)"
                    s.freshSignatureMs != null -> "indice de pneus neufs sans plein (à confirmer par le pilote)"
                    added > 0.5f -> "plein ; aucun indice de changement de pneus (ne prouve pas qu'ils n'ont pas été changés)"
                    else -> "immobile sans plein ni indice de pneus (tête-à-queue, pénalité ou arrêt sans service ?)"
                }))
        }
        val median = clean.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val fuel = JSONObject()
            .put("consommation_mediane_par_tour", tag("CALCUL", median?.let(::r1)))
            .put("tours_mesures", clean.size)
            .put("capacite", tag("MESURE", r1(c.capacity)))
            .put("tours_par_plein", tag("CALCUL", median?.takeIf { it > 0f && c.capacity > 0f }?.let { r1(c.capacity / it) }))
            .put("arrets_carburant_necessaires", tag("CALCUL", median?.takeIf { it > 0f && c.capacity > 0f }?.let {
                val need = it * c.totalLaps - c.capacity
                if (need <= 0f) 0 else ceil(need / c.capacity).toInt()
            }))
            .put("carburant_a_l_arrivee", tag("MESURE", c.fuelAtFinish?.let(::r1)))
        val durationMs = (c.finishMs ?: c.lastMs) - c.startMs
        val o = JSONObject()
            .put("tours_annonces", tag("MESURE", c.totalLaps))
            .put("voiture", tag("MESURE", c.carCode))
            .put("terminee", c.finishMs != null)
            .put("tours_boucles", c.laps.count { it.endMs != null })
            .put("duree_ms", tag("MESURE", durationMs))
            .put("position_ligne_0x84", tag("MESURE", c.finishPos))
            .put("tours", laps)
            .put("arrets", stops)
            .put("carburant", fuel)
            .put("flux", JSONObject().put("trous_en_course", c.laps.sumOf { it.gaps }).put("plus_long_trou_ms", c.laps.maxOfOrNull { it.maxGapMs } ?: 0))
        rules?.let { o.put("controle_reglement", check(c, it)) }
        return o
    }

    /** Contrôle contre le règlement publié. Une règle absente du fichier n'est pas contrôlée. */
    private fun check(c: Race, rules: JSONObject): JSONArray {
        val a = JSONArray()
        fun item(name: String, expected: Any, measured: Any?, ok: Boolean?, note: String) = a.put(JSONObject()
            .put("regle", name).put("attendu_REGLEMENT", expected).put("constate", measured ?: JSONObject.NULL)
            .put("verdict", when (ok) { true -> "CONFORME"; false -> "ECART"; null -> "NON_EVALUABLE" }).put("note", note))
        if (rules.has("tours")) {
            val n = rules.getInt("tours")
            item("tours", n, c.totalLaps, c.totalLaps == n, "Nombre de tours lu dans le paquet GT7 (0x76).")
            item("course_terminee", true, c.finishMs != null, c.finishMs != null, if (c.finishMs != null) "Ligne d'arrivée franchie dans la trace." else "Trace coupée avant l'arrivée (abandon, retour au menu, application relancée ?).")
        }
        if (rules.has("arrets_obligatoires")) {
            val n = rules.getInt("arrets_obligatoires")
            item("arrets_obligatoires", n, c.stops.size, if (c.stops.size >= n) true else if (c.finishMs != null) false else null,
                "Arrêts détectés (immobile en course). GT7 ne transmet pas s'il valide l'arrêt : vérifier le résultat officiel.")
        }
        if (rules.has("changement_pneus_impose")) {
            val imposed = rules.optBoolean("changement_pneus_impose", false)
            item("changement_pneus_impose", imposed, c.stops.count { it.freshSignatureMs != null },
                if (!imposed) true else null,
                if (imposed) "Le Lab ne peut pas prouver un changement de pneus (indice seulement)."
                else "Le règlement publié n'impose pas de changement de pneus : un arrêt obligatoire n'implique pas de pneus neufs.")
        }
        if (rules.has("carburant_multiplicateur")) item("carburant_multiplicateur", rules.getInt("carburant_multiplicateur"), null, null, "Non transmis par GT7 : la consommation mesurée en découle.")
        if (rules.has("usure_multiplicateur")) item("usure_multiplicateur", rules.getInt("usure_multiplicateur"), null, null, "Non transmis par GT7 ; l'usure n'est pas mesurée.")
        return a
    }
}
