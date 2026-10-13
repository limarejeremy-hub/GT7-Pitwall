package com.gt7pitwall.core.radio

import java.text.Normalizer

/** Ce que le pilote dit à l'ingénieur, une fois la phrase reconnue. */
sealed interface DriverCommand {
    data class Ask(val query: DriverQuery) : DriverCommand

    /** "Je suis troisième" : position annoncée par le pilote (GT7 ne la transmet pas). */
    data class ReportPosition(val position: Int) : DriverCommand

    /** "J'ai doublé". */
    data object Overtook : DriverCommand

    /** "Je me suis fait doubler". */
    data object Overtaken : DriverCommand

    /** "Silence" : plus d'annonces automatiques, seulement les alertes et les réponses. */
    data object Quiet : DriverCommand

    /** "Tu peux parler" : annonces automatiques réactivées. */
    data object Talk : DriverCommand

    /** "Répète". */
    data object Repeat : DriverCommand
}

/**
 * Analyse simple et prévisible des phrases françaises reconnues.
 * Fonctionne sans Internet : mots-clés + ordinaux. À enrichir au fil des essais.
 */
object DriverCommandParser {

    private val ORDINALS: Map<String, Int> = mapOf(
        "premier" to 1, "premiere" to 1, "1er" to 1, "1re" to 1,
        "deuxieme" to 2, "second" to 2, "seconde" to 2,
        "troisieme" to 3, "quatrieme" to 4, "cinquieme" to 5, "sixieme" to 6,
        "septieme" to 7, "huitieme" to 8, "neuvieme" to 9, "dixieme" to 10,
        "onzieme" to 11, "douzieme" to 12, "treizieme" to 13, "quatorzieme" to 14,
        "quinzieme" to 15, "seizieme" to 16, "dix-septieme" to 17, "dix-huitieme" to 18,
        "dix-neuvieme" to 19, "vingtieme" to 20,
    )

    private val CARDINALS: Map<String, Int> = mapOf(
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5,
        "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11,
        "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16,
        "dix-sept" to 17, "dix-huit" to 18, "dix-neuf" to 19, "vingt" to 20,
    )

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('’', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()

    fun parse(text: String): DriverCommand? {
        val t = normalize(text)
        if (t.isEmpty()) return null

        if (has(t, "repete", "repeter", "redis")) return DriverCommand.Repeat
        if (has(t, "silence", "tais-toi", "tais toi", "chut")) return DriverCommand.Quiet
        if (has(t, "tu peux parler", "reparle", "parle-moi", "parle moi")) return DriverCommand.Talk

        // "Il m'a doublé" avant "j'ai doublé".
        if (has(t, "fait doubler", "fait passer", "fait depasser", "m'a double", "m'a passe", "m'a depasse", "ma double", "ma passe", "ma depasse", "perdu une place")) {
            return DriverCommand.Overtaken
        }
        if (has(t, "j'ai double", "j'ai passe", "j'ai depasse", "jai double", "jai passe", "jai depasse", "gagne une place")) {
            return DriverCommand.Overtook
        }

        positionIn(t)?.let { return DriverCommand.ReportPosition(it) }

        val query = when {
            has(t, "position", "quelle place", "je suis combien") -> DriverQuery.POSITION
            has(t, "essence", "carburant", "fuel", "reservoir", "conso") -> DriverQuery.FUEL
            has(t, "secteur") -> DriverQuery.SECTORS
            has(t, "meilleur", "record") -> DriverQuery.BEST_LAP
            has(t, "dernier tour", "chrono", "temps") -> DriverQuery.LAST_LAP
            has(t, "rythme", "regularite", "moyenne") -> DriverQuery.PACE
            has(t, "tour", "course", "reste") -> DriverQuery.RACE_STATUS
            else -> null
        }
        return query?.let { DriverCommand.Ask(it) }
    }

    /** "je suis troisième", "je suis 3e", "en position trois", "P3". */
    private fun positionIn(t: String): Int? {
        val words = t.split(' ', ',', '.', '!', '?').filter { it.isNotEmpty() }
        val mentionsPosition = has(t, "je suis", "position", "place", "je roule")
        for ((i, w) in words.withIndex()) {
            ORDINALS[w]?.let { if (mentionsPosition) return it }
            Regex("^(\\d{1,2})(e|eme|er|re)$").find(w)?.let { if (mentionsPosition) return it.groupValues[1].toInt() }
            Regex("^p(\\d{1,2})$").find(w)?.let { return it.groupValues[1].toInt() }
            if ((w == "position" || w == "place") && i + 1 < words.size) {
                val next = words[i + 1]
                (CARDINALS[next] ?: next.toIntOrNull())?.let { return it }
            }
        }
        return null
    }

    private fun has(t: String, vararg keys: String) = keys.any { it in t }
}
