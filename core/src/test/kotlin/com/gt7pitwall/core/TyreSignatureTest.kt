package com.gt7pitwall.core

import com.gt7pitwall.core.tyres.TyreHunterCalibration
import com.gt7pitwall.core.tyres.TyreHunterCalibration.Example
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signature pneus neufs / pneus conservés, mesurée sur des arrêts étiquetés par le pilote. Valeurs de test synthétiques :
 * aucune n'est un résultat d'essai PS5. La confirmation automatique reste désactivée, même « prête ».
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
}
