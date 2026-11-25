package com.gt7pitwall.core.strategy

/**
 * V515 — scénarios EXPLICITEMENT SIMULÉS pour éprouver le Strategy Engine et le contrôleur (jamais une course réelle,
 * jamais une victoire démontrée). Chiffres choisis pour isoler un mécanisme ; ils ne décrivent aucun circuit GT7.
 */
object StrategySimulations {
    data class Case(
        val id: String,
        val title: String,
        val input: StrategyEngine.Input,
        val rulesValid: Boolean = true,
        val telemetryAgeMs: Long? = 50,
        /** Ce que le scénario doit montrer (texte pour le rapport). */
        val expectation: String,
    )

    private val costs = StopCosts(
        base = Cost(25_000.0, 2_000.0, "SIMULATION : passage aux stands 25 s ± 2 s"),
        tyreService = Cost(8_000.0, 1_000.0, "SIMULATION : pneus 8 s ± 1 s"),
    )

    private fun base(
        currentLap: Int = 5, totalLaps: Int = 12, fuelNow: Float = 55f, perLap: Float? = 9f, tyreAge: Int = 4,
        slope: Double? = null, slopeSpread: Double = 0.0, planned: List<PlannedStop> = emptyList(), tyreLife: Int? = null,
        mandatory: Int = 0, outLap: Double? = null, tank: Float = 100f, pitEntry: Boolean = true,
    ) = StrategyEngine.Input(
        currentLap = currentLap, lapFractionLeft = 1f, totalLaps = totalLaps, fuelNow = fuelNow, tank = tank, perLap = perLap,
        perLapMeasured = true, tyreAge = tyreAge, tyreLifeLaps = tyreLife, paceMs = 100_000.0, paceSpreadMs = 300.0,
        slopeMsPerLap = slope, slopeSpread = slopeSpread, outLapPenaltyMs = outLap, outLapSpread = outLap?.let { it * 0.3 } ?: 0.0,
        stopCosts = costs, mandatoryStopsLeft = mandatory, plannedStops = planned, pitEntryStillPossible = pitEntry,
    )

    private fun stop(lap: Int, tyres: Boolean = false, fuel: Boolean = true, mandatory: Boolean = false) =
        PlannedStop(lap, null, forFuel = fuel, mandatory = mandatory, changeTyres = tyres)

    val cases: List<Case> = listOf(
        Case("undercut-rentable", "Undercut rentable : pneus qui perdent 0,6 s par tour",
            base(tyreAge = 10, slope = 600.0, slopeSpread = 50.0, planned = listOf(stop(9, tyres = true)), outLap = 1_500.0),
            expectation = "arrêt anticipé plus rapide au-delà de l'incertitude ; potentiel conditionnel sans référence adverse"),
        Case("undercut-perdant", "Undercut sans gain : dégradation faible, tour de sortie lent",
            base(tyreAge = 10, slope = 30.0, slopeSpread = 30.0, planned = listOf(stop(9, tyres = true)), outLap = 3_000.0),
            expectation = "aucun gain démontré : on garde le plan"),
        Case("overcut-rentable", "Overcut rentable : pneus récents, arrêt prévu trop tôt",
            base(tyreAge = 1, slope = 800.0, slopeSpread = 80.0, planned = listOf(stop(6, tyres = true))),
            expectation = "arrêt retardé plus rapide au-delà de l'incertitude"),
        Case("pneus-neufs-sans-gain", "Pneus neufs sans gain mesurable (dégradation inconnue)",
            base(planned = listOf(stop(8))),
            expectation = "changer les pneus au ravitaillement coûte le service des pneus ; le plan sans pneus est gardé"),
        Case("pneus-imposent-arret", "Changer les pneus plus tôt imposerait un arrêt supplémentaire (durée déclarée 6 tours)",
            base(totalLaps = 16, fuelNow = 100f, perLap = 8f, tyreAge = 0, tyreLife = 6, planned = listOf(stop(10, tyres = true, fuel = false))),
            expectation = "l'arrêt anticipé avec un seul arrêt est impossible (pneus au-delà de leur durée) ; un arrêt au tour 10 suffit"),
        Case("economie-evite-arret", "Une économie de carburant permettrait d'éviter l'arrêt",
            base(fuelNow = 70f, planned = listOf(stop(8))),
            expectation = "aller au bout est faisable avec environ 6 % d'économie, mais son coût est trop incertain pour être recommandé"),
        Case("plein-insuffisant", "Carburant insuffisant pour atteindre l'arrêt prévu",
            base(fuelNow = 20f, planned = listOf(stop(8))),
            expectation = "plan actuel infaisable : arrêt plus tôt recommandé"),
        Case("reglement-impossible", "Deux arrêts obligatoires restants au tour 11 sur 12",
            base(currentLap = 11, fuelNow = 40f, mandatory = 2),
            expectation = "aucune stratégie faisable : le contrôleur bloque"),
        Case("conso-en-hausse", "Consommation soudain plus forte (11 au lieu de 9)",
            base(fuelNow = 40f, perLap = 11f, planned = listOf(stop(8))),
            expectation = "l'arrêt prévu n'est plus atteignable : arrêt avancé"),
        Case("arret-manque", "Arrêt prévu au tour 7 manqué (on est au tour 8)",
            base(currentLap = 8, fuelNow = 20f, planned = listOf(stop(7))),
            expectation = "le plan n'a plus d'arrêt et le carburant manque : arrêt au tour en cours recommandé"),
        Case("dernier-tour", "Dernier tour",
            base(currentLap = 12, fuelNow = 15f),
            expectation = "aucun arrêt possible ; finir"),
        Case("adversaires-absents", "Aucune donnée adverse (GT7 ne transmet ni écarts ni arrêts)",
            base(tyreAge = 10, slope = 600.0, slopeSpread = 50.0, planned = listOf(stop(9, tyres = true))),
            expectation = "undercut seulement conditionnel, jamais un dépassement annoncé"),
        Case("reglement-perime", "Fiche Sport périmée",
            base(planned = listOf(stop(8))), rulesValid = false,
            expectation = "le contrôleur rend la stratégie provisoire"),
        Case("telemetrie-coupee", "Télémétrie interrompue depuis 10 s",
            base(planned = listOf(stop(8))), telemetryAgeMs = 10_000,
            expectation = "le contrôleur rend la stratégie provisoire"),
    )

    /** État stratégique minimal correspondant à un cas simulé (pour le contrôleur). */
    fun state(c: Case, atMs: Long = 1_000_000L): StrategyState {
        val i = c.input
        fun <T> m(v: T?, unit: String) = Sourced(v, unit, "SIMULATION", Nature.MESURE, atMs)
        return StrategyState(
            atMs = atMs,
            track = Sourced.unknown("", "SIMULATION"), currentLap = m(i.currentLap, "tour"), totalLaps = m(i.totalLaps, "tours"),
            lapsRemaining = m(i.totalLaps - i.currentLap + i.lapFractionLeft, "tours"), timeRemainingMs = Sourced.unknown("ms", "course aux tours"),
            position = Sourced.unknown("", "SIMULATION"), rulesValid = Sourced(c.rulesValid, "", if (c.rulesValid) "règlement de la simulation" else "fiche Sport périmée", Nature.CALCUL),
            rulesIssues = if (c.rulesValid) emptyList() else listOf("fiche Sport périmée"),
            mandatoryStopsLeft = m(i.mandatoryStopsLeft, "arrêts"),
            fuelNow = m(i.fuelNow, "unités"), tank = m(i.tank, "unités"),
            perLap = i.perLap?.let { m(it, "unités/tour") } ?: Sourced.unknown("unités/tour", "consommation inconnue"),
            perLapTrend = Sourced.unknown("unités/tour", "SIMULATION"), lapsOfFuel = m(i.perLap?.let { i.fuelNow / it }, "tours"),
            safetyLaps = StrategyEngine.SAFETY_LAPS, maxSaving = Sourced(i.maxSaving, "fraction", "garde-fou V512", Nature.CALCUL),
            compound = Sourced.unknown("", "non déclarée"), tyreAge = m(i.tyreAge, "tours"), tyreAgeCertain = i.tyreAgeCertain,
            tyreAnomalies = emptyList(), gripLoss = Sourced.unknown("", "SIMULATION"),
            degradationMsPerLap = i.slopeMsPerLap?.let { Sourced(it, "ms/tour", "SIMULATION", Nature.ESTIMATION) } ?: Sourced.unknown("ms/tour", "inconnue"),
            degradationSpreadMs = i.slopeSpread, freshTyreGainMs = Sourced.unknown("ms", "SIMULATION"),
            pitZoneValidated = false, pitEntryStillPossible = i.pitEntryStillPossible, stopCosts = i.stopCosts,
            refuelMsPerUnit = Sourced.unknown("ms/unité", "inconnu"), outLapPenaltyMs = i.outLapPenaltyMs?.let { Sourced(it, "ms", "SIMULATION", Nature.ESTIMATION) } ?: Sourced.unknown("ms", "non démontrée"),
            plannedStops = i.plannedStops, cars = Sourced.unknown("", "SIMULATION"), opponentsDeclared = emptyList(),
            paceMs = Sourced(i.paceMs, "ms", "SIMULATION", Nature.MESURE, atMs), paceSpreadMs = i.paceSpreadMs,
            telemetryAgeMs = c.telemetryAgeMs,
        )
    }
}
