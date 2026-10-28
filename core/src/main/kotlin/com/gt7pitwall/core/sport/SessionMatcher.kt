package com.gt7pitwall.core.sport

import com.gt7pitwall.core.race.Series
import java.time.LocalDate

/**
 * Quelle course Sport de la semaine le pilote est-il en train de courir ? GT7 ne transmet pas l'épreuve (A, B ou C) :
 * Pitwall compare ce que la PS5 mesure à ce que les fiches publiques annoncent.
 *
 * Trois preuves indépendantes :
 * - le nombre de tours annoncé par GT7 (dès la grille) ;
 * - la catégorie de la voiture (Gr.3, Gr.4, N…), d'après la base communautaire, si la fiche cite une catégorie ;
 * - le circuit reconnu par la télémétrie (après un tour).
 *
 * Une preuve contraire exclut l'épreuve. Une épreuve n'est retenue que si elle seule reste compatible. Elle est
 * « reconnue » seulement quand tours ET circuit concordent (et que la voiture ne la contredit pas) ; avec moins, elle
 * est « probable ». Même reconnue, rien ne prouve qu'il s'agit de la course officielle plutôt que d'un salon aux mêmes
 * réglages : c'est dit comme tel, jamais affirmé.
 *
 * Course du 26/10 : profil B (6 tours) chargé pour une course GT7 de 14 tours ; avec cette règle, le profil B est exclu.
 *
 * Issue #51 (Sport C SUGO, 28/10) : le circuit « reconnu » n'était que le nom publié par la fiche de l'épreuve déjà
 * choisie, compté comme une seconde preuve. Seul un circuit MESURÉ (géométrie X/Z) peut rendre une épreuve reconnue ;
 * une longueur de tour compatible ne fait qu'une épreuve probable (SUGO 3 586 m et Laguna Seca 3 602 m).
 */
object SessionMatcher {
    enum class Confidence(val label: String) {
        /** Tours et circuit concordent, la voiture ne contredit pas. */
        RECOGNIZED("reconnue"),

        /** Une seule épreuve compatible, mais pas encore toutes les preuves. */
        PROBABLE("probable"),
    }

    sealed interface Result {
        /** Une seule épreuve correspond ; [evidence] : ce qui la désigne (« 14 tours », « voiture Gr.3 », « circuit »). */
        data class Match(
            val series: Series,
            val edition: Edition,
            val evidence: List<String>,
            val confidence: Confidence = Confidence.PROBABLE,
        ) : Result

        /** Plusieurs épreuves correspondent : il faut une preuve de plus (le circuit, après un tour) ou le pilote. */
        data class Ambiguous(val candidates: List<Series>) : Result

        /** Des preuves existent et aucune épreuve de la semaine ne correspond : course hors programme Sport. */
        data class NoMatch(val reason: String) : Result

        /** Rien à comparer (pas de programme, nombre de tours inconnu, circuit pas encore reconnu). */
        data object Unknown : Result
    }

    /**
     * [gt7Laps] : tours annoncés par GT7 (null ou 0 si inconnu) ; [trackId] : circuit reconnu par la télémétrie, s'il
     * l'est ; [carGroup] : catégorie de la voiture d'après la base (« 3 », « N »…), si elle est connue ; [today] : date
     * du jour (heure de Paris).
     */
    fun match(
        editions: Map<Series, Edition>,
        gt7Laps: Int?,
        trackId: Int?,
        today: LocalDate,
        carGroup: String? = null,
        /** Le circuit vient d'une mesure indépendante (géométrie) ; faux : longueur compatible seulement. */
        trackMeasured: Boolean = true,
    ): Result {
        val laps = gt7Laps?.takeIf { it > 0 }
        val current = editions.filter { (s, e) -> s != Series.OTHER && e.covers(today) }
        if (current.isEmpty() || (laps == null && trackId == null && carGroup == null)) return Result.Unknown
        data class Scored(val series: Series, val edition: Edition, val evidence: List<String>, val laps: Boolean, val track: Boolean)
        val scored = current.mapNotNull { (series, ed) ->
            val edLaps = ed.laps.takeIf { it.known }?.value
            val evidence = mutableListOf<String>()
            var lapsOk = false
            var trackOk = false
            if (laps != null && edLaps != null) {
                if (edLaps != laps) return@mapNotNull null
                evidence += "$laps tours"
                lapsOk = true
            }
            val groups = ed.cars.takeIf { it.known }?.value?.let(SportNames::carGroups)
            if (carGroup != null && groups != null) {
                if (carGroup !in groups) return@mapNotNull null
                evidence += "voiture ${groupLabel(carGroup)}"
            }
            if (trackId != null && ed.trackId != null) {
                if (trackMeasured) {
                    if (ed.trackId != trackId) return@mapNotNull null
                    evidence += "circuit mesuré"
                    trackOk = true
                } else if (ed.trackId == trackId) {
                    // Longueur compatible : un indice, jamais une exclusion ni une reconnaissance (circuits voisins).
                    evidence += "longueur du tour compatible"
                }
            }
            Scored(series, ed, evidence, lapsOk, trackOk)
        }
        if (scored.isEmpty()) {
            val what = listOfNotNull(laps?.let { "$it tours" }, carGroup?.let { "une voiture ${groupLabel(it)}" }, trackId?.let { "ce circuit" })
                .joinToString(", ")
            return Result.NoMatch("aucune course Sport de la semaine avec $what")
        }
        if (scored.all { it.evidence.isEmpty() }) return Result.Unknown
        // Une seule épreuve compatible, désignée par au moins une preuve. Une autre épreuve non contredite (circuit
        // publié non reconnu, tours non publiés) reste possible : on ne tranche pas contre elle.
        if (scored.size == 1) {
            val s = scored[0]
            val confidence = if (s.laps && s.track) Confidence.RECOGNIZED else Confidence.PROBABLE
            return Result.Match(s.series, s.edition, s.evidence, confidence)
        }
        return Result.Ambiguous(scored.map { it.series })
    }

    fun groupLabel(group: String): String = if (group == "N") "de route (N)" else "Gr.$group"
}
