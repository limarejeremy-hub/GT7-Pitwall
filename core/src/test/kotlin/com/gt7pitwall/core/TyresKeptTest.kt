package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.CommandSource
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.strategy.TyreService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Essai PS5 : « je n'ai pas changé les pneus » et ses variantes étaient mal comprises (changement nié, question sur
 * les pneus, ou « je n'ai pas compris »). Les pneus conservés doivent être notés comme tels, jamais comme neufs.
 */
class TyresKeptTest {

    private val explicit = listOf(
        "je n'ai pas changé les pneus", "j'ai pas changé les pneus", "je n'ai pas changé de pneus", "non j'ai pas changé les pneus",
        "Non, je n'ai pas changé les pneus.", "j'ai gardé les mêmes pneus", "mêmes pneus", "pas de changement de pneus",
        "j'ai pas touché aux pneus", "pneus pas changés", "j'ai conservé les pneus", "même train de pneus",
    )

    /** Réponses courtes qui ne nomment pas les pneus : comprises seulement après une question sur les pneus. */
    private val contextual = listOf(
        "non je les ai pas changés", "non pas changé", "j'ai rien changé", "je ne les ai pas changés", "non c'est les mêmes",
    )

    private fun stopAwaitingTyres(): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = FakeStorage())
        val s = RaceSim(c, totalLaps = 10, perLap = 9f, fuel = 100f)
        s.start()
        s.driveLaps(3)
        s.pitStop(fuel = 30f)
        assertTrue(c.ledger.awaitingTyreChangeAnswer, "l'ingénieur demande si les pneus ont changé")
        return c to s
    }

    @Test
    fun everyWayOfSayingTheTyresWereKeptIsParsed() {
        for (s in explicit) assertEquals(DriverCommand.TyresKept(explicit = true), DriverCommandParser.parse(s), s)
        for (s in contextual) assertEquals(DriverCommand.TyresKept(explicit = false), DriverCommandParser.parse(s), s)
        // Un vrai changement reste un changement.
        assertEquals(DriverCommand.TyresChanged, DriverCommandParser.parse("j'ai changé les pneus"))
        assertEquals(DriverCommand.TyresChanged, DriverCommandParser.parse("j'ai mis des pneus neufs"))
        // Ni les autres sujets, ni « quand même ».
        for (s in listOf("j'ai pas changé de stratégie", "je garde le même rythme", "pas de changement de plan",
            "les pneus tiennent quand même")) {
            assertFalse(DriverCommandParser.parse(s) is DriverCommand.TyresKept, s)
        }
        // La grammaire Vosk contient chaque mot, sinon la phrase est inaudible.
        val vocabulary = DriverCommandParser.vocabulary("radio").toSet()
        for (s in (explicit + contextual).filter { "," !in it }) {
            val missing = s.lowercase().split(' ').filter { it !in vocabulary }
            assertTrue(missing.isEmpty(), "« $s » : mots absents du vocabulaire $missing")
        }
    }

    @Test
    fun aNegativeAnswerToTheTyreQuestionKeepsTheSameTyres() {
        for (s in explicit + contextual + "non") {
            val (c, sim) = stopAwaitingTyres()
            sim.say(s)
            assertEquals("Plein terminé, on conserve le même train de pneus.", sim.lastAnswer, s)
            assertEquals(TyreService.NOT_CHANGED, c.ledger.stopList.single().tyres, s)
            assertTrue(c.ledger.changes.isEmpty(), s)
            assertFalse(c.awaitingDriverAnswer, s)
        }
    }

    @Test
    fun theAnswerHeardInTheOpenConversationNeedsNoSecondConfirmation() {
        val (c, sim) = stopAwaitingTyres()
        val out = c.utterance("non je les ai pas changés", source = CommandSource.CONVERSATION)
        assertTrue(out.any { "même train de pneus" in it.text }, out.toString())
        assertEquals(TyreService.NOT_CHANGED, c.ledger.stopList.single().tyres)
        sim.driveLaps(1)
    }

    @Test
    fun saidLaterTheKeptTyresStillLabelTheRecentStop() {
        val (c, sim) = stopAwaitingTyres()
        sim.driveLaps(2) // la question expire sans réponse
        assertFalse(c.ledger.awaitingTyreChangeAnswer)
        assertEquals(TyreService.UNKNOWN, c.ledger.stopList.single().tyres)
        sim.say("je n'ai pas changé les pneus")
        assertEquals(TyreService.NOT_CHANGED, c.ledger.stopList.single().tyres)
        assertTrue(c.ledger.tyreStint(sim.lap, null).certain, "l'âge des pneus redevient certain")
    }

    @Test
    fun withoutAStopNothingChangesAndShortAnswersAreNotGuessed() {
        for (s in explicit) {
            val c = RaceController(storage = FakeStorage())
            val sim = RaceSim(c, totalLaps = 10, perLap = 9f, fuel = 100f)
            sim.start()
            sim.driveLaps(2)
            sim.say(s)
            assertEquals("D'accord, je ne note aucun changement de pneus.", sim.lastAnswer, s)
            assertTrue(c.ledger.changes.isEmpty() && c.ledger.stopList.isEmpty(), s)
        }
        for (s in contextual) {
            val c = RaceController(storage = FakeStorage())
            val sim = RaceSim(c, totalLaps = 10, perLap = 9f, fuel = 100f)
            sim.start()
            sim.driveLaps(2)
            sim.say(s)
            assertTrue("mêmes pneus" in sim.lastAnswer, "$s → ${sim.lastAnswer}")
        }
        // Une question n'enregistre rien.
        assertEquals(DriverCommand.Ask(DriverQuery.TYRE_CHANGE), RaceController(storage = FakeStorage())
            .vet(DriverCommand.TyresKept(), "est-ce que je garde les mêmes pneus ?").let { (it as com.gt7pitwall.core.radio.Vetting.Redirect).command })
    }
}
