package com.gt7pitwall.core.ai

import com.gt7pitwall.core.radio.DriverCommandParser

/**
 * Ce que fait une phrase du pilote dans la conversation, au-delà des commandes : se rétracter, exprimer une préférence,
 * demander l'avis de l'ingénieur, poser une hypothèse, décider. La distinction est faite avant tout appel au modèle :
 * une phrase ambiguë ne modifie jamais une stratégie confirmée.
 */
object DialogueActs {
    sealed interface Act {
        /** « Oublie ce que je viens de dire », « laisse tomber ». */
        data object Retract : Act

        /** « Non attends, finalement on reste en piste » : se rétracte, puis [rest] est traité comme une nouvelle phrase. */
        data class Correction(val rest: String) : Act

        /** « Tu es humain ? », « t'es une IA ? » */
        data object Identity : Act

        /** « Tu le sens comment ? », « on est bien ? », « ça se passe comment ? » */
        data object Feeling : Act

        /** « Je veux préserver les pneus » : préférence pour cette course, pas un réglage permanent. */
        data class RacePreference(val text: String) : Act

        /** « Retiens que je préfère des réponses courtes » : préférence durable, à confirmer avant d'être retenue. */
        data class LastingPreference(val text: String) : Act

        /** « Je veux rentrer maintenant », « je rentre ce tour » : intention d'arrêter tout de suite. */
        data object PitNowWish : Act

        /** « Je rentre quand même », « c'est décidé », « c'est ma décision » : décision explicite du pilote. */
        data object Decision : Act

        /** « Et si je mets les tendres ? » : hypothèse, jamais enregistrée comme un fait. */
        data class Hypothesis(val text: String, val tyres: Boolean) : Act
    }

    private fun norm(text: String) = " " + DriverCommandParser.normalize(text).replace("[unk]", " ")
        .replace(Regex("[?!.,;:«»\"]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private fun has(t: String, vararg p: String) = p.any { Regex(it).containsMatchIn(t) }

    private val CORRECTION = Regex("^ (non )?(attends|attend|finalement|en fait|non non|pardon)( attends)?( finalement)? (.{6,})$")

    fun classify(text: String): Act? {
        val t = norm(text)
        if (t.isBlank()) return null
        if (has(t, "^ (oublie|oublies)( (ca|ce que je (viens de dire|t'ai dit)|tout ca|ma derniere phrase))? $", "^ laisse tomber $",
                "^ annule ce que je (viens de dire|t'ai dit) $", "^ non oublie $", "^ (bon )?oublie ca ",
            )
        ) {
            return Act.Retract
        }
        CORRECTION.find(t)?.let { m ->
            val rest = m.groupValues[5].trim()
            if (rest.split(' ').size >= 2) return Act.Correction(rest)
        }
        if (has(t, " (t'es|tu es|es tu|t'es un|tu es un) (un )?(humain|vrai|robot|une ia|une intelligence|reel|une machine|un programme) ",
                " (je parle|je cause) a (un|une) (humain|robot|ia|machine) ", " t'es qui ", " tu es qui ",
            )
        ) {
            return Act.Identity
        }
        if (has(t, " tu (le )?sens comment ", " tu la sens comment ", " comment tu (la )?sens (la course|ca) ", "^ (alors )?on est bien $",
                " ca se passe comment ", " comment ca se passe ", "^ (bon )?(alors )?(t'en|tu en) penses quoi $", " ton avis sur la course ",
            )
        ) {
            return Act.Feeling
        }
        if (has(t, " retiens (que|bien que) ", " souviens toi (que|pour la prochaine) ", " (a l'avenir|dorenavant|desormais|a partir de maintenant) ",
                " pour les prochaines courses ",
            ) && !has(t, " ce tour ", " cette course ")
        ) {
            return Act.LastingPreference(text.trim())
        }
        if (has(t, " je (veux|voudrais|prefere|aimerais) (preserver|menager|economiser|garder) (les |mes )?(pneus|gommes|essence|carburant) ",
                " (je veux|je prefere|on va) (assurer|jouer la securite|finir la course|ne pas prendre de risque) ", " objectif (c'est )?(de )?finir ",
                " (je veux|je prefere|on va) attaquer ",
            )
        ) {
            return Act.RacePreference(text.trim())
        }
        val pitNow = has(t, " (rentrer|rentre|box|m'arreter|arreter|passer aux stands)( aux stands)? (maintenant|tout de suite|ce tour|a ce tour|a la fin de ce tour|direct) ",
            " (rentrer|rentre|box) (maintenant|tout de suite)",
        )
        if (pitNow && has(t, " (je veux|je voudrais|je vais|je prefere|j'aimerais|il faut que je|je dois) ")) return Act.PitNowWish
        if (has(t, " (je rentre|on rentre|je m'arrete) (quand meme|de toute facon) ", " c'est (decide|ma decision|mon choix) ", " je decide ",
                " (je rentre|on rentre),? point ",
            )
        ) {
            return Act.Decision
        }
        if (has(t, "^ (et )?si (je|on) ", " (et )?si (je|on) (mets|met|monte|passe|prends|tente|essaye) ")) {
            return Act.Hypothesis(text.trim(), tyres = has(t, " (pneu|pneus|gomme|gommes|tendres|mediums|durs|tendre|medium|dur) "))
        }
        return null
    }

    /** Oui / non courts, pour répondre à une confirmation du dialogue. */
    fun yes(text: String) = has(norm(text), "^ (oui|ouais|affirmatif|exactement|c'est ca|vas y|confirme|je confirme|ok|d'accord)( oui)?( c'est ca)?( je confirme)? $")
    fun no(text: String) = has(norm(text), "^ (non|nan|negatif|pas du tout|annule|laisse tomber)( merci)?( non)? $")
}
