package com.gt7pitwall.core.race

/**
 * D'où vient le circuit retenu pour une session (issue #51). Seule une observation de la PS5 indépendante de la fiche
 * Sport peut faire reconnaître une épreuve ; le nom publié par la fiche est une attente, jamais une preuve.
 */
enum class TrackEvidence(val label: String) {
    /** Circuit annoncé par la fiche de la semaine : utile avant la grille, jamais une preuve. */
    SCHEDULE_EXPECTED("attendu d'après la fiche Sport"),

    /** Longueur du tour mesurée compatible : circuit probable (des circuits voisins existent). */
    LAP_LENGTH_COMPATIBLE("longueur du tour compatible"),

    /** Tracé X/Z mesuré par la PS5 conforme à une signature : seule preuve indépendante du circuit. */
    GEOMETRY_MEASURED("tracé mesuré par la PS5"),

    /** Circuit choisi ou dit par le pilote. */
    PILOT_DECLARED("déclaré par le pilote"),
}
