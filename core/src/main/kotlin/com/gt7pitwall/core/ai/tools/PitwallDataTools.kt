package com.gt7pitwall.core.ai.tools

import com.gt7pitwall.core.ai.AiFacts
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

    enum class Provenance(val label: String) {
        MEASURED("mesuré (télémétrie GT7)"),
        CALCULATED("calculé par le moteur Pitwall"),
        PUBLISHED("publié (programme Sport, source tierce)"),
        DECLARED("déclaré par le pilote"),
    }

    enum class Tool(val toolName: String, val description: String, val topic: ToolSnapshot.Topic?) {
        LIVE("get_live_race_state", "État de la course en direct : télémétrie active ou non, voiture, circuit, tour, position, chronos, rythme, météo déclarée.", ToolSnapshot.Topic.LIVE),
        RULES("get_race_rules_and_sources", "Règlement de l'épreuve et ses sources : obligations (gommes, arrêts), programme Sport de la semaine, champs inconnus. Deux gommes imposées ne veulent pas dire un arrêt explicite.", ToolSnapshot.Topic.RULES),
        FUEL("get_fuel_strategy", "Carburant et plan d'arrêt calculés par le moteur : consommation mesurée, marge, arrêt prévu, arrêts faits.", ToolSnapshot.Topic.FUEL),
        TYRES("get_tyre_strategy", "Pneus : gomme, âge du train, prochaine gomme, performance estimée des gommes sur l'historique comparable.", ToolSnapshot.Topic.TYRES),
        LAPS("get_recent_laps", "Derniers tours mesurés : chrono, carburant consommé, blocages, patinages, vitesse maximale, antipatinage.", ToolSnapshot.Topic.LAPS),
        HISTORY("get_session_history", "Historique comparable : sessions archivées, justesse des prévisions, dernier bilan, profil de pilotage.", ToolSnapshot.Topic.HISTORY),
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

    /** Exécute un appel du modèle sur le cliché ; ne modifie rien. */
    fun call(name: String, snapshot: ToolSnapshot): ToolResult {
        val tool = Tool.byName(name) ?: return ToolResult.unknown(name, snapshot.observedAtMs)
        return snapshot.result(tool)
    }
}

/** Un fait rendu par un outil, avec sa provenance. */
data class ToolFact(val text: String, val provenance: PitwallDataTools.Provenance)

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
) {
    val confidence: String
        get() = when {
            !known -> "aucune"
            facts.isEmpty() -> "aucune donnée"
            conflicts.isNotEmpty() -> "faible (sources contradictoires)"
            facts.any { it.provenance == PitwallDataTools.Provenance.MEASURED } && telemetryLive -> "élevée (mesures en direct)"
            else -> "moyenne (calculs, publications ou déclarations)"
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
        put("facts", JSONArray().also { a -> facts.forEach { f -> a.put(JSONObject().put("text", f.text).put("provenance", f.provenance.label)) } })
        put("missing", JSONArray(missing))
        put("conflicts", JSONArray(conflicts))
        put("rule", "Utilise seulement ces faits ; ne calcule pas de nouveau chiffre ; dis ce qui manque.")
    }.toString()

    companion object {
        fun unknown(name: String, at: Long) = ToolResult(name.take(60), false, at, false, null, emptyList(), emptyList(), emptyList())
    }
}

/**
 * Cliché des faits du moteur au moment de la question, rangés par sujet. Construit à partir de [AiFacts] (les phrases
 * de [com.gt7pitwall.core.race.RaceController.aiFacts]) : aucun calcul refait, aucune source dupliquée.
 */
class ToolSnapshot(
    val observedAtMs: Long,
    val telemetryLive: Boolean,
    val lap: Int?,
    private val byTopic: Map<Topic, List<ToolFact>>,
) {
    enum class Topic { LIVE, RULES, FUEL, TYRES, LAPS, HISTORY }

    fun facts(t: Topic): List<ToolFact> = byTopic[t].orEmpty()

    fun result(tool: PitwallDataTools.Tool): ToolResult {
        val topic = tool.topic ?: return ToolResult.unknown(tool.toolName, observedAtMs)
        val f = facts(topic)
        val missing = buildList {
            if (!telemetryLive && topic in setOf(Topic.LIVE, Topic.FUEL, Topic.TYRES)) add("télémétrie en direct (aucune course suivie en ce moment)")
            if (topic == Topic.LIVE) add("écarts, arrêts et pneus des adversaires (GT7 ne les transmet pas)")
            if (topic == Topic.TYRES) add("usure réelle et gomme montée (GT7 ne les transmet pas)")
            if (f.isEmpty()) add("aucun fait disponible sur ce sujet")
        }
        val conflicts = f.filter { CONFLICT.containsMatchIn(it.text) }.map { it.text }
        return ToolResult(tool.toolName, true, observedAtMs, telemetryLive, lap, f, missing, conflicts)
    }

    companion object {
        private val CONFLICT = Regex("(?i)contradi|conflit|incohéren")

        /** Range chaque phrase des faits dans un sujet, avec sa provenance (préfixes des phrases du moteur). */
        fun from(facts: AiFacts, telemetryLive: Boolean, lap: Int?, observedAtMs: Long): ToolSnapshot {
            val map = linkedMapOf<Topic, MutableList<ToolFact>>()
            for (line in facts.lines) {
                val topic = topicOf(line)
                map.getOrPut(topic) { mutableListOf() } += ToolFact(line, provenanceOf(line, topic))
            }
            facts.observations.forEach { o -> map.getOrPut(Topic.HISTORY) { mutableListOf() } += ToolFact(o.text, PitwallDataTools.Provenance.MEASURED) }
            return ToolSnapshot(observedAtMs, telemetryLive, lap, map)
        }

        private fun starts(line: String, vararg p: String) = p.any { line.startsWith(it) }

        fun topicOf(line: String): Topic = when {
            starts(line, "Épreuve", "Programme de la semaine", "Règlement", "Obligations du règlement") -> Topic.RULES
            starts(line, "Carburant", "Stratégie", "Arrêt prévu", "Arrêts aux stands") -> Topic.FUEL
            starts(line, "Pneus", "Changement de pneus", "Prochaine gomme", "Performance des gommes") -> Topic.TYRES
            Regex("^(Tour \\d+ :|Dernière session, tour|Détail du dernier tour)").containsMatchIn(line) -> Topic.LAPS
            starts(line, "Historique", "Justesse", "Dernier bilan") -> Topic.HISTORY
            starts(line, "GT7 transmet", "Aucune télémétrie", "Voiture", "Circuit", "Course", "Position", "Places", "Dernier tour",
                "Meilleur tour", "Rythme", "Secteurs", "Pluie", "Pas de pluie", "Déclaré par le pilote", "Intention du pilote") -> Topic.LIVE
            else -> Topic.HISTORY // profil de pilotage et autres constats durables
        }

        fun provenanceOf(line: String, topic: Topic): PitwallDataTools.Provenance = when {
            Regex("(?i)déclaré|d'après toi|signalée? par le pilote|intention du pilote|confirmé par le pilote").containsMatchIn(line) -> PitwallDataTools.Provenance.DECLARED
            line.startsWith("Programme de la semaine") -> PitwallDataTools.Provenance.PUBLISHED
            Regex("(?i)estimé|prévision|plan|stratégie|arrêt prévu|marge").containsMatchIn(line) -> PitwallDataTools.Provenance.CALCULATED
            topic == Topic.LAPS || topic == Topic.LIVE -> PitwallDataTools.Provenance.MEASURED
            else -> PitwallDataTools.Provenance.CALCULATED
        }
    }
}
