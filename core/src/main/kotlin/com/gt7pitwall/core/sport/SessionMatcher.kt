package com.gt7pitwall.core.sport

import com.gt7pitwall.core.race.Series
import java.time.LocalDate

/**
 * Quelle course Sport de la semaine le pilote est-il en train de courir ? GT7 ne transmet pas l'épreuve (A, B ou C)
 * mais donne le nombre de tours ; le circuit est reconnu après un tour. On ne retient une épreuve que si au moins une
 * preuve la désigne (tours ou circuit identiques à la fiche publiée) et qu'aucune autre ne correspond aussi bien ;
 * une preuve contraire (tours ou circuit différents) l'exclut. Sinon : rien n'est choisi, l'état est dit.
 *
 * Course du 26/10 : profil B (6 tours) chargé pour une course GT7 de 14 tours ; avec cette règle, le profil B est exclu.
 */
object SessionMatcher {
    sealed interface Result {
        /** Une seule épreuve correspond ; [evidence] : ce qui la désigne (« 14 tours », « circuit »). */
        data class Match(val series: Series, val edition: Edition, val evidence: List<String>) : Result

        /** Plusieurs épreuves correspondent : il faut une preuve de plus (le circuit, après un tour). */
        data class Ambiguous(val candidates: List<Series>) : Result

        /** Des preuves existent et aucune épreuve de la semaine ne correspond : course hors programme Sport. */
        data class NoMatch(val reason: String) : Result

        /** Rien à comparer (pas de programme, nombre de tours inconnu, circuit pas encore reconnu). */
        data object Unknown : Result
    }

    /**
     * [gt7Laps] : tours annoncés par GT7 (null ou 0 si inconnu) ; [trackId] : circuit reconnu par la télémétrie, s'il
     * l'est ; [today] : date du jour (heure de Paris).
     */
    fun match(editions: Map<Series, Edition>, gt7Laps: Int?, trackId: Int?, today: LocalDate): Result {
        val laps = gt7Laps?.takeIf { it > 0 }
        val current = editions.filter { (s, e) -> s != Series.OTHER && e.covers(today) }
        if (current.isEmpty() || (laps == null && trackId == null)) return Result.Unknown
        val scored = current.mapNotNull { (series, ed) ->
            val edLaps = ed.laps.takeIf { it.known }?.value
            val evidence = mutableListOf<String>()
            if (laps != null && edLaps != null) {
                if (edLaps != laps) return@mapNotNull null
                evidence += "$laps tours"
            }
            if (trackId != null && ed.trackId != null) {
                if (ed.trackId != trackId) return@mapNotNull null
                evidence += "circuit"
            }
            Triple(series, ed, evidence)
        }
        if (scored.isEmpty()) {
            val what = listOfNotNull(laps?.let { "$it tours" }, trackId?.let { "ce circuit" }).joinToString(" sur ")
            return Result.NoMatch("aucune course Sport de la semaine avec $what")
        }
        if (scored.all { it.third.isEmpty() }) return Result.Unknown
        // Une seule épreuve compatible, désignée par au moins une preuve. Une autre épreuve non contredite (circuit
        // publié non reconnu, tours non publiés) reste possible : on ne tranche pas contre elle.
        val compatible = scored.map { it.first }
        if (scored.size == 1) return Result.Match(scored[0].first, scored[0].second, scored[0].third)
        return Result.Ambiguous(compatible)
    }
}
