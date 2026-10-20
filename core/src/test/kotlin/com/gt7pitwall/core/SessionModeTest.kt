package com.gt7pitwall.core

import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.HistoryRelation
import com.gt7pitwall.core.race.HistorySummary
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.ProfileBook
import com.gt7pitwall.core.race.ProfileEntry
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.HistoryModel
import com.gt7pitwall.core.strategy.RaceRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** D0 : Sport officiel, entraînement Sport, course personnalisée et chrono ne se confondent jamais. */
class SessionModeTest {
    private val official = ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_B)
    private val practice = ProfileId.of(SessionMode.SPORT_PRACTICE, Series.DAILY_B)
    private val rules = RaceRules(totalLaps = 12, trackId = 462)

    private fun ctx(id: ProfileId, r: RaceRules = rules) = SessionContext(EventProfile(id = id), r, 3607, 100f)

    private fun session(n: Int, ctx: SessionContext): SessionRecord {
        val rnd = Random(n)
        val laps = (1..12).map { LapEntry(it, 100_000 + rnd.nextInt(-300, 300), (6.0 * (1 + rnd.nextDouble(-0.02, 0.02))).toFloat(), false) }
        return SessionRecord("s$n-${ctx.profile.id.code}", ctx, n.toLong(), n.toLong(), SessionOutcome.FINISHED, laps)
    }

    @Test
    fun modesAreDistinctProfilesWithConsistentKinds() {
        assertEquals(ProfileId(Series.OTHER, SessionKind.RACE, SessionMode.CUSTOM), ProfileId.of(SessionMode.CUSTOM, Series.DAILY_A))
        assertEquals(SessionKind.QUALIFYING, ProfileId.of(SessionMode.TIME_TRIAL, Series.DAILY_C).kind)
        assertEquals(SessionKind.RACE, practice.kind)
        assertFalse(official == practice)
        assertEquals("Entraînement Sport B", practice.label)
        assertEquals("DAILY_B/RACE/SPORT_PRACTICE", practice.code)
        assertEquals(practice, ProfileId.parse(practice.code))
        // Ancien code sans type de session : type non précisé (une session ancienne peut avoir été courue hors ligne).
        assertEquals(SessionMode.UNVERIFIED, ProfileId.parse("DAILY_B/RACE")!!.mode)
        assertEquals(official, ProfileId.parse("DAILY_B/RACE")!!.normalized())
    }

    @Test
    fun practiceTakesTheSportRulesAsAnUnconfirmedTemplate() {
        val confirmed = EventProfile(
            id = official,
            confirmed = true,
            ruleStatus = mapOf(RuleField.LAPS to FieldStatus.CONFIRMED, RuleField.TRACK to FieldStatus.CONFIRMED),
        )
        val book = ProfileBook(mapOf(official to ProfileEntry(confirmed, rules)), official)
        val p = book.select(practice)
        assertEquals(rules, p.rules)
        assertFalse(p.profile.confirmed)
        assertEquals(FieldStatus.PROBABLE, p.profile.ruleStatus[RuleField.LAPS])
        // L'officiel n'est pas touché.
        assertTrue(book.entries.getValue(official).profile.confirmed)
    }

    @Test
    fun theSelectedModeIsRememberedAndArchivedAsIs() {
        val store = FakeStorage()
        val c = RaceController(storage = store)
        c.selectProfile(practice)
        c.setRules(rules)
        val sim = RaceSim(c, totalLaps = 12, perLap = 2f)
        sim.start()
        sim.driveLaps(3)
        // Mémorisé (profils sauvegardés) et relu au redémarrage.
        assertEquals(practice, RaceJson.book(store.profilesJson!!)!!.active)
        assertEquals(practice, RaceController(storage = store).profile.id)
        // Archivé comme entraînement, jamais comme Course Sport officielle.
        val archived = store.sessionsJson.values.mapNotNull(RaceJson::session)
        assertTrue(archived.isNotEmpty())
        assertTrue(archived.all { it.context.profile.id.mode == SessionMode.SPORT_PRACTICE }, archived.map { it.context.profile.id }.toString())
    }

    @Test
    fun practiceHistoryIsUsedForOfficialOnlyWhenIdenticalAndNeverAsReliable() {
        val practiceRaces = (1..5).map { session(it, ctx(practice)) }
        // Identique (règlement, voiture, circuit, multiplicateurs) : retenu, mais au plus « provisoire ».
        val m = HistoryModel.learn(ctx(official), practiceRaces)
        assertEquals(5, m.sessionsUsed)
        assertEquals(5, m.relatedUsed)
        assertEquals(Confidence.PROVISIONAL, m.fuelPerLap!!.confidence, "cinq entraînements ne valent pas une Course Sport vérifiée")
        // Les mêmes cinq courses en Sport officiel : relativement fiable.
        val officialRaces = (1..5).map { session(it, ctx(official)) }
        assertEquals(Confidence.FAIRLY_RELIABLE, HistoryModel.learn(ctx(official), officialRaces).fuelPerLap!!.confidence)

        // Règlement différent (tours) : un entraînement n'est plus réutilisé du tout.
        val other = (1..5).map { session(it, ctx(practice, rules.copy(totalLaps = 8))) }
        val m2 = HistoryModel.learn(ctx(official), other)
        assertEquals(0, m2.sessionsUsed)
        assertEquals(5, m2.sessionsExcluded)
        assertNull(m2.fuelPerLap)

        val summary = HistorySummary.of(ctx(official), practiceRaces + other)
        assertEquals(5, summary.related)
        assertEquals(0, summary.sameProfile)
    }

    @Test
    fun customAndTimeTrialNeverMixWithSport() {
        val custom = ProfileId.of(SessionMode.CUSTOM, Series.OTHER)
        val trial = ProfileId.of(SessionMode.TIME_TRIAL, Series.DAILY_B)
        assertEquals(HistoryRelation.NONE, HistoryRelation.of(official, custom))
        assertEquals(HistoryRelation.NONE, HistoryRelation.of(official, trial))
        assertEquals(HistoryRelation.NONE, HistoryRelation.of(custom, ProfileId.parse("OTHER/RACE")!!), "personnalisée : jamais d'ancienne session")
        assertEquals(HistoryRelation.RELATED, HistoryRelation.of(official, ProfileId.parse("DAILY_B/RACE")!!))
        val m = HistoryModel.learn(ctx(official), (1..3).map { session(it, ctx(custom, rules)) })
        assertEquals(0, m.sessionsUsed)
    }

    @Test
    fun timeTrialDoesNotReadTheRacePosition() {
        val c = RaceController(storage = FakeStorage())
        c.selectProfile(ProfileId.of(SessionMode.TIME_TRIAL, Series.DAILY_A))
        val sim = RaceSim(c, totalLaps = 5, perLap = 2f)
        sim.start()
        assertFalse(c.positionTracker.enabled)
        assertNotNull(c.profile)
    }
}
