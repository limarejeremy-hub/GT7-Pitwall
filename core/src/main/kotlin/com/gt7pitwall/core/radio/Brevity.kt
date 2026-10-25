package com.gt7pitwall.core.radio

/**
 * Longueur des réponses (essai PS5 : bilans interminables en pleine course). Une question simple reçoit la réponse
 * utile en une ou deux phrases ; l'analyse complète seulement quand le pilote la demande (« détails », « analyse »,
 * « bilan complet », « explique », « pourquoi »).
 */
object Brevity {
    /** Phrases d'une réponse simple. */
    const val SIMPLE_SENTENCES = 2

    private val DETAIL = Regex(
        "(^|\\s)(details?|detaille|analyse|bilan|complet|complete|explique|expliquer|pourquoi|developpe|en profondeur|point complet)($|\\s|\\?)",
    )

    /** Le pilote demande explicitement le détail. */
    fun wantsDetail(question: String): Boolean = DETAIL.containsMatchIn(DriverCommandParser.normalize(question))

    /** Phrases sans information pour une question simple : rappel du règlement, détail du calcul, mesure insuffisante. */
    private val FILLER = Regex("^(course de \\d+|consommation |adherence : pas encore|je mesure )", RegexOption.IGNORE_CASE)

    /** Réponse courte : au plus [max] phrases, sans les phrases de remplissage (gardées si elles sont tout le contenu). */
    fun short(text: String, max: Int = SIMPLE_SENTENCES): String {
        val all = SpeechStyle.sentences(text)
        if (all.size <= 1) return text.trim()
        val useful = all.filterNot { FILLER.containsMatchIn(DriverCommandParser.normalize(it)) }.ifEmpty { all }
        return useful.take(max).joinToString(" ")
    }
}
