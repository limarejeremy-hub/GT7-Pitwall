package com.gt7pitwall.core

import com.gt7pitwall.core.radio.SpeechStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/** Débit et hauteur selon le moteur de synthèse choisi dans Android (essai du pilote du 23/11). */
class SpeechStyleEngineTest {
    private val alert = SpeechStyle.Prosody(1.12f, 1.0f)
    private val cheer = SpeechStyle.Prosody(1.06f, 1.06f)

    @Test
    fun tunedEnginesKeepTheirProsody() {
        for (e in listOf("com.google.android.tts", "com.samsung.SMT", null)) {
            val p = SpeechStyle.applied(e, 1.05f, alert)
            assertEquals(1.05f * 1.12f, p.rate, 1e-4f)
            assertEquals(1.06f, SpeechStyle.applied(e, 1.05f, cheer).pitch, 1e-4f)
        }
    }

    @Test
    fun aNeuralEngineSpeaksAtNaturalRateAndPitch() {
        val e = "com.k2fsa.sherpa.onnx.tts.engine"
        assertEquals(SpeechStyle.NEUTRAL, SpeechStyle.applied(e, 1.05f, alert))
        assertEquals(SpeechStyle.NEUTRAL, SpeechStyle.applied(e, 1.05f, cheer))
    }
}
