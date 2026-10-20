package com.gt7pitwall.core.strategy

/**
 * Gommes de GT7 (codes affichés dans le jeu et dans les règlements).
 * GT7 ne transmet pas la gomme montée : elle est toujours déclarée par le pilote.
 */
enum class TyreCompound(val family: Family, val hardness: Hardness?, val spoken: String) {
    CH(Family.COMFORT, Hardness.HARD, "confort durs"),
    CM(Family.COMFORT, Hardness.MEDIUM, "confort mediums"),
    CS(Family.COMFORT, Hardness.SOFT, "confort tendres"),
    SH(Family.SPORT, Hardness.HARD, "sport durs"),
    SM(Family.SPORT, Hardness.MEDIUM, "sport mediums"),
    SS(Family.SPORT, Hardness.SOFT, "sport tendres"),
    RH(Family.RACING, Hardness.HARD, "course durs"),
    RM(Family.RACING, Hardness.MEDIUM, "course mediums"),
    RS(Family.RACING, Hardness.SOFT, "course tendres"),
    IM(Family.WET, null, "intermédiaires"),
    W(Family.WET, null, "pluie"),
    D(Family.OTHER, null, "terre"),
    ;

    enum class Family { COMFORT, SPORT, RACING, WET, OTHER }
    enum class Hardness { HARD, MEDIUM, SOFT }

    companion object {
        /** "RH|RM", "RH, RM", "rh rm" → {RH, RM}. Les codes inconnus sont ignorés. */
        fun parseCodes(text: String): Set<TyreCompound> =
            text.uppercase().split('|', ',', ';', ' ', '+', '/')
                .mapNotNull { code -> entries.firstOrNull { it.name == code.trim() } }
                .toCollection(linkedSetOf())

        fun codes(set: Collection<TyreCompound>): String = set.joinToString("|") { it.name }

        /**
         * Gomme dans une phrase française normalisée (sans accents) : "les durs", "pneus mediums",
         * "tendres course", "intermediaires". Sans famille précisée, on ne choisit que si le
         * règlement ne laisse qu'une possibilité ; sinon null (l'ingénieur demande de préciser).
         */
        fun fromSpeech(t: String, allowed: Set<TyreCompound>, required: Set<TyreCompound>): TyreCompound? {
            entries.firstOrNull { Regex("(^|\\s)${it.name.lowercase()}($|\\s)").containsMatchIn(t) && it != D }?.let { return it }
            if (Regex("intermediaire").containsMatchIn(t)) return IM
            if (Regex("(pneus? |gommes? )(pluie|wet|mouille)").containsMatchIn(t) || Regex("(^|\\s)wet($|\\s)").containsMatchIn(t)) return W
            val hardness = when {
                Regex("(^|\\s)(durs?|dures?|hard)($|\\s)").containsMatchIn(t) -> Hardness.HARD
                Regex("(^|\\s)(mediums?|moyens?|moyennes?)($|\\s)").containsMatchIn(t) -> Hardness.MEDIUM
                Regex("(^|\\s)(tendres?|softs?)($|\\s)").containsMatchIn(t) -> Hardness.SOFT
                else -> return null
            }
            val family = when {
                // « cause » : erreur réelle de reconnaissance de « course » (« je suis en cause dur »).
                Regex("(^|\\s)(course|cause|racing|competition)($|\\s)").containsMatchIn(t) -> Family.RACING
                Regex("(^|\\s)sports?($|\\s)").containsMatchIn(t) -> Family.SPORT
                Regex("(^|\\s)confort($|\\s)").containsMatchIn(t) -> Family.COMFORT
                else -> null
            }
            val matching = entries.filter { it.hardness == hardness && (family == null || it.family == family) }
            if (matching.size == 1) return matching[0]
            // Famille non dite : le règlement doit lever l'ambiguïté.
            val pool = (allowed + required).filter { it in matching }.distinct()
            return pool.singleOrNull()
        }
    }
}
