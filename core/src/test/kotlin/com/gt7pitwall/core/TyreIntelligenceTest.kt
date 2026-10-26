package com.gt7pitwall.core

import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.session.PitAssessment
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreService
import com.gt7pitwall.core.tyres.ThermalPhase
import com.gt7pitwall.core.tyres.ThermalSample
import com.gt7pitwall.core.tyres.TyreChangeHunter
import com.gt7pitwall.core.tyres.TyreChangeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tyre Intelligence V2 : détection du changement de pneus (Tyre Change Hunter) et âge du train de pneus. Le diagnostic
 * thermique ne fait que suspecter : seul le pilote confirme, et l'âge ne repart de zéro qu'après sa confirmation.
 */
class TyreIntelligenceTest {

    private fun race(laps: Int = 10, storage: FakeStorage = FakeStorage()): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = storage)
        return c to RaceSim(c, totalLaps = laps, perLap = 9f, fuel = 100f)
    }

    private fun RaceSim.count(fragment: String) = texts.count { fragment in it }

    /** Messages du dernier arrêt (la question sur les pneus peut être suivie de l'annonce du plan). */
    private fun RaceSim.recent() = texts.takeLast(4)

    /** 1. Ravitaillement sans changement de pneus : l'âge du train est conservé. */
    @Test
    fun refuelWithoutTyreChangeKeepsTheTyreAge() {
        val (c, s) = race()
        s.start()
        s.driveLaps(3)
        s.pitStop(fuel = 30f)
        val stop = c.ledger.stopList.single()
        assertEquals(StopEvidence.REFUEL, stop.evidence)
        assertEquals(TyreChangeVerdict.UNDETERMINED.name, stop.thermal, "températures stables : rien de suspecté")
        assertTrue(s.recent().any { it.endsWith("As-tu changé les pneus ?") }, s.recent().toString())
        // Avant la réponse, rien n'a bougé : l'âge suit les tours bouclés depuis le départ.
        assertEquals(s.lap - 1, c.ledger.tyreAge(s.lap))
        s.say("non")
        assertEquals("Plein terminé, on conserve le même train de pneus.", s.lastAnswer)
        assertEquals(TyreService.NOT_CHANGED, c.ledger.stopList.single().tyres)
        s.driveLaps(2)
        assertTrue(c.ledger.changes.isEmpty())
        assertEquals(s.lap - 1, c.ledger.tyreAge(s.lap), "le relais continue depuis le départ")
        assertTrue(c.ledger.tyreStint(s.lap, null).certain, "âge certain : le pilote a répondu")
    }

    /** 2. Ravitaillement avec changement : suspecté par les températures, confirmé par le pilote, âge à zéro. */
    @Test
    fun refuelWithConfirmedTyreChangeResetsTheAge() {
        val (c, s) = race()
        s.start()
        s.driveLaps(3)
        val ageBefore = c.ledger.tyreAge(s.lap)
        s.pitStop(fuel = 30f, changeTyres = true)
        val stop = c.ledger.stopList.single()
        assertEquals(TyreChangeVerdict.SUSPECTED.name, stop.thermal)
        assertTrue(s.recent().any { it.endsWith("Les quatre pneus ont changé de température d'un coup : pneus neufs ?") }, s.recent().toString())
        // Suspecté n'est pas confirmé : l'âge n'est pas remis à zéro sans le pilote.
        assertTrue(c.ledger.changes.isEmpty())
        assertTrue(c.ledger.tyreAge(s.lap) >= ageBefore)
        s.say("oui")
        assertTrue(s.lastAnswer.startsWith("Pneus neufs confirmés : je repars sur un nouveau relais."), s.lastAnswer)
        assertTrue(s.lastAnswer.endsWith("Tu as monté quelle gomme ?"), s.lastAnswer)
        assertEquals(0, c.ledger.tyreAge(s.lap))
        assertEquals(TyreService.CHANGED, c.ledger.stopList.single().tyres)
        s.driveLaps(2)
        assertEquals(2, c.ledger.tyreAge(s.lap))
    }

    /** 3. Changement de pneus sans ravitaillement : proposé grâce à la signature thermique, compté sur le « oui ». */
    @Test
    fun tyreChangeWithoutRefuelIsProposedThenConfirmed() {
        val (c, s) = race()
        s.start()
        s.driveLaps(3)
        s.pitStop(fuel = 0f, changeTyres = true)
        val ask = s.said.last()
        assertTrue(ask.expectsReply && "sans ravitaillement, et les quatre pneus ont changé de température d'un coup. Tu as changé les pneus ?" in ask.text, ask.text)
        assertEquals(0, c.ledger.stopCount, "rien n'est enregistré sans le pilote")
        assertTrue(c.ledger.changes.isEmpty())
        s.say("oui")
        assertEquals(StopEvidence.DRIVER_CONFIRMED, c.ledger.stopList.single().evidence)
        assertTrue("Pneus neufs confirmés" in s.lastAnswer, s.lastAnswer)
        assertEquals(0, c.ledger.tyreAge(s.lap))

        // Même arrêt sans essence, mais températures stables et aucune obligation : rien n'est proposé.
        val (c2, s2) = race()
        s2.start()
        s2.driveLaps(3)
        val before = s2.said.size
        s2.pitStop(fuel = 0f)
        assertTrue(s2.said.drop(before).none { it.expectsReply }, s2.texts.drop(before).toString())
        assertEquals(0, c2.ledger.stopCount)
    }

    /** Course réelle 26/10 : arrêt sans essence T7, puis « je suis en medium » au tour suivant. */
    @Test
    fun aPendingNoRefuelStopMakesOldTyresUncertainUntilPilotNamesFreshCompound() {
        val (c, s) = race(laps = 14)
        c.setRules(c.rules.copy(startTyre = com.gt7pitwall.core.strategy.TyreCompound.RH))
        s.start()
        s.driveLaps(6)
        s.pitStop(
            fuel = 0f,
            changeTyres = true,
            freshTemps = floatArrayOf(60f, 60f, 60f, 60f),
        )
        assertEquals(0, c.ledger.stopCount, "la signature thermique seule ne doit pas confirmer l'arrêt")
        val candidate = c.ledger.pending
        assertTrue(candidate != null, "un arrêt probable existe en attente du pilote")
        assertFalse(c.ledger.tyreStint(s.lap, c.rules.startTyre).certain,
            "les RH ne doivent plus être présentés comme certains après l'arrêt suspecté")
        val effectiveLap = candidate.lap + 1
        s.say("je suis en medium")
        assertEquals(0, c.ledger.stopCount, "la famille des mediums nécessite la confirmation du pilote")
        assertTrue("Tu confirmes des pneus course mediums" in s.lastAnswer, s.lastAnswer)
        s.say("oui")
        assertEquals(1, c.ledger.stopCount)
        assertEquals(StopEvidence.DRIVER_CONFIRMED, c.ledger.stopList.single().evidence)
        assertEquals(TyreService.CHANGED, c.ledger.stopList.single().tyres)
        assertEquals(com.gt7pitwall.core.strategy.TyreCompound.RM,
            c.ledger.currentTyre(c.rules.startTyre))
        assertEquals(com.gt7pitwall.core.strategy.TyreCompound.RH, c.rules.startTyre,
            "les pneus de départ ne doivent pas être réécrits")
        assertEquals(effectiveLap, c.ledger.changes.single().lap,
            "le nouveau train commence juste après l'arrêt, pas à la déclaration vocale")
        assertTrue(c.ledger.tyreStint(s.lap, c.rules.startTyre).certain)
    }

    /** 4. Signature thermique hors des stands (en roulant, ou arrêté en piste) : aucun faux changement. */
    @Test
    fun aThermalSignatureOutsideThePitsNeverMeansATyreChange() {
        val (c, s) = race()
        s.start()
        s.driveLaps(2)
        s.driveTo(1.5f)
        s.tyreTemps = floatArrayOf(50f, 50f, 48f, 48f) // chute brutale en roulant (pluie, flaque)
        s.driveLaps(1)
        s.tyreTemps = floatArrayOf(82f, 84f, 78f, 79f)
        // Arrêt en piste avec la même chute pendant l'immobilisation.
        s.driveTo(3f)
        repeat(360) { s.step(80f - it * 0.22f) }
        s.stand(8f) { if (it == 240) s.tyreTemps = floatArrayOf(45f, 46f, 44f, 44f) }
        repeat(240) { s.step(it / 3f) }
        s.driveLaps(1)
        assertTrue(c.ledger.changes.isEmpty())
        assertEquals(0, c.ledger.stopCount)
        assertEquals(s.lap - 1, c.ledger.tyreAge(s.lap))
        assertTrue(s.texts.none { "pneus neufs" in it.lowercase() || "changé de température" in it }, s.texts.toString())
        // Un arrêt en piste n'est jamais analysé comme un changement.
        assertTrue(c.journal.recent().none { it.details["hunter_verdict"] == TyreChangeVerdict.SUSPECTED.name })
    }

    /** 5. Températures aberrantes momentanées pendant un arrêt : aucun faux positif. */
    @Test
    fun momentaryAberrantTemperaturesAreNotATyreChange() {
        // Analyse pure : un pic d'une seconde, des valeurs absurdes (capteur non initialisé, chargement).
        fun series(vararg t: Float) = t.mapIndexed { i, v ->
            ThermalSample(i, if (i < 5) ThermalPhase.BEFORE else ThermalPhase.STOP, listOf(v, v + 2, v - 3, v - 2), 0f, 50f)
        }
        val spike = series(82f, 82f, 82f, 82f, 82f, 82f, 82f, 40f, 82f, 82f, 82f, 82f)
        assertEquals(TyreChangeVerdict.UNDETERMINED, TyreChangeHunter.analyze(4, PitAssessment.REFUEL, spike).verdict)
        val garbage = series(82f, 82f, 82f, 82f, 82f, 82f, 999f, -400f, Float.NaN, 82f, 82f, 82f)
        assertEquals(TyreChangeVerdict.UNDETERMINED, TyreChangeHunter.analyze(4, PitAssessment.REFUEL, garbage).verdict)
        val small = series(82f, 82f, 82f, 82f, 82f, 82f, 82f, 75f, 75f, 75f, 75f, 75f)
        val r = TyreChangeHunter.analyze(4, PitAssessment.REFUEL, small)
        assertEquals(TyreChangeVerdict.UNDETERMINED, r.verdict, "7 °C : sous le seuil expérimental")
        // Témoin : une vraie chute durable des quatre pneus est suspectée.
        val real = series(82f, 82f, 82f, 82f, 82f, 82f, 82f, 55f, 55f, 56f, 56f, 57f)
        assertEquals(TyreChangeVerdict.SUSPECTED, TyreChangeHunter.analyze(4, PitAssessment.REFUEL, real).verdict)
        // Un seul pneu qui chute : pas une variation simultanée.
        val one = (0 until 12).map { i ->
            ThermalSample(i, if (i < 5) ThermalPhase.BEFORE else ThermalPhase.STOP, listOf(if (i >= 7) 50f else 82f, 84f, 79f, 80f), 0f, 50f)
        }
        assertEquals(TyreChangeVerdict.UNDETERMINED, TyreChangeHunter.analyze(4, PitAssessment.REFUEL, one).verdict)

        // En course : un pic d'une seconde pendant le plein, puis retour à la normale.
        val (c, s) = race()
        s.start()
        s.driveLaps(3)
        val normal = s.tyreTemps.copyOf()
        s.pitStop(fuel = 30f, stopSeconds = 8f) {
            when (it) {
                200 -> s.tyreTemps = floatArrayOf(30f, 31f, 29f, 30f)
                260 -> s.tyreTemps = normal.copyOf()
            }
        }
        s.driveLaps(1)
        assertEquals(TyreChangeVerdict.UNDETERMINED.name, c.ledger.stopList.single().thermal)
        assertTrue(s.texts.none { "changé de température" in it })
        assertTrue(c.ledger.changes.isEmpty())
    }

    /** 6. Gomme inconnue : demandée une fois, jamais inventée. */
    @Test
    fun anUnknownCompoundIsAskedOnceAndNeverGuessed() {
        val storage = FakeStorage()
        val (c, s) = race(storage = storage)
        s.start()
        s.driveLaps(3)
        assertNull(c.ledger.currentTyre(c.rules.startTyre), "aucune gomme de départ déclarée")
        s.pitStop(fuel = 30f, changeTyres = true)
        s.say("oui")
        assertEquals(1, s.count("Tu as monté quelle gomme ?"))
        s.say("non")
        assertEquals("D'accord, gomme non précisée : je ne la devine pas.", s.lastAnswer)
        assertNull(c.ledger.currentTyre(c.rules.startTyre))
        assertNull(c.ledger.changes.single().compound)
        s.driveLaps(3)
        assertEquals(1, s.count("Tu as monté quelle gomme ?"), "la question n'est pas répétée")
        val stint = c.ledger.tyreStint(s.lap, c.rules.startTyre)
        assertNull(stint.compound)
        assertEquals(3, stint.age)
        // Sans réponse, la question expire au bout d'une minute, sans être reposée.
        val (c2, s2) = race()
        s2.start()
        s2.driveLaps(2)
        s2.pitStop(fuel = 30f, changeTyres = true)
        s2.say("oui")
        s2.driveLaps(2)
        assertFalse(c2.ledger.awaitingTyreDeclaration)
        assertEquals(1, s2.count("Tu as monté quelle gomme ?"))
        assertNull(c2.ledger.changes.single().compound)
    }

    /** 7. Historique insuffisant : aucun conseil de performance n'est affirmé. */
    @Test
    fun withoutHistoryNoTyrePerformanceIsClaimed() {
        val (c, s) = race()
        s.start()
        s.driveLaps(4)
        assertNull(c.tyreModel.slopeMsPerLap)
        assertNull(c.tyreModel.performantLaps)
        val change = c.tyreChangeSentence()
        assertTrue("GT7 ne transmet pas l'usure" in change, change)
        val next = c.nextTyreSentence()
        assertTrue("Pas assez d'historique comparable pour chiffrer les gommes." in next, next)
        assertNull(c.tyreScenarios().recommended)
        assertFalse(Regex("\\d+ ?%").containsMatchIn(change + next), "jamais de pourcentage d'usure : $change $next")
        assertFalse("apprise" in change + next || "Estimation" in change + next, "$change $next")
        assertTrue(s.texts.none { Regex("(?i)usés? à \\d+").containsMatchIn(it) })
    }

    /** 8. Course terminée puis nouvelle session : les relais ne sont jamais mélangés. */
    @Test
    fun aNewSessionNeverInheritsTheTyresOfThePreviousOne() {
        val storage = FakeStorage()
        val c = RaceController(storage = storage)
        val s = RaceSim(c, totalLaps = 6, perLap = 9f, fuel = 100f)
        s.start()
        s.driveLaps(3)
        s.pitStop(fuel = 30f, changeTyres = true)
        s.say("oui")
        s.driveLaps(6 - s.lap)
        s.driveLaps(1) // arrivée
        // Après l'arrivée : retour au stand au tour 7/6 avec une chute de température (transition, menus).
        val stopsAtFinish = c.ledger.stopCount
        s.pitStop(fuel = 20f, changeTyres = true)
        assertEquals(stopsAtFinish, c.ledger.stopCount, "aucun arrêt après l'arrivée")
        assertTrue(s.texts.none { "pneus neufs ?" in it && s.texts.indexOf(it) > s.texts.indexOfLast { t -> "Course terminée" in t } })
        assertTrue(c.journal.recent().any { "Passage ignoré : course terminée" in it.text })

        // Nouvelle course, même contrôleur : rien du relais précédent.
        val s2 = RaceSim(c, totalLaps = 6, perLap = 9f, fuel = 100f)
        s2.start()
        s2.driveLaps(2)
        assertTrue(c.ledger.changes.isEmpty(), c.ledger.changes.toString())
        assertEquals(0, c.ledger.stopCount)
        assertEquals(s2.lap - 1, c.ledger.tyreAge(s2.lap))
        assertEquals(2, storage.sessions.size, storage.sessions.map { it.id }.toString())
        val first = storage.sessions.first()
        assertEquals(1, first.stops.size)
        assertEquals(TyreService.CHANGED, first.stops.single().tyres)
        assertEquals(TyreChangeVerdict.SUSPECTED.name, first.stops.single().thermal)
    }

    /** 9 et 10. Deux arrêts réels, un seul changement (C7 Gr.4, Dragon Trail) : registre cohérent, rien en double. */
    @Test
    fun twoRealStopsWithOneTyreChangeKeepAConsistentLedgerWithoutDuplicates() {
        val storage = FakeStorage()
        val (c, s) = race(laps = 10, storage = storage)
        c.setRules(RaceRules(mandatoryStops = 2))
        s.start()
        s.driveLaps(3)
        // Arrêt 1 : plein et pneus neufs.
        s.pitStop(fuel = 30f, changeTyres = true)
        s.say("oui")
        s.say("oui") // répété par erreur : rien de plus
        val firstChangeLap = c.ledger.changes.single().lap
        s.driveLaps(7 - s.lap)
        // Arrêt 2 : plein seul, températures stables (les pneus de l'arrêt 1 ont chauffé).
        s.tyreTemps = floatArrayOf(80f, 82f, 77f, 78f)
        s.pitStop(fuel = 25f)
        assertTrue(s.recent().any { it.endsWith("As-tu changé les pneus ?") }, s.recent().toString())
        s.say("non")
        s.driveLaps(10 - s.lap)

        val stops = c.ledger.stopList
        assertEquals(2, stops.size)
        assertEquals(listOf(StopEvidence.REFUEL, StopEvidence.REFUEL), stops.map { it.evidence })
        assertEquals(listOf(TyreService.CHANGED, TyreService.NOT_CHANGED), stops.map { it.tyres })
        assertEquals(listOf(TyreChangeVerdict.SUSPECTED.name, TyreChangeVerdict.UNDETERMINED.name), stops.map { it.thermal })
        assertEquals(1, c.ledger.changes.size)
        assertEquals(s.lap - firstChangeLap, c.ledger.tyreAge(s.lap), "un seul relais depuis l'arrêt 1")
        assertTrue(c.ledger.tyreStint(s.lap, null).certain)

        // 10. Aucun doublon : une annonce, une question, une étiquette et une série par passage.
        assertEquals(1, s.count("Pneus neufs confirmés"))
        assertEquals(1, s.count("Tu as monté quelle gomme ?"))
        assertEquals(1, s.count("Plein terminé, on conserve le même train de pneus."))
        assertEquals(1, s.count("pneus neufs ?"))
        assertEquals(1, s.count("As-tu changé les pneus ?"))
        val events = c.journal.recent(2000)
        assertEquals(2, events.count { it.type == JournalType.DECISION && it.text.startsWith("Tyre Change Hunter, tour") })
        assertEquals(2, events.count { it.text.startsWith("Tyre Change Hunter, séries du tour") })
        val labels = events.filter { it.details["hunter_etiquette"] != null && it.details["origine_etiquette"] != null }
        assertEquals(listOf("pneus_changes", "pneus_conserves"), labels.map { it.details["hunter_etiquette"] })
        assertEquals(events.map { it.seq }.distinct().size, events.size, "numéros d'événement uniques")
        // Séries exportées : avant, arrêt et sortie, une valeur par seconde pour chaque pneu.
        val series = events.first { it.text.startsWith("Tyre Change Hunter, séries du tour") }.details
        val phases = series.getValue("phases")
        assertTrue(Regex("A\\d+ S\\d+ E\\d+").matches(phases), phases)
        val seconds = phases.split(' ').sumOf { it.drop(1).toInt() }
        for (k in listOf("avg", "avd", "arg", "ard", "kmh", "carburant")) assertEquals(seconds, series.getValue(k).split(',').size, k)
        assertTrue(series.values.none { "masqué" in it }, series.toString())

        // Calibration : un positif et un négatif bien classés, encore loin d'une confirmation automatique.
        val cal = c.tyreHunterCalibration()
        assertEquals(1, cal.positives)
        assertEquals(1, cal.negatives)
        assertEquals(0, cal.falsePositives + cal.falseNegatives)
        assertFalse(cal.ready)
    }

    /** Arrêt resté sans réponse sur les pneus : âge incertain, dit à la radio et gardé dans l'historique. */
    @Test
    fun anUnansweredStopMakesTheAgeUncertainAndItIsArchived() {
        val storage = FakeStorage()
        val (c, s) = race(storage = storage)
        c.setRules(RaceRules(tyreStintLaps = 12))
        s.start()
        s.driveLaps(3)
        s.pitStop(fuel = 30f)
        s.driveLaps(2) // pas de réponse : la question expire
        assertFalse(c.ledger.awaitingTyreChangeAnswer)
        assertTrue(c.ledger.changes.isEmpty(), "jamais de pneus neufs supposés")
        assertFalse(c.ledger.tyreStint(s.lap, null).certain)
        val said = c.tyreChangeSentence()
        assertTrue("Âge incertain" in said, said)
        val laps = storage.sessions.single().laps
        assertTrue(laps.last().tyreAgeCertain.not(), laps.last().toString())
        assertTrue(laps.first { it.lap == 2 }.tyreAgeCertain, "les tours d'avant l'arrêt restent certains")
    }
}
