package com.gt7pitwall.core.strategy

/** Règlement de l'épreuve, saisi par le pilote (GT7 ne le transmet pas). */
data class RaceRules(
    /** Nombre de tours (si GT7 ne l'envoie pas déjà). */
    val totalLaps: Int? = null,
    /** Course au temps, en minutes. */
    val durationMin: Int? = null,
    /** Multiplicateur de consommation de l'épreuve (×1, ×2…). Sert uniquement à exploiter l'historique. */
    val fuelMultiplier: Int = 1,
    /** Multiplicateur d'usure des pneus de l'épreuve. */
    val tyreMultiplier: Int = 1,
    /** Arrêts aux stands imposés par le règlement. */
    val mandatoryStops: Int = 0,
    /** Gommes autorisées (vide = non précisé). */
    val allowedTyres: Set<TyreCompound> = emptySet(),
    /** Gommes à utiliser obligatoirement au moins une fois pendant la course. */
    val requiredTyres: Set<TyreCompound> = emptySet(),
    /** Gomme montée au départ, déclarée par le pilote. */
    val startTyre: TyreCompound? = null,
    /**
     * Nombre de tours maximal d'un train de pneus, donné par le pilote (« les pneus tiennent 8 tours »).
     * GT7 ne transmet pas l'usure : c'est la seule base fiable pour planifier les changements de pneus.
     */
    val tyreStintLaps: Int? = null,
    /** Circuit choisi ou reconnu (identifiant de la base GT7). */
    val trackId: Int? = null,
) {
    val isEmpty: Boolean
        get() = totalLaps == null && durationMin == null && fuelMultiplier == 1 && tyreMultiplier == 1 &&
            mandatoryStops == 0 && allowedTyres.isEmpty() && requiredTyres.isEmpty() && startTyre == null &&
            tyreStintLaps == null
}
