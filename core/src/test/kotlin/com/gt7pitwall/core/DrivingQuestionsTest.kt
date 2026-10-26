package com.gt7pitwall.core

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.DrivingAnalysis
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.strategy.RaceRules
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Questions sur le pilotage : chaque question atteint le bon moteur (course du 26/10). */
class DrivingQuestionsTest {
    private fun q(s: String) = (DriverCommandParser.parse(s) as? DriverCommand.Ask)?.query

    @Test
    fun eachQuestionReachesTheRightEngine() {
        val expected = mapOf(
            "mes tours sont bons ?" to DriverQuery.DRIVING_QUALITY,
            "tu trouves que je conduis bien ?" to DriverQuery.DRIVING_QUALITY,
            "où est-ce que je perds du temps ?" to DriverQuery.DRIVING_LOSS,
            "où je perds du temps ?" to DriverQuery.DRIVING_LOSS,
            "sur quel virage je peux m'améliorer ?" to DriverQuery.DRIVING_LOSS,
            "pourquoi je suis plus lent ?" to DriverQuery.DRIVING_LOSS,
            "je freine trop tôt ?" to DriverQuery.DRIVING_BRAKING,
            "tu vois une amélioration ?" to DriverQuery.DRIVING_PROGRESS,
            "je progresse ?" to DriverQuery.DRIVING_PROGRESS,
            "qu'est-ce que je dois changer ?" to DriverQuery.DRIVING_ADVICE,
            "qu'est-ce que tu as appris sur mon pilotage ?" to DriverQuery.DRIVING_LEARNED,
        )
        for ((s, query) in expected) assertEquals(query, q(s), s)
        // Les autres moteurs gardent leurs questions.
        assertEquals(DriverQuery.TYRES, q("mes pneus chauffent trop ?"))
        assertEquals(DriverQuery.PLAN, q("quelle est ma stratégie ?"))
        assertEquals(DriverQuery.RACE_STATUS, q("combien de tours il reste ?"))
        // Régularité : le moteur de rythme (moyenne et écart des derniers tours mesurés).
        assertEquals(DriverQuery.PACE, q("je suis régulier ?"))
        // Technique (« comment ») : la conversation et ses principes de pilotage, pas la mesure.
        assertNull(DrivingAnalysis.classify("comment améliorer mon freinage ?"))
        assertFalse(q("est-ce que mes pneus tiendront ?") in DrivingAnalysis.QUERIES)
        // « Qu'est-ce que je dois changer » sur les pneus n'est pas une question de pilotage.
        assertNull(DrivingAnalysis.classify("je dois changer les pneus quand"))
    }

    private fun race(): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = FakeStorage(rules = RaceRules(totalLaps = 14)))
        return c to RaceSim(c, totalLaps = 14, perLap = 3f)
    }

    @Test
    fun theRealTranscriptionOfThe26thGetsADrivingAnswerNotTheLapCount() = runBlocking<Unit> {
        val (c, s) = race()
        s.start()
        s.driveLaps(9)
        val out = RadioConversation(c).onUtterance("est ce quelle tour sont mon", "puisque mes tours sont bons", model = null)
        val a = out.last { it.category == MessageCategory.ANSWER }.text
        assertFalse(a.startsWith("Tour 10 sur 14"), a)
        assertTrue("tours propres" in a, a)
    }

    @Test
    fun answersAreMeasuredAndHonestWithoutEnoughData() = runBlocking<Unit> {
        val (c, s) = race()
        s.start()
        s.driveLaps(2)
        val early = c.drivingAnswer(DriverQuery.DRIVING_QUALITY)
        assertTrue(early.startsWith("Pas encore assez de tours propres"), early)
        s.driveLaps(7)
        val loss = c.drivingAnswer(DriverQuery.DRIVING_LOSS)
        assertTrue("secteur" in loss, loss)
        val braking = c.drivingAnswer(DriverQuery.DRIVING_BRAKING)
        // Sans profil virage par virage mesuré, jamais d'affirmation sur le point de freinage.
        assertTrue("je ne peux pas dire" in braking || "Virage" in braking, braking)
        val learned = c.learningReport()
        assertTrue(learned.any { it.startsWith("Tours analysés") }, learned.toString())
    }
}
