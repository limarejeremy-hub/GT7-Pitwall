package com.gt7pitwall.core

import com.gt7pitwall.core.radio.BenchSeries
import com.gt7pitwall.core.radio.FrenchNumbers
import com.gt7pitwall.core.radio.UtteranceSegmenter
import com.gt7pitwall.core.radio.WordErrorRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecognitionBenchTest {
    private val block = 4_000 // 0,25 s à 16 kHz
    private val voice = ShortArray(block) { if (it % 2 == 0) 3_000 else -3_000 }
    private val silence = ShortArray(block)

    // --- Découpage des phrases ---

    @Test
    fun aSentenceEndsAfterSilenceAndKeepsAShortPreRoll() {
        val s = UtteranceSegmenter()
        repeat(4) { assertNull(s.accept(silence, block)) } // 1 s de silence avant
        repeat(8) { assertNull(s.accept(voice, block)) } // 2 s de voix
        assertNull(s.accept(silence, block))
        assertNull(s.accept(silence, block))
        assertNull(s.accept(silence, block))
        val out = s.accept(silence, block) // 1 s de silence : fin (0,8 s demandés)
        assertNotNull(out)
        // Au moins 0,3 s d'avance (deux blocs de 0,25 s) + 2 s de voix + 1 s de silence.
        assertEquals((2 + 8 + 4) * block, out.size)
    }

    @Test
    fun aClickIsNotASentence() {
        val s = UtteranceSegmenter(minVoiceMs = 300)
        val click = ShortArray(block) { if (it < 800) 5_000 else 0 } // RMS au-dessus du seuil sur un bloc
        assertNull(s.accept(click, block))
        var out: ShortArray? = null
        repeat(4) { out = out ?: s.accept(silence, block) }
        assertNull(out)
    }

    @Test
    fun aVeryLongSentenceIsCutAtTheMaximum() {
        val s = UtteranceSegmenter(maxMs = 3_000)
        var out: ShortArray? = null
        var blocks = 0
        while (out == null && blocks < 40) {
            out = s.accept(voice, block)
            blocks++
        }
        assertEquals(12, blocks)
        assertEquals(12 * block, out!!.size)
    }

    @Test
    fun flushGivesTheSentenceInProgress() {
        val s = UtteranceSegmenter()
        repeat(4) { s.accept(voice, block) }
        assertEquals(4 * block, s.flush()!!.size)
        assertNull(s.flush())
    }

    // --- Taux d'erreur par mots ---

    @Test
    fun accentsPunctuationAndApostrophesDoNotCount() {
        assertEquals(0.0, WordErrorRate.of("Est-ce que tu m'entends ?", "est ce que tu m entends"))
        assertEquals(0.0, WordErrorRate.of("quel est l'écart avec la voiture devant", "Quel est l'ecart avec la voiture devant."))
    }

    @Test
    fun digitsAndWordsAreTheSame() {
        assertEquals(0.0, WordErrorRate.of("je viens de doubler la voiture vingt", "Je viens de doubler la voiture 20."))
        assertEquals(0.0, WordErrorRate.of("course de vingt et un tours", "course de 21 tours"))
        assertEquals(listOf("quatre", "vingt", "dix", "sept"), FrenchNumbers.words(97))
        assertEquals(listOf("soixante", "et", "onze"), FrenchNumbers.words(71))
        assertEquals(listOf("deux", "cent", "trente", "quatre"), FrenchNumbers.words(234))
    }

    @Test
    fun substitutionsDeletionsAndInsertionsAreCounted() {
        assertEquals(0.25, WordErrorRate.of("combien de tours restant", "combien des tours restant"))
        assertEquals(0.5, WordErrorRate.of("merci bien reçu ok", "merci reçu"))
        assertEquals(1.0, WordErrorRate.of("merci", "merci beaucoup"))
        assertEquals(1.0, WordErrorRate.of("merci", ""))
    }

    @Test
    fun voskUnknownWordsAreErrors() {
        assertEquals(0.5, WordErrorRate.of("bien reçu", "bien [unk]"))
    }

    // --- Série d'essai ---

    @Test
    fun aSeriesComparesTheEngineAndVoskOnEachExpectedPhrase() {
        val series = BenchSeries(listOf("combien d'essence il me reste", "merci bien reçu"))
        series.start()
        assertEquals("combien d'essence il me reste", series.current)
        series.reference(1_000, "combien d essence il me reste", free = true)
        val a = series.candidate(1_400, "zipformer", "combien d'essence il me reste", 900, 300, 2_000, screenOff = false)
        assertEquals(0.0, a.wer)
        assertEquals(0.0, a.voskWer)
        assertEquals("merci bien reçu", series.current)
        val b = series.candidate(9_000, "zipformer", "merci bien", 700, 200, 1_500, screenOff = true)
        assertEquals(1.0 / 3, b.wer!!, 1e-9)
        // Vosk arrive après le moteur testé : rattaché quand même.
        series.reference(9_500, "merci bien reçu", free = false)
        assertEquals(0.0, series.attempts.last().voskWer)
        assertFalse(series.active) // série terminée
        val sum = series.summary()
        assertEquals(2, sum.phrases)
        assertEquals(1, sum.engineExact)
        assertEquals(2, sum.voskExact)
        assertEquals(800L, sum.meanLatencyMs)
        assertEquals(500.0 / 3_500, sum.realTimeFactor!!, 1e-9)
        assertEquals(1, sum.screenOff)
    }

    @Test
    fun voskFreeTranscriptionIsPreferredToTheRadioVocabulary() {
        val series = BenchSeries(listOf("la piste est en train de sécher"))
        series.start()
        series.reference(1_000, "la piste [unk]", free = false)
        series.reference(1_100, "la piste est en train de sécher", free = true)
        val a = series.candidate(1_200, "whisper", "La piste est en train de sécher.", 1_500, 900, 2_500, false)
        assertEquals("la piste est en train de sécher", a.vosk)
        assertTrue(a.voskFree)
    }

    @Test
    fun aLateFreeTranscriptionReplacesTheRadioVocabularyOne() {
        val series = BenchSeries(listOf("la piste est en train de sécher"))
        series.start()
        series.candidate(1_000, "whisper", "la piste est en train de sécher", 1_500, 900, 2_500, false)
        series.reference(1_200, "la piste [unk]", free = false)
        series.reference(1_300, "la piste est en train de sécher", free = true)
        assertEquals(0.0, series.attempts.single().voskWer)
    }

    @Test
    fun referencesTooFarApartAreNotPaired() {
        val series = BenchSeries(listOf("merci bien reçu"))
        series.start()
        series.reference(1_000, "merci bien reçu", free = true)
        val a = series.candidate(20_000, "zipformer", "merci bien reçu", 600, 100, 1_000, false)
        assertNull(a.vosk)
    }

    @Test
    fun skippingAndFreeListening() {
        val series = BenchSeries(listOf("a b", "c d"))
        val free = series.candidate(0, "zipformer", "bonjour", 500, 100, 1_000, false)
        assertNull(free.expected)
        assertNull(free.wer)
        series.start()
        series.skip()
        assertEquals("c d", series.current)
        series.skip()
        assertFalse(series.active)
        assertNull(series.current)
    }

    @Test
    fun theDefaultPhrasesNeverContainTheWakeWord() {
        assertEquals(20, BenchSeries.PHRASES.size)
        assertTrue(BenchSeries.PHRASES.none { "radio" in WordErrorRate.words(it) })
    }
}
