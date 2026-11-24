package com.gt7pitwall.lab

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.race.ProfileBook
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.race.RaceStorage
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.telemetry.PositionProbe
import com.gt7pitwall.core.telemetry.TelemetryTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Pitwall Lab v0 — rejeu de traces brutes GT7 (`.gt7trace`) hors téléphone, avec le moteur de course réel (`:core`).
 *
 * Règle absolue : rien n'est inventé. Chaque valeur du rapport porte sa nature :
 * - `MESURE`     : lue telle quelle dans les paquets GT7 de la trace ;
 * - `CALCUL`     : produite par le moteur Pitwall à partir de ces mesures (archive, messages, classement consolidé) ;
 * - `SIMULATION` : scénario construit (aucun en v0 : le Lab ne fabrique pas de télémétrie).
 *
 * Commandes :
 *   inspect <trace>                     mesures brutes : échantillons, formats, trous, pertes UDP, transitions 0x84/0x86
 *   replay  <trace> [série] [type]      rejeu dans RaceController : archives, classement, messages produits
 *   anomalies <trace>                   incohérences de télémétrie et événements suspects (MESURE)
 *   diff    <a.json> <b.json> [la] [lb] [rapport.md]  différences entre deux rapports `replay` (voir lab/compare.sh)
 *   vega    <trace> [scenarios] [md]    banc vega-golden : questions à Véga sur des états reconstitués (moteur déterministe)
 *   vega-diff <a.json> <b.json> [la] [lb] [md]  améliorations et régressions de Véga entre deux versions
 *   list    <dossier>                   traces disponibles
 *   gabarit <dossier>                   trace FABRIQUÉE pour les tests (jamais une mesure)
 * Sortie : JSON sur la sortie standard. Les traces sont privées : le Lab ne les copie ni ne les publie.
 */
fun main(args: Array<String>) {
    // Sortie UTF-8 quel que soit le système (flèches, accents).
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    val out = runCatching { PitwallLab.run(args.toList()) }.getOrElse { e ->
        JSONObject().put("erreur", e.message ?: e.toString())
    }
    println(out.toString(2))
    if (out.has("erreur")) exitProcess(1)
}

object PitwallLab {
    const val MESURE = "MESURE"
    const val CALCUL = "CALCUL"

    fun run(args: List<String>): JSONObject = when (args.firstOrNull()) {
        "inspect" -> inspect(File(args.getOrNull(1) ?: error("inspect <trace>")))
        "replay" -> replay(
            File(args.getOrNull(1) ?: error("replay <trace> [série] [type]")),
            args.getOrNull(2)?.let { Series.valueOf(it) } ?: Series.DAILY_B,
            args.getOrNull(3)?.let { SessionKind.valueOf(it) } ?: SessionKind.RACE,
        )
        "list" -> list(File(args.getOrNull(1) ?: error("list <dossier>")))
        "anomalies" -> Anomalies.scan(File(args.getOrNull(1) ?: error("anomalies <trace>")))
        "carburant" -> FuelReplay.run(File(args.getOrNull(1) ?: error("carburant <journal.json>")))
        "fuel-performance" -> FuelPerformance.run(args.drop(1).map(::File).also { require(it.isNotEmpty()) { "fuel-performance <trace...>" } })
        "pit-research" -> PitResearch.run(args.drop(1).map(::File).also { require(it.isNotEmpty()) { "pit-research <trace...>" } })
        "tyre-research" -> TyreResearch.run(args.drop(1).map(::File).also { require(it.isNotEmpty()) { "tyre-research <trace...>" } })
        "endurance" -> Endurance.run(
            File(args.getOrNull(1) ?: error("endurance <trace> [regles.json]")),
            args.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::File),
        )
        "vega" -> VegaGolden.run(
            File(args.getOrNull(1) ?: error("vega <trace> [scenarios.json] [rapport.md]")),
            File(args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "lab/vega-golden/scenarios.json"),
        ).also { r -> args.getOrNull(3)?.let { File(it).writeText(VegaReport.markdown(r)) } }
        "vega-diff" -> VegaReport.diff(
            JSONObject(File(args.getOrNull(1) ?: error("vega-diff <a.json> <b.json> [la] [lb] [rapport.md]")).readText()),
            JSONObject(File(args.getOrNull(2) ?: error("vega-diff <a.json> <b.json>")).readText()),
            args.getOrNull(3) ?: "A", args.getOrNull(4) ?: "B",
        ).also { d -> args.getOrNull(5)?.let { File(it).writeText(VegaReport.diffMarkdown(d)) } }
        "diff" -> Compare.diff(
            JSONObject(File(args.getOrNull(1) ?: error("diff <rapportA.json> <rapportB.json> [libelléA] [libelléB]")).readText()),
            JSONObject(File(args.getOrNull(2) ?: error("diff <rapportA.json> <rapportB.json>")).readText()),
            args.getOrNull(3) ?: "A", args.getOrNull(4) ?: "B",
        ).also { d -> args.getOrNull(5)?.let { File(it).writeText(Compare.markdown(d)) } }
        "gabarit" -> JSONObject().put("gabarit", Gabarit.write(File(args.getOrNull(1) ?: error("gabarit <dossier>"))).absolutePath)
            .put("gabarit_endurance", Gabarit.writeEndurance(File(args[1])).absolutePath)
            .put("nature", "Trace FABRIQUÉE pour tester le Lab ; jamais une mesure.")
        else -> error("commande inconnue : inspect | replay | anomalies | endurance | carburant | diff | vega | vega-diff | list | gabarit")
    }

    /** Trace fabriquée (gabarit de test) : signalée en tête de tout rapport. */
    private fun warn(f: File, o: JSONObject): JSONObject =
        if (Gabarit.MARK in f.name) o.put("avertissement", "Trace GABARIT fabriquée pour les tests : aucune valeur n'est une mesure réelle de GT7.") else o

    private fun tagged(source: String, value: Any?): JSONObject = JSONObject().put("source", source).put("valeur", value ?: JSONObject.NULL)

    fun list(dir: File): JSONObject {
        require(dir.isDirectory) { "dossier introuvable : ${dir.name}" }
        val files = dir.listFiles { f -> f.name.endsWith(".gt7trace") }.orEmpty().sortedBy { it.name }
        return JSONObject().put("traces", JSONArray(files.map { JSONObject().put("fichier", it.name).put("octets", it.length()) }))
    }

    /** Mesures brutes de la trace, sans moteur. */
    fun inspect(f: File): JSONObject {
        require(f.isFile) { "trace introuvable : ${f.name}" }
        var lastId: Int? = null
        var lostUdp = 0L
        var minFuel: Float? = null
        var maxFuel: Float? = null
        val cars = linkedSetOf<Int>()
        val laps = sortedSetOf<Int>()
        val totals = sortedSetOf<Int>()
        val transitions = JSONArray()
        var last84: Pair<Int, Int>? = null
        var firstMs: Long? = null
        val clock = TelemetryTrace.VirtualClock(0)
        val r = TelemetryTrace.replay(f.inputStream(), clock) { p ->
            if (firstMs == null) firstMs = clock.nowMs
            lastId?.let { if (p.packetId > it + 1) lostUdp += (p.packetId - it - 1) }
            lastId = p.packetId
            if (p.carCode > 0) cars += p.carCode
            if (p.fuelCapacity > 0f) {
                minFuel = minOf(minFuel ?: p.fuelLevel, p.fuelLevel)
                maxFuel = maxOf(maxFuel ?: p.fuelLevel, p.fuelLevel)
            }
            laps += p.currentLap
            totals += p.totalLaps
            val v = p.preRacePosition to p.preRaceCarCount
            if (v != last84) {
                last84 = v
                if (transitions.length() < 400) {
                    transitions.put(JSONObject().put("t_ms", clock.nowMs).put("tour", p.currentLap).put("tours", p.totalLaps).put("x84", v.first).put("x86", v.second))
                }
            }
        }
        return warn(f, JSONObject())
            .put("trace", f.name)
            .put("nature", "Mesures brutes lues dans les paquets GT7 ; aucun calcul du moteur.")
            .put("echantillons", tagged(MESURE, r.frames))
            .put("decodes", tagged(MESURE, r.decoded))
            .put("illisibles", tagged(MESURE, r.frames - r.decoded))
            .put("formats", tagged(MESURE, JSONObject(r.formats)))
            .put("duree_s", tagged(MESURE, r.durationMs / 1000))
            .put("trous_plus_500ms", tagged(MESURE, r.gapsOver500Ms))
            .put("paquets_gt7_manquants", tagged(MESURE, lostUdp).put("note", "d'après les numéros 0x70 ; inclut l'échantillonnage de la trace (20/30/60 Hz)"))
            .put("voitures", tagged(MESURE, JSONArray(cars.toList())))
            .put("tours_vus", tagged(MESURE, JSONArray(laps.toList())))
            .put("nombres_de_tours_vus", tagged(MESURE, JSONArray(totals.toList())))
            .put("carburant_min_max", tagged(MESURE, if (minFuel == null) null else JSONArray(listOf(minFuel, maxFuel))))
            .put("transitions_x84_x86", tagged(MESURE, transitions).put("note", "400 premières au plus"))
    }

    /** Rejeu dans le moteur de course réel : ce que Pitwall aurait archivé et dit. */
    fun replay(f: File, series: Series, kind: SessionKind): JSONObject {
        require(f.isFile) { "trace introuvable : ${f.name}" }
        val clock = TelemetryTrace.VirtualClock(0)
        val storage = MemoryStorage()
        val c = RaceController(storage = storage, clock = clock::now)
        c.selectProfile(ProfileId(series, kind))
        val said = mutableListOf<EngineerMessage>()
        val r = TelemetryTrace.replay(f.inputStream(), clock) { p -> said += c.onPacket(p) }
        val sessions = storage.sessions().sortedBy { it.startedAtMs }
        val spoken = said.filter { it.category != MessageCategory.DRIVER }
        return warn(f, JSONObject())
            .put("trace", f.name)
            .put("profil", "${series.name} ${kind.name} (choisi pour le rejeu, pas une détection)")
            .put("nature", "Archives et messages CALCULÉS par le moteur Pitwall à partir des mesures de la trace.")
            .put("echantillons", tagged(MESURE, r.frames))
            .put("formats", tagged(MESURE, JSONObject(r.formats)))
            .put("sessions", JSONArray(sessions.map(::session)))
            .put("messages", tagged(CALCUL, JSONArray(spoken.map { JSONObject().put("texte", it.text).put("priorite", it.priority.name).put("categorie", it.category.name) })))
            .put("journal", tagged(CALCUL, JSONArray(storage.journal.mapNotNull { l ->
                runCatching {
                    val o = JSONObject(l)
                    JSONObject().put("session", o.optString("session")).put("tour", o.opt("lap") ?: JSONObject.NULL)
                        .put("type", o.optString("type")).put("source", o.optString("source")).put("texte", o.optString("text"))
                        .put("decision", o.opt("decision") ?: JSONObject.NULL)
                }.getOrNull()
            })))
            .put("compteurs", tagged(
                CALCUL,
                JSONObject()
                    .put("course_terminee", spoken.count { it.text.startsWith("Course terminée") })
                    .put("bilans", said.count { it.dedupKey?.startsWith("bilan:") == true })
                    .put("revisions_dites", spoken.count { it.text.startsWith("Classement GT7 révisé") }),
            ))
            .put("limites", JSONArray(listOf(
                "Trace échantillonnée (souvent 20 Hz) : certaines analyses de pilotage diffèrent du direct à 60 Hz.",
                "Aucun historique du pilote : comparaisons et coaching limités à la trace.",
                "Aucun son : la radio n'est vue qu'en texte.",
            )))
    }

    private fun session(s: SessionRecord): JSONObject = JSONObject()
        .put("id", s.id)
        .put("issue", s.outcome.name)
        .put("tours", tagged(MESURE, JSONArray(s.laps.map { JSONObject().put("tour", it.lap).put("temps_ms", it.timeMs ?: JSONObject.NULL).put("carburant", it.fuelUsed ?: JSONObject.NULL).put("plein", it.refuelled) })))
        .put("arrets", tagged(CALCUL, JSONArray(s.stops.map { JSONObject().put("tour", it.lap).put("preuve", it.evidence.name).put("carburant_ajoute", it.fuelAdded ?: JSONObject.NULL) })))
        .put("grille", tagged(MESURE, s.gridPosition))
        .put("ligne", tagged(MESURE, s.lineFinishPosition?.let { "P$it/${s.lineFinishCars}" }))
        .put("resultat_archive", tagged(CALCUL, s.finishPosition?.let { "P$it/${s.finishCars ?: "?"}" }).put("consolide", s.finishConsolidated))
        .put("revisions_apres_ligne", tagged(MESURE, JSONArray(s.postFinishChanges.map { it.text })))
}

/** Stockage en mémoire : rien n'est écrit sur disque (les sessions passent par leur JSON, comme sur le téléphone). */
class MemoryStorage : RaceStorage {
    private var rules = RaceRules()
    private val memory = mutableMapOf<String, CarTrackRecord>()
    private var profiles: String? = null
    private val sessions = linkedMapOf<String, String>()
    override fun loadRules() = rules
    override fun saveRules(r: RaceRules) { rules = r }
    override fun loadMemory(): Map<String, CarTrackRecord> = memory
    override fun saveMemory(records: Collection<CarTrackRecord>) {
        memory.clear()
        records.forEach { memory[it.key] = it }
    }
    override fun loadProfiles(): ProfileBook? = profiles?.let(RaceJson::book)
    override fun saveProfiles(book: ProfileBook) { profiles = RaceJson.book(book) }
    override fun loadSessions(): List<SessionRecord> = sessions.values.mapNotNull(RaceJson::session)
    override fun saveSession(record: SessionRecord) { sessions[record.id] = RaceJson.session(record) }
    fun sessions(): List<SessionRecord> = loadSessions()

    /** Journal de course complet (toutes les sessions de la trace), lignes JSON comme sur le téléphone. */
    val journal = mutableListOf<String>()
    override fun appendJournal(sessionId: String, line: String) { journal += line }
}
