package com.gt7pitwall.core.sport

import com.gt7pitwall.core.strategy.TyreCompound

/**
 * Ce que l'ingénieur dit d'une course Sport reconnue ou probable. Chaque élément n'est dit que s'il est vérifié :
 * - le nombre de tours, seulement celui annoncé par GT7 (mesure de la PS5) ;
 * - les obligations (gommes imposées, arrêts, multiplicateurs), seulement si une fiche publique les donne ; avec une
 *   seule source, la source est nommée ; contradictoires ou absentes, elles sont dites « non confirmées », jamais
 *   remplacées par une valeur par défaut.
 *
 * Exemple (deux sources concordantes) : « Course Sport C reconnue. 14 tours. Gommes course durs et course mediums
 * imposées. Je prépare la stratégie. »
 */
object SportAnnouncement {
    fun text(match: SessionMatcher.Result.Match, gt7Laps: Int?): String = buildString {
        val ed = match.edition
        val name = match.series.label
        when (match.confidence) {
            SessionMatcher.Confidence.RECOGNIZED -> append("$name reconnue.")
            SessionMatcher.Confidence.PROBABLE -> append("$name probable (${match.evidence.joinToString(", ")}).")
        }
        gt7Laps?.takeIf { it > 0 }?.let { append(" $it tours.") }
        append(" ").append(tyres(ed))
        stops(ed)?.let { append(" ").append(it) }
        multipliers(ed)?.let { append(" ").append(it) }
        append(
            when (match.confidence) {
                SessionMatcher.Confidence.RECOGNIZED -> " Je prépare la stratégie."
                SessionMatcher.Confidence.PROBABLE -> " J'applique ce règlement et je confirme au premier tour."
            },
        )
    }

    /** « selon Victory » quand une seule source donne la valeur ; rien quand deux sources concordent. */
    private fun origin(f: Sourced<*>): String =
        if (f.status == FieldStatus.PROBABLE) " selon ${f.sources.map { it.name }.distinct().joinToString(" et ")}" else ""

    private fun spoken(set: Set<TyreCompound>): String =
        set.sortedBy { it.ordinal }.joinToString(" et ") { it.spoken }

    private fun tyres(ed: Edition): String {
        val req = ed.requiredTyres
        val allowed = ed.allowedTyres
        return when {
            req.known && req.value!!.isNotEmpty() -> {
                val s = req.value
                "Gomme${if (s.size > 1) "s" else ""} ${spoken(s)} imposée${if (s.size > 1) "s" else ""}${origin(req)}."
            }
            req.known -> "Aucune gomme imposée${origin(req)}."
            // Une seule gomme autorisée : c'est forcément celle de toute la course (règle déduite, pas devinée).
            allowed.known && allowed.value!!.size == 1 -> "Pneus ${spoken(allowed.value)} uniquement${origin(allowed)}."
            req.status == FieldStatus.CONFLICT -> "Gommes imposées : les sources se contredisent, non confirmé."
            else -> "Gommes imposées : non confirmé."
        }
    }

    private fun stops(ed: Edition): String? {
        val st = ed.mandatoryStops
        return when {
            st.known && st.value!! > 0 -> "${st.value} arrêt${if (st.value > 1) "s" else ""} obligatoire${if (st.value > 1) "s" else ""}${origin(st)}."
            st.status == FieldStatus.CONFLICT -> "Arrêts obligatoires : les sources se contredisent."
            else -> null
        }
    }

    private fun multipliers(ed: Edition): String? {
        val parts = listOfNotNull(
            ed.fuelMultiplier.takeIf { it.known && it.value!! > 1 }?.let { "carburant fois ${it.value}" },
            ed.tyreMultiplier.takeIf { it.known && it.value!! > 1 }?.let { "usure fois ${it.value}" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.uppercase() }?.plus(".")
    }
}
