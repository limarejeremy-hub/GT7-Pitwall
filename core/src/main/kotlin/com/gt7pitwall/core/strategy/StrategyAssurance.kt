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
    ) {
        val changed: Boolean get() = proposed != null && chosen != null && proposed.stopLaps != chosen.stopLaps
    }

    var last: Decision? = null
        private set

    fun reset() { last = null }

    fun review(state: StrategyState, report: StrategyEngine.Report, nowMs: Long = state.atMs): Decision {
        val checks = mutableListOf<Check>()
        val reasons = mutableListOf<String>()
        var verdict = Verdict.APPROVED
        fun worse(v: Verdict) { if (v.ordinal > verdict.ordinal || (v == Verdict.INSUFFICIENT && verdict != Verdict.BLOCKED)) verdict = v }
        val lap = state.currentLap.value ?: 0

        // 1. Télémétrie et fraîcheur.
        val age = state.telemetryAgeMs
        if (age == null || age > TELEMETRY_STALE_MS) {
            checks += Check("télémétrie", false, if (age == null) "aucun paquet GT7" else "dernier paquet il y a ${age / 1000} s")
            worse(if (age == null) Verdict.INSUFFICIENT else Verdict.PROVISIONAL)
            reasons += "télémétrie interrompue"
        } else checks += Check("télémétrie", true, "paquet reçu il y a ${age} ms")

        // 2. Carburant mesuré, valeurs plausibles.
        val per = state.perLap
        val tank = state.tank.value
        when {
            !per.known -> { checks += Check("consommation", false, per.source); worse(Verdict.INSUFFICIENT); reasons += "consommation inconnue" }
            per.value!! <= 0f || (tank != null && per.value >= tank) -> { checks += Check("consommation", false, "valeur impossible : ${per.value} ${per.unit}"); worse(Verdict.BLOCKED); reasons += "consommation impossible" }
            per.nature != Nature.MESURE -> { checks += Check("consommation", null, "${per.nature.label} : ${per.source}"); worse(Verdict.PROVISIONAL); reasons += "consommation non mesurée dans cette course" }
            else -> checks += Check("consommation", true, "mesurée : ${"%.2f".format(java.util.Locale.FRANCE, per.value)} ${per.unit}")
        }
        val pace = state.paceMs.value
        if (pace != null && (pace < 15_000 || pace > 1_200_000)) { checks += Check("rythme", false, "chrono impossible : $pace ms"); worse(Verdict.BLOCKED) }

        // 3. Règlement.
        if (state.rulesValid.value != true) {
            checks += Check("règlement", false, state.rulesIssues.joinToString(" ; ").ifEmpty { state.rulesValid.source })
            worse(Verdict.PROVISIONAL); reasons += "règlement non validé"
        } else checks += Check("règlement", true, state.rulesValid.source)

        // 4. Gomme.
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
        var proposed = report.recommended
        var chosen = proposed
        if (proposed == null) {
            checks += Check("faisabilité", false, "aucune stratégie faisable trouvée")
            worse(Verdict.BLOCKED); reasons += "aucune stratégie faisable"
        } else {
            if (!proposed.feasible) { checks += Check("faisabilité", false, proposed.issues.joinToString()); worse(Verdict.BLOCKED); reasons += proposed.issues.first() }
            else checks += Check("faisabilité", true, "carburant et obligations respectés")
            proposed.minMarginLaps?.let { m ->
                if (m < StrategyEngine.SAFETY_LAPS - 0.01f && proposed.saving == 0f) { checks += Check("marge carburant", false, "${"%.1f".format(java.util.Locale.FRANCE, m)} tour"); worse(Verdict.BLOCKED) }
            }
            // Entrée des stands à ce tour.
            if (proposed.stopLaps.firstOrNull() == lap && !state.pitEntryStillPossible) {
                checks += Check("entrée des stands", false, "déjà passée à ce tour"); worse(Verdict.BLOCKED); reasons += "entrée des stands déjà passée"
            }
            // Incertitude : un changement de plan n'est approuvé que s'il dépasse l'incertitude (fait par le moteur), et
            // reste provisoire s'il repose sur une hypothèse (coût des pneus supposé, économie estimée).
            if (proposed !== ref && proposed.unknowns.any { "estimation expérimentale" in it || "non démontré" in it }) {
                worse(Verdict.PROVISIONAL); reasons += "repose sur une estimation non validée"
            }
        }
        // 8. Stabilité (hystérésis) : un nouveau choix doit battre l'ancien de plus de HYSTERESIS × incertitude.
        val previous = last?.chosen
        if (proposed != null && previous != null && previous.stopLaps != proposed.stopLaps && verdict != Verdict.BLOCKED) {
            val stillFeasible = report.scenarios.firstOrNull { it.stopLaps == previous.stopLaps && it.stops.map { s -> s.tyres } == previous.stops.map { s -> s.tyres } }
                ?: StrategyEngine.Scenario(previous.kind, previous.stops, previous.stints, previous.feasible, previous.issues, null, null, null, null, null, 0f, 0.0, emptyList(), emptyList())
            val gain = if (stillFeasible.totalMs != null && proposed.totalMs != null) stillFeasible.totalMs - proposed.totalMs else null
            val u = proposed.uncertaintyMs ?: StrategyEngine.MIN_UNCERTAINTY_MS
            val lapsMoved = previous.stopLaps.firstOrNull()?.let { it < lap } == true
            if (!lapsMoved && stillFeasible.feasible && (gain == null || gain < HYSTERESIS * u)) {
                chosen = report.scenarios.firstOrNull { it.stopLaps == previous.stopLaps } ?: previous
                checks += Check("stabilité", true, "plan précédent conservé : l'écart (${gain?.let { StrategyOptions.seconds(it.roundToLong()) } ?: "non chiffré"}) ne dépasse pas ${HYSTERESIS} × l'incertitude")
                reasons += "stabilité : écart trop faible pour changer d'avis"
            }
        }
        // 9. Aucun pourcentage d'usure ni écart adverse dans la phrase finale.
        val statement = statement(verdict, chosen, ref, report, reasons)
        if (WEAR.containsMatchIn(statement) || GAP.containsMatchIn(statement)) { checks += Check("formulation", false, "chiffre non transmis par GT7"); worse(Verdict.BLOCKED) }
        if (verdict == Verdict.BLOCKED && chosen !== ref && ref?.feasible == true) chosen = ref
        val d = Decision(verdict, chosen, proposed, checks, reasons.distinct(), statement(verdict, chosen, ref, report, reasons), lap, nowMs)
        if (verdict != Verdict.INSUFFICIENT) last = d
        return d
    }

    /** Recommandation, raison, conséquence, réserve : une phrase courte, compréhensible en conduisant. */
    private fun statement(v: Verdict, chosen: StrategyEngine.Scenario?, ref: StrategyEngine.Scenario?, r: StrategyEngine.Report, reasons: List<String>): String {
        if (chosen == null) return "Je n'ai aucune stratégie fiable à proposer : ${reasons.firstOrNull() ?: "données insuffisantes"}."
        val keep = chosen === ref || chosen.stopLaps == ref?.stopLaps
        val what = when {
            chosen.stops.isEmpty() -> "aller au bout sans nouvel arrêt" + if (chosen.saving > 0f) ", en économisant ${(chosen.saving * 100).roundToInt()} % de carburant" else ""
            else -> "arrêt fin du tour ${chosen.stops.first().lap}" + (if (chosen.stops.first().tyres) " avec pneus" else " sans pneus") +
                (chosen.stops.first().fuelAdded?.let { if (it > 0.05f) ", ${it.roundToInt()} de carburant" else ", sans carburant" } ?: "") +
                if (chosen.stops.size > 1) ", puis ${chosen.stops.size - 1} autre(s) arrêt(s)" else ""
        }
        val why = when {
            keep && r.indistinguishable -> "aucune autre option ne gagne plus que notre incertitude"
            keep -> "le plan actuel reste le meilleur faisable"
            chosen.deltaMs != null -> "gain estimé ${StrategyOptions.seconds((-chosen.deltaMs).roundToLong())}, incertitude ${StrategyOptions.seconds((chosen.uncertaintyMs ?: 0.0).roundToLong())}"
            else -> "le plan actuel n'est plus faisable"
        }
        val consequence = chosen.minMarginLaps?.let { "marge carburant ${"%.1f".format(java.util.Locale.FRANCE, it)} tour" }
        val reserve = when (v) {
            Verdict.APPROVED -> null
            Verdict.PROVISIONAL -> "provisoire : ${reasons.firstOrNull() ?: "à surveiller"}"
            Verdict.BLOCKED -> "proposition bloquée, je garde le plan : ${reasons.firstOrNull() ?: "contrôle échoué"}"
            Verdict.INSUFFICIENT -> "données insuffisantes : ${reasons.firstOrNull() ?: ""}"
        }
        return listOfNotNull(
            (if (keep) "On garde : " else "Je recommande : ") + what + ".",
            why.replaceFirstChar { it.uppercase() } + ".",
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
