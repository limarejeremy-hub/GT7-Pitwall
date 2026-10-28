package com.gt7pitwall.core

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DuplexMode
import com.gt7pitwall.core.radio.DuplexPolicy
import com.gt7pitwall.core.radio.EchoFilter
import com.gt7pitwall.core.radio.InterruptDetector
import com.gt7pitwall.core.radio.Vetting
import com.gt7pitwall.core.radio.VoiceRoute
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #45 (course PS5 du 28/10, APK #457) : 26 interruptions de l'ingénieur, toutes moins de 2,6 s après une annonce,
 * par des mots qui ressemblaient à cette annonce ; deux « course de 2 minutes » appliquées alors que GT7 annonçait 5 tours.
 * Phrases et annonces reprises du journal de cette course.
 */
class RadioEchoTest {
    private val tour3 = "Tour 3 : 2 minutes 9 secondes 5."
    private val p10 = "Tu es P10 sur 14."
    private val p8 = "Tu es P8 sur 14."

    @Test fun theMisheardEchoesOfThe28thAreRecognised() {
        assertTrue(EchoFilter.isEcho("dix second", listOf(p10)))
        assertTrue(EchoFilter.isEcho("peux huitième", listOf(p8)))
        assertTrue(EchoFilter.isEcho("tour trois", listOf(tour3)))
        assertTrue(EchoFilter.isEcho("deux minutes neuf", listOf(tour3)))
        assertTrue(EchoFilter.isEcho("meilleur tôt", listOf("Meilleur tour en 2 minutes 7.")))
        // Le pilote qui reprend un mot de l'ingénieur n'est pas un écho.
        assertFalse(EchoFilter.isEcho("j'ai mis les pneus durs", listOf("Tu confirmes tes pneus ?")))
        assertFalse(EchoFilter.isEcho("combien d'essence il me reste", listOf(p8)))
        assertFalse(EchoFilter.isEcho("stop", listOf(tour3)))
    }

    @Test fun discordHearingItsOwnVoiceNeverInterruptsTheEngineer() {
        val d = InterruptDetector()
        var now = 1_000L
        fun heard(engineer: String, words: String): Boolean {
            d.engineer(true, engineer, now)
            d.voice(now + 200)
            now += 3_000
            return d.words(words, now - 2_700)
        }
        assertFalse(heard(p10, "dix second"))
        assertFalse(heard(p8, "peux huitième"))
        assertFalse(heard(tour3, "deux minutes neuf"))
        assertFalse(heard(tour3, "tour trois"))
        assertTrue(d.rejected >= 4)
        // Un vrai « attends » ou « stop » du pilote coupe toujours (mode avec interruption activée).
        assertTrue(heard(tour3, "attends"))
        assertTrue(heard(p8, "stop stop"))
    }

    @Test fun onDiscordTheInterruptionIsOffUnlessTheDriverEnablesIt() {
        assertEquals(DuplexMode.OFF, DuplexPolicy.mode(VoiceRoute.REMOTE, phoneSpeaker = false))
        assertEquals(DuplexMode.FULL, DuplexPolicy.mode(VoiceRoute.REMOTE, phoneSpeaker = false, remoteDuplexOptIn = true))
    }

    private fun conversation(c: RaceController) = RadioConversation(c)

    private fun hear(c: RaceController, text: String, near: Boolean): List<EngineerMessage> =
        runBlocking { conversation(c).onUtterance(text, text, null, nearEngineer = near) }

    private fun spoken(m: List<EngineerMessage>) = m.filter { it.category != MessageCategory.DRIVER }

    /** Course de 5 tours suivie par la télémétrie (comme le 28/10). */
    private fun fiveLapRace(): Pair<RaceController, RaceSim> {
        val c = RaceController()
        val sim = RaceSim(c, totalLaps = 5, perLap = 3f)
        sim.start()
        sim.driveLaps(2)
        return c to sim
    }

    @Test fun theLapTimeHeardAsTwoMinutesNeverBecomesARaceDuration() {
        val (c, _) = fiveLapRace()
        val before = c.rules
        c.noteSpoken(tour3)
        // Phrase commencée pendant l'annonce : écho, écarté sans réponse parlée.
        val echo = hear(c, "deux minutes", near = true)
        assertTrue(spoken(echo).isEmpty(), echo.toString())
        assertEquals(before, c.rules)
        // Même hors écho : « 2 minutes 9 » est un chrono, et GT7 annonce 5 tours.
        assertIs<Vetting.Ignore>(c.vet(DriverCommandParser.parse("deux minutes neuf")!!, "deux minutes neuf"))
        val v = c.vet(DriverCommandParser.parse("course de 30 minutes")!!, "course de 30 minutes")
        assertIs<Vetting.Discuss>(v)
        assertTrue("GT7 annonce une course de 5 tours" in v.reply, v.reply)
        hear(c, "course de 30 minutes", near = false)
        hear(c, "oui", near = false)
        assertNull(c.rules.durationMin)
        assertTrue(c.journal.recent().any { "écho probable" in it.text || "chrono" in (it.decision ?: "") })
    }

    @Test fun aRuleSaidByVoiceNeedsAYesAndTheYesIsNeverTakenForAnEcho() {
        val c = RaceController() // sans télémétrie : course au temps possible
        val ask = spoken(hear(c, "course de 30 minutes", near = false))
        assertTrue(ask.any { it.text == "Tu confirmes : course de 30 minutes ?" }, ask.toString())
        assertNull(c.rules.durationMin)
        // Le « oui » arrive juste après la question : jamais écarté comme écho.
        c.noteSpoken("Tu confirmes : course de 30 minutes ?")
        hear(c, "oui", near = true)
        assertEquals(30, c.rules.durationMin)
    }

    @Test fun anEchoedPositionIsNotDeclared() {
        val (c, _) = fiveLapRace()
        val position = c.engineer.driverPosition
        c.noteSpoken("Tu es P5 sur 14.")
        assertTrue(spoken(hear(c, "cinq", near = true)).isEmpty())
        assertTrue(spoken(hear(c, "quatorze", near = true)).isEmpty())
        assertEquals(position, c.engineer.driverPosition)
        assertEquals(2, c.echoIgnored)
    }

    @Test fun aDriverReplyAfterTheEngineerFinishedIsNeverTakenForAnEcho() {
        val (c, _) = fiveLapRace()
        c.noteSpoken("Arrêt prévu fin du tour 4.")
        // Dite après la fin de la phrase (near = false) : traitée normalement, même si elle reprend ses mots.
        hear(c, "arrêt fait", near = false)
        assertEquals(1, c.ledger.stopCount)
        assertEquals(0, c.echoIgnored)
    }

    @Test fun duringTheEngineerVoiceAStopNeedsAYes() {
        val (c, _) = fiveLapRace()
        c.noteSpoken("Tu es P8 sur 14.")
        val m = spoken(hear(c, "arrêt fait", near = true))
        assertTrue(m.any { it.text.startsWith("Tu confirmes que tu as effectué ton arrêt") }, m.toString())
        assertEquals(0, c.ledger.stopCount)
    }

    @Test fun aLongAnswerKeepsItsEchoWindowUntilItEnds() {
        var now = 1_000_000L
        val c = RaceController(clock = { now })
        c.echoSensitive = true
        val long = (1..40).joinToString(" ") { "mot$it" }
        c.noteSpoken(long)
        now += 20_000
        assertEquals(listOf(long), c.recentEngineer())
        now += 30_000
        assertTrue(c.recentEngineer().isEmpty())
        // Hors fenêtre : rien n'est pris pour un écho.
        assertNull(c.echoOfEngineer("mot1 mot2"))
    }

    // ------------------------------------------------ Revue de la PR #46

    @Test fun onlyTheCurrentPhraseStartCountsForTheEchoWindow() {
        val t = com.gt7pitwall.core.radio.EchoTiming
        // Ancien début à 8 s, ingénieur de 9 à 10 s, nouvelle demande remise à 30 s : jamais un écho.
        assertFalse(t.near(30_000, 8_000, false, false, 9_000, 10_000))
        // Début trop ancien pour la phrase en cours : ignoré ; sans début, la remise à 30 s est loin de la fin.
        assertFalse(t.near(30_000, 15_000, false, false, 9_000, 10_000))
        assertFalse(t.near(30_000, null, false, false, 9_000, 10_000))
        // Phrase commencée pendant la voix, ou dans les 2 s qui suivent : écho possible.
        assertTrue(t.near(13_000, 9_500, false, false, 9_000, 10_000))
        assertTrue(t.near(13_000, 11_800, false, false, 9_000, 10_000))
        // Reprise normale 2,5 s après la fin : réponse du pilote, pas un écho.
        assertFalse(t.near(14_000, 12_500, false, false, 9_000, 10_000))
        // Google ou secours (pas de début connu) : seulement si la remise suit de près la fin de la voix.
        assertTrue(t.near(13_500, null, false, false, 9_000, 10_000))
        // L'ingénieur parle encore, ou interruption en cours : toujours comparé.
        assertTrue(t.near(30_000, null, true, false, 9_000, 10_000))
        assertTrue(t.near(30_000, null, false, true, 9_000, 10_000))
        // Silence : aucune voix de l'ingénieur encore entendue.
        assertFalse(t.near(30_000, 29_000, false, false, null, null))
    }

    @Test fun shortRealQuestionsAfterTheEngineerAreNeverSuppressed() {
        val (c, _) = fiveLapRace()
        c.noteSpoken(p8)
        c.noteSpoken(tour3)
        for (q in listOf("position ?", "combien de tours ?", "quelle position", "combien d'essence")) {
            val m = hear(c, q, near = true)
            assertTrue(m.none { "Écho de l'ingénieur ignoré" in it.text }, "$q → $m")
            assertTrue(spoken(m).isNotEmpty(), "$q → $m")
        }
        assertEquals(0, c.echoIgnored)
    }

    @Test fun aYesHeardInTheEngineersOwnSentenceConfirmsNothing() {
        val c = RaceController()
        hear(c, "course de 30 minutes", near = false)
        c.noteSpoken("Oui, je note. Tu confirmes : course de 30 minutes ?")
        hear(c, "oui", near = true)
        assertNull(c.rules.durationMin, "le « oui » venait de la phrase de l'ingénieur")
        hear(c, "oui", near = false)
        assertEquals(30, c.rules.durationMin)
    }

    @Test fun radioStatesHaveDistinctJournalCodes() {
        val code = com.gt7pitwall.core.radio.RadioStatusCode
        assertEquals("MICRO_MUET", code.of("Écoute radio : micro du Fold : que des zéros depuis 20 s (micro coupé par Android ?)."))
        assertEquals("ECOUTE_INDISPONIBLE", code.of("Écoute radio : démarrage impossible (modèle absent)."))
        assertEquals("FAUSSE_INTERRUPTION", code.of("FAUSSE_INTERRUPTION refusée (écho probable) : « dix second »"))
        assertEquals("ECOUTE_REPRISE", code.of("Écoute radio : la voix de l'ingénieur n'a pas signalé sa fin depuis 60 s — écoute reprise."))
        assertEquals("ECOUTE_RELANCEE", code.of("Écoute radio : plus aucun son reçu — écoute relancée."))
    }
}
