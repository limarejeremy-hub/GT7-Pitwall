package com.gt7pitwall.core.race

import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreService

/** Comment une session s'est terminée. */
enum class SessionOutcome {
    /** Enregistrée au fil des tours : l'application tourne encore (ou s'est arrêtée brutalement). */
    IN_PROGRESS,

    /** Dernier tour bouclé (GT7 a annoncé l'arrivée). */
    FINISHED,

    /** Session quittée, télémétrie arrêtée ou application fermée avant l'arrivée. */
    INTERRUPTED,
}

/**
 * Un tour mesuré. [fuelUsed] : consommation mesurée sur le tour entier sans ravitaillement (unité de GT7), sinon null
 * (inconnue, jamais devinée).
 */
data class LapEntry(
    val lap: Int,
    val timeMs: Int?,
    val fuelUsed: Float?,
    val refuelled: Boolean,
    /** Carburant à bord à la fin du tour, si observé. */
    val fuelAfter: Float? = null,
    /**
     * Relais pneus (indépendant du carburant) : tours déjà bouclés avec ce train au début du tour, gomme déclarée, et
     * certitude (faux si un arrêt sans réponse sur les pneus a eu lieu depuis le dernier changement déclaré).
     */
    val tyreAge: Int? = null,
    val tyreCompound: TyreCompound? = null,
    val tyreCertain: Boolean = true,
    /**
     * D'où vient la gomme (essai du 28/10 : « RH, certaine » dans l'archive alors qu'elle était seulement déclarée).
     * GT7 ne transmet jamais la gomme : [tyreCertain] est la certitude de la DÉCLARATION, jamais une mesure.
     */
    val tyreSource: TyreSource? = null,
    /**
     * Certitude de l'âge des pneus seule (changement déclaré ou départ), indépendante de la gomme. [tyreCertain] exige
     * en plus une gomme connue : une gomme inconnue n'est jamais « certaine ».
     */
    val tyreAgeCertain: Boolean = tyreCertain,
    /** Tour commencé dans la voie des stands (consommation atypique, exclue des moyennes). */
    val pitLane: Boolean = false,
    /** Format C : part du tour sur un vibreur (0..1) et sorties de piste mesurées. */
    val kerbShare: Float? = null,
    val offTrackEvents: Int? = null,
    /** Mesures GT7 du tour (null si le tour n'a pas été suivi en entier). */
    val tcsShare: Float? = null,
    val lockups: Int? = null,
    val wheelspins: Int? = null,
    /** Blocages + patinages à l'avant et à l'arrière. */
    val frontEvents: Int? = null,
    val rearEvents: Int? = null,
    /** Températures moyennes des quatre pneus (avant gauche, avant droit, arrière gauche, arrière droit). */
    val wheelTemps: List<Float>? = null,
)

/**
 * Arrêt avec sa preuve (mesurée : ravitaillement ; déclarée ou confirmée par le pilote) et ce qui a été fait aux pneus
 * (déclaré ; inconnu si le pilote n'a pas répondu, ou pour les sessions archivées avant ce suivi).
 */
data class StopEntry(
    val lap: Int,
    val evidence: StopEvidence,
    val fuelAdded: Float?,
    val tyres: TyreService = TyreService.UNKNOWN,
    /** Verdict expérimental du Tyre Change Hunter (absent des sessions plus anciennes). */
    val thermal: String? = null,
)

/** Pneus déclarés par le pilote (GT7 ne transmet pas la gomme). */
data class TyreEntry(val lap: Int, val compound: TyreCompound?)

/**
 * Plan annoncé à un moment de la course : tours d'arrêt prévus, consommation sur laquelle il reposait (pour mesurer
 * ensuite l'erreur de prévision), et pourquoi il a changé.
 */
data class PlanEntry(
    val lap: Int,
    val stopLaps: List<Int>,
    val reason: String? = null,
    val fuelPerLap: Float? = null,
    /** MEASURED, HISTORY… (d'où venait la consommation). */
    val fuelSource: String? = null,
    val pitLossMs: Int? = null,
)

/** Déclaration utile du pilote (« je perds l'avant », « on rentre au tour 8 »), jamais une mesure. */
data class DriverNote(val lap: Int, val text: String)

/**
 * Session archivée : de quoi comparer les courses entre elles. Les mesures (tours, ravitaillements) sont séparées
 * des déclarations (pneus, notes, arrêts déclarés) et du plan (prévision) ; rien n'est complété par supposition.
 */
data class SessionRecord(
    val id: String,
    val context: SessionContext,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val outcome: SessionOutcome,
    val laps: List<LapEntry>,
    val stops: List<StopEntry> = emptyList(),
    val startTyre: TyreCompound? = null,
    val tyres: List<TyreEntry> = emptyList(),
    val plans: List<PlanEntry> = emptyList(),
    val notes: List<DriverNote> = emptyList(),
    val gridPosition: Int? = null,
    val finishPosition: Int? = null,
    /** Bilan de pilotage de fin de course (absent des anciennes sessions et des courses interrompues). */
    val report: List<String> = emptyList(),
) {
    val completedLaps: Int get() = laps.size

    /** Plan annoncé en premier (la stratégie prévue) et dernier plan (ce qui a été suivi). */
    val plannedStops: List<Int>? get() = plans.firstOrNull()?.stopLaps
    val actualStops: List<Int> get() = stops.map { it.lap }

    companion object {
        const val SCHEMA = 1
    }
}

/** Ce que l'historique contient pour l'épreuve active. */
data class HistorySummary(
    /** Sessions archivées de la même épreuve (série × qualification/course). */
    val sameProfile: Int,
    /** Dont comparables : même voiture, circuit et multiplicateurs (et même version si déclarée). */
    val comparable: Int,
    /** Dont même règlement complet (tours, arrêts, gommes imposées). */
    val sameEvent: Int,
    /** Tours mesurés dans les sessions comparables. */
    val comparableLaps: Int,
    /** Dont sessions d'un autre type (entraînement, type non précisé) reproduisant exactement l'épreuve. */
    val related: Int = 0,
) {
    companion object {
        val EMPTY = HistorySummary(0, 0, 0, 0)

        fun of(context: SessionContext, sessions: Collection<SessionRecord>, excludeId: String? = null): HistorySummary {
            val same = sessions.filter { it.id != excludeId && it.context.profile.id == context.profile.id }
            val levels = same.map { it to Compatibility.compare(context, it.context).first }
            // Entraînements et sessions au type non précisé : seulement s'ils reproduisent exactement l'épreuve.
            val related = sessions.filter {
                it.id != excludeId && HistoryRelation.of(context.profile.id, it.context.profile.id) == HistoryRelation.RELATED &&
                    Compatibility.compare(context, it.context).first == Comparability.SAME_EVENT
            }
            val comparable = levels.filter { it.second != Comparability.INCOMPATIBLE }.map { it.first } + related
            return HistorySummary(
                sameProfile = same.size,
                comparable = comparable.size,
                sameEvent = levels.count { it.second == Comparability.SAME_EVENT } + related.size,
                comparableLaps = comparable.sumOf { it.completedLaps },
                related = related.size,
            )
        }
    }
}

/** Provenance de la gomme d'un tour. Il n'existe pas de valeur « mesurée » : l'UDP de GT7 ne contient pas la gomme. */
enum class TyreSource(val code: String, val label: String) {
    DECLARED_CHANGE("declared_change", "changement déclaré par le pilote"),
    DECLARED_START("declared_start", "gomme de départ saisie (pilote ou règlement)"),
    ;

    companion object {
        fun of(code: String?): TyreSource? = entries.firstOrNull { it.code == code }
    }
}
