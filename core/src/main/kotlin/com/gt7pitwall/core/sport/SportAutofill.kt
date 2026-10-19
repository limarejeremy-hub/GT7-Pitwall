package com.gt7pitwall.core.sport

import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.strategy.RaceRules

/**
 * Règlement d'une épreuve à partir de l'édition hebdomadaire publiée. Seuls les champs connus sont repris ; un champ
 * inconnu ou contradictoire garde la valeur neutre du règlement mais reste marqué inconnu (jamais présenté comme vérifié).
 */
object SportAutofill {
    fun rules(edition: Edition, kind: SessionKind): Pair<RaceRules, Map<RuleField, FieldStatus>> {
        val st = edition.status
        val allowed = edition.allowedTyres.takeIf { it.known }?.value ?: emptySet()
        // Qualification : chrono seul ; on ne reprend que le circuit et les pneus (le reste ne la concerne pas).
        if (kind == SessionKind.QUALIFYING) {
            return RaceRules(trackId = edition.trackId, allowedTyres = allowed, startTyre = allowed.singleOrNull()) to
                mapOf(RuleField.TRACK to st.getValue(RuleField.TRACK), RuleField.ALLOWED_TYRES to st.getValue(RuleField.ALLOWED_TYRES))
        }
        val rules = RaceRules(
            totalLaps = edition.laps.takeIf { it.known }?.value,
            fuelMultiplier = edition.fuelMultiplier.takeIf { it.known }?.value ?: 1,
            tyreMultiplier = edition.tyreMultiplier.takeIf { it.known }?.value ?: 1,
            mandatoryStops = edition.mandatoryStops.takeIf { it.known }?.value ?: 0,
            allowedTyres = allowed,
            requiredTyres = edition.requiredTyres.takeIf { it.known }?.value ?: emptySet(),
            // Une seule gomme autorisée : c'est forcément celle du départ (déduit du règlement, pas de la télémétrie).
            startTyre = allowed.singleOrNull(),
            trackId = edition.trackId,
        )
        return rules to st
    }
}
