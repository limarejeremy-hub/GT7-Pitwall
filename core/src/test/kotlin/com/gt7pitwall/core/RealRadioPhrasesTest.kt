package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.RadioLevel
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phrases réellement entendues pendant l'essai du 14/10 (version 35), telles que la reconnaissance les a écrites. */
class RealRadioPhrasesTest {
    private val p = DriverCommandParser::parse

    @Test
    fun aNegatedSilenceDoesNotMuteTheRadio() {
        // Le pilote a dit « j'ai pas dit silence » : la radio s'était coupée.
        assertEquals(DriverCommand.Talk, p("j'ai pas dix silence"))
        assertEquals(DriverCommand.Talk, p("j'ai pas dit silence"))
        assertEquals(DriverCommand.Talk, p("je n'ai jamais dit chut"))
        assertEquals(DriverCommand.Quiet, p("silence"))

        val c = RaceController()
        val s = RaceSim(c)
        s.start()
        s.say("silence")
        assertEquals(RadioLevel.CRITICAL_ONLY, c.engineer.radioLevel)
        s.say("j'ai pas dix silence")
        assertEquals(RadioLevel.FULL, c.engineer.radioLevel)
    }

    @Test
    fun losingSeveralPlacesAsksForThePositionInsteadOfGuessing() {
        // « je me fais doubler par tout le monde » avait donné « tu es P2 » : position inventée.
        assertEquals(DriverCommand.SeveralPlaces(lost = true), p("le fait doubler par tout le monde"))
        assertEquals(DriverCommand.SeveralPlaces(lost = true), p("je me fais doubler par tout le monde"))
        assertEquals(DriverCommand.SeveralPlaces(lost = true), p("j'ai perdu plusieurs places"))
        assertEquals(DriverCommand.SeveralPlaces(lost = false), p("j'ai doublé plusieurs voitures"))
        // Un seul dépassement reste un seul dépassement.
        assertEquals(DriverCommand.Overtaken, p("je me suis fait doubler"))
        assertEquals(DriverCommand.Overtook, p("j'ai doublé"))

        val c = RaceController()
        val s = RaceSim(c)
        s.start()
        s.say("je suis troisième")
        s.say("le fait doubler par tout le monde")
        assertEquals(3, c.engineer.driverPosition, "la position n'est pas devinée")
        assertTrue(s.said.last().expectsReply && "Donne-moi ta position" in s.lastAnswer, s.lastAnswer)
        s.say("huitième")
        assertEquals(8, c.engineer.driverPosition)
    }

    @Test
    fun otherPhrasesOfTheTestStillWork() {
        assertEquals(DriverCommand.Ask(DriverQuery.RACE_STATUS), p("est ce combien tours"))
        assertTrue(p("sors pneus intermediaires") is DriverCommand.Tyres)
        val vocabulary = DriverCommandParser.vocabulary("radio").toSet()
        for (w in "je me fais doubler par tout le monde j'ai pas dit silence plusieurs voitures".split(' ')) {
            assertTrue(w in vocabulary, "« $w » absent du vocabulaire radio")
        }
    }

    @Test
    fun regularityQuestionsAreAnsweredFromTheMeasuredLaps() {
        // Essai du 14/10 (v39) : « tu es régulier » parti à Gemini, qui a répondu « tu as dit que tu es régulier ».
        assertEquals(DriverCommand.Ask(DriverQuery.PACE), p("tu es regulier"))
        assertEquals(DriverCommand.Ask(DriverQuery.PACE), p("est-ce que je suis régulier"))
        assertTrue(com.gt7pitwall.core.ai.RadioAi.route("tu es regulier", "tu es régulier") is com.gt7pitwall.core.ai.RadioAi.Route.Parser)
        val c = RaceController()
        val s = RaceSim(c)
        s.start()
        s.driveLaps(1)
        s.say("tu es régulier")
        assertTrue("au moins deux tours" in s.lastAnswer, s.lastAnswer)
    }
}
