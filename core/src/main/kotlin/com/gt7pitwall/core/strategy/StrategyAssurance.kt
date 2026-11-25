package com.gt7pitwall.core.strategy

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * V515-B — contrôleur de fiabilité, INDÉPENDANT du moteur : il relit le résultat de [StrategyEngine] et l'état
 * [StrategyState] avant toute annonce. Une stratégie qui n'est pas approuvée n'est jamais présentée comme certaine.
 * Il ne supprime jamais une alerte de sécurité : carburant critique, obligations et rappels « Box » restent ceux du plan.
 *
 * Il garde aussi la décision stable : un nouveau choix ne remplace l'ancien que si son avantage dépasse nettement
 * l'incertitude (hystérésis), pour ne pas changer d'avis à chaque tour.
 */
class StrategyAssurance {
    enum class Verdict(val label: String) {
        APPROVED("approuvé"),
        PROVISIONAL("provisoire, à surveiller"),
        BLOCKED("bloqué"),
        INSUFFICIENT("données insuffisantes"),
    }

    data class Check(val name: String, val ok: Boolean?, val detail: String)

    /** Faisabilité ≠ rentabilité : un arrêt peut être faisable sans être démontré plus rapide. */
    enum class Profitability(val label: String) {
        PROVEN("gain démontré par les données"),
        NOT_PROVEN("faisable, rentabilité non démontrée"),
        NOT_APPLICABLE("plan actuel gardé : aucun gain revendiqué"),
    }

    data class Decision(
        val verdict: Verdict,
        /** Scénario retenu (le plan actuel si rien n'est démontré meilleur ou si le choix est bloqué). */
        val chosen: StrategyEngine.Scenario?,
        /** Scénario que le moteur proposait (peut différer de [chosen] : stabilité, blocage). */
        val proposed: StrategyEngine.Scenario?,
        val checks: List<Check>,
        val reasons: List<String>,
        /** Phrase pour Véga : recommandation, raison, conséquence, réserve. */
        val statement: String,
        val lap: Int,
        val atMs: Long,
        val profitability: Profitability = Profitability.NOT_APPLICABLE,
        /** Hypothèses inconnues ou supposées dont dépend le gain annoncé. */
        val dependencies: List<String> = emptyList(),
        /** Ce qui pèse dans l'écart avec le plan actuel (cause, ms), du plus lourd au plus léger. */
        val drivers: List<Pair<String, Double>> = emptyList(),
    ) {
        val changed: Boolean get() = proposed != null && chosen != null && proposed.key != chosen.key
    }

    var last: Decision? = null
        private set

    fun reset() { last = null }

    /**
     * [reevaluate] : réévalue un plan (tours, pneus) avec les données actuelles. Sans elle, un plan précédent absent des
     * scénarios A à J n'est jamais gardé (pas de plan périmé repris tel quel).
     */
    fun review(
        state: StrategyState, report: StrategyEngine.Report, nowMs: Long = state.atMs,
        reevaluate: ((List<Pair<Int, Boolean>>) -> StrategyEngine.Scenario?)? = null,
    ): Decision {
        val checks = mutableListOf<Check>()
        val reasons = mutableListOf<String>()
        val blocks = mutableListOf<String>()
        var verdict = Verdict.APPROVED
        // Gravité : approuvé < provisoire < données insuffisantes < bloqué (un blocage n'est jamais masqué).
        fun rank(v: Verdict) = when (v) { Verdict.APPROVED -> 0; Verdict.PROVISIONAL -> 1; Verdict.INSUFFICIENT -> 2; Verdict.BLOCKED -> 3 }
        fun worse(v: Verdict) { if (rank(v) > rank(verdict)) verdict = v }
        fun block(why: String) { worse(Verdict.BLOCKED); blocks += why }
        val lap = state.currentLap.value ?: 0
        val okNatures = setOf(Nature.MESURE, Nature.SIMULATION)

        // 1. Télémétrie et fraîcheur.
        val age = state.telemetryAgeMs
        if (age == null || age > TELEMETRY_STALE_MS) {
            checks += Check("télémétrie", false, if (age == null) "aucun paquet GT7" else "dernier paquet il y a ${age / 1000} s")
            worse(if (age == null) Verdict.INSUFFICIENT else Verdict.PROVISIONAL)
            reasons += "télémétrie interrompue"
        } else checks += Check("télémétrie", true, "paquet reçu il y a ${age} ms")

        // 2. Carburant, valeurs plausibles.
        val per = state.perLap
        val tank = state.tank.value
        when {
            !per.known -> { checks += Check("consommation", false, per.source); worse(Verdict.INSUFFICIENT); reasons += "consommation inconnue" }
            per.value!! <= 0f || (tank != null && per.value >= tank) -> { checks += Check("consommation", false, "valeur impossible : ${per.value} ${per.unit}"); block("consommation impossible") }
            per.nature !in okNatures -> { checks += Check("consommation", null, "${per.nature.label} : ${per.source}"); worse(Verdict.PROVISIONAL); reasons += "consommation non mesurée dans cette course" }
            else -> checks += Check("consommation", true, "${per.nature.label} : ${"%.2f".format(java.util.Locale.FRANCE, per.value)} ${per.unit}")
        }
        val pace = state.paceMs.value
        if (pace != null && (pace < 15_000 || pace > 1_200_000)) { checks += Check("rythme", false, "chrono impossible : $pace ms"); block("chrono impossible") }
        if (state.paceMs.known && state.paceMs.nature !in okNatures) checks += Check("rythme", null, "${state.paceMs.nature.label} : ${state.paceMs.source}")

        // 3. Règlement.
        if (state.rulesValid.value != true) {
            checks += Check("règlement", false, state.rulesIssues.joinToString(" ; ").ifEmpty { state.rulesValid.source })
            worse(Verdict.PROVISIONAL); reasons += "règlement non validé"
        } else checks += Check("règlement", true, state.rulesValid.source)

        // 4. Gomme et âge des pneus.
        if (!state.compound.known) checks += Check("gomme", null, "non déclarée : GT7 ne la transmet pas")
        else checks += Check("gomme", true, "${state.compound.value!!.spoken} (${state.compound.nature.label})")
        if (!state.tyreAgeCertain) { checks += Check("âge des pneus", null, "changement non confirmé après un arrêt"); worse(Verdict.PROVISIONAL); reasons += "âge des pneus incertain" }

        // 5. Coûts d'arrêt.
        val costs = state.stopCosts
        val assumed = listOfNotNull(costs?.base, costs?.tyreService).filter { it.assumed }
        when {
            costs?.base == null && costs?.observed.isNullOrEmpty() -> { checks += Check("coût des arrêts", false, "inconnu"); reasons += "coût des arrêts inconnu" }
            assumed.isNotEmpty() -> checks += Check("coût des arrêts", null, assumed.joinToString { it.source })
            else -> checks += Check("coût des arrêts", true, costs?.base?.source ?: "arrêts observés")
        }

        // 6. Adversaires : aucune donnée inventée (seulement des déclarations encore valables).
        val opp = state.opponents(nowMs)
        checks += Check("adversaires", true, if (opp.isEmpty()) "aucune donnée adverse : undercut et overcut seulement conditionnels" else "${opp.size} déclaration(s) du pilote, valable(s) 60 s")

        // 7. Le scénario proposé.
        val ref = report.reference
        val proposed = report.recommended
        var chosen = proposed
        fun isRef(x: StrategyEngine.Scenario?) = x != null && ref != null && x.key == ref.key
        if (proposed == null) {
            checks += Check("faisabilité", false, "aucune stratégie faisable trouvée"); block("aucune stratégie faisable")
        } else {
            if (!proposed.feasible) { checks += Check("faisabilité", false, proposed.issues.joinToString()); block(proposed.issues.first()) }
            else checks += Check("faisabilité", true, "carburant et obligations respectés")
            proposed.minMarginLaps?.let { m ->
                if (m < StrategyEngine.SAFETY_LAPS - 0.01f && proposed.saving == 0f) { checks += Check("marge carburant", false, "${"%.1f".format(java.util.Locale.FRANCE, m)} tour"); block("marge carburant insuffisante") }
            }
            if (proposed.stopLaps.firstOrNull() == lap && !state.pitEntryStillPossible) {
                checks += Check("entrée des stands", false, "déjà passée à ce tour"); block("entrée des stands déjà passée")
            }
            // Un changement de plan qui repose sur une estimation non validée reste provisoire (garder A ne l'est pas).
            if (!isRef(proposed) && proposed.unknowns.any { "estimation expérimentale" in it || "non démontré" in it }) {
                worse(Verdict.PROVISIONAL); reasons += "repose sur une estimation non validée"
            }
        }
        // 8. Stabilité (hystérésis) : l'ancien choix, RÉÉVALUÉ avec les données actuelles, est gardé si le nouveau ne le
        // bat pas de plus de HYSTERESIS × incertitude. Jamais un objet périmé, jamais un arrêt dont l'entrée est passée.
        val previous = last?.chosen
        if (proposed != null && previous != null && previous.key != proposed.key && verdict != Verdict.BLOCKED && reevaluate != null) {
            val re = reevaluate(previous.stops.map { it.lap to it.tyres })
            val entryPassed = re?.stopLaps?.firstOrNull() == lap && !state.pitEntryStillPossible
            val u = proposed.uncertaintyMs ?: StrategyEngine.MIN_UNCERTAINTY_MS
            val gain = if (re?.totalMs != null && proposed.totalMs != null) re.totalMs - proposed.totalMs else null
            if (re != null && re.feasible && !entryPassed && gain != null && gain < HYSTERESIS * u) {
                chosen = re
                checks += Check("stabilité", true, "plan précédent conservé : l'écart (${StrategyOptions.seconds(gain.roundToLong())}) ne dépasse pas $HYSTERESIS × l'incertitude")
                reasons += "stabilité : écart trop faible pour changer d'avis"
            }
        }
        if (verdict == Verdict.BLOCKED) chosen = ref?.takeIf { it.feasible }
        // 8 bis. Rentabilité : un changement de plan ne vaut « gain démontré » que si aucune hypothèse déterminante n'est
        // inconnue ou supposée (V515.1). Sinon il reste faisable mais provisoire, et la phrase le dit.
        val change = chosen != null && ref != null && chosen.key != ref.key && ref.feasible
        val deps = if (change) dependencies(state, chosen!!, ref!!, lap) else emptyList()
        val profit = when {
            !change -> Profitability.NOT_APPLICABLE
            deps.isEmpty() -> Profitability.PROVEN
            else -> Profitability.NOT_PROVEN
        }
        if (profit == Profitability.NOT_PROVEN) { worse(Verdict.PROVISIONAL); reasons += "rentabilité non démontrée : ${deps.joinToString(", ")}" }
        if (change) checks += Check("rentabilité", deps.isEmpty(), if (deps.isEmpty()) "gain démontré" else deps.joinToString(" ; "))
        val drivers = chosen?.deltaParts.orEmpty().entries.sortedByDescending { kotlin.math.abs(it.value) }.map { it.key to it.value }
        // 9. Aucun pourcentage d'usure ni écart adverse dans la phrase finale.
        var statement = statement(verdict, chosen, ref, report, blocks + reasons, profit, drivers)
        if (WEAR.containsMatchIn(statement) || GAP.containsMatchIn(statement)) {
            checks += Check("formulation", false, "chiffre non transmis par GT7"); block("formulation non vérifiable")
            chosen = ref?.takeIf { it.feasible }
            statement = statement(verdict, chosen, ref, report, blocks + reasons, Profitability.NOT_APPLICABLE, emptyList())
        }
        val d = Decision(verdict, chosen, proposed, checks, (blocks + reasons).distinct(), statement, lap, nowMs, profit, deps, drivers)
        if (verdict != Verdict.INSUFFICIENT) last = d
        return d
    }

    /**
     * Hypothèses déterminantes du gain d'un changement de plan : seulement celles qui DIFFÈRENT entre le plan choisi et
     * le plan actuel (une inconnue commune aux deux ne change pas leur écart).
     */
    private fun dependencies(state: StrategyState, chosen: StrategyEngine.Scenario, ref: StrategyEngine.Scenario, lap: Int): List<String> {
        val deps = mutableListOf<String>()
        val parts = chosen.deltaParts.orEmpty()
        if (chosen.tyreStops != ref.tyreStops) {
            if (state.stopCosts?.tyreService?.assumed != false) deps += "temps de changement des pneus supposé"
            if (!state.outLapPenaltyMs.known) deps += "tour de sortie non calibré"
        }
        val tyreGain = parts[StrategyEngine.PART_TYRES] ?: 0.0
        if (tyreGain < 0 && !state.freshTyreGainMs.known) deps += "dégradation estimée, gain des pneus neufs non démontré"
        val fuelA = chosen.stops.sumOf { (it.fuelAdded ?: 0f).toDouble() }
        val fuelB = ref.stops.sumOf { (it.fuelAdded ?: 0f).toDouble() }
        if (kotlin.math.abs(fuelA - fuelB) >= 1.0 && !state.refuelMsPerUnit.known && chosen.stops.size == ref.stops.size) deps += "temps de remplissage inconnu"
        if ((parts[StrategyEngine.PART_SAVING] ?: 0.0) != 0.0) deps += "coût de l'économie de carburant estimé"
        if (state.rulesValid.value != true) deps += "règlement non confirmé"
        // Position fiable : distance parcourue (CALCUL sur la vitesse mesurée) ; le carburant consommé est trop approximatif.
        if (chosen.stopLaps.firstOrNull() == lap && state.lapProgress.nature !in setOf(Nature.CALCUL, Nature.MESURE, Nature.SIMULATION)) deps += "position dans le tour incertaine"
        return deps.distinct()
    }

    private fun driversText(drivers: List<Pair<String, Double>>): String? = drivers.take(2).takeIf { it.isNotEmpty() }?.joinToString(", ") { (k, v) ->
        "$k ${if (v <= 0) "−" else "+"}${StrategyOptions.seconds(kotlin.math.abs(v).roundToLong())}"
    }

    /** Recommandation, raison, conséquence, réserve : une phrase courte, compréhensible en conduisant. */
    private fun statement(
        v: Verdict, chosen: StrategyEngine.Scenario?, ref: StrategyEngine.Scenario?, r: StrategyEngine.Report, reasons: List<String>,
        profit: Profitability = Profitability.NOT_APPLICABLE, drivers: List<Pair<String, Double>> = emptyList(),
    ): String {
        if (chosen == null) return "Je n'ai aucune stratégie fiable à proposer : ${reasons.firstOrNull() ?: "données insuffisantes"}. Les rappels suivent le plan actuel."
        val keep = ref != null && chosen.key == ref.key
        val what = when {
            chosen.stops.isEmpty() -> "aller au bout sans nouvel arrêt" + if (chosen.saving > 0f) ", en économisant ${String.format(java.util.Locale.FRANCE, "%.1f", chosen.saving * 100)} % de carburant" else ""
            else -> "arrêt fin du tour ${chosen.stops.first().lap}" + (if (chosen.stops.first().tyres) " avec pneus" else " sans pneus") +
                (chosen.stops.first().fuelAdded?.let { if (it > 0.05f) ", ${it.roundToInt()} unités de carburant" else ", sans carburant" } ?: "") +
                if (chosen.stops.size > 1) ", puis ${chosen.stops.size - 1} autre(s) arrêt(s)" else ""
        }
        val why = when {
            ref != null && !ref.feasible -> "le plan actuel n'est plus faisable : ${ref.issues.firstOrNull() ?: ""}"
            keep && r.indistinguishable -> "aucune autre option ne gagne plus que notre incertitude"
            keep -> "le plan actuel reste le meilleur faisable"
            chosen.deltaMs != null && chosen.deltaMs < 0 -> "gain estimé ${StrategyOptions.seconds((-chosen.deltaMs).roundToLong())}, incertitude ${StrategyOptions.seconds((chosen.uncertaintyMs ?: 0.0).roundToLong())}"
            chosen.deltaMs != null -> "plus lent d'environ ${StrategyOptions.seconds(chosen.deltaMs.roundToLong())}, gardé pour la stabilité"
            else -> "écart non chiffrable"
        }
        val consequence = chosen.minMarginLaps?.let { "marge carburant ${"%.1f".format(java.util.Locale.FRANCE, it)} tour" }
        val weighs = if (!keep) driversText(drivers)?.let { "ce qui pèse : $it" } else null
        val reserve = when (v) {
            Verdict.APPROVED -> null
            Verdict.PROVISIONAL -> if (profit == Profitability.NOT_PROVEN) "faisable, mais ${reasons.firstOrNull { it.startsWith("rentabilité") } ?: reasons.firstOrNull() ?: "gain non démontré"}" else "provisoire : ${reasons.firstOrNull() ?: "à surveiller"}"
            Verdict.BLOCKED -> "proposition bloquée, je garde le plan : ${reasons.firstOrNull() ?: "contrôle échoué"}"
            Verdict.INSUFFICIENT -> "données insuffisantes : ${reasons.firstOrNull() ?: ""}"
        }
        val opener = when {
            keep -> "On garde : "
            profit == Profitability.NOT_PROVEN -> "Possible : "
            else -> "Je recommande : "
        }
        return listOfNotNull(
            opener + what + ".",
            why.replaceFirstChar { it.uppercase() } + ".",
            weighs?.replaceFirstChar { it.uppercase() }?.let { "$it." },
            consequence?.replaceFirstChar { it.uppercase() }?.let { "$it." },
            reserve?.replaceFirstChar { it.uppercase() }?.let { "$it." },
        ).joinToString(" ")
    }

    companion object {
        const val TELEMETRY_STALE_MS = 3_000L
        /** Un nouveau plan doit battre l'ancien de plus de 1,5 × l'incertitude pour le remplacer. */
        const val HYSTERESIS = 1.5
        private val WEAR = Regex("\\d+ ?% d'usure|usure (de |à )?\\d+ ?%", RegexOption.IGNORE_CASE)
        private val GAP = Regex("(à|de) \\d+[,.]?\\d* ?s (devant|derrière)|écart (de|avec) .* \\d+[,.]?\\d* ?s", RegexOption.IGNORE_CASE)

        /** Écart en valeur absolue, utile aux tests. */
        fun gap(a: Double, b: Double) = abs(a - b)
    }
}
