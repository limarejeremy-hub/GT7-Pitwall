package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Passages aux stands : uniquement sur preuve, jamais sur un simple arrêt à 0 km/h. */
class PitDetectionTest {

    private fun sim(rules: RaceRules = RaceRules(mandatoryStops = 1)) =
        RaceSim(RaceController(storage = FakeStorage(rules)), totalLaps = 10).apply {
            start()
            driveLaps(2) // la trajectoire d'un tour complet est connue
        }

    @Test
    fun realRefuelIsCountedOnceEvenAcrossTheLine() {
        val s = sim()
        s.fuel = 50f // assez de place dans le réservoir pour 30 L
        s.pitStop(liters = 30f)
        s.driveLaps(1)
        val stops = s.controller.ledger.stopList
        assertEquals(1, stops.size, s.texts.toString())
        assertEquals(StopEvidence.REFUEL, stops[0].evidence)
        assertEquals(30f, stops[0].litersAdded!!, 0.5f)
        assertTrue(s.texts.any { it.startsWith("Arrêt 1 enregistré : ravitaillement de 30 litres. Arrêts obligatoires : 1 sur 1.") }, s.texts.toString())
    }

    @Test
    fun twoSeparatePitStopsAreTwoStops() {
        val s = sim(RaceRules(mandatoryStops = 2))
        s.pitStop(liters = 20f)
        s.driveLaps(2)
        s.pitStop(liters = 20f)
        s.driveLaps(1)
        assertEquals(2, s.controller.ledger.stopCount)
        assertEquals(listOf(1, 2), s.controller.ledger.stopList.map { it.number })
    }

    @Test
    fun stopWithoutRefuelAsksConfirmationAndCountsOnlyOnYes() {
        val s = sim()
        s.pitStop(liters = 0f, stopSeconds = 8f) // changement de pneus seul
        assertEquals(0, s.controller.ledger.stopCount)
        val q = s.said.last { "Tu as bien effectué ton arrêt" in it.text }
        assertEquals(Priority.CRITICAL, q.priority)
        assertTrue(q.expectsReply)
        s.say("oui")
        assertEquals(1, s.controller.ledger.stopCount)
        assertEquals(StopEvidence.DRIVER_CONFIRMED, s.controller.ledger.stopList[0].evidence)
        assertTrue(s.texts.any { it.startsWith("Arrêt 1 enregistré : confirmé par toi.") }, s.texts.toString())
    }

    @Test
    fun driverCanRejectAPossibleStop() {
        val s = sim()
        s.pitStop(liters = 0f, stopSeconds = 8f)
        s.say("non")
        assertEquals(0, s.controller.ledger.stopCount)
        assertNull(s.controller.ledger.pending)
        assertTrue(s.lastAnswer.contains("je ne compte pas cet arrêt"))
    }

    @Test
    fun crashIsNeverAPitStop() {
        val s = sim()
        s.crash()
        s.driveLaps(1)
        assertEquals(0, s.controller.ledger.stopCount)
        assertNull(s.controller.ledger.pending)
        assertTrue(s.texts.none { "Tu as bien effectué ton arrêt" in it }, s.texts.toString())
        assertTrue(s.texts.any { it.startsWith("Arrêt en piste : je ne le compte pas") })
    }

    @Test
    fun slowStopOnTrackIsNotAPitStop() {
        val s = sim()
        s.stopOnTrack()
        s.driveLaps(1)
        assertEquals(0, s.controller.ledger.stopCount)
        assertTrue(s.texts.none { "Tu as bien effectué ton arrêt" in it })
    }

    @Test
    fun slowStopOnTheRacingLineNearTheFinishIsNotAPitStop() {
        val s = sim()
        s.slowStopNearLineOnRacingLine()
        assertEquals(0, s.controller.ledger.stopCount)
        assertNull(s.controller.ledger.pending)
    }

    @Test
    fun gridAndStartAreNotStops() {
        val s = RaceSim(RaceController(storage = FakeStorage(RaceRules(mandatoryStops = 1))))
        s.stand(10f) // longue attente sur la grille
        s.start()
        s.driveLaps(1)
        assertEquals(0, s.controller.ledger.stopCount)
        assertNull(s.controller.ledger.pending)
    }

    @Test
    fun withoutObligationsAStopWithoutFuelIsNotQuestioned() {
        val s = sim(RaceRules())
        s.pitStop(liters = 0f, stopSeconds = 8f)
        assertNull(s.controller.ledger.pending)
        assertTrue(s.texts.none { "Tu as bien effectué" in it })
    }

    @Test
    fun declareAndCancelByVoice() {
        val s = sim()
        s.say("j'ai fait mon arrêt")
        assertEquals(StopEvidence.DRIVER_DECLARED, s.controller.ledger.stopList.single().evidence)
        s.say("annule l'arrêt")
        assertEquals(0, s.controller.ledger.stopCount)
        assertTrue(s.lastAnswer.startsWith("Arrêt 1 retiré"))
        s.say("annule l'arrêt")
        assertTrue(s.lastAnswer.startsWith("Aucun arrêt à retirer"))
    }

    @Test
    fun refuelDoesNotValidateTyreObligations() {
        val rules = RaceRules(
            mandatoryStops = 1,
            allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM),
            requiredTyres = setOf(TyreCompound.RH, TyreCompound.RM),
            startTyre = TyreCompound.RM,
        )
        val s = sim(rules)
        s.pitStop(liters = 25f)
        assertEquals(1, s.controller.ledger.stopCount)
        assertEquals(setOf(TyreCompound.RH), s.controller.missingTyres)
        val stopMsg = s.said.last { it.text.startsWith("Arrêt 1 enregistré") }
        assertTrue("Quels pneus as-tu montés" in stopMsg.text, stopMsg.text)
        assertTrue(stopMsg.expectsReply)
        val status = s.controller.obligationsSentence()
        assertTrue("il manque course durs" in status, status)
        assertFalse("respect" in status)

        s.say("j'ai mis les durs") // famille déduite : seules les gommes course sont autorisées
        assertTrue(s.controller.missingTyres.isEmpty())
        assertTrue(s.lastAnswer.contains("Gommes imposées : toutes déclarées"), s.lastAnswer)
        assertFalse(s.controller.obligationsSentence().contains("respect"))
    }

    @Test
    fun ambiguousTyreNameIsNotGuessed() {
        val s = sim(RaceRules(requiredTyres = setOf(TyreCompound.RH, TyreCompound.SH)))
        s.say("j'ai mis les durs")
        assertTrue(s.lastAnswer.startsWith("Précise la gomme"), s.lastAnswer)
        assertTrue(s.controller.ledger.changes.isEmpty())
    }

    @Test
    fun answerOfConfirmationIsCategorisedAsAnswer() {
        val s = sim()
        s.pitStop(liters = 0f, stopSeconds = 8f)
        s.say("oui")
        assertEquals(MessageCategory.ANSWER, s.said.last().category)
        assertNotNull(s.controller.ledger.stopList.singleOrNull())
    }
}
