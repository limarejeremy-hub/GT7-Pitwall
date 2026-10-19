package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.strategy.PlanWarning
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Retours d'essai : arrêt dit à la voix, plan répété, arrivée trop bavarde, appel au stand pour les pneus. */
class PitVoiceAndTyreCallTest {

    private val planAnnounce: (String) -> Boolean = { "Stratégie :" in it || it.startsWith("Nouveau plan") }

    @Test
    fun spokenPitStopsAreUnderstood() {
        val p = DriverCommandParser::parse
        val stops = listOf(
            "j'ai fait un arrêt aux stands", "j'ai fait un arrêt au stand", "je suis passé aux stands",
            "j'ai fait le plein", "arrêt fait", "je suis sorti des stands", "je me suis arrêté",
        )
        for (s in stops) assertEquals(DriverCommand.DeclareStop, p(s), s)
        // La reconnaissance vocale est limitée à une grammaire : chaque mot doit y figurer, sinon la phrase est inaudible.
        val vocabulary = DriverCommandParser.vocabulary("radio").toSet()
        for (s in stops + listOf(
            "quand est-ce que je rentre aux stands", "je dois faire le plein quand", "annule l'arrêt",
            "les pneus tiennent huit tours", "j'ai changé les pneus", "j'ai fait mon arrêt et changé les pneus",
        )) {
            val missing = s.split(' ').filter { it !in vocabulary }
            assertTrue(missing.isEmpty(), "« $s » : mots absents du vocabulaire $missing")
        }
        // Une question sur l'arrêt n'est pas une déclaration.
        assertEquals(DriverCommand.Ask(DriverQuery.PLAN), p("quand est-ce que je rentre aux stands"))
        assertEquals(DriverCommand.Ask(DriverQuery.PLAN), p("je dois faire le plein quand"))
        assertEquals(DriverCommand.CancelStop, p("annule l'arrêt"))
        assertEquals(DriverCommand.SetTyreStint(8), p("les pneus tiennent huit tours"))
        assertEquals(DriverCommand.TyresChanged, p("j'ai changé les pneus"))
        assertEquals(DriverCommand.StopAndTyres(DriverCommand.TyresChanged), p("j'ai fait mon arrêt et changé les pneus"))
    }

    @Test
    fun earlyStopDeclaredByVoiceUpdatesTheObligationsAndThePlan() {
        val c = RaceController()
        val s = RaceSim(c, totalLaps = 15, perLap = 2f)
        c.setRules(RaceRules(mandatoryStops = 1))
        s.start()
        s.driveLaps(2)
        val planned = c.plan!!.stops.single().endOfLap
        assertTrue(planned > 4, "arrêt prévu au tour $planned")

        // Le pilote s'arrête plus tôt que prévu, sans essence, puis le dit.
        s.say("j'ai fait un arrêt aux stands")
        assertEquals(StopEvidence.DRIVER_DECLARED, c.ledger.stopList.single().evidence)
        assertTrue("Arrêts obligatoires : 1 sur 1" in s.lastAnswer, s.lastAnswer)
        assertTrue(c.plan!!.stops.isEmpty())
        assertEquals("Nouveau plan : plus d'arrêt jusqu'à l'arrivée.", s.texts.last(planAnnounce))
    }

    @Test
    fun aRefuelThenTheSameStopSaidAtTheRadioIsCountedOnce() {
        val c = RaceController()
        val s = RaceSim(c, totalLaps = 15, perLap = 2f, fuel = 50f)
        c.setRules(RaceRules(mandatoryStops = 2))
        s.start()
        s.driveLaps(2)
        // 10 L en 6 s : moins de 0,05 L par paquet, la hausse doit être cumulée pour être vue.
        s.pitStop(fuel = 10f)
        assertEquals(StopEvidence.REFUEL, c.ledger.stopList.single().evidence)
        assertEquals(10f, c.ledger.stopList.single().fuelAdded!!, 0.2f)
        s.say("j'ai fait un arrêt aux stands")
        assertEquals(1, c.ledger.stopCount, "un seul passage")
        assertTrue("déjà enregistré" in s.lastAnswer, s.lastAnswer)
        // Le tour du ravitaillement n'entre pas dans la consommation mesurée.
        s.driveLaps(2)
        assertEquals(2f, c.fuel!!.perLap!!, 0.05f)
    }

    @Test
    fun planIsAnnouncedOnceAndTheFinishIsShort() {
        val c = RaceController()
        // 15 tours à 8 L avec 60 L à bord : un ravitaillement nécessaire.
        val s = RaceSim(c, totalLaps = 15, perLap = 8f, fuel = 60f)
        s.start()
        s.driveLaps(2)
        val stop = c.plan!!.stops.single()
        while (s.lap < stop.endOfLap) s.driveLaps(1)
        s.pitStop(fuel = stop.fuel!!.toFloat())
        s.driveLaps(14 - s.lap)
        s.driveLaps(1) // dernier tour commencé
        s.say("je suis cinquième")
        s.driveLaps(1)

        val texts = s.texts
        assertEquals(1, texts.count(planAnnounce), texts.filter(planAnnounce).toString())
        assertEquals(1, texts.count { it.startsWith("Box, box") })
        assertTrue(texts.none { it.startsWith("Tour 15 :") }, "pas de commentaire sur le dernier chrono")
        val finish = texts.last()
        assertTrue(Regex("Course terminée ! Meilleur tour : .+\\. Tu termines P5\\.").matches(finish), finish)
        assertFalse("Beau boulot" in finish)
    }

    @Test
    fun theEngineerCallsTheTyreChangeLikeAFuelStop() {
        val c = RaceController()
        val s = RaceSim(c, totalLaps = 15, perLap = 2f)
        s.start()
        s.say("les pneus tiennent huit tours")
        assertEquals(8, c.rules.tyreStintLaps)
        val stop = c.plan!!.stops.single()
        assertTrue(stop.changeTyres && !stop.forFuel && stop.endOfLap <= 8, stop.toString())

        while (s.lap < stop.endOfLap) s.driveLaps(1)
        val box = s.texts.last { it.startsWith("Box, box") }
        assertTrue("Change les pneus" in box && "pas besoin d'essence" in box, box)
        assertTrue(s.texts.any { it.startsWith("Stand au prochain tour") && "On changera les pneus" in it })

        // Arrêt sans essence : l'ingénieur demande confirmation, puis si les pneus ont été changés.
        s.pitStop(fuel = 0f)
        assertTrue(s.said.last().expectsReply && "effectué ton arrêt" in s.texts.last(), s.texts.last())
        s.say("oui")
        assertTrue("As-tu changé les pneus ?" in s.lastAnswer, s.lastAnswer)
        s.say("oui")
        assertTrue("pneus neufs" in s.lastAnswer, s.lastAnswer)
        assertEquals(0, c.ledger.tyreAge(s.lap))
        assertTrue(c.plan!!.stops.isEmpty(), c.plan.toString())
        assertFalse(PlanWarning.TYRES_OVERDUE in c.plan!!.warnings)
    }

    @Test
    fun keepingWornTyresTriggersAWarningAndANewTyreStop() {
        val c = RaceController()
        val s = RaceSim(c, totalLaps = 15, perLap = 2f)
        s.start()
        s.say("les pneus tiennent huit tours")
        val stop = c.plan!!.stops.single()
        while (s.lap < stop.endOfLap) s.driveLaps(1)
        s.pitStop(fuel = 0f)
        s.say("oui")
        s.say("non")
        assertTrue("mêmes pneus" in s.lastAnswer, s.lastAnswer)
        assertTrue(s.texts.any { "dépassé leur durée de vie" in it })
        // Nouvel arrêt pour les pneus dès la fin de ce tour.
        val next = c.plan!!.stops.first()
        assertTrue(next.changeTyres && next.endOfLap == s.lap, "${s.lap} $next")
    }
}
