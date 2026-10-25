package com.gt7pitwall.core.ai

/** D'où vient une information : l'IA doit les distinguer, et ne jamais présenter une hypothèse comme une mesure. */
enum class Provenance(val label: String) {
    MEASURED("mesure GT7"),
    ESTIMATED("calcul du moteur"),
    DRIVER("déclaration du pilote"),
    DECISION("décision prise ensemble"),
    AI("hypothèse de l'IA"),
}

/** Sujet de la conversation : une relance (« pourquoi ? », « et si… ») le garde. */
enum class Topic(val label: String) {
    TYRES("pneus"),
    PLAN("stratégie et arrêts"),
    FUEL("carburant"),
    PACE("rythme et chronos"),
    COACHING("pilotage"),
    MEMORY("ce qu'on a décidé"),
    OTHER("conversation"),
}

/**
 * Mémoire de la course, pour la durée de la session : intentions du pilote, plans proposés et décisions prises
 * ensemble, faits confirmés ou corrigés, derniers échanges (et un résumé des plus anciens). Rien ici n'est une
 * mesure nouvelle : ce sont des traces de ce qui a été dit, décidé ou calculé, chacune avec sa provenance.
 */
class RaceMemory {
    data class Item(val lap: Int, val atMs: Long, val text: String, val provenance: Provenance)

    /** Plan d'arrêts proposé par le moteur ([stops] : fins de tour), avec sa raison. */
    data class Plan(val lap: Int, val atMs: Long, val stops: List<Int>, val reason: String?)

    data class Exchange(
        val lap: Int,
        val atMs: Long,
        val question: String,
        val answer: String,
        /** Qui a répondu : « moteur Pitwall », « gemini-3.5-flash-lite »… */
        val answeredBy: String,
        val topic: Topic,
        /** Calcul du moteur sur lequel la réponse s'appuyait (repris pour la relance « pourquoi ? »). */
        val analysis: String? = null,
    )

    private val intentions = ArrayDeque<Item>()
    private val facts = ArrayDeque<Item>()
    private val decisions = ArrayDeque<Item>()
    private val plans = ArrayDeque<Plan>()
    private val exchanges = ArrayDeque<Exchange>()
    private val summary = ArrayDeque<String>()
    private val preferences = ArrayDeque<Item>()
    private val hypotheses = ArrayDeque<Item>()
    private val disagreements = ArrayDeque<Item>()

    /** Ordre des déclarations du pilote (intentions, préférences, hypothèses) : la plus récente est retirée en premier. */
    private val driverOrder = ArrayDeque<Pair<ArrayDeque<Item>, Item>>()

    /** Plan sur lequel le pilote a dit « d'accord » (sinon null : seul le moteur l'a proposé). */
    var agreedPlan: Plan? = null
        private set

    @Synchronized
    fun clear() {
        intentions.clear(); facts.clear(); decisions.clear(); plans.clear(); exchanges.clear(); summary.clear()
        preferences.clear(); hypotheses.clear(); disagreements.clear(); driverOrder.clear()
        agreedPlan = null
    }

    @Synchronized
    fun intention(lap: Int, atMs: Long, text: String) = said(intentions, Item(lap, atMs, text, Provenance.DRIVER), MAX_INTENTIONS)

    /** Fait confirmé ou corrigé (gomme, arrêt, règlement) : [provenance] dit qui l'a établi. */
    @Synchronized
    fun fact(lap: Int, atMs: Long, text: String, provenance: Provenance) = push(facts, Item(lap, atMs, text, provenance), MAX_FACTS)

    @Synchronized
    fun planProposed(lap: Int, atMs: Long, stops: List<Int>, reason: String?) {
        if (plans.lastOrNull()?.stops == stops) return
        push(plans, Plan(lap, atMs, stops, reason), MAX_PLANS)
    }

    /** Le pilote valide (« d'accord, on fait comme ça ») : le plan en cours devient une décision commune. */
    @Synchronized
    fun agree(lap: Int, atMs: Long, what: String) {
        agreedPlan = plans.lastOrNull()?.copy(lap = lap, atMs = atMs)
        push(decisions, Item(lap, atMs, what, Provenance.DECISION), MAX_DECISIONS)
    }

    /** Préférence du pilote pour cette course (« je veux préserver les pneus ») : pas un réglage permanent. */
    @Synchronized
    fun preference(lap: Int, atMs: Long, text: String) = said(preferences, Item(lap, atMs, text, Provenance.DRIVER), MAX_PREFERENCES)

    /** Hypothèse discutée (« et si je mets les tendres ? ») : jamais un fait ni une décision. */
    @Synchronized
    fun hypothesis(lap: Int, atMs: Long, text: String) = said(hypotheses, Item(lap, atMs, text, Provenance.DRIVER), MAX_HYPOTHESES)

    /** Désaccord entre le pilote et la recommandation du moteur, avec ce que chacun a dit. */
    @Synchronized
    fun disagreement(lap: Int, atMs: Long, text: String) = push(disagreements, Item(lap, atMs, text, Provenance.DRIVER), MAX_DISAGREEMENTS)

    /** Décision explicite du pilote, confirmée (« je rentre quand même » puis « oui »). */
    @Synchronized
    fun decision(lap: Int, atMs: Long, text: String) = push(decisions, Item(lap, atMs, text, Provenance.DECISION), MAX_DECISIONS)

    /**
     * « Oublie ce que je viens de dire » : retire la dernière déclaration du pilote (intention, préférence, hypothèse) faite
     * dans les [windowMs] dernières millisecondes. Renvoie son texte, ou null s'il n'y avait rien de récent.
     */
    @Synchronized
    fun retractLast(nowMs: Long, windowMs: Long = RETRACT_WINDOW_MS): String? {
        // Ordre d'arrivée, pas l'heure : deux phrases peuvent tomber dans la même milliseconde.
        while (driverOrder.isNotEmpty()) {
            val (q, item) = driverOrder.last()
            if (q.none { it === item }) {
                driverOrder.removeLast() // déjà sortie de sa liste (plus ancienne que la limite)
                continue
            }
            if (nowMs - item.atMs > windowMs) return null
            driverOrder.removeLast()
            q.removeAll { it === item }
            return item.text
        }
        return null
    }

    @get:Synchronized
    val preferenceList: List<Item> get() = preferences.toList()

    @get:Synchronized
    val hypothesisList: List<Item> get() = hypotheses.toList()

    @get:Synchronized
    val disagreementList: List<Item> get() = disagreements.toList()

    @Synchronized
    fun exchange(e: Exchange) {
        if (exchanges.size >= MAX_EXCHANGES) {
            val old = exchanges.removeFirst()
            push(summary, "Tour ${old.lap}, ${old.topic.label} : « ${old.question.take(60)} » → ${firstSentence(old.answer)}", MAX_SUMMARY)
        }
        exchanges.addLast(e)
    }

    @get:Synchronized
    val recent: List<Exchange> get() = exchanges.toList()

    @get:Synchronized
    val lastExchange: Exchange? get() = exchanges.lastOrNull()

    @get:Synchronized
    val planHistory: List<Plan> get() = plans.toList()

    @get:Synchronized
    val decisionList: List<Item> get() = decisions.toList()

    @get:Synchronized
    val intentionList: List<Item> get() = intentions.toList()

    @get:Synchronized
    val factList: List<Item> get() = facts.toList()

    /** Sujet en cours si le dernier échange est récent (sinon une nouvelle question repart de zéro). */
    @Synchronized
    fun currentTopic(nowMs: Long, windowMs: Long = TOPIC_WINDOW_MS): Topic? =
        exchanges.lastOrNull()?.takeIf { nowMs - it.atMs < windowMs }?.topic

    /**
     * Mémoire résumée pour l'IA, chaque ligne avec sa provenance. [pending] : questions de l'ingénieur encore sans
     * réponse ; [trend] : évolution du rythme et du carburant, déjà calculée par le moteur.
     */
    @Synchronized
    fun render(pending: List<String>, trend: List<String>): String = buildString {
        fun section(title: String, lines: List<String>) {
            if (lines.isEmpty()) return
            append(title).append(" :\n")
            lines.forEach { append("- ").append(it).append('\n') }
        }
        section("PLANS PROPOSÉS PAR LE MOTEUR (du plus ancien au plus récent)", plans.map { "tour ${it.lap} : ${stopsText(it.stops)}" + (it.reason?.let { r -> " ($r)" } ?: "") })
        section(
            "DÉCISIONS PRISES ENSEMBLE",
            decisions.map { "tour ${it.lap} : ${it.text}" }.ifEmpty { listOf("aucune décision validée par le pilote pour l'instant") },
        )
        section("INTENTIONS DU PILOTE (pas encore réalisées)", intentions.map { "tour ${it.lap} : ${it.text}" })
        section("PRÉFÉRENCES DU PILOTE POUR CETTE COURSE (à respecter dans tes conseils)", preferences.map { "tour ${it.lap} : ${it.text}" })
        section("HYPOTHÈSES DISCUTÉES (pas des faits, rien n'a changé)", hypotheses.map { "tour ${it.lap} : ${it.text}" })
        section("DÉSACCORDS (le pilote n'était pas d'accord avec la recommandation)", disagreements.map { "tour ${it.lap} : ${it.text}" })
        section("FAITS CONFIRMÉS OU CORRIGÉS", facts.map { "tour ${it.lap} [${it.provenance.label}] : ${it.text}" })
        section("QUESTIONS DE L'INGÉNIEUR EN ATTENTE DE RÉPONSE", pending)
        section("ÉVOLUTION DU RYTHME ET DU CARBURANT", trend)
        section("RÉSUMÉ DES ÉCHANGES PLUS ANCIENS", summary.toList())
    }

    private fun said(q: ArrayDeque<Item>, item: Item, max: Int) {
        push(q, item, max)
        driverOrder.addLast(q to item)
        while (driverOrder.size > MAX_DRIVER_ORDER) driverOrder.removeFirst()
    }

    private fun <T> push(q: ArrayDeque<T>, item: T, max: Int) {
        q.addLast(item)
        while (q.size > max) q.removeFirst()
    }

    companion object {
        /** Nom de l'auteur d'une réponse calculée sans IA. */
        const val ENGINE = "moteur Pitwall"
        const val MAX_INTENTIONS = 5
        const val MAX_FACTS = 10
        const val MAX_DECISIONS = 6
        const val MAX_PLANS = 8
        const val MAX_EXCHANGES = 6
        const val MAX_SUMMARY = 8
        const val MAX_PREFERENCES = 4
        const val MAX_HYPOTHESES = 4
        const val MAX_DISAGREEMENTS = 4
        const val RETRACT_WINDOW_MS = 90_000L
        const val MAX_DRIVER_ORDER = 16
        const val TOPIC_WINDOW_MS = 120_000L

        fun stopsText(stops: List<Int>): String = when {
            stops.isEmpty() -> "aucun arrêt"
            stops.size == 1 -> "arrêt fin du tour ${stops[0]}"
            else -> "arrêts fin des tours ${stops.dropLast(1).joinToString(", ")} et ${stops.last()}"
        }

        fun firstSentence(s: String): String = s.trim().let { t ->
            val i = Regex("[.!?](\\s|$)").find(t)?.range?.first
            if (i == null) t.take(120) else t.substring(0, i + 1)
        }
    }
}
