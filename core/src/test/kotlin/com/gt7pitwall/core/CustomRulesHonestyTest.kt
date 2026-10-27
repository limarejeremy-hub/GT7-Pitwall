package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.race.SessionJournal
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.strategy.RaceRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #30-B, course du 27/10 (Suzuka, course personnalisée de 5 tours) : le journal présentait « arrets_obligatoires=1 »
 * à côté des tours GT7, comme si la PS5 l'avait transmis. La PS5 ne transmet que les tours ; le règlement mémorisé est
 * dit comme tel, une seule question courte est posée au départ, et il n'est jamais rangé en course Sport.
 */
class CustomRulesHonestyTest {
    private fun suzuka27(store: FakeStorage = FakeStorage(rules = RaceRules(totalLaps = 5, fuelMultiplier = 2, tyreMultiplier = 8, mandatoryStops = 1))): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = store)
        val s = RaceSim(c, totalLaps = 5, perLap = 3f)
        s.start()
        s.driveLaps(1)
        return c to s
    }

    @Test
    fun theStoredRuleIsAskedOnceAndNeverPresentedAsDetectedByThePs5() {
        val store = FakeStorage(rules = RaceRules(totalLaps = 5, fuelMultiplier = 2, tyreMultiplier = 8, mandatoryStops = 1))
        val (c, s) = suzuka27(store)
        assertEquals(SessionMode.CUSTOM, c.profile.id.mode, "une course personnalisée ne devient pas une course Sport")
        val asks = s.texts.filter { it.startsWith("Course personnalisée : j'ai en mémoire") }
        assertEquals(listOf("Course personnalisée : j'ai en mémoire 1 arrêt obligatoire. Toujours valable pour cette course ?"), asks)
        val start = SessionJournal.load(store, c.journal.sessionId!!).first { it.text.startsWith("Début de session") }
        assertEquals("5", start.details["tours_gt7"])
        assertEquals("règlement local mémorisé, non vérifié par la PS5", start.details["source_reglement"])
        s.driveLaps(1)
        assertEquals(1, s.texts.count { it.startsWith("Course personnalisée : j'ai en mémoire") }, "une seule question")
    }

    @Test
    fun answeringNoRemovesTheObligationAsADriverDeclaration() {
        val (c, s) = suzuka27()
        s.say("non")
        assertEquals("D'accord : pas d'arrêt obligatoire ni de gomme imposée pour cette course.", s.lastAnswer)
        assertEquals(0, c.rules.mandatoryStops)
        assertEquals(FieldStatus.PILOT, c.profile.ruleStatus[RuleField.MANDATORY_STOPS])
        assertTrue(c.plan?.stops.isNullOrEmpty())
    }

    @Test
    fun answeringYesKeepsItWithoutReserve() {
        val (c, s) = suzuka27()
        s.say("oui")
        assertEquals("Compris, je garde ce règlement pour cette course.", s.lastAnswer)
        assertEquals(null, c.regulationDoubt())
        assertEquals("règlement local confirmé par le pilote pour cette course", c.ruleSourceLabel())
    }

    @Test
    fun withoutAnAnswerTheStopIsCalledAsToBeConfirmed() {
        val (c, s) = suzuka27()
        assertEquals("règlement local mémorisé, non confirmé pour cette course", c.regulationDoubt())
        s.driveLaps(4)
        val box = s.texts.filter { it.startsWith("Box, box") }
        assertTrue(box.isNotEmpty(), s.texts.toString())
        assertTrue(box.all { "Obligation à confirmer : règlement local mémorisé, non confirmé pour cette course." in it }, box.toString())
    }

    @Test
    fun noObligationNoQuestion() {
        val c = RaceController(storage = FakeStorage(rules = RaceRules(totalLaps = 5, fuelMultiplier = 2)))
        val s = RaceSim(c, totalLaps = 5, perLap = 3f)
        s.start()
        s.driveLaps(2)
        assertFalse(s.texts.any { it.startsWith("Course personnalisée : j'ai en mémoire") })
    }
}
