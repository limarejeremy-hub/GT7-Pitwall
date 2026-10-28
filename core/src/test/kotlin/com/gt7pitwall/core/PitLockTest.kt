package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #47 (Porsche 963, Fuji 10 tours, APK #473) : pendant le plein de l'arrêt du tour 6, « je viens de mettre des
 * pneus pluie » puis « oui » ont relancé le plan (« arrêt fin du tour 7 », « carburant critique ») alors que la voiture
 * se ravitaillait ; la durée fantôme de 2 min d'une course précédente restait dans le règlement actif.
 */
class PitLockTest {
    /** 10 tours, 15 unités par tour sur 100 : un arrêt autour du tour 6. */
    private fun fuji(): RaceSim = RaceSim(RaceController(), totalLaps = 10, perLap = 15f).also { it.start() }

    @Test fun nothingIsReplannedOrAnnouncedWhileTheCarIsBeingRefuelled() {
        val sim = fuji()
        sim.driveLaps(1)
        val stopLap = sim.controller.plan?.stops?.firstOrNull()?.endOfLap ?: error("pas d'arrêt prévu : ${sim.texts}")
        while (sim.lap < stopLap) sim.driveLaps(1)
        val duringStand = mutableListOf<String>()
        var mark = 0
        sim.pitStop(fuel = 57f, changeTyres = true) { tick ->
            if (tick == 60) {
                mark = sim.said.size
                sim.say("je viens de mettre des pneus pluie")
            }
            if (tick == 200) {
                sim.say("oui")
                duringStand += sim.texts.drop(mark)
            }
        }
        // Pendant le service : ni nouveau plan, ni nouvel arrêt, ni carburant critique.
        assertTrue(duringStand.none { it.startsWith("Nouveau plan") || it.startsWith("Stratégie") || "Carburant critique" in it }, duringStand.toString())
        assertTrue(sim.controller.journal.recent().any { it.text == "Plan gelé : passage au stand en cours" })
        // La gomme déclarée est bien prise en compte, et l'arrêt est compté une seule fois.
        sim.driveLaps(1)
        assertEquals(1, sim.controller.ledger.stopCount)
        assertEquals(TyreCompound.W, sim.controller.ledger.currentTyre(null))
        assertTrue(sim.controller.plan?.stops?.none { it.endOfLap == stopLap + 1 } != false, sim.controller.plan.toString())
    }

    @Test fun aRememberedDurationIsDiscardedWhenGt7AnnouncesLaps() {
        val storage = FakeStorage(RaceRules(durationMin = 2))
        val c = RaceController(storage = storage)
        assertEquals(2, c.rules.durationMin)
        val sim = RaceSim(c, totalLaps = 10, perLap = 3f)
        sim.start()
        sim.driveLaps(1)
        assertNull(c.rules.durationMin)
        assertNull(storage.rules.durationMin)
        assertTrue(c.journal.recent().any { "Durée mémorisée de 2 min écartée" in it.text })
    }

    @Test fun aStartTyreFromAPreviousRaceIsNotReused() {
        var now = 1_793_000_000_000L
        val c = RaceController(clock = { now })
        val first = RaceSim(c, totalLaps = 3, perLap = 3f, carCode = 3607)
        c.setRules(c.rules.copy(startTyre = TyreCompound.RH))
        now += 1_000
        first.start()
        first.driveLaps(3)
        now += 600_000
        // Nouvelle course (autre voiture) sans nouvelle déclaration : la gomme RH n'est pas reprise.
        val second = RaceSim(c, totalLaps = 10, perLap = 3f, carCode = 3600)
        second.start()
        assertNull(c.rules.startTyre)
        // Déclarée avant la course suivante (menus, grille) : gardée.
        now += 600_000
        c.setRules(c.rules.copy(startTyre = TyreCompound.RM))
        RaceSim(c, totalLaps = 5, perLap = 3f, carCode = 3436).start()
        assertEquals(TyreCompound.RM, c.rules.startTyre)
    }

    @Test fun askingWhenToPitGivesTheNextStopNotTheLapsLeft() {
        for (q in listOf("je rentre dans combien de tours", "dans combien de tours je rentre", "quand est-ce que je boxe", "je rentre quand au stand")) {
            assertEquals(DriverCommand.Ask(DriverQuery.PLAN), DriverCommandParser.parse(q), q)
        }
        // Les tours restants de la course restent une autre question.
        assertEquals(DriverCommand.Ask(DriverQuery.RACE_STATUS), DriverCommandParser.parse("combien de tours il reste"))
    }

    @Test fun aQuestionAboutRainNeverDeclaresAWetTrack() {
        val sim = fuji()
        sim.say("c'est normal que je glisse pleut")
        sim.say("est-ce normal que je glisse il pleut")
        assertFalse(sim.controller.engineer.raining)
        assertTrue(sim.texts.any { "GT7 ne me transmet pas la météo" in it }, sim.texts.takeLast(4).toString())
        sim.say("il pleut")
        assertTrue(sim.controller.engineer.raining)
    }
}
