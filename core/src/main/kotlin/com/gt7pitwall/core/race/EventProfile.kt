package com.gt7pitwall.core.race

import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.strategy.RaceRules

/** Épreuve Sport de GT7 : courses quotidiennes A, B, C, ou autre (hors Sport, essais). */
enum class Series(val label: String) {
    DAILY_A("Course Sport A"),
    DAILY_B("Course Sport B"),
    DAILY_C("Course Sport C"),
    OTHER("Autre épreuve"),
    ;

    /** Lettre de la course Sport (« A », « B », « C ») ; espace pour une autre épreuve. */
    val letter: Char get() = if (this == OTHER) ' ' else name.last()
}

/** Qualification et course ne se mélangent jamais : rythme, essence et pneus n'y ont pas le même sens. */
enum class SessionKind(val label: String) {
    QUALIFYING("Qualification"),
    RACE("Course"),
}

/**
 * Type de session, choisi par le pilote : la télémétrie ne permet pas de distinguer une Course Sport en ligne d'une
 * course hors ligne qui la reproduit. Chaque type a son règlement, son historique et sa mémoire.
 */
enum class SessionMode(val label: String, val detail: String) {
    SPORT_OFFICIAL("Sport officiel", "Course A, B ou C en ligne"),
    SPORT_PRACTICE("Entraînement Sport", "Course hors ligne reprenant A, B ou C : règlement de la semaine pris comme modèle, à vérifier"),
    CUSTOM("Course personnalisée", "Règlement libre"),
    TIME_TRIAL("Qualification / chrono", "Performance pure, sans stratégie de course"),

    /** Session archivée avant la séparation des types : officielle ou entraînement, on ne le sait pas. */
    UNVERIFIED("Type non précisé", "Session archivée avant la séparation des types de session"),

    /**
     * Course A, B ou C reconnue automatiquement par la télémétrie (tours, voiture, circuit). La PS5 ne dit pas si la
     * course est en ligne ou dans un salon privé qui la reproduit : son règlement de la semaine sert à la stratégie,
     * mais elle n'est jamais comptée comme course Sport officielle (carrière locale) sans confirmation du pilote.
     */
    SPORT_IDENTIFIED(
        "Sport reconnu",
        "Course A, B ou C reconnue par la télémétrie ; en ligne ou salon privé : la PS5 ne le dit pas. Touche « Sport officiel » pour la compter dans ta carrière",
    ),
    ;

    /** Types que le pilote choisit lui-même ; « Sport reconnu » n'est posé que par la reconnaissance automatique. */
    val selectable: Boolean get() = this != UNVERIFIED && this != SPORT_IDENTIFIED

    companion object {
        fun default(series: Series, kind: SessionKind): SessionMode = when {
            kind == SessionKind.QUALIFYING -> TIME_TRIAL
            series == Series.OTHER -> CUSTOM
            else -> SPORT_OFFICIAL
        }
    }
}

/** Identifiant d'un profil d'épreuve : une série × qualification ou course × type de session. */
data class ProfileId(val series: Series, val kind: SessionKind, val mode: SessionMode = SessionMode.default(series, kind)) {
    val label: String get() = when (mode) {
        SessionMode.SPORT_OFFICIAL -> "${series.label}, ${kind.label.lowercase()}"
        SessionMode.SPORT_PRACTICE -> "Entraînement ${series.label.removePrefix("Course ")}"
        SessionMode.CUSTOM -> "Course personnalisée"
        SessionMode.TIME_TRIAL -> if (series == Series.OTHER) "Qualification / chrono" else "${series.label}, qualification"
        SessionMode.UNVERIFIED -> "${series.label}, ${kind.label.lowercase()} (type non précisé)"
        SessionMode.SPORT_IDENTIFIED -> "${series.label} (reconnue automatiquement)"
    }
    val code: String get() = "${series.name}/${kind.name}/${mode.name}"

    /** Portée de la mémoire voiture × circuit : l'ancien code pour le type par défaut (mémoire conservée), le code complet sinon. */
    val memoryScope: String get() = if (mode == SessionMode.default(series, kind)) "${series.name}/${kind.name}" else code

    /** Série Sport (A, B, C), officielle ou en entraînement. */
    val sport: Boolean get() = series != Series.OTHER

    /** Profil d'une session ancienne (type non précisé) rangé dans le type par défaut, pour continuer à l'utiliser. */
    fun normalized(): ProfileId = if (mode == SessionMode.UNVERIFIED) copy(mode = SessionMode.default(series, kind)) else this

    companion object {
        /** Profil où sont rangés le règlement et la mémoire d'avant les profils (migration sans perte). */
        val LEGACY = ProfileId(Series.OTHER, SessionKind.RACE)

        /** Profil cohérent pour un type et une série : personnalisée hors Sport, chrono en qualification. */
        fun of(mode: SessionMode, series: Series): ProfileId = when (mode) {
            SessionMode.CUSTOM -> ProfileId(Series.OTHER, SessionKind.RACE, mode)
            SessionMode.TIME_TRIAL -> ProfileId(series, SessionKind.QUALIFYING, mode)
            SessionMode.SPORT_OFFICIAL, SessionMode.SPORT_PRACTICE, SessionMode.UNVERIFIED, SessionMode.SPORT_IDENTIFIED ->
                ProfileId(if (series == Series.OTHER) Series.DAILY_A else series, SessionKind.RACE, mode)
        }

        /** « SERIE/TYPE/MODE » ; un ancien code sans type de session donne une session au type non précisé. */
        fun parse(code: String?): ProfileId? {
            val parts = code?.split('/') ?: return null
            if (parts.size !in 2..3) return null
            val s = Series.entries.firstOrNull { it.name == parts[0] } ?: return null
            val k = SessionKind.entries.firstOrNull { it.name == parts[1] } ?: return null
            val m = if (parts.size == 3) SessionMode.entries.firstOrNull { it.name == parts[2] } ?: return null else SessionMode.UNVERIFIED
            return ProfileId(s, k, m)
        }
    }
}

/** Ce que l'historique d'une session peut apporter à une autre, selon leurs types. */
enum class HistoryRelation {
    /** Même épreuve, même type. */
    SAME,

    /** Même série et même nature (course), mais l'une est un entraînement ou d'un type non précisé : réutilisable
     *  seulement si tout le reste est identique, avec une confiance réduite (BoP et réglages non vérifiés). */
    RELATED,

    NONE,
    ;

    companion object {
        private val SPORT_LIKE = setOf(SessionMode.SPORT_OFFICIAL, SessionMode.SPORT_PRACTICE, SessionMode.UNVERIFIED, SessionMode.SPORT_IDENTIFIED)

        fun of(target: ProfileId, other: ProfileId): HistoryRelation = when {
            target == other -> SAME
            target.series == other.series && target.kind == other.kind && target.series != Series.OTHER &&
                target.kind == SessionKind.RACE && target.mode in SPORT_LIKE && other.mode in SPORT_LIKE -> RELATED
            else -> NONE
        }
    }
}

/**
 * Ce que le pilote déclare sur l'épreuve et que GT7 ne transmet pas. [confirmed] : le pilote a relu et confirmé le
 * règlement ; toute modification du règlement retire la confirmation.
 */
data class EventProfile(
    val id: ProfileId = ProfileId.LEGACY,
    /** Version de GT7 déclarée (« 1.62 »), null si non précisée. */
    val gameVersion: String? = null,
    /** Contexte réglages / BoP déclaré (« BoP activée, réglages interdits »). */
    val setupNote: String? = null,
    /** Date de l'épreuve (aaaa-mm-jj) : début de l'édition hebdomadaire, ou date de confirmation du règlement. */
    val eventDate: String? = null,
    val confirmed: Boolean = false,
    /** Édition hebdomadaire appliquée (« DAILY_B@2026-10-19 »), null : règlement saisi à la main. */
    val editionId: String? = null,
    /** Empreinte de l'édition appliquée : une correction de la source en cours de semaine est détectée. */
    val editionFingerprint: String? = null,
    /**
     * Fiabilité de chaque champ du règlement. Vide : règlement saisi entièrement par le pilote (ancien
     * fonctionnement). Un champ inconnu n'est jamais présenté comme vérifié.
     */
    val ruleStatus: Map<RuleField, FieldStatus> = emptyMap(),
) {
    /** Champs du règlement qui comptent pour la stratégie et restent inconnus ou contradictoires. */
    fun gaps(rules: RaceRules): List<RuleField> {
        if (ruleStatus.isEmpty()) return emptyList()
        val important = buildList {
            if (id.kind == SessionKind.RACE) {
                add(RuleField.LAPS)
                add(RuleField.MANDATORY_STOPS)
                add(RuleField.FUEL_MULTIPLIER)
                add(RuleField.TYRE_MULTIPLIER)
                // Une seule gomme autorisée : l'obligation de gomme est sans objet.
                if (rules.allowedTyres.size != 1) add(RuleField.REQUIRED_TYRES)
            }
            add(RuleField.TRACK)
        }
        return important.filter { f ->
            val st = ruleStatus[f]
            st == null || st == FieldStatus.UNKNOWN || st == FieldStatus.CONFLICT
        }
    }
}

/** Un profil et son règlement : chaque épreuve garde le sien. */
data class ProfileEntry(val profile: EventProfile, val rules: RaceRules)

/**
 * Règlements par épreuve. Changer d'épreuve range le règlement en cours et reprend celui de l'épreuve choisie :
 * le règlement de la Course A ne peut pas se retrouver dans la Course B.
 */
class ProfileBook(entries: Map<ProfileId, ProfileEntry>, active: ProfileId) {
    private val book = LinkedHashMap(entries)

    var active: ProfileId = active
        private set

    init {
        if (active !in book) book[active] = ProfileEntry(EventProfile(id = active), RaceRules())
    }

    val entries: Map<ProfileId, ProfileEntry> get() = LinkedHashMap(book)

    val current: ProfileEntry get() = book.getValue(active)

    /**
     * Passe à l'épreuve [id]. Nouvelle épreuve : règlement vide, sauf le circuit de la même série (qualification et
     * course d'une même épreuve se courent sur le même circuit).
     */
    fun select(id: ProfileId): ProfileEntry {
        active = id
        return book.getOrPut(id) {
            // Entraînement : le règlement de la Course Sport sert de modèle, jamais confirmé (conditions à vérifier).
            if (id.mode == SessionMode.SPORT_PRACTICE) {
                book[ProfileId(id.series, SessionKind.RACE, SessionMode.SPORT_OFFICIAL)]?.let { official ->
                    return@getOrPut ProfileEntry(practiceFrom(official.profile, id), official.rules)
                }
            }
            val sibling = book[ProfileId(id.series, if (id.kind == SessionKind.RACE) SessionKind.QUALIFYING else SessionKind.RACE)]
            val track = sibling?.rules?.trackId?.takeIf { id.series != Series.OTHER }
            ProfileEntry(EventProfile(id = id, gameVersion = sibling?.profile?.gameVersion), RaceRules(trackId = track))
        }
    }

    /**
     * Nouveau règlement de l'épreuve active : la confirmation tombe s'il a changé, et les champs modifiés deviennent
     * « saisis par toi ».
     */
    fun updateRules(rules: RaceRules): ProfileEntry {
        val old = current
        if (rules == old.rules) return old
        val changed = changedFields(old.rules, rules)
        var status = old.profile.ruleStatus
        if (status.isNotEmpty()) status = status + changed.associateWith { FieldStatus.PILOT }
        // Essai du 28/10 : la gomme de départ dite à la radio, ou le circuit reconnu par Pitwall, retiraient en silence la
        // confirmation du pilote. Ni l'une ni l'autre ne change le règlement confirmé : la confirmation reste.
        val keeps = changed.isEmpty() || (changed == setOf(RuleField.TRACK) && old.rules.trackId == null)
        return ProfileEntry(old.profile.copy(confirmed = old.profile.confirmed && keeps, ruleStatus = status), rules).also { book[active] = it }
    }

    /** Le pilote donne lui-même ces champs (même si la valeur ne change pas : « aucun arrêt obligatoire »). */
    fun declare(rules: RaceRules, fields: Set<RuleField>): ProfileEntry {
        val old = current
        val status = old.profile.ruleStatus + fields.associateWith { FieldStatus.PILOT }
        return ProfileEntry(old.profile.copy(confirmed = false, ruleStatus = status), rules).also { book[active] = it }
    }

    /**
     * Édition hebdomadaire publiée pour la série de l'épreuve [id] : appliquée si c'est une nouvelle semaine, ou si la
     * source a corrigé l'édition et que le pilote n'a pas encore confirmé. Rien de la semaine précédente n'est gardé.
     * Renvoie true si le règlement a changé.
     */
    fun applyEdition(id: ProfileId, edition: Edition, rules: (ProfileId) -> Pair<RaceRules, Map<RuleField, FieldStatus>>): Boolean {
        val old = book[id]
        val sameWeek = old?.profile?.editionId == edition.id
        if (sameWeek && (old!!.profile.confirmed || old.profile.editionFingerprint == edition.fingerprint)) return false
        val (published, publishedStatus) = rules(id)
        // Même semaine, fiche corrigée par la source : ce que le pilote a saisi lui-même est gardé (valeur et statut).
        // Une nouvelle semaine repart de la fiche publiée : le règlement change chaque semaine.
        val pilotFields = if (sameWeek) old!!.profile.ruleStatus.filterValues { it == FieldStatus.PILOT }.keys else emptySet()
        val r = pilotFields.fold(published) { acc, f -> keepField(acc, old!!.rules, f) }
        val status = publishedStatus + pilotFields.associateWith { FieldStatus.PILOT }
        val base = old?.profile ?: EventProfile(id = id)
        book[id] = ProfileEntry(
            base.copy(
                id = id,
                confirmed = false,
                eventDate = edition.validFrom.toString(),
                editionId = edition.id,
                editionFingerprint = edition.fingerprint,
                ruleStatus = status,
            ),
            r,
        )
        return true
    }

    private fun keepField(into: RaceRules, from: RaceRules, f: RuleField): RaceRules = when (f) {
        RuleField.TRACK -> into.copy(trackId = from.trackId)
        RuleField.LAPS -> into.copy(totalLaps = from.totalLaps)
        RuleField.FUEL_MULTIPLIER -> into.copy(fuelMultiplier = from.fuelMultiplier)
        RuleField.TYRE_MULTIPLIER -> into.copy(tyreMultiplier = from.tyreMultiplier)
        RuleField.MANDATORY_STOPS -> into.copy(mandatoryStops = from.mandatoryStops)
        RuleField.ALLOWED_TYRES -> into.copy(allowedTyres = from.allowedTyres, startTyre = from.startTyre)
        RuleField.REQUIRED_TYRES -> into.copy(requiredTyres = from.requiredTyres)
        RuleField.CARS, RuleField.BOP, RuleField.SETTINGS -> into
    }

    fun updateProfile(profile: EventProfile): ProfileEntry {
        val old = current
        // Version ou réglages changés : ce n'est plus le même contexte, le pilote doit reconfirmer.
        val changed = profile.gameVersion != old.profile.gameVersion || profile.setupNote != old.profile.setupNote
        val p = profile.copy(id = active, confirmed = profile.confirmed && !changed)
        return ProfileEntry(p, old.rules).also { book[active] = it }
    }

    /**
     * Le pilote confirme le règlement de l'épreuve active, à la date [today] (aaaa-mm-jj) : les champs probables
     * deviennent confirmés ; les champs inconnus le restent (il faut les saisir).
     */
    fun confirm(today: String): ProfileEntry {
        val old = current
        val status = old.profile.ruleStatus.mapValues { (_, s) -> if (s == FieldStatus.PROBABLE) FieldStatus.CONFIRMED else s }
        val date = old.profile.eventDate?.takeIf { old.profile.editionId != null } ?: today
        return old.copy(profile = old.profile.copy(confirmed = true, eventDate = date, ruleStatus = status)).also { book[active] = it }
    }

    companion object {
        /** Profil d'entraînement repris de l'officiel : les champs confirmés redeviennent probables. */
        fun practiceFrom(official: EventProfile, id: ProfileId): EventProfile = official.copy(
            id = id,
            confirmed = false,
            ruleStatus = official.ruleStatus.mapValues { (_, st) -> if (st == FieldStatus.CONFIRMED) FieldStatus.PROBABLE else st },
        )

        /** Champs du règlement qui diffèrent entre deux versions. */
        fun changedFields(a: RaceRules, b: RaceRules): Set<RuleField> = buildSet {
            if (a.trackId != b.trackId) add(RuleField.TRACK)
            if (a.totalLaps != b.totalLaps || a.durationMin != b.durationMin) add(RuleField.LAPS)
            if (a.fuelMultiplier != b.fuelMultiplier) add(RuleField.FUEL_MULTIPLIER)
            if (a.tyreMultiplier != b.tyreMultiplier) add(RuleField.TYRE_MULTIPLIER)
            if (a.mandatoryStops != b.mandatoryStops) add(RuleField.MANDATORY_STOPS)
            if (a.allowedTyres != b.allowedTyres) add(RuleField.ALLOWED_TYRES)
            if (a.requiredTyres != b.requiredTyres) add(RuleField.REQUIRED_TYRES)
        }

        /** Premier lancement après la mise à jour : l'ancien règlement unique devient celui du profil « Autre, course ». */
        fun migrate(legacyRules: RaceRules): ProfileBook =
            ProfileBook(mapOf(ProfileId.LEGACY to ProfileEntry(EventProfile(id = ProfileId.LEGACY), legacyRules)), ProfileId.LEGACY)
    }
}

/** Tout ce qui identifie une session pour la comparer à d'autres. */
data class SessionContext(
    val profile: EventProfile,
    val rules: RaceRules,
    /** Voiture d'après GT7 (null avant le premier paquet). */
    val carCode: Int?,
    /** Capacité du réservoir transmise par GT7 (unité de carburant de GT7). */
    val fuelCapacity: Float?,
    /** Nombre de tours annoncé par GT7 dans le paquet (null : inconnu, essai libre ou archive ancienne). */
    val gameTotalLaps: Int? = null,
) {
    /**
     * Règlement capturé au départ contredit par GT7 (issue #36-D2 : « 6 tours » déclarés, 14 annoncés et courus). Le
     * règlement de cette session n'est alors pas une preuve : ni durée, ni arrêts, ni gomme n'en sont appris.
     */
    val rulesContradiction: String?
        get() {
            val declared = rules.totalLaps ?: return null
            val game = gameTotalLaps ?: return null
            return if (declared != game) "règlement de $declared tours, GT7 en annonçait $game" else null
        }
}

/** Deux sessions sont-elles comparables ? Le niveau dit ce qu'on peut en réutiliser. */
enum class Comparability {
    /** Même épreuve, même voiture, même circuit, même règlement : tout est comparable (rythme, essence, stratégie). */
    SAME_EVENT,

    /** Même épreuve, voiture et circuit, multiplicateurs identiques, mais règlement de course différent (tours, arrêts) :
     *  consommation par tour et rythme comparables, pas la stratégie. */
    SAME_CAR_TRACK,

    INCOMPATIBLE,
}

object Compatibility {
    /** Niveau de comparaison entre deux sessions, et la raison quand elles sont incompatibles. */
    fun compare(a: SessionContext, b: SessionContext): Pair<Comparability, String?> {
        fun no(why: String) = Comparability.INCOMPATIBLE to why
        if (a.profile.id.series != b.profile.id.series) return no("épreuve différente")
        if (a.profile.id.kind != b.profile.id.kind) return no("qualification et course séparées")
        val track = a.rules.trackId ?: return no("circuit inconnu")
        if (track != b.rules.trackId) return no("circuit différent")
        val car = a.carCode ?: return no("voiture inconnue")
        if (car != b.carCode) return no("voiture différente")
        if (a.rules.fuelMultiplier != b.rules.fuelMultiplier) return no("multiplicateur de carburant différent")
        if (a.rules.tyreMultiplier != b.rules.tyreMultiplier) return no("multiplicateur d'usure différent")
        val va = a.profile.gameVersion?.trim()?.takeIf { it.isNotEmpty() }
        val vb = b.profile.gameVersion?.trim()?.takeIf { it.isNotEmpty() }
        if (va != null && vb != null && va != vb) return no("version de GT7 différente")
        // Règlement contredit par GT7 : jamais « même épreuve » (stratégie, arrêts, gomme), seulement voiture et circuit.
        if (a.rulesContradiction != null || b.rulesContradiction != null) return Comparability.SAME_CAR_TRACK to null
        val sameRace = a.rules.totalLaps == b.rules.totalLaps && a.rules.durationMin == b.rules.durationMin &&
            a.rules.mandatoryStops == b.rules.mandatoryStops && a.rules.requiredTyres == b.rules.requiredTyres
        return (if (sameRace) Comparability.SAME_EVENT else Comparability.SAME_CAR_TRACK) to null
    }
}
