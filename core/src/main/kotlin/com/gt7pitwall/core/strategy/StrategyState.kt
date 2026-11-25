package com.gt7pitwall.core.strategy

/**
 * V515 — état stratégique UNIQUE, partagé par le moteur de stratégie, le contrôleur, le tableau et Véga. Chaque valeur
 * garde son unité, sa source, sa fraîcheur, sa nature et ses limites : rien n'est présenté plus sûr qu'il ne l'est.
 */
enum class Nature(val label: String) {
    MESURE("mesure"),
    CALCUL("calcul"),
    ESTIMATION("estimation"),
    HYPOTHESE("hypothèse"),
    DECLARATION("déclaré par le pilote"),
    /** Valeur d'un scénario SIMULÉ (banc d'essai), jamais une mesure de course. */
    SIMULATION("simulation"),
    INCONNU("inconnu"),
}

/** Une valeur sourcée. [atMs] : instant de la mesure (horloge du moteur), null si sans objet. */
data class Sourced<T>(
    val value: T?,
    val unit: String,
    val source: String,
    val nature: Nature,
    val atMs: Long? = null,
    val limit: String? = null,
) {
    val known: Boolean get() = value != null && nature != Nature.INCONNU

    fun ageMs(nowMs: Long): Long? = atMs?.let { nowMs - it }

    companion object {
        fun <T> unknown(unit: String, why: String) = Sourced<T>(null, unit, why, Nature.INCONNU)
    }
}

/** Donnée sur un adversaire DÉCLARÉE par le pilote : horodatée, elle expire vite (jamais une mesure GT7). */
data class DeclaredOpponent(val text: String, val atMs: Long, val validMs: Long = DECLARED_VALID_MS) {
    fun valid(nowMs: Long): Boolean = nowMs - atMs in 0..validMs

    companion object {
        const val DECLARED_VALID_MS = 60_000L
    }
}

data class StrategyState(
    val atMs: Long,
    // --- Course
    val track: Sourced<String>,
    val currentLap: Sourced<Int>,
    val totalLaps: Sourced<Int>,
    val lapsRemaining: Sourced<Float>,
    val timeRemainingMs: Sourced<Long>,
    val position: Sourced<Int>,
    val rulesValid: Sourced<Boolean>,
    val rulesIssues: List<String>,
    val mandatoryStopsLeft: Sourced<Int>,
    // --- Carburant
    val fuelNow: Sourced<Float>,
    val tank: Sourced<Float>,
    val perLap: Sourced<Float>,
    val perLapTrend: Sourced<Float>,
    val lapsOfFuel: Sourced<Float>,
    val safetyLaps: Float,
    val maxSaving: Sourced<Float>,
    // --- Pneus
    val compound: Sourced<TyreCompound>,
    val tyreAge: Sourced<Int>,
    val tyreAgeCertain: Boolean,
    val tyreAnomalies: List<String>,
    val gripLoss: Sourced<String>,
    val degradationMsPerLap: Sourced<Double>,
    val degradationSpreadMs: Double,
    val freshTyreGainMs: Sourced<Double>,
    // --- Stands
    val pitZoneValidated: Boolean,
    val pitEntryStillPossible: Boolean,
    val stopCosts: StopCosts?,
    val refuelMsPerUnit: Sourced<Double>,
    val outLapPenaltyMs: Sourced<Double>,
    val plannedStops: List<PlannedStop>,
    // --- Adversaires (limites de GT7 : position et peloton seulement ; rien sur les écarts ni leurs arrêts)
    val cars: Sourced<Int>,
    val opponentsDeclared: List<DeclaredOpponent>,
    // --- Rythme
    val paceMs: Sourced<Double>,
    val paceSpreadMs: Double,
    /** Âge (ms) du dernier paquet GT7 reçu : une télémétrie interrompue rend la stratégie provisoire. */
    val telemetryAgeMs: Long?,
) {
    /** Adversaires déclarés encore valables (les autres ont expiré). */
    fun opponents(nowMs: Long = atMs): List<DeclaredOpponent> = opponentsDeclared.filter { it.valid(nowMs) }
}
