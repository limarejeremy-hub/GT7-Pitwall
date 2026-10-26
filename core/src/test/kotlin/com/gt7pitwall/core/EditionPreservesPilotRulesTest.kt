package com.gt7pitwall.core

import com.gt7pitwall.core.race.ProfileBook
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.sport.Sourced
import com.gt7pitwall.core.sport.SportAutofill
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fiche hebdomadaire (GTPlanet ou GridStats) corrigée par la source : ce que le pilote a saisi n'est jamais écrasé. */
class EditionPreservesPilotRulesTest {
    private val id = ProfileId(Series.DAILY_B, SessionKind.RACE)
    private val src = SourceRef("source de test", null, 0L)

    private fun edition(start: LocalDate, laps: Int, stops: Int?) = Edition(
        series = Series.DAILY_B, validFrom = start, validTo = start.plusDays(7),
        trackName = Sourced("Circuit de test", FieldStatus.PROBABLE, listOf(src)), trackId = null,
        laps = Sourced(laps, FieldStatus.PROBABLE, listOf(src)),
        cars = Sourced.unknown(), bop = Sourced.unknown(), settings = Sourced.unknown(), startType = Sourced.unknown(),
        allowedTyres = Sourced.unknown(), requiredTyres = Sourced.unknown(),
        mandatoryStops = stops?.let { Sourced(it, FieldStatus.PROBABLE, listOf(src)) } ?: Sourced.unknown(),
        fuelMultiplier = Sourced.unknown(), tyreMultiplier = Sourced.unknown(), sources = listOf(src),
    )

    @Test
    fun aSourceCorrectionKeepsWhatTheDriverEnteredThatWeek() {
        val book = ProfileBook(emptyMap(), id)
        val monday = LocalDate.of(2026, 10, 26)
        book.applyEdition(id, edition(monday, 14, null)) { SportAutofill.rules(edition(monday, 14, null), it.kind) }
        // Le pilote saisit « aucun arrêt obligatoire ».
        book.declare(book.current.rules.copy(mandatoryStops = 0), setOf(RuleField.MANDATORY_STOPS))
        // La source corrige la fiche dans la semaine : 12 tours, un arrêt obligatoire.
        val fixed = edition(monday, 12, 1)
        book.applyEdition(id, fixed) { SportAutofill.rules(fixed, it.kind) }
        assertEquals(12, book.current.rules.totalLaps, "les champs non saisis suivent la correction")
        assertEquals(0, book.current.rules.mandatoryStops, "la saisie du pilote est gardée")
        assertEquals(FieldStatus.PILOT, book.current.profile.ruleStatus[RuleField.MANDATORY_STOPS])

        // Nouvelle semaine : nouveau règlement publié, la saisie de la semaine passée ne s'applique plus.
        val next = edition(monday.plusDays(7), 10, 1)
        book.applyEdition(id, next) { SportAutofill.rules(next, it.kind) }
        assertEquals(1, book.current.rules.mandatoryStops)
        assertEquals(FieldStatus.PROBABLE, book.current.profile.ruleStatus[RuleField.MANDATORY_STOPS])
    }

    @Test
    fun aConfirmedProfileIsNeverOverwrittenInTheSameWeek() {
        val book = ProfileBook(emptyMap(), id)
        val monday = LocalDate.of(2026, 10, 26)
        val first = edition(monday, 14, 0)
        book.applyEdition(id, first) { SportAutofill.rules(first, it.kind) }
        book.confirm("2026-10-26")
        val fixed = edition(monday, 12, 1)
        assertEquals(false, book.applyEdition(id, fixed) { SportAutofill.rules(fixed, it.kind) })
        assertEquals(14, book.current.rules.totalLaps)
    }
}
