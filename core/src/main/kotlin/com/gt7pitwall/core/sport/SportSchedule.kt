package com.gt7pitwall.core.sport

import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.LocalDate

/** Fiabilité d'une information du programme Sport. */
enum class FieldStatus(val label: String) {
    /** Deux sources indépendantes concordent, ou le pilote l'a confirmée. */
    CONFIRMED("confirmé"),

    /** Une seule source publique la donne. */
    PROBABLE("probable"),

    /** Saisie ou corrigée par le pilote. */
    PILOT("saisi par toi"),

    /** Aucune source ne la donne. */
    UNKNOWN("inconnu"),

    /** Les sources se contredisent : rien n'est appliqué. */
    CONFLICT("contradictoire"),
}

/** D'où vient une information, et quand elle a été lue. */
data class SourceRef(val name: String, val url: String?, val fetchedAtMs: Long)

/** Une valeur avec sa source, sa fiabilité et, si besoin, une précision (conflit, valeur illisible). */
data class Sourced<T>(
    val value: T?,
    val status: FieldStatus,
    val sources: List<SourceRef> = emptyList(),
    val note: String? = null,
) {
    val known: Boolean get() = value != null && status != FieldStatus.UNKNOWN && status != FieldStatus.CONFLICT

    companion object {
        fun <T> unknown(note: String? = null) = Sourced<T>(null, FieldStatus.UNKNOWN, note = note)
    }
}

/** Champs du règlement qu'une source peut donner (et dont on suit la fiabilité). */
enum class RuleField(val label: String) {
    TRACK("circuit"),
    LAPS("nombre de tours"),
    CARS("voitures"),
    FUEL_MULTIPLIER("multiplicateur de carburant"),
    TYRE_MULTIPLIER("multiplicateur d'usure"),
    MANDATORY_STOPS("arrêts obligatoires"),
    ALLOWED_TYRES("pneus autorisés"),
    REQUIRED_TYRES("pneus imposés"),
    BOP("BoP"),
    SETTINGS("réglages"),
}

/**
 * Une course quotidienne telle qu'une source la décrit. Champ null : la source ne le dit pas (jamais une valeur par
 * défaut). [lines] : le texte brut lu, pour vérifier l'extraction.
 */
data class DailyRaceInfo(
    val series: Series,
    val source: SourceRef,
    val trackName: String? = null,
    val laps: Int? = null,
    val durationMin: Int? = null,
    val cars: String? = null,
    val bop: String? = null,
    val allowedTyres: Set<TyreCompound>? = null,
    /** Gommes à utiliser obligatoirement, si la source le précise ; null : non précisé. */
    val requiredTyres: Set<TyreCompound>? = null,
    val settings: String? = null,
    val startType: String? = null,
    /** 0 : la source indique explicitement aucun arrêt obligatoire. */
    val mandatoryStops: Int? = null,
    val fuelMultiplier: Int? = null,
    val tyreMultiplier: Int? = null,
    val lines: List<String> = emptyList(),
)

/** Une semaine de courses quotidiennes publiée par une source. */
data class WeeklyPost(
    val source: SourceRef,
    /** Premier jour de validité (date de publication, heure de Paris). */
    val validFrom: LocalDate,
    val title: String,
    val races: Map<Series, DailyRaceInfo>,
)

/**
 * Édition hebdomadaire d'une course (A, B ou C) : chaque champ avec sa fiabilité, fusionnée depuis toutes les sources.
 * L'identifiant repose sur la période de validité ; l'empreinte change si les caractéristiques publiées changent.
 */
data class Edition(
    val series: Series,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val trackName: Sourced<String>,
    /** Circuit de la base GT7, si le nom publié le désigne sans ambiguïté. */
    val trackId: Int?,
    val trackCandidates: List<Int> = emptyList(),
    val laps: Sourced<Int>,
    val cars: Sourced<String>,
    val bop: Sourced<String>,
    val settings: Sourced<String>,
    val startType: Sourced<String>,
    val allowedTyres: Sourced<Set<TyreCompound>>,
    val requiredTyres: Sourced<Set<TyreCompound>>,
    val mandatoryStops: Sourced<Int>,
    val fuelMultiplier: Sourced<Int>,
    val tyreMultiplier: Sourced<Int>,
    val sources: List<SourceRef>,
) {
    val id: String get() = "${series.name}@$validFrom"

    /** Empreinte des caractéristiques publiées : détecte une correction en cours de semaine. */
    val fingerprint: String
        get() = listOf(trackName.value, laps.value, cars.value, allowedTyres.value?.let(TyreCompound::codes), requiredTyres.value?.let(TyreCompound::codes), mandatoryStops.value, fuelMultiplier.value, tyreMultiplier.value)
            .joinToString("|")

    fun covers(day: LocalDate): Boolean = !day.isBefore(validFrom) && day.isBefore(validTo)

    /** Statut de chaque champ du règlement, pour l'affichage et les garde-fous de la stratégie. */
    val status: Map<RuleField, FieldStatus>
        get() = mapOf(
            // The published circuit name can be independently verified without a telemetry ID.
            // Keep trackId null until geometry/GT7 database proves it; never invent an ID.
            RuleField.TRACK to trackName.status,
            RuleField.LAPS to laps.status,
            RuleField.CARS to cars.status,
            RuleField.FUEL_MULTIPLIER to fuelMultiplier.status,
            RuleField.TYRE_MULTIPLIER to tyreMultiplier.status,
            RuleField.MANDATORY_STOPS to mandatoryStops.status,
            RuleField.ALLOWED_TYRES to allowedTyres.status,
            RuleField.REQUIRED_TYRES to requiredTyres.status,
            RuleField.BOP to bop.status,
            RuleField.SETTINGS to settings.status,
        )
}

/** État du programme pour l'application : édition en cours de chaque course, et fraîcheur. */
data class ScheduleState(
    val editions: Map<Series, Edition>,
    /** Toutes les semaines connues (les anciennes restent consultables). */
    val history: List<Edition>,
    val lastFetchMs: Long?,
    val lastError: String?,
    /** Aucune édition ne couvre aujourd'hui : le programme connu est périmé. */
    val stale: Boolean,
)
