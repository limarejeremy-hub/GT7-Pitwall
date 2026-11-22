package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.FrenchFormat
import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.radio.SpokenFrench
import com.gt7pitwall.core.radio.VoiceBench
import com.gt7pitwall.core.radio.VoiceEngineReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** V509 : diagnostic du moteur de voix et banc A/B (voix actuelle seulement ; GPT-Live indisponible sans clé payante). */
class VoiceBenchTest {
    @Test
    fun engineReportSaysWhoReallySpeaks() {
        val r = VoiceEngineReport(
            route = "Discord (casque PS5)", enginePackage = "com.samsung.SMT", voiceName = "fr-FR-SMTf00",
            locale = "fr-FR", networkVoice = false, quality = 400, nativeRateHz = 22_050, outputRateHz = 48_000, lastSynthesisMs = 180,
        )
        val l = r.lines()
        assertEquals("Discord (casque PS5) : synthèse vocale Android du téléphone — Samsung TTS (com.samsung.SMT)", l[0])
        assertEquals("Voix : fr-FR-SMTf00, fr-FR, voix locale, qualité 400/500", l[1])
        assertEquals("Dernière phrase : 22050 Hz rééchantillonnés en 48000 Hz, synthèse 180 ms", l[2])
        assertTrue(l.last().startsWith("GPT ne produit aucun son"))
        assertEquals("Services vocaux Google", VoiceEngineReport.label("com.google.android.tts"))
        assertEquals("moteur pas encore initialisé", VoiceEngineReport.label(null))
    }

    @Test
    fun theThreeTestPhrasesAreUnderstoodLocally() {
        // Les trois phrases du protocole passent par des réponses calculées, sans IA.
        assertEquals(DriverCommand.Ask(DriverQuery.FUEL), DriverCommandParser.parse("Je finis avec ce carburant ?"))
        assertEquals(StrategyDialogue.Ask.Early, StrategyDialogue.classify("Et si je rentre maintenant ?", afterStrategyAnswer = true))
        assertEquals(DriverCommand.Ask(DriverQuery.RACE_STATUS), DriverCommandParser.parse("Attends, il reste combien de tours ?"))
    }

    @Test
    fun pronunciationLinesReachTheVoiceAsWords() {
        assertEquals("onzième", FrenchFormat.ordinalWords(11))
        assertEquals("Onzième sur quinze.", SpokenFrench.forAudio(VoiceBench.PRONUNCIATION[0]))
        assertEquals("P onze sur quinze.", SpokenFrench.forAudio(VoiceBench.PRONUNCIATION[2]))
    }

    @Test
    fun protocolRecordsDelayFollowUpAndBargeIn() {
        val b = VoiceBench()
        assertFalse(b.start(VoiceBench.Variant.B), "B refusé : aucune clé")
        assertFalse(b.running)
        assertTrue(b.start())
        assertEquals(VoiceBench.Step.FUEL, b.current)
        b.onAudible(10_000)
        b.onTurn("véga je finis avec ce carburant", addressed = true, delayMs = 1_240)
        assertEquals(VoiceBench.Step.FOLLOW_UP, b.current)
        b.onAudible(20_000)
        b.onTurn("et si je rentre maintenant", addressed = false, delayMs = 980)
        assertEquals(VoiceBench.Step.BARGE_IN, b.current)
        b.onBargeIn("attends", 21_700)
        b.onTurn("attends il reste combien de tours", addressed = false, delayMs = 760)
        assertEquals(VoiceBench.Step.PRONUNCIATION, b.current)
        b.onPronunciationPlayed()
        assertNull(b.current)
        val r = b.results()
        assertEquals(1_240L, r[0].delayMs)
        assertEquals(false, r[1].addressed)
        assertEquals(1_700L, r[2].cutAfterMs, "coupée 1,7 s après le début de la réponse")
        assertEquals(760L, r[2].delayMs)
        val l = b.lines()
        assertTrue("prise sans « Véga »" in l[1], l[1])
        assertTrue("coupée après 1700 ms de parole (« attends »)" in l[2], l[2])
        val e = b.export(listOf("Discord (casque PS5) : synthèse vocale Android du téléphone — Samsung TTS"))
        assertTrue("GPT-Live réel désactivé" in e && "réponse en 1240 ms" in e, e)
    }

    @Test
    fun followUpWithWakeWordAndMissedBargeInAreReportedAsSuch() {
        val b = VoiceBench()
        b.start()
        b.onTurn("véga je finis avec ce carburant", true, null)
        b.onTurn("véga et si je rentre maintenant", true, 900)
        b.onTurn("il reste combien de tours", true, 700)
        val l = b.lines()
        assertTrue("délai non mesuré" in l[0], l[0])
        assertTrue("avec mot d'appel" in l[1] && "pas été prise seule" in l[1], l[1])
        assertTrue("aucune interruption détectée" in l[2], l[2])
    }
}
