package com.gt7pitwall.core.strategy

/**
 * Réponses stratégiques prêtes pour la radio (jalon C), calculées par le moteur et jamais par l'IA : la stratégie
 * recommandée et pourquoi, prolonger d'un tour, rester dehors quand d'autres rentrent, carburant pour finir, dernier
 * changement de plan. Rien sur les adversaires : GT7 ne transmet ni leurs écarts ni leurs pneus.
 */
data class StrategyBrief(
    val recommendation: String,
    val reason: String,
    val extendOneLap: String,
    val othersPitting: String,
    val fuelToFinish: String,
    val lastChange: String?,
    val basis: List<String>,
    val unverifiedRules: List<String>,
) {
    /** Lignes de faits pour l'IA de la radio (elle reformule, ne calcule pas). */
    fun facts(): List<String> = buildList {
        add("Stratégie recommandée : $recommendation")
        add("Pourquoi : $reason")
        add("Prolonger d'un tour : $extendOneLap")
        add("Si d'autres voitures rentrent aux stands : $othersPitting")
        add("Carburant pour finir : $fuelToFinish")
        lastChange?.let { add("Dernier changement de stratégie : $it") }
        add("Sur quoi reposent les calculs : ${basis.joinToString(" ; ")}.")
        if (unverifiedRules.isNotEmpty()) add("Règlement non vérifié sur : ${unverifiedRules.joinToString(", ")} : stratégie non validée réglementairement.")
    }

    companion object {
        fun of(
            report: StrategyReport,
            plan: RacePlan,
            currentLap: Int,
            fuel: FuelReport?,
            capacity: Float?,
            lastChange: String?,
        ): StrategyBrief {
            val rec = report.recommendedOption
            val next = plan.stops.firstOrNull { it.endOfLap >= currentLap }
            val recommendation = buildString {
                append(rec.kind.label.replaceFirstChar { it.uppercase() })
                if (next == null) {
                    append(" : pas d'arrêt jusqu'à l'arrivée")
                } else {
                    append(" : arrêt à la fin du tour ${next.endOfLap}")
                    next.fuel?.takeIf { next.forFuel }?.let { append(", ${FuelUnits.amount(it.toFloat(), capacity)}") }
                    when {
                        next.tyreToFit != null -> append(", monter les ${next.tyreToFit.spoken}")
                        next.changeTyres -> append(", pneus neufs")
                    }
                }
                append(".")
            }
            val ext = report.option(OptionKind.EXTENDED)
            val extendOneLap = when {
                next == null -> "rien à prolonger : aucun arrêt n'est prévu."
                ext == null -> "pas d'alternative calculée."
                !ext.feasible -> "non, ${ext.issues.firstOrNull() ?: "plan irréalisable"}."
                ext.deltaMs != null && report.uncertaintyMs != null && -ext.deltaMs > report.uncertaintyMs ->
                    "oui, possible jusqu'au tour ${ext.stopLaps.first()} et plus rapide d'environ ${StrategyOptions.seconds(-ext.deltaMs)}."
                else -> "oui, possible jusqu'au tour ${ext.stopLaps.first()} selon notre autonomie estimée, mais sans gain démontré."
            }
            val othersPitting = buildString {
                append("je n'ai aucune information sur leurs écarts ni leurs pneus. ")
                when {
                    next == null -> append("Nous n'avons pas besoin de nous arrêter : on reste dehors.")
                    report.recommended == OptionKind.EARLY -> append("Nos propres données recommandent déjà d'avancer l'arrêt au tour ${rec.stopLaps.first()}.")
                    else -> {
                        append("Nos données ne montrent aucun gain à rentrer maintenant ; ")
                        append("notre arrêt reste prévu fin du tour ${next.endOfLap}")
                        if (ext != null && ext.feasible) append(", et l'autonomie permet même d'aller au tour ${ext.stopLaps.first()}")
                        append(".")
                    }
                }
            }
            val fuelToFinish = when {
                fuel == null -> "pas encore de mesure."
                fuel.fuelToFinish == null || fuel.perLap == null -> fuel.lapsOfFuel?.let { "autonomie de ${"%.1f".format(it).replace('.', ',')} tours, distance restante inconnue." }
                    ?: "consommation pas encore mesurée."
                else -> "il faut ${FuelUnits.amount(fuel.fuelToFinish, capacity)} pour finir, il en reste ${FuelUnits.amount(fuel.fuelLevel, capacity)}" +
                    (fuel.lapsOfFuel?.let { " (${"%.1f".format(it).replace('.', ',')} tours d'autonomie)" } ?: "") + "."
            }
            return StrategyBrief(recommendation, report.reason, extendOneLap, othersPitting, fuelToFinish, lastChange, report.basis, report.unverifiedRules)
        }
    }
}
