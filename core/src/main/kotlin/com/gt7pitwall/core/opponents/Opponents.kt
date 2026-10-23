package com.gt7pitwall.core.opponents

/**
 * Point d'extension pour les adversaires.
 *
 * IMPORTANT : la télémétrie de GT7 ne transmet AUCUNE information sur les autres voitures
 * (ni position en course, ni écarts, ni temps). L'application n'en invente donc pas.
 *
 * Pistes possibles pour plus tard, toutes à valider :
 *  - saisie manuelle rapide par le pilote (position, écart annoncé à l'écran) ;
 *  - lecture de l'écran par une autre source (capture vidéo + OCR) ;
 *  - toute future évolution du protocole GT7.
 */
data class OpponentInfo(
    val position: Int,
    val gapMs: Int?,
    val source: String,
)

interface OpponentsSource {
    /** Liste vide tant qu'aucune source réelle n'est branchée. */
    fun current(): List<OpponentInfo>
}

object NoOpponentsSource : OpponentsSource {
    override fun current(): List<OpponentInfo> = emptyList()
}
