package com.gt7pitwall.core.opponents

/**
 * Point d'extension pour les adversaires.
 *
 * IMPORTANT : à ce jour, aucune source vérifiée ne donne les écarts avec les autres voitures. La télémétrie de GT7
 * (formats A, B, ~ et C) ne décrit que notre voiture, plus notre rang (0x84/0x86). L'application n'invente donc
 * rien : [NoOpponentsSource] reste branchée tant qu'une source réelle n'a pas été validée sur PS5
 * (voir docs/GAP_HUNTER.md).
 *
 * Toute future valeur doit garder sa provenance, son horodatage, sa fraîcheur et sa fiabilité, et dire si c'est une
 * mesure, une estimation ou une indisponibilité. Aucune annonce radio d'un temps adverse sans [Kind.MEASURED] frais.
 */
data class OpponentInfo(
    /** Rang de la voiture concernée, si connu. */
    val position: Int?,
    /** Écart en millisecondes (positif : la voiture est devant nous), null si indisponible. */
    val gapMs: Int?,
    /** Provenance lisible : « télémétrie GT7 0x… », « saisie du pilote »… */
    val source: String,
    val kind: Kind = if (gapMs == null) Kind.UNAVAILABLE else Kind.ESTIMATED,
    /** Instant de la mesure (horloge du téléphone, ms). */
    val measuredAtMs: Long = 0,
    /** Fiabilité 0..1 de la valeur, d'après sa source (1 = mesure directe validée). */
    val reliability: Double = 0.0,
    val relation: Relation = Relation.OTHER,
) {
    enum class Kind { MEASURED, ESTIMATED, UNAVAILABLE }

    enum class Relation { AHEAD, BEHIND, LEADER, OTHER }

    /** Âge de la valeur, null si l'horodatage est absent (≤ 0) ou dans le futur : la valeur n'est alors pas datée. */
    fun ageMs(nowMs: Long): Long? = if (measuredAtMs <= 0 || measuredAtMs > nowMs) null else nowMs - measuredAtMs

    /**
     * Seule une mesure directe, fiable et récente peut être dite à la radio. Refusé si : estimation ou indisponibilité,
     * écart absent, horodatage absent, invalide ou futur, valeur trop ancienne, fiabilité hors de 0..1 ou insuffisante.
     */
    fun announceable(nowMs: Long, maxAgeMs: Long = 5_000, minReliability: Double = 0.9): Boolean {
        if (kind != Kind.MEASURED || gapMs == null) return false
        if (reliability.isNaN() || reliability !in 0.0..1.0 || reliability < minReliability) return false
        val age = ageMs(nowMs) ?: return false
        return age <= maxAgeMs
    }
}

interface OpponentsSource {
    /** Nom de la source, pour le diagnostic. */
    val name: String get() = javaClass.simpleName

    /** Liste vide tant qu'aucune source réelle n'est branchée. */
    fun current(): List<OpponentInfo>
}

object NoOpponentsSource : OpponentsSource {
    override val name: String = "aucune source d'écarts validée"
    override fun current(): List<OpponentInfo> = emptyList()
}
