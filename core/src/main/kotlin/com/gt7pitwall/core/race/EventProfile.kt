package com.gt7pitwall.core.race

import com.gt7pitwall.core.strategy.RaceRules

/** Épreuve Sport de GT7 : courses quotidiennes A, B, C, ou autre (hors Sport, essais). */
enum class Series(val label: String) {
    DAILY_A("Course Sport A"),
    DAILY_B("Course Sport B"),
    DAILY_C("Course Sport C"),
    OTHER("Autre épreuve"),
}

/** Qualification et course ne se mélangent jamais : rythme, essence et pneus n'y ont pas le même sens. */
enum class SessionKind(val label: String) {
    QUALIFYING("Qualification"),
    RACE("Course"),
}

/** Identifiant d'un profil d'épreuve : une série × qualification ou course. */
data class ProfileId(val series: Series, val kind: SessionKind) {
    val label: String get() = "${series.label}, ${kind.label.lowercase()}"
    val code: String get() = "${series.name}/${kind.name}"

    companion object {
        /** Profil où sont rangés le règlement et la mémoire d'avant les profils (migration sans perte). */
        val LEGACY = ProfileId(Series.OTHER, SessionKind.RACE)

        fun parse(code: String?): ProfileId? {
            val parts = code?.split('/') ?: return null
            if (parts.size != 2) return null
            val s = Series.entries.firstOrNull { it.name == parts[0] } ?: return null
            val k = SessionKind.entries.firstOrNull { it.name == parts[1] } ?: return null
            return ProfileId(s, k)
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
    /** Date de l'épreuve (aaaa-mm-jj), fixée à la confirmation du règlement. */
    val eventDate: String? = null,
    val confirmed: Boolean = false,
)

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
            val sibling = book[ProfileId(id.series, if (id.kind == SessionKind.RACE) SessionKind.QUALIFYING else SessionKind.RACE)]
            val track = sibling?.rules?.trackId?.takeIf { id.series != Series.OTHER }
            ProfileEntry(EventProfile(id = id, gameVersion = sibling?.profile?.gameVersion), RaceRules(trackId = track))
        }
    }

    /** Nouveau règlement de l'épreuve active : la confirmation tombe s'il a changé. */
    fun updateRules(rules: RaceRules): ProfileEntry {
        val old = current
        val profile = if (rules != old.rules) old.profile.copy(confirmed = false) else old.profile
        return ProfileEntry(profile, rules).also { book[active] = it }
    }

    fun updateProfile(profile: EventProfile): ProfileEntry {
        val old = current
        // Version ou réglages changés : ce n'est plus le même contexte, le pilote doit reconfirmer.
        val changed = profile.gameVersion != old.profile.gameVersion || profile.setupNote != old.profile.setupNote
        val p = profile.copy(id = active, confirmed = profile.confirmed && !changed)
        return ProfileEntry(p, old.rules).also { book[active] = it }
    }

    /** Le pilote confirme le règlement de l'épreuve active, à la date [today] (aaaa-mm-jj). */
    fun confirm(today: String): ProfileEntry {
        val old = current
        return old.copy(profile = old.profile.copy(confirmed = true, eventDate = today)).also { book[active] = it }
    }

    companion object {
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
)

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
        val sameRace = a.rules.totalLaps == b.rules.totalLaps && a.rules.durationMin == b.rules.durationMin &&
            a.rules.mandatoryStops == b.rules.mandatoryStops && a.rules.requiredTyres == b.rules.requiredTyres
        return (if (sameRace) Comparability.SAME_EVENT else Comparability.SAME_CAR_TRACK) to null
    }
}
