package com.gt7pitwall.core.strategy

import kotlin.math.max

/**
 * V512 lot 2 — décision carburant quand l'essence à bord ne suffit pas pour finir avec la marge de sécurité : économiser
 * jusqu'à l'arrivée, ou faire un arrêt de plus.
 *
 * Course du Mans du 23/11 (14 h) : 37,4 % ajoutés au tour 7 pour 43 % recommandés. Pitwall a planifié un arrêt de 6 %
 * (≈ 27 s au stand) alors que le pilote a fini sans s'arrêter avec 0,1 % (MESURE). Il fallait chiffrer l'économie.
 *
 * Natures :
 * - consommation par tour, carburant à bord, tours restants : MESURE ou CALCUL du moteur ;
 * - coût d'un arrêt : CALCUL (temps au stand mesuré sur les courses comparables, sinon base de données du circuit) ;
 * - coût de l'économie : **ESTIMATION** ([liftCostMsPerUnit], voir sa documentation) ;
 * - économie réalisable : **ESTIMATION** ([maxSavingFraction]).
 *
 * Sécurité : la cible d'économie vise toujours l'arrivée AVEC la marge [safetyLaps]. Une économie plus forte que
 * [maxSavingFraction] n'est jamais conseillée quand un arrêt est encore possible. La décision est revue à chaque tour.
 */
object FuelIntelligence {
    /**
     * Temps perdu par unité de carburant économisée en lift-and-coast (ms). ESTIMATION fondée sur un seul tour réel :
     * Le Mans 23/11, Porsche 963, tour 14 à 8,42 contre 9,6–10,1 aux tours 9 à 13, pour 225,1 s contre 221,0 s de
     * moyenne, soit environ 3,2 s par unité. Un seul tour : à apprendre sur d'autres courses.
     */
    const val LIFT_COST_MS_PER_UNIT = 3_200.0

    /**
     * Part maximale de la consommation qu'on peut économiser sans risque (ESTIMATION) : 14 % observés sur le même tour 14
     * (un seul tour, possiblement aidé par la fin de course), gardé plus bas par prudence.
     */
    const val MAX_SAVING_FRACTION = 0.10

    /** L'économie doit coûter nettement moins que l'arrêt (incertitude de l'estimation) pour être préférée. */
    const val SAVE_MARGIN = 0.8

    enum class Kind {
        /** Assez de carburant avec la marge. */
        ENOUGH,
        /** Économie réalisable et moins coûteuse qu'un arrêt : rester en piste avec une consommation cible. */
        SAVE,
        /** Économie trop forte ou plus coûteuse : un arrêt de plus. */
        STOP,
        /** Plus aucun arrêt possible (dernier tour) : économie, seule option. */
        SAVE_NO_STOP_LEFT,
        /** Plus aucun arrêt possible et l'économie nécessaire dépasse ce qui est réaliste : risque de finir à sec. */
        CRITICAL,
    }

    data class Input(
        val fuelNow: Float,
        /** Consommation par tour MESURÉE en course (aucune décision d'économie sur un historique). */
        val perLap: Float,
        /** Tours de course restants, tour en cours compris (fraction restante + tours suivants). */
        val lapsToGo: Float,
        /** Un arrêt est-il encore possible (au moins un tour après le tour en cours) ? */
        val stopPossible: Boolean,
        /** Temps perdu par un arrêt carburant seul (ms, CALCUL), null si inconnu. */
        val pitLossMs: Double?,
        val tank: Float,
        val safetyLaps: Float = RacePlanner.SAFETY_LAPS,
        val liftCostMsPerUnit: Double = LIFT_COST_MS_PER_UNIT,
        val maxSavingFraction: Double = MAX_SAVING_FRACTION,
    )

    data class Result(
        val kind: Kind,
        /** Carburant manquant pour finir avec la marge (unités GT7, CALCUL) ; ≤ 0 : rien ne manque. */
        val deficit: Float,
        /** Carburant manquant pour finir sans marge (CALCUL). */
        val deficitNoMargin: Float,
        /** Consommation par tour à viser pour finir avec la marge (CALCUL). */
        val targetPerLap: Float?,
        /** Part de la consommation à économiser (CALCUL), 0 si rien ne manque. */
        val savingFraction: Double,
        /** Temps estimé perdu en économisant (ESTIMATION), null si rien ne manque. */
        val liftLossMs: Double?,
        /** Temps perdu par un arrêt (CALCUL), null si inconnu. */
        val pitLossMs: Double?,
        val tank: Float,
    ) {
        fun pct(x: Float): Float = if (tank > 0f) x / tank * 100f else x
    }

    fun decide(i: Input): Result {
        val perLap = i.perLap
        val toGo = i.lapsToGo.coerceAtLeast(0f)
        val needMargin = (toGo + i.safetyLaps) * perLap
        val deficit = needMargin - i.fuelNow
        val deficitNoMargin = toGo * perLap - i.fuelNow
        if (deficit <= 0f || toGo <= 0f || perLap <= 0f) {
            return Result(Kind.ENOUGH, deficit, deficitNoMargin, null, 0.0, null, i.pitLossMs, i.tank)
        }
        // Cible avec la marge : la marge de sécurité reste dans le réservoir à l'arrivée. Plus aucun arrêt possible : la
        // marge ne peut plus être reconstituée, la cible est d'arriver (Le Mans 23/11, tour 14 : cible 8,5 sans marge,
        // 8,42 consommés réellement, arrivée avec 0,1).
        val usable = if (i.stopPossible) max(0f, i.fuelNow - i.safetyLaps * perLap) else i.fuelNow
        val target = usable / toGo
        val saving = (1.0 - target / perLap).coerceIn(0.0, 1.0)
        val liftLoss = deficit * i.liftCostMsPerUnit
        val feasible = saving <= i.maxSavingFraction
        val kind = when {
            !i.stopPossible -> if (saving <= i.maxSavingFraction * 1.5) Kind.SAVE_NO_STOP_LEFT else Kind.CRITICAL
            !feasible -> Kind.STOP
            // Coût de l'arrêt inconnu : jamais d'économie présentée comme plus rentable sans le chiffre.
            i.pitLossMs == null -> Kind.STOP
            liftLoss <= i.pitLossMs * SAVE_MARGIN -> Kind.SAVE
            else -> Kind.STOP
        }
        return Result(kind, deficit, deficitNoMargin, target, saving, liftLoss, i.pitLossMs, i.tank)
    }

    private fun f1(x: Double) = String.format(java.util.Locale.FRANCE, "%.1f", x)

    /** Phrase radio de la décision (unités affichées en % du réservoir, comme le reste de la radio). */
    fun sentence(r: Result): String? {
        val missing = "Il manque environ ${f1(r.pct(r.deficit).toDouble())} % pour finir avec la marge de sécurité."
        val target = r.targetPerLap?.let { "vise ${f1(r.pct(it).toDouble())} % par tour" }
        val cost = r.liftLossMs?.let { "environ ${f1(it / 1000.0)} s perdues (estimation)" }
        return when (r.kind) {
            Kind.ENOUGH -> null
            Kind.SAVE -> "$missing Économie plutôt qu'un arrêt : $target, soit ${f1(r.savingFraction * 100)} % de moins, " +
                "$cost contre ${f1((r.pitLossMs ?: 0.0) / 1000.0)} s pour un arrêt court (estimation). Lève le pied avant les gros freinages."
            Kind.STOP -> when {
                r.savingFraction > MAX_SAVING_FRACTION ->
                    "$missing L'économie nécessaire (${f1(r.savingFraction * 100)} % par tour) est trop forte : il faut un arrêt."
                r.pitLossMs == null -> "$missing Coût d'un arrêt pas encore mesuré : par sécurité, je garde un arrêt court."
                else -> "$missing Un arrêt court coûte moins : environ ${f1(r.pitLossMs / 1000.0)} s au stand contre " +
                    "${f1((r.liftLossMs ?: 0.0) / 1000.0)} s d'économie (estimations)."
            }
            Kind.SAVE_NO_STOP_LEFT -> "Carburant juste pour finir : $target, ${f1(r.savingFraction * 100)} % de moins. Lève le pied avant les freinages."
            Kind.CRITICAL -> "Carburant critique : économise au maximum, lève le pied dans chaque ligne droite. " +
                "Il faudrait ${f1(r.savingFraction * 100)} % de moins par tour."
        }
    }
}
