package com.gt7pitwall.core.strategy

/** Ce qui prouve un arrêt aux stands. */
enum class StopEvidence {
    /** Le niveau d'essence a augmenté : GT7 ne ravitaille qu'aux stands. */
    REFUEL,

    /** Arrêt sans ravitaillement ressemblant à un passage aux stands, confirmé par le pilote. */
    DRIVER_CONFIRMED,

    /** Arrêt déclaré par le pilote sans détection. */
    DRIVER_DECLARED,
}

/**
 * Pneus à un arrêt. GT7 ne transmet pas le changement de pneus : il est toujours déclaré ou confirmé par le pilote. Un
 * ravitaillement ne prouve pas un changement de pneus, et inversement.
 */
enum class TyreService(val label: String) {
    CHANGED("pneus changés"),
    NOT_CHANGED("mêmes pneus"),

    /** Pas de réponse du pilote : le relais pneus continue, mais son âge devient incertain. */
    UNKNOWN("pneus non précisés"),
}

data class PitStopRecord(
    val number: Int,
    val lap: Int,
    val evidence: StopEvidence,
    val fuelAdded: Float?,
    val tyres: TyreService = TyreService.UNKNOWN,
    /** Diagnostic thermique Tyre Change Hunter de ce passage (indice expérimental, jamais une preuve), s'il a été fait. */
    val thermal: String? = null,
) {
    /** Ravitaillement mesuré (le niveau d'essence a monté). */
    val refuelled: Boolean get() = (fuelAdded ?: 0f) > 0f
}

/**
 * Relais pneus en cours, indépendant du carburant : il commence au départ ou au dernier changement de pneus déclaré.
 * [certain] : faux si un arrêt a eu lieu depuis sans que le pilote dise s'il a changé les pneus.
 */
data class TyreStint(val startLap: Int, val age: Int, val compound: TyreCompound?, val certain: Boolean, val fromStart: Boolean)

/** Arrêt possible (sans ravitaillement) en attente de confirmation du pilote. */
data class StopCandidate(val lap: Int, val locationKnown: Boolean)

/** Changement de pneus déclaré au tour [lap] (gomme inconnue si le pilote ne l'a pas précisée). */
data class TyreChange(val lap: Int, val compound: TyreCompound?)

/**
 * Registre de course : arrêts aux stands avec leur preuve, et gommes déclarées.
 * Un arrêt n'est compté que sur preuve (ravitaillement) ou confirmation / déclaration du pilote.
 * Les gommes ne sont jamais déduites de la télémétrie : GT7 ne les transmet pas.
 */
class PitLedger {
    private val stops = mutableListOf<PitStopRecord>()
    private val tyreChanges = mutableListOf<TyreChange>()

    var pending: StopCandidate? = null
        private set

    /** Après un arrêt, on attend la déclaration des gommes montées (si le règlement en impose). */
    var awaitingTyreDeclaration: Boolean = false

    /** Après un arrêt, on attend un oui / non : « As-tu changé les pneus ? ». */
    var awaitingTyreChangeAnswer: Boolean = false

    val stopList: List<PitStopRecord> get() = stops.toList()
    val stopCount: Int get() = stops.size
    val changes: List<TyreChange> get() = tyreChanges.toList()

    fun reset() {
        stops.clear()
        tyreChanges.clear()
        pending = null
        awaitingTyreDeclaration = false
        awaitingTyreChangeAnswer = false
    }

    /**
     * Rapproche une déclaration faite *pendant le passage au stand* du ravitaillement GT7 qui arrive
     * quelques secondes plus tard. Le contrôleur donne l'identifiant de la déclaration de CETTE visite ;
     * on ne fusionne jamais deux arrêts distincts au seul motif que leurs tours sont voisins.
     */
    fun recordRefuel(lap: Int, amount: Float, declaredInThisVisitNumber: Int? = null): PitStopRecord {
        pending = null
        if (declaredInThisVisitNumber != null) {
            val i = stops.indexOfLast {
                it.number == declaredInThisVisitNumber && it.evidence == StopEvidence.DRIVER_DECLARED &&
                    it.lap - lap in 0..1
            }
            if (i >= 0) {
                val previous = stops[i]
                val measured = previous.copy(lap = lap, evidence = StopEvidence.REFUEL, fuelAdded = amount)
                stops[i] = measured
                return measured
            }
        }
        return add(lap, StopEvidence.REFUEL, amount)
    }

    fun propose(lap: Int, locationKnown: Boolean): StopCandidate =
        StopCandidate(lap, locationKnown).also { pending = it }

    /** Le pilote confirme l'arrêt proposé. null si rien n'était en attente. */
    fun confirmPending(): PitStopRecord? {
        val c = pending ?: return null
        pending = null
        return add(c.lap, StopEvidence.DRIVER_CONFIRMED, null)
    }

    fun rejectPending(): Boolean {
        val had = pending != null
        pending = null
        return had
    }

    /** « J'ai fait mon arrêt » : confirme l'arrêt en attente, sinon l'enregistre comme déclaré. */
    fun declare(lap: Int): PitStopRecord = confirmPending() ?: add(lap, StopEvidence.DRIVER_DECLARED, null)

    /** « Annule l'arrêt » : retire le dernier arrêt (ou l'arrêt en attente). */
    fun cancelLast(): PitStopRecord? {
        if (rejectPending()) return null
        return stops.removeLastOrNull()
    }

    /**
     * Pneus changés au tour [lap]. Un passage aux stands peut chevaucher la ligne (déclaré au tour 6, détecté au tour 7) :
     * un changement déjà noté pour ce passage est enrichi (gomme précisée ou corrigée), jamais doublé, et une gomme
     * connue n'est jamais effacée par une confirmation sans gomme. Renvoie la gomme remplacée, s'il y en avait une
     * différente (correction, pour la traçabilité).
     */
    fun fitTyres(lap: Int, compound: TyreCompound?): TyreCompound? {
        awaitingTyreDeclaration = false
        awaitingTyreChangeAnswer = false
        // L'arrêt de ce passage (fin du tour précédent ou ce tour-ci) a servi aux pneus.
        markTyres(lap, TyreService.CHANGED)
        val i = samePassageChange(lap)
        if (i < 0) {
            tyreChanges += TyreChange(lap, compound)
            return null
        }
        val existing = tyreChanges[i]
        if (compound == null || compound == existing.compound) return null
        tyreChanges[i] = existing.copy(compound = compound)
        return existing.compound
    }

    /** Changement de pneus déjà noté pour le passage aux stands en cours (même tour ou tour précédent), sinon -1. */
    private fun samePassageChange(lap: Int): Int {
        val i = tyreChanges.indexOfLast { lap - it.lap in 0..1 }
        if (i < 0) return -1
        // Un autre arrêt enregistré après ce changement, et après son passage, en fait un passage différent.
        val c = tyreChanges[i]
        return if (stops.any { it.lap > c.lap + 1 }) -1 else i
    }

    /** Changement déjà noté pour ce passage (gomme comprise, éventuellement inconnue). */
    fun changeForPassage(lap: Int): TyreChange? = samePassageChange(lap).takeIf { it >= 0 }?.let { tyreChanges[it] }

    /** « Mêmes pneus » : l'arrêt récent n'a pas touché aux pneus, le relais pneus continue avec certitude. */
    fun keepTyres(lap: Int) {
        awaitingTyreDeclaration = false
        awaitingTyreChangeAnswer = false
        markTyres(lap, TyreService.NOT_CHANGED)
    }

    /** Verdict du diagnostic thermique, noté sur l'arrêt de ce passage (sans rien changer aux pneus). */
    fun setThermal(lap: Int, verdict: String) {
        val i = stops.indexOfLast { lap - it.lap in 0..2 }
        if (i >= 0) stops[i] = stops[i].copy(thermal = verdict)
    }

    private fun markTyres(lap: Int, service: TyreService) {
        val i = stops.indexOfLast { lap - it.lap in 0..2 }
        if (i >= 0 && (stops[i].tyres == TyreService.UNKNOWN || service == TyreService.CHANGED)) stops[i] = stops[i].copy(tyres = service)
    }

    /** Relais pneus au tour [currentLap], d'après les seules déclarations de changement (jamais un ravitaillement). */
    fun tyreStint(currentLap: Int, start: TyreCompound?): TyreStint {
        val last = tyreChanges.lastOrNull()
        val since = last?.lap ?: 1
        val unknownSince = stops.any { it.tyres == TyreService.UNKNOWN && (last == null || it.lap >= last.lap - 1) }
        // Arrêt sans essence observé : même avant le « oui » du pilote, il invalide la certitude
        // de la gomme et de son âge. Son absence au registre ne doit pas effacer la suspicion.
        val unresolved = pending?.let { it.lap >= since - 1 } == true
        return TyreStint(since, tyreAge(currentLap), currentTyre(start), certain = !unknownSince && !unresolved, fromStart = last == null)
    }

    /** Gommes utilisées d'après les déclarations du pilote (départ + changements). */
    fun tyresUsed(start: TyreCompound?): Set<TyreCompound> =
        (listOfNotNull(start) + tyreChanges.mapNotNull { it.compound }).toCollection(linkedSetOf())

    fun currentTyre(start: TyreCompound?): TyreCompound? = if (tyreChanges.isEmpty()) start else tyreChanges.last().compound

    /**
     * Tours bouclés avec le train de pneus actuel avant le tour [currentLap].
     * Un changement déclaré pendant le tour L (arrêt à la fin du tour L-1, ou dans la voie des stands) remet le compteur à zéro à partir de ce tour.
     */
    fun tyreAge(currentLap: Int): Int {
        val since = tyreChanges.lastOrNull()?.lap ?: 1
        return (currentLap - since).coerceAtLeast(0)
    }

    fun missingRequired(rules: RaceRules): Set<TyreCompound> = rules.requiredTyres - tyresUsed(rules.startTyre)

    private fun add(lap: Int, evidence: StopEvidence, amount: Float?): PitStopRecord =
        // Un changement déjà déclaré pour ce passage (« pneus changés » avant la détection du plein) est gardé.
        PitStopRecord(
            stops.size + 1, lap, evidence, amount,
            tyres = if (tyreChanges.any { it.lap - lap in 0..2 }) TyreService.CHANGED else TyreService.UNKNOWN,
        ).also { stops += it }
}
