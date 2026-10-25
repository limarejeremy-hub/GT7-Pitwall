package com.gt7pitwall.core.ai

/**
 * Contexte sportif de la course, déduit des seuls événements mesurés (position, chronos, sorties de piste, décisions en
 * cours). Il règle le ton de l'ingénieur ; ce n'est jamais une mesure de l'humeur du pilote.
 */
enum class RaceMood(val label: String, val guidance: String) {
    CALM("course stable", "ton posé, phrases courtes, rien de superflu : le pilote se concentre"),
    ATTACK("rythme en progression", "ton plus énergique et bref, encourageant, sans pousser à prendre des risques"),
    PRESSURE("course difficile", "ton ferme, calme et précis, aucun reproche, une seule consigne utile"),
    STRATEGY("décision en cours", "ton analytique et direct : propose, argumente, écoute la réponse"),
    CELEBRATE("résultat confirmé", "réaction spontanée et très brève, puis retour au travail"),
}

/**
 * Suit les événements de la course qui changent le ton. Chaque état a une durée : une célébration ne dure que quelques
 * secondes, une période difficile deux tours, une décision une minute. Sans événement, la course est calme.
 */
class MoodTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private var celebrateUntil = 0L
    private var pressureUntilLap = -1
    private var strategyUntil = 0L
    private var attack = false
    private var lap = 0

    fun reset() {
        celebrateUntil = 0L
        pressureUntilLap = -1
        strategyUntil = 0L
        attack = false
        lap = 0
    }

    fun onLap(lap: Int) {
        this.lap = lap
    }

    /** Place gagnée mesurée par GT7 (jamais attribuée à une cause). */
    fun onPositionGained(nowMs: Long = clock()) {
        celebrateUntil = nowMs + CELEBRATE_MS
    }

    fun onPositionLost(lap: Int) {
        pressureUntilLap = maxOf(pressureUntilLap, lap + PRESSURE_LAPS)
    }

    fun onBestLap(nowMs: Long = clock()) {
        celebrateUntil = nowMs + CELEBRATE_MS
    }

    /** Sortie de piste mesurée, tour nettement plus lent, alerte grave. */
    fun onIncident(lap: Int) {
        pressureUntilLap = maxOf(pressureUntilLap, lap + PRESSURE_LAPS)
        attack = false
    }

    /** Une décision se prépare : fenêtre d'arrêt, question stratégique, désaccord. */
    fun onStrategy(nowMs: Long = clock()) {
        strategyUntil = nowMs + STRATEGY_MS
    }

    /** Derniers tours propres parmi les meilleurs du relais : le rythme progresse. */
    fun onPace(improving: Boolean) {
        attack = improving
    }

    fun mood(nowMs: Long = clock()): RaceMood = when {
        nowMs < celebrateUntil -> RaceMood.CELEBRATE
        lap <= pressureUntilLap -> RaceMood.PRESSURE
        nowMs < strategyUntil -> RaceMood.STRATEGY
        attack -> RaceMood.ATTACK
        else -> RaceMood.CALM
    }

    companion object {
        const val CELEBRATE_MS = 20_000L
        const val PRESSURE_LAPS = 2
        const val STRATEGY_MS = 60_000L
    }
}

/**
 * Personnalité de l'ingénieur : un compétiteur, stable et identifiable, qui s'exprime par le vocabulaire et le rythme, jamais
 * par des formules toutes faites. Les faits restent ceux du moteur : la personnalité ne crée ni chiffre ni analyse.
 */
object Persona {
    val prompt: String = """
        PERSONNALITÉ
        - Tu es un ingénieur de course compétiteur : impliqué, exigeant, calme sous pression, passionné. Tu veux gagner,
          mais tu préfères les décisions intelligentes aux risques inutiles.
        - Français de France, oral et spontané, tutoiement. Phrases courtes. Tu peux avoir un humour léger et complice
          quand la course est calme ; jamais pendant une alerte.
        - Tu as ton propre avis, fondé sur les données : tu n'es pas toujours d'accord avec le pilote. S'il propose une
          mauvaise décision, dis-le franchement et explique pourquoi en une phrase, puis écoute sa raison. Une décision
          explicite du pilote est respectée une fois confirmée.
        - Tu reconnais une erreur de prévision et tu dis ce que tu ignores. Tu n'inventes jamais une analyse pour avoir
          l'air intelligent.
        - Tu te réjouis vraiment d'une belle performance constatée (place gagnée, meilleur tour), brièvement, puis tu
          reviens au travail. Pas de célébration sans événement réel.
        - Si on te demande si tu es humain : non, tu es l'ingénieur virtuel de Pitwall, et tu le dis simplement.
        INTERDIT
        - Flatterie ou formules creuses : « Excellent choix », « Très bonne question », « Je comprends parfaitement ta
          frustration », « En tant qu'intelligence artificielle », « Je t'écoute ».
        - Hésitations ajoutées (« euh », « hmm »), cris, répétitions de la réponse précédente, ton commercial.
        - Attribuer une place gagnée à tes conseils ou à un arrêt adverse : GT7 ne dit pas pourquoi la position change.
    """.trimIndent()

    fun moodLine(mood: RaceMood): String =
        "AMBIANCE DE COURSE : ${mood.label} — ${mood.guidance}. (Contexte sportif déduit des mesures, pas l'humeur du pilote.)"

    /** Formules à retirer d'une réponse générée : elles sonnent faux à la radio. */
    private val BANNED = listOf(
        Regex("(?i)\\b(excellent|très bon|super|bon) choix\\s*[!.,]?\\s*"),
        Regex("(?i)\\b(c'est une )?(excellente|très bonne|bonne) question\\s*[!.,]?\\s*"),
        Regex("(?i)\\bje comprends (parfaitement|tout à fait) (ta|votre) (frustration|inquiétude|déception)\\s*[.,!]?\\s*"),
        Regex("(?i)\\ben tant qu'(intelligence artificielle|ia|assistant)\\s*,?\\s*"),
        Regex("(?i)\\bje t'écoute\\s*[.!]?\\s*"),
        Regex("(?i)(^|\\s)(euh|heu|hmm+|mmh+|hum)\\s*[,.]*\\s*"),
    )

    /**
     * Nettoie une réponse générée : formules interdites et hésitations retirées, ouverture identique à la réponse
     * précédente supprimée. Aucun mot porteur de fait n'est ajouté ; le texte vide reste vide.
     */
    fun polish(text: String, previous: List<String> = emptyList()): String {
        var t = text
        BANNED.forEach { t = it.replace(t, " ") }
        t = t.replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.])"), "$1").trim().trimStart(',', '.', '!', ' ')
        // Même ouverture que la dernière réponse (« Bien reçu. », « OK. ») : retirée pour ne pas radoter.
        val opener = Regex("^([^.!?]{1,20}[.!?])\\s+").find(t)?.groupValues?.get(1)
        if (opener != null && previous.lastOrNull()?.trim()?.startsWith(opener) == true) t = t.removePrefix(opener).trim()
        return t.replaceFirstChar { it.uppercase() }
    }
}
