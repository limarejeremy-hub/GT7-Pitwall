package com.gt7pitwall.core.ai.tools

import com.gt7pitwall.core.strategy.TyreCompound

/**
 * Revue #72 (V493) : contrôle du SENS d'une réponse sur le règlement Sport. Le filtre des chiffres ne suffit pas : dire
 * « RH et RM autorisés » quand les deux sont OBLIGATOIRES cache une règle décisive. Rejette aussi l'affirmation d'une
 * obligation que le programme ne vérifie pas.
 */
object SportSemantics {
    private val OBLIGATION = Regex("(?i)obligatoire|imposée?s?|impos|doi(t|vent) (être )?utilis|à utiliser|requis|mandatory|required")
    private val PERMISSION = Regex("(?i)autoris|permis|au choix|disponibles?")

    /** Problème trouvé dans [answer], ou null si la réponse respecte les règles connues. */
    fun problem(answer: String, rules: List<AnalysisFacts.SportRule>): String? {
        val text = answer.replace('\n', ' ')
        for (r in rules.filter { it.current }) {
            val seg = segmentFor(text, r.series.letter, rules.size) ?: continue
            val mentions = TyreCompound.entries.filter { Regex("\\b${it.name}\\b").containsMatchIn(seg) }.toSet()
            val required = r.required
            if (required != null && required.size >= 2) {
                if (mentions.containsAll(required) && PERMISSION.containsMatchIn(seg) && !OBLIGATION.containsMatchIn(seg)) {
                    return "${r.series.label} : gommes ${required.joinToString(", ") { it.name }} OBLIGATOIRES présentées comme seulement autorisées"
                }
            }
            if (required == null && mentions.isNotEmpty() && OBLIGATION.containsMatchIn(seg)) {
                return "${r.series.label} : obligation de gommes affirmée alors qu'elle n'est pas vérifiée"
            }
        }
        return null
    }

    /**
     * Partie de la réponse qui parle de la course [letter] (« C : … », « course C … », « Sport C … ») jusqu'à la course
     * suivante ; la réponse entière si elle ne parle que d'une course.
     */
    private fun segmentFor(text: String, letter: Char, editions: Int): String? {
        val marker = Regex("(?i)(?:\\b(?:course|sport|daily)\\s+|(?:^|[\\s(;.,])\\(?)$letter\\b\\)?\\s*[:\\-–—]?")
        val m = marker.find(text) ?: return if (editions == 1) text else null
        val rest = text.substring(m.range.first)
        val next = Regex("(?i)(?:\\b(?:course|sport|daily)\\s+|[;.]\\s*)[ABC]\\b\\)?\\s*[:\\-–—]").find(rest, (m.range.last - m.range.first) + 1)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }
}
