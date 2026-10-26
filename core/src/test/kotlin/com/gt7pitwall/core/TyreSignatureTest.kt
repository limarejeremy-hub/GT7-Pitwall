package com.gt7pitwall.core

import com.gt7pitwall.core.tyres.TyreHunterCalibration
import com.gt7pitwall.core.tyres.TyreHunterCalibration.Example
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signature pneus neufs / pneus conservés. Le premier test utilise des valeurs synthétiques ;
 * le second vérifie une capture PS5 distincte, confirmée après la course par le pilote.
 * La confirmation automatique reste désactivée même si la calibration semble « prête ».
 */
class TyreSignatureTest {

    @Test
    fun theStepsMustSeparateChangedAndKeptTyresBeforeAnyClaimOfReliability() {
        val changed = List(3) { Example(suspected = true, changed = true, stepC = listOf(-22f, -20f, -18f, -19f)) }
        val kept = List(3) { Example(suspected = false, changed = false, stepC = listOf(-3f, -2f, -4f, -1f)) }
        val ok = TyreHunterCalibration.evaluate(changed + kept)
        assertEquals(18f, ok.changedMinStepC)
        assertEquals(1f, ok.keptMaxStepC)
        assertTrue(ok.separated && ok.ready, ok.text)
        assertTrue("confirmation automatique toujours désactivée" in ok.text, ok.text)

        // Un arrêt « pneus conservés » au saut aussi fort qu'un changement : plus de séparation, donc pas fiable.
        val overlap = TyreHunterCalibration.evaluate(changed + kept + Example(suspected = false, changed = false, stepC = listOf(-16f, -15f, -17f, -15f)))
        assertFalse(overlap.separated, overlap.text)
        assertFalse(overlap.ready)
        assertTrue("pas de séparation nette" in overlap.text, overlap.text)

        // Sauts relus du journal.
        assertEquals(listOf(12.5f, 11f, 9.8f, 10.2f), TyreHunterCalibration.parseStep("12.5,11.0,9.8,10.2"))
        assertTrue(TyreHunterCalibration.parseStep("aucun").isEmpty())
    }
    /**
     * Course réelle du 26/10/2026 à Interlagos : pneus RH -> RM au tour 7,
     * sans ravitaillement. L'application n'a entendu aucune confirmation en course :
     * le pilote a fourni l'étiquette après l'export. Ce n'est qu'un vrai positif.
     */
    @Test
    fun realInterlagosPositiveConfirmsThermalSignatureButNotAutomaticCalibration() {
        val resource = "/tyre_calibration/2026-10-26-interlagos-t7.json"
        val contents = checkNotNull(javaClass.getResourceAsStream(resource)) {
            "Fichier terrain de calibration introuvable : $resource"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val entry = JSONObject(contents)
        val truth = entry.getJSONObject("ground_truth")
        val detector = entry.getJSONObject("detector")
        val deltas = detector.getJSONObject("four_wheel_step_celsius")
        val observed = listOf("front_left", "front_right", "rear_left", "rear_right")
            .map { deltas.getDouble(it).toFloat() }
        assertEquals("SUSPECTED", detector.getString("verdict"))
        assertEquals("TYRES_CHANGED", truth.getString("label"))
        assertEquals("RH", truth.getString("previous_compound"))
        assertEquals("RM", truth.getString("new_compound"))
        assertTrue(truth.getBoolean("no_refuel"))
        assertFalse(truth.getBoolean("confirmation_in_recorded_voice_log"))
        assertEquals(listOf(-29.2f, -20.2f, -18.5f, -13.4f), observed)
        val sample = Example(suspected = true, changed = true, stepC = observed)
        assertEquals(13.4f, sample.weakestStepC)
        val status = TyreHunterCalibration.evaluate(listOf(sample))
        assertEquals(1, status.positives)
        assertEquals(0, status.negatives)
        assertEquals(0, status.falsePositives)
        assertEquals(0, status.falseNegatives)
        assertFalse(status.separated)
        assertFalse(status.ready, "Un cas réel positif sans témoin négatif ne suffit jamais")
    }

}
