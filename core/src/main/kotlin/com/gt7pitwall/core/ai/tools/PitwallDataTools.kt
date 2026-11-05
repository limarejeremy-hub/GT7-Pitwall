package com.gt7pitwall.core.ai.tools

import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.FactProvenance
import com.gt7pitwall.core.ai.FactTopic
import org.json.JSONArray
import org.json.JSONObject

/**
 * Issue #70, étape 2 : outils de données de Véga, en LECTURE SEULE. GPT peut demander une tranche des faits du moteur
 * (état de course, règlement et sources, carburant, pneus, tours récents, historique) au lieu d'en recevoir un bloc.
 *
 * - Aucun outil n'écrit : pas de règlement, journal, plan ni réglage modifiable par ce chemin (il n'y a pas de
 *   fonction pour cela ; une commande du pilote reste traitée par le moteur et son garde).
 * - Les réponses viennent d'un [ToolSnapshot] figé au moment de la question (sous le verrou de la course) : jamais
 *   de flux 20–60 Hz envoyé, jamais de donnée calculée ici (les phrases sont celles du moteur, déjà vérifiées).
 * - Chaque fait porte sa provenance (mesuré / calculé / publié / déclaré) ; les manques et contradictions sont dits.
 * - Un nom d'outil inconnu (outil « halluciné ») reçoit une erreur, jamais une donnée.
 */
object PitwallDataTools {
    const val NAMESPACE = "pitwall"

    enum class Tool(val toolName: String, val description: String, val topic: FactTopic?) {
        LIVE("get_live_race_state", "État de la course en direct : télémétrie active ou non, voiture, circuit, tour, position, chronos, rythme, météo déclarée.", FactTopic.LIVE),
        RULES("get_race_rules_and_sources", "Règlement de l'épreuve et ses sources : obligations (gommes, arrêts), programme Sport de la semaine, champs inconnus. Deux gommes imposées ne veulent pas dire un arrêt explicite.", FactTopic.RULES),
        FUEL("get_fuel_strategy", "Carburant et plan d'arrêt calculés par le moteur : consommation mesurée, marge, arrêt prévu, arrêts faits.", FactTopic.FUEL),
        TYRES("get_tyre_strategy", "Pneus : gomme, âge du train, prochaine gomme, performance estimée des gommes sur l'historique comparable.", FactTopic.TYRES),
        LAPS("get_recent_laps", "Derniers tours mesurés : chrono, carburant consommé, blocages, patinages, vitesse maximale, antipatinage.", FactTopic.LAPS),
        HISTORY("get_session_history", "Historique comparable : sessions archivées, justesse des prévisions, dernier bilan, profil de pilotage.", FactTopic.HISTORY),
        CORNERS("get_corner_comparison", "Comparaison par virage de la session : temps perdu sur le meilleur passage du même virage, vitesse minimale, blocages, patinages, cause probable et confiance ; pneus ou pilotage ; motricité. Virages non comparables signalés.", FactTopic.CORNERS),
        DEBRIEF("get_driver_debrief", "Débrief : dernier bilan de course (carburant prévu/mesuré, arrêts, pertes aux stands, places) et constats de pilotage mesurés.", FactTopic.DEBRIEF),
        START_TYRE("get_start_tyre_advice", "Conseil de gomme de départ déjà calculé par le moteur (statut, base, départs permis). Aucun conseil inventé s'il n'existe pas.", FactTopic.START_TYRE),
        SPORT("get_current_sport_events", "Courses Sport A/B/C de la semaine depuis le cache local du programme : dates, circuit, tours, pneus autorisés/imposés, arrêts, multiplicateurs, BoP, avec source et statut de chaque champ. Aucune recherche web.", FactTopic.SPORT),
        ;

        companion object {
            fun byName(name: String): Tool? {
                val n = name.trim().removePrefix("$NAMESPACE.").removePrefix("${NAMESPACE}__")
                return entries.firstOrNull { it.toolName == n }
            }
        }
    }

    /** Groupe d'outils au format « namespace » de la Responses API (seul format d'outils personnalisés pris en charge par le forfait). */
    fun namespace(): JSONObject = JSONObject()
        .put("type", "namespace")
        .put("name", NAMESPACE)
        .put("description", "Données Pitwall en lecture seule : faits du moteur, avec provenance. Appelle seulement ce qui sert la question.")
        .put("tools", JSONArray().also { a ->
            Tool.entries.forEach { t ->
                a.put(
                    JSONObject().put("type", "function").put("name", t.toolName).put("description", t.description)
                        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()).put("additionalProperties", false))
                        .put("strict", true),
                )
            }
        })

    /** Exécute un appel du modèle sur le cliché ; ne modifie rien. Cliché trop vieux : refusé, aucune donnée. */
    fun call(name: String, snapshot: ToolSnapshot, nowMs: Long = snapshot.observedAtMs): ToolResult {
        val tool = Tool.byName(name) ?: return ToolResult.unknown(name, snapshot.observedAtMs)
        return snapshot.result(tool, nowMs)
    }
}

/** Un fait rendu par un outil, avec sa provenance, sa source et sa fiabilité (fixées à la création du fait). */
data class ToolFact(val text: String, val provenance: FactProvenance, val source: String = "inconnue", val reliability: String? = null)

/** Contrat de réponse d'un outil (#70) : quand, d'où, quelle confiance, ce qui manque, ce qui se contredit. */
data class ToolResult(
    val tool: String,
    val known: Boolean,
    val observedAtMs: Long,
    val telemetryLive: Boolean,
    val lap: Int?,
    val facts: List<ToolFact>,
    val missing: List<String>,
    val conflicts: List<String>,
    /** Le cliché est trop vieux pour la question (session changée, garage, réponse tardive) : aucune donnée rendue. */
    val stale: Boolean = false,
) {
    val confidence: String
        get() = when {
            !known -> "aucune"
            stale -> "aucune (cliché périmé)"
            facts.isEmpty() -> "aucune donnée"
            conflicts.isNotEmpty() -> "faible (sources contradictoires)"
            facts.any { it.provenance == FactProvenance.UNKNOWN } -> "faible (provenance inconnue pour une partie des faits)"
            telemetryLive && facts.all { it.provenance == FactProvenance.MEASURED || it.provenance == FactProvenance.REFERENCE } -> "élevée (mesures en direct)"
            else -> "moyenne (calculs du moteur, publications ou déclarations)"
        }

    /** Texte des faits rendus : sert au contrôle des chiffres de la réponse (rien d'autre n'est autorisé). */
    val evidenceText: String get() = facts.joinToString("\n") { it.text }

    fun toJson(): String = JSONObject().apply {
        put("tool", tool)
        if (!known) {
            put("error", "outil inconnu : aucune donnée. Outils disponibles : ${PitwallDataTools.Tool.entries.joinToString { it.toolName }}")
            return@apply
        }
        put("observed_at_ms", observedAtMs)
        put("telemetry_live", telemetryLive)
        lap?.let { put("lap", it) }
        put("confidence", confidence)
        if (stale) put("stale", true)
        put("facts", JSONArray().also { a ->
            facts.forEach { f -> a.put(JSONObject().put("text", f.text).put("provenance", f.provenance.label).put("source", f.source).apply { f.reliability?.let { put("reliability", it) } }) }
        })
        put("missing", JSONArray(missing))
        put("conflicts", JSONArray(conflicts))
        put("rule", "Utilise seulement ces faits ; ne calcule pas de nouveau chiffre ; dis ce qui manque.")
    }.toString()

    companion object {
        fun unknown(name: String, at: Long) = ToolResult(name.take(60), false, at, false, null, emptyList(), emptyList(), emptyList())
    }
}

/**
 * Cliché des faits du moteur au moment de la question, rangés par sujet. Sujet, provenance et source viennent de
 * [AiFacts.tagged] (fixés par [com.gt7pitwall.core.race.RaceController.aiFacts]) : rien n'est deviné d'après le texte.
 * Une phrase non étiquetée est rangée en [FactTopic.OTHER] avec une provenance [FactProvenance.UNKNOWN], et n'est
 * servie par aucun outil.
 */
class ToolSnapshot(
    val observedAtMs: Long,
    val telemetryLive: Boolean,
    val lap: Int?,
    private val byTopic: Map<FactTopic, List<ToolFact>>,
    private val conflicts: List<String> = emptyList(),
    private val maxAgeMs: Long = MAX_AGE_MS,
    /** V492 : sections d'analyse (virages, débrief, gomme de départ, Sport), déjà étiquetées par [AnalysisFacts]. */
    private val sections: Map<FactTopic, AnalysisFacts.Section> = emptyMap(),
) {
    fun facts(t: FactTopic): List<ToolFact> = byTopic[t].orEmpty()

    /** Phrases sans étiquette (provenance inconnue) : jamais servies, comptées pour le diagnostic. */
    val untagged: Int get() = facts(FactTopic.OTHER).size

    fun result(tool: PitwallDataTools.Tool, nowMs: Long = observedAtMs): ToolResult {
        val topic = tool.topic ?: return ToolResult.unknown(tool.toolName, observedAtMs)
        if (nowMs - observedAtMs > maxAgeMs) {
            return ToolResult(tool.toolName, true, observedAtMs, telemetryLive, lap, emptyList(), listOf("cliché périmé : repose la question"), emptyList(), stale = true)
        }
        sections[topic]?.let { sec ->
            val missing = sec.missing + if (sec.facts.isEmpty()) listOf("aucun fait disponible sur ce sujet") else emptyList()
            return ToolResult(tool.toolName, true, observedAtMs, telemetryLive, lap, sec.facts, missing.distinct(), sec.conflicts)
        }
        if (topic in ANALYSIS_TOPICS) {
            return ToolResult(tool.toolName, true, observedAtMs, telemetryLive, lap, emptyList(), listOf("analyse non fournie pour cette question"), emptyList())
        }
        val f = facts(topic)
        val missing = buildList {
            if (!telemetryLive && topic in setOf(FactTopic.LIVE, FactTopic.FUEL, FactTopic.TYRES)) add("télémétrie en direct (aucune course suivie en ce moment)")
            if (topic == FactTopic.LIVE) add("écarts, arrêts et pneus des adversaires (GT7 ne les transmet pas)")
            if (topic == FactTopic.TYRES) add("usure réelle et gomme montée (GT7 ne les transmet pas)")
            if (f.isEmpty()) add("aucun fait disponible sur ce sujet")
        }
        return ToolResult(tool.toolName, true, observedAtMs, telemetryLive, lap, f, missing, if (topic == FactTopic.RULES) conflicts else emptyList())
    }

    companion object {
        /** Au-delà, le cliché ne vaut plus pour la question (la radio écarte de toute façon une réponse tardive). */
        const val MAX_AGE_MS = 60_000L
        private val ANALYSIS_TOPICS = setOf(FactTopic.CORNERS, FactTopic.DEBRIEF, FactTopic.START_TYRE, FactTopic.SPORT)

        fun from(
            facts: AiFacts,
            telemetryLive: Boolean,
            lap: Int?,
            observedAtMs: Long,
            sections: Map<FactTopic, AnalysisFacts.Section> = emptyMap(),
        ): ToolSnapshot {
            val map = linkedMapOf<FactTopic, MutableList<ToolFact>>()
            val byText = facts.tagged.associateBy { it.text }
            for (line in facts.lines) {
                val t = byText[line]
                val topic = t?.topic ?: FactTopic.OTHER
                map.getOrPut(topic) { mutableListOf() } += ToolFact(line, t?.provenance ?: FactProvenance.UNKNOWN, t?.source ?: "non classée", t?.reliability)
            }
            // Constats de pilotage : calculés par le moteur à partir de la télémétrie des tours.
            facts.observations.forEach { o -> map.getOrPut(FactTopic.LAPS) { mutableListOf() } += ToolFact(o.text, FactProvenance.CALCULATED, "constats de pilotage (télémétrie des tours)") }
            return ToolSnapshot(observedAtMs, telemetryLive, lap, map, facts.conflicts, sections = sections)
        }
    }
}
