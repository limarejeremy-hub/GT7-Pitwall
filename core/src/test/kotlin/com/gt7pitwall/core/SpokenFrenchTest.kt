package com.gt7pitwall.core

import com.gt7pitwall.core.radio.SpokenFrench
import kotlin.test.Test
import kotlin.test.assertEquals

/** #73 P1-C : texte de synthèse seulement. Ces tests ne prouvent PAS la prononciation (écoute Fold et Discord à faire). */
class SpokenFrenchTest {
    @Test fun theSamplesOfTheIssueAreWrittenInWords() {
        assertEquals("Bien reçu, P onze.", SpokenFrench.forAudio("Bien reçu, P11."))
        assertEquals("Position onze.", SpokenFrench.forAudio("Position 11"))
        assertEquals("Il reste onze tours.", SpokenFrench.forAudio("Il reste 11 tours."))
        assertEquals("onze, douze, treize, seize, zéro.", SpokenFrench.forAudio("11, 12, 13, 16, 0"))
        assertEquals("Tu termines P douze sur seize.", SpokenFrench.forAudio("Tu termines P12 sur 16."))
    }

    @Test fun timesDecimalsAndCodesAreNeverTouched() {
        assertEquals("Tour six : 1:59.523.", SpokenFrench.forAudio("Tour 6 : 1:59.523."))
        assertEquals("Secteur deux en 48,3 secondes.", SpokenFrench.forAudio("Secteur 2 en 48,3 secondes."))
        assertEquals("Gr.3, N300 et T7 restent tels quels.", SpokenFrench.forAudio("Gr.3, N300 et T7 restent tels quels."))
        assertEquals("Arrêt fin du tour 25.", SpokenFrench.forAudio("Arrêt fin du tour 25."), "au-delà de vingt : le moteur lit le nombre")
        assertEquals("Le 1er relais.", SpokenFrench.forAudio("Le 1er relais."))
    }

    @Test fun everyPhraseEndsWithPunctuation() {
        assertEquals("Box à la fin de ce tour.", SpokenFrench.forAudio("Box à la fin de ce tour"))
        assertEquals("Tu confirmes ?", SpokenFrench.forAudio("Tu confirmes ?"))
        assertEquals("", SpokenFrench.forAudio("  "))
    }

    @Test fun discordAudioGetsSilenceBeforeAndAfter() {
        val pcm = ShortArray(960) { 1000 }
        val out = SpokenFrench.padded(pcm, 48_000)
        assertEquals(48_000 * 60 / 1000 + 960 + 48_000 * 160 / 1000, out.size)
        assertEquals(0, out.first().toInt())
        assertEquals(0, out.last().toInt())
        assertEquals(1000, out[48_000 * 60 / 1000].toInt(), "la phrase elle-même est intacte")
        assertEquals(0, SpokenFrench.padded(ShortArray(0), 48_000).size)
    }
}
