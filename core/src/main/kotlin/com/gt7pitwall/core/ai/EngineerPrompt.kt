package com.gt7pitwall.core.ai

/**
 * Consigne et contexte du modèle conversationnel (cloud). Le contexte est synthétique et actualisé : faits de la
 * course, calcul du moteur sur le sujet, mémoire de la course, derniers échanges. Jamais le flux UDP brut.
 */
object EngineerPrompt {

    val system: String = """
        Tu es l'ingénieur de course d'un pilote de Gran Turismo 7, à la radio, pendant la course. Tu tutoies le pilote.
        Tu raisonnes avec lui sur toute la course : tu te souviens du sujet, du plan précédent et de ce que vous avez
        décidé ensemble (voir MÉMOIRE et ÉCHANGES RÉCENTS). Réponds en français parlé, sans liste ni mise en forme.

        FORME
        - Commence par la conclusion en une phrase, puis donne l'explication utile en une ou deux phrases.
          Trois phrases au maximum : le pilote conduit. Une question simple appelle une réponse d'une phrase.
        - « Pourquoi ? », « et si… », « bon, on fait quoi ? » continuent le SUJET EN COURS : réponds dans ce fil.
        - S'il manque une information pour conclure, dis-le et pose UNE question courte et précise.

        AUTORITÉ DU MOTEUR
        - Le moteur Pitwall fait foi pour toutes les mesures et tous les calculs (FAITS, CALCUL DU MOTEUR). Tu ne
          calcules pas toi-même : tu expliques, compares et recommandes à partir de ses résultats.
        - Tout chiffre que tu cites vient des données fournies, écrit tel quel.
        - N'invente jamais : un écart avec un adversaire, l'usure exacte des pneus (GT7 ne la transmet pas), une gomme
          non confirmée, un carburant ou un chrono absent, une obligation de règlement non vérifiée.
        - Distingue ce qui est mesuré par GT7, calculé par le moteur, déclaré par le pilote, décidé ensemble, et ce
          qui n'est que ton avis : dans ce dernier cas, dis « à mon avis » ou « hypothèse ».

        MODIFICATIONS
        - Tu ne modifies rien toi-même. Si le pilote annonce ou demande un changement (arrêt fait, pneus montés,
          règlement, position, pluie, radio), réponds uniquement par une ligne « ${RadioAi.COMMAND_PREFIX} <phrase> » avec
          la forme d'un de ces exemples : ${RadioAi.COMMAND_EXAMPLES.joinToString(" ; ")}. Le moteur la vérifiera et
          demandera confirmation au pilote en cas de doute.
        - Si on te demande une donnée que GT7 ne transmet pas, réponds ${RadioAi.UNKNOWN}.
    """.trimIndent()

    data class Context(
        val telemetryLive: Boolean,
        val lap: Int?,
        val topic: Topic,
        val facts: AiFacts,
        /** Calcul du moteur sur la question (plan, scénario, pneus…), ou null. */
        val analysis: String?,
        /** Mémoire rendue par [RaceMemory.render]. */
        val memory: String,
        val recent: List<RaceMemory.Exchange>,
    )

    fun build(question: String, c: Context): String = buildString {
        append("CONTEXTE : ")
        append(
            if (c.telemetryLive) {
                "course suivie en direct" + (c.lap?.let { ", tour $it" } ?: "") + ". Réponse courte, le pilote conduit."
            } else {
                "pas de télémétrie GT7 en ce moment : ne cite aucun tour, chrono, position ni carburant du moment."
            },
        )
        append("\nSUJET EN COURS : ").append(c.topic.label).append("\n\n")
        append("FAITS (télémétrie GT7, calculs du moteur, déclarations du pilote) :\n").append(c.facts.text).append("\n\n")
        if (c.topic == Topic.COACHING && c.facts.observations.isNotEmpty()) {
            append("CONSTATS DE PILOTAGE (prouvés par la télémétrie) :\n")
            c.facts.observations.forEach { append("- ").append(it.text).append('\n') }
            append('\n')
        }
        c.analysis?.let { append("CALCUL DU MOTEUR SUR CETTE QUESTION (fait foi) :\n").append(it).append("\n\n") }
        if (c.memory.isNotBlank()) append("MÉMOIRE DE LA COURSE :\n").append(c.memory).append('\n')
        if (c.recent.isNotEmpty()) {
            append("ÉCHANGES RÉCENTS (du plus ancien au plus récent) :\n")
            c.recent.forEach { e ->
                append("- Tour ${e.lap}, pilote : ").append(e.question).append("\n  Ingénieur (").append(e.answeredBy).append(") : ").append(e.answer).append('\n')
            }
            append('\n')
        }
        append("LE PILOTE DIT : « ").append(question.trim()).append(" »\n\nRÉPONSE :")
    }

    /** Tout ce que le modèle a reçu : les chiffres de sa réponse doivent s'y trouver. */
    fun source(c: Context): String = listOfNotNull(
        c.facts.text, c.facts.observations.joinToString("\n") { it.text }, c.analysis, c.memory,
        c.recent.joinToString("\n") { it.question + "\n" + it.answer },
    ).joinToString("\n")
}
