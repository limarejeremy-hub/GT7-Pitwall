package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.race.TyreSource
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #41 P0-B (essai du 28/10) : l'écran disait « règlement confirmé le 2026-10-27 », l'archive `confirmed=false` ;
 * la gomme était « certaine » dans l'archive alors qu'elle n'était que déclarée.
 */
class ProvenanceTest {
    @Test fun aDeclaredStartTyreOrARecognisedTrackKeepsTheConfirmation() {
        val race = RaceController()
        race.setRules(race.rules.copy(totalLaps = 5, trackId = null))
        race.confirmRules("2026-10-27")
        assertTrue(race.profile.confirmed)
        race.setRules(race.rules.copy(startTyre = TyreCompound.RH))
        assertTrue(race.profile.confirmed, "gomme de départ : pas une modification du règlement")
        race.setRules(race.rules.copy(trackId = 7))
        assertTrue(race.profile.confirmed, "circuit reconnu alors qu'il était vide")
        // Course personnalisée (cas du 28/10) : une confirmation de la veille n'est pas celle de cette course.
        assertTrue(race.ruleStatusText.startsWith("Règlement local confirmé par toi le 2026-10-27, pas encore reconfirmé pour cette course"), race.ruleStatusText)
        assertTrue("GT7 ne transmet pas le règlement" in race.ruleStatusText)
    }

    @Test fun aRealRuleChangeDropsTheConfirmationVisiblyAndTheArchiveSaysTheSame() {
        val sim = RaceSim(totalLaps = 5)
        val race = sim.controller
        race.setRules(race.rules.copy(totalLaps = 5))
        race.confirmRules("2026-10-27")
        sim.start()
        sim.driveLaps(1)
        race.setRules(race.rules.copy(totalLaps = 6))
        assertFalse(race.profile.confirmed)
        assertNotNull(race.confirmationDropped)
        assertTrue(race.ruleStatusText.startsWith("Confirmation retirée"), race.ruleStatusText)
        // Écran et archive disent la même chose.
        assertEquals(false, race.currentRecord()?.context?.profile?.confirmed)
        assertTrue(race.journal.recent().any { "Confirmation du règlement retirée" in it.text })
        race.confirmRules("2026-10-28")
        assertNull(race.confirmationDropped)
        assertTrue(race.profile.confirmed)
    }

    @Test fun theArchivedCompoundSaysItWasDeclaredNeverMeasured() {
        val sim = RaceSim(totalLaps = 5)
        val race = sim.controller
        race.setRules(race.rules.copy(totalLaps = 5, startTyre = TyreCompound.RH))
        sim.start()
        sim.driveLaps(2)
        val r = assertNotNull(race.currentRecord())
        val lap = r.laps.first()
        assertEquals(TyreCompound.RH, lap.tyreCompound)
        assertEquals(TyreSource.DECLARED_START, lap.tyreSource)
        val back = assertNotNull(RaceJson.session(RaceJson.session(r)))
        assertEquals(TyreSource.DECLARED_START, back.laps.first().tyreSource)
        // Aucune provenance « mesurée » n'existe : l'UDP de GT7 ne contient pas la gomme.
        assertTrue(TyreSource.entries.none { "mesur" in it.label && "non" !in it.label })
    }

    @Test fun anUnknownCompoundHasNoSource() {
        val sim = RaceSim(totalLaps = 5)
        val race = sim.controller
        race.setRules(race.rules.copy(totalLaps = 5, startTyre = null))
        sim.start()
        sim.driveLaps(2)
        val lap = assertNotNull(race.currentRecord()).laps.first()
        assertNull(lap.tyreCompound)
        assertNull(lap.tyreSource)
        assertFalse(lap.tyreCertain)
    }
}
