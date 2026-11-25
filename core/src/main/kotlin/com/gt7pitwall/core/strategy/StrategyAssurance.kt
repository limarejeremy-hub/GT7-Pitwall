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
        /** Le plan actuel est infaisable : changer est imposé, aucun gain n'est revendiqué. */
        FORCED("plan actuel infaisable : changement imposé, aucun gain revendiqué"),
        /** Aucune stratégie retenue : rien à qualifier. */
        NONE(""),
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
            // V515.1 : les estimations non validées qui DIFFÈRENT entre le plan proposé et le plan actuel sont jugées à
            // l'étape 8 bis (dépendances) ; une inconnue commune aux deux ne rend plus un changement provisoire à tort.
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
        val change = chosen != null && ref != null && chosen.key != ref.key
        val forced = change && !ref!!.feasible
        val deps = if (change && !forced) dependencies(state, chosen!!, ref!!, lap).toMutableList() else mutableListOf()
        // Un plan gardé pour la stabilité peut être PLUS LENT que le plan actuel : jamais un gain démontré.
        if (change && !forced) {
            val beats = chosen!!.deltaMs?.let { -it > (chosen.uncertaintyMs ?: StrategyEngine.MIN_UNCERTAINTY_MS) } == true
            if (!beats) deps += "gain inférieur à l'incertitude (gardé pour la stabilité)"
        }
        val profit = when {
            chosen == null -> Profitability.NONE
            !change -> Profitability.NOT_APPLICABLE
            forced -> Profitability.FORCED
            // Gain démontré : aucune hypothèse déterminante ET aucune autre réserve (verdict encore approuvé).
            deps.isEmpty() && verdict == Verdict.APPROVED -> Profitability.PROVEN
            else -> Profitability.NOT_PROVEN
        }
        if (profit == Profitability.NOT_PROVEN) {
            worse(Verdict.PROVISIONAL)
            // En tête des réserves : c'est elle que Véga prononce en premier.
            reasons.add(0, "rentabilité non démontrée" + if (deps.isEmpty()) " (${reasons.firstOrNull() ?: "réserve du contrôleur"})" else " : ${deps.joinToString(", ")}")
        }
        if (change && !forced) checks += Check("rentabilité", profit == Profitability.PROVEN, if (profit == Profitability.PROVEN) "gain démontré" else deps.joinToString(" ; ").ifEmpty { "verdict provisoire" })
        // Ce qui pèse : seulement les causes d'au moins 0,1 s (en dessous, « −0,0 s » n'apprend rien au pilote).
        var drivers = chosen?.deltaParts.orEmpty().entries.filter { kotlin.math.abs(it.value) >= 100.0 }
            .sortedByDescending { kotlin.math.abs(it.value) }.map { it.key to it.value }
        var profitOut = profit
        var depsOut: List<String> = deps.distinct()
        // 9. Aucun pourcentage d'usure ni écart adverse dans la phrase finale.
        var statement = statement(verdict, chosen, ref, report, blocks + reasons, profit, drivers)
        if (WEAR.containsMatchIn(statement) || GAP.containsMatchIn(statement)) {
            checks += Check("formulation", false, "chiffre non transmis par GT7"); block("formulation non vérifiable")
            chosen = ref?.takeIf { it.feasible }
            // Le plan abandonné n'est plus qualifié : rentabilité, dépendances et causes remises à zéro.
            profitOut = if (chosen == null) Profitability.NONE else Profitability.NOT_APPLICABLE
            depsOut = emptyList(); drivers = emptyList()
            statement = statement(verdict, chosen, ref, report, blocks + reasons, profitOut, drivers)
        }
        val d = Decision(verdict, chosen, proposed, checks, (blocks + reasons).distinct(), statement, lap, nowMs, profitOut, depsOut, drivers)
        if (verdict != Verdict.INSUFFICIENT) last = d
        return d
    }

    /**
     * Hypothèses déterminantes du gain d'un changement de plan : seulement celles qui DIFFÈRENT entre le plan choisi et
     * le plan actuel (une inconnue commune aux deux ne change pas leur écart).
     */
    internal fun dependencies(state: StrategyState, chosen: StrategyEngine.Scenario, ref: StrategyEngine.Scenario, lap: Int): List<String> {
        val deps = mutableListOf<String>()
        val parts = chosen.deltaParts.orEmpty()
        val costs = state.stopCosts
        val stopsDiffer = chosen.stops.size != ref.stops.size
        fun kind(sp: StrategyEngine.StopPlan) = StopKind.of(fuel = (sp.fuelAdded ?: 1f) > 0.05f, tyres = sp.tyres)
        // Pneus : service et tour de sortie ne pèsent que si le nombre de changements diffère ET que le coût des arrêts
        // avec pneus n'est pas observé (une perte observée couvre le tour d'arrêt ET le tour de sortie).
        if (chosen.tyreStops != ref.tyreStops) {
            val tyreKinds = (chosen.stops + ref.stops).filter { it.tyres }.map(::kind).distinct()
            val tyreObserved = costs != null && tyreKinds.isNotEmpty() && tyreKinds.all { costs.observed[it] != null }
            if (!tyreObserved) {
                if (costs?.tyreService?.assumed != false) deps += "temps de changement des pneus supposé"
                if (!state.outLapPenaltyMs.known) deps += "tour de sortie non calibré"
            }
        }
        // Dégradation : la pente chiffre le gain des pneus ; il n'est démontré que par un gain des pneus neufs OBSERVÉ,
        // positif, et assez grand pour couvrir la part attribuée aux pneus.
        val tyrePart = parts[StrategyEngine.PART_TYRES] ?: 0.0
        if (tyrePart < 0) {
            val fresh = state.freshTyreGainMs.value?.takeIf { state.freshTyreGainMs.known && it > 0 }
            val laps = state.lapsRemaining.value?.toDouble() ?: 0.0
            if (fresh == null || -tyrePart > fresh * laps) deps += "dégradation estimée, gain des pneus neufs non démontré"
        }
        if (kotlin.math.abs(tyrePart) >= 1_000.0 && !state.tyreAgeCertain) deps += "âge des pneus incertain"
        // Remplissage : inconnu, il compte 0 et avantage le plan qui ajoute PLUS de carburant ; compris dans un coût
        // d'arrêt observé, il est recompté à chaque arrêt supplémentaire (volume de l'arrêt observé, pas celui du plan).
        fun refuelIncluded(sp: StrategyEngine.StopPlan): Boolean {
            val k = kind(sp)
            return costs != null && k.fuel && (costs.observed[k] != null || costs.baseIncludesRefuel)
        }
        val fuelA = chosen.stops.sumOf { (it.fuelAdded ?: 0f).toDouble() }
        val fuelB = ref.stops.sumOf { (it.fuelAdded ?: 0f).toDouble() }
        if (fuelA - fuelB >= 1.0 && !state.refuelMsPerUnit.known && chosen.stops.none(::refuelIncluded)) deps += "temps de remplissage inconnu"
        if (stopsDiffer && (chosen.stops + ref.stops).any(::refuelIncluded)) deps += "plein compris dans le coût d'arrêt observé (recompté à chaque arrêt)"
        // Coût d'un arrêt : il ne pèse que si le nombre d'arrêts diffère ; appris sur moins de 3 arrêts ou supposé, il
        // n'est pas démontré (une seule perte observée ne dit rien de sa répétabilité).
        if (stopsDiffer && costs != null) {
            val used = (chosen.stops + ref.stops).mapNotNull { costs.cost(kind(it)) }
            val few = used.mapNotNull { it.samples }.minOrNull()
            if (used.any { it.assumed } || costs.base?.assumed == true) deps += "coût d'arrêt supposé"
            else if (few != null && few < MIN_STOP_SAMPLES) deps += "coût d'arrêt appris sur $few arrêt${if (few > 1) "s" else ""}"
        }
        // Poids du carburant : jamais calibré en course (compté 0). Il pèse quand les deux plans embarquent des
        // quantités différentes pendant des tours différents (ordre de grandeur prudent : 15 ms par unité et par tour).
        if (parts[StrategyEngine.PART_FUEL_LOAD] == null) {
            val a = chosen.fuelLapUnits; val b = ref.fuelLapUnits
            if (a != null && b != null) {
                val weight = FUEL_WEIGHT_GUARD_MS * kotlin.math.abs(a - b)
                if (weight >= maxOf(500.0, 0.5 * kotlin.math.abs(chosen.deltaMs ?: 0.0))) deps += "effet du poids du carburant non calibré"
            }
        }
        if ((parts[StrategyEngine.PART_SAVING] ?: 0.0) != 0.0) deps += "coût de l'économie de carburant estimé"
        // Consommation estimée (pas mesurée dans cette course) : elle décide du nombre d'arrêts et de l'économie.
        if ((stopsDiffer || chosen.saving != ref.saving) && state.perLap.nature != Nature.MESURE && state.perLap.nature != Nature.SIMULATION) deps += "consommation estimée"
        // Position fiable : distance parcourue (CALCUL sur la vitesse mesurée) ; le carburant consommé est trop approximatif.
        if (chosen.stopLaps.firstOrNull() == lap && state.lapProgress.nature !in setOf(Nature.CALCUL, Nature.MESURE, Nature.SIMULATION)) deps += "position dans le tour incertaine"
        // Le règlement non confirmé est commun aux deux plans : il rend la stratégie provisoire (contrôle 3), pas une dépendance.
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
        /** Un coût d'arrêt appris sur moins d'arrêts que ceci n'est pas démontré (répétabilité inconnue). */
        const val MIN_STOP_SAMPLES = 3
        /**
         * Garde (pas un modèle) : effet du poids du carburant supposé au plus 15 ms par unité et par tour, ordre de grandeur
         * du sport automobile réel (ESTIMATION externe, non mesurée dans GT7). Sert seulement à dire quand cet effet,
         * compté 0, pourrait renverser l'écart ; jamais ajouté au temps.
         */
        const val FUEL_WEIGHT_GUARD_MS = 15.0
        private val WEAR = Regex("\\d+ ?% d'usure|usure (de |à )?\\d+ ?%", RegexOption.IGNORE_CASE)
        private val GAP = Regex("(à|de) \\d+[,.]?\\d* ?s (devant|derrière)|écart (de|avec) .* \\d+[,.]?\\d* ?s", RegexOption.IGNORE_CASE)

        /** Écart en valeur absolue, utile aux tests. */
        fun gap(a: Double, b: Double) = abs(a - b)
    }
}
