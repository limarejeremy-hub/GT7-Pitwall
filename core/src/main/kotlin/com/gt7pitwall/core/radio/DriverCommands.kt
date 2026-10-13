package com.gt7pitwall.core.radio

import java.text.Normalizer

/** Ce que le pilote dit à l'ingénieur, une fois la phrase reconnue. */
sealed interface DriverCommand {
    data class Ask(val query: DriverQuery) : DriverCommand

    /** "Je suis troisième" : position annoncée par le pilote (GT7 ne la transmet pas). */
    data class ReportPosition(val position: Int) : DriverCommand

    /** "Je suis dernier" : position = nombre de voitures au départ (si GT7 l'a transmis). */
    data object ReportLast : DriverCommand

    /** "J'ai doublé". */
    data object Overtook : DriverCommand

    /** "Je me suis fait doubler". */
    data object Overtaken : DriverCommand

    /** "Silence" : seulement les messages critiques et les réponses. */
    data object Quiet : DriverCommand

    /** "Radio essentielle" : critiques + changements de stratégie, sans statistiques. */
    data object Essential : DriverCommand

    /** "Tu peux parler" : toutes les annonces. */
    data object Talk : DriverCommand

    /** "Oui" (réponse à une question de l'ingénieur). */
    data object Yes : DriverCommand

    /** "Non". */
    data object No : DriverCommand

    /** "J'ai fait mon arrêt". */
    data object DeclareStop : DriverCommand

    /** "Annule l'arrêt". */
    data object CancelStop : DriverCommand

    /** "J'ai mis les durs" / "je pars en mediums" : gommes déclarées (le texte est interprété avec le règlement). */
    data class Tyres(val text: String, val atStart: Boolean) : DriverCommand

    /** "Il pleut" : le pilote signale la pluie (GT7 ne transmet pas la météo). */
    data object Rain : DriverCommand

    /** "Piste sèche". */
    data object Dry : DriverCommand

    /** Règlement dicté à la radio. */
    sealed interface Rule : DriverCommand

    /** "Course de 12 tours". */
    data class SetLaps(val laps: Int) : Rule

    /** "Course de 30 minutes". */
    data class SetDuration(val minutes: Int) : Rule

    /** "Arrêt obligatoire", "deux arrêts obligatoires", "pas d'arrêt obligatoire". */
    data class SetMandatoryStops(val stops: Int) : Rule

    /** "Consommation fois deux". */
    data class SetFuelMultiplier(val factor: Int) : Rule

    /** "Usure fois trois". */
    data class SetTyreMultiplier(val factor: Int) : Rule

    /** "Répète". */
    data object Repeat : DriverCommand
}

/**
 * Analyse simple et prévisible des phrases françaises reconnues.
 * Fonctionne sans Internet : mots-clés + ordinaux. À enrichir au fil des essais.
 */
object DriverCommandParser {

    private val SIMPLE_ORDINALS = listOf(
        "premier" to 1, "premiere" to 1, "1er" to 1, "1re" to 1,
        "deuxieme" to 2, "second" to 2, "seconde" to 2, "troisieme" to 3, "quatrieme" to 4,
        "cinquieme" to 5, "sixieme" to 6, "septieme" to 7, "huitieme" to 8, "neuvieme" to 9,
        "dixieme" to 10, "onzieme" to 11, "douzieme" to 12, "treizieme" to 13, "quatorzieme" to 14,
        "quinzieme" to 15, "seizieme" to 16, "vingtieme" to 20, "trentieme" to 30,
    )
    private val UNIT_ORDINALS = listOf(
        "deuxieme" to 2, "troisieme" to 3, "quatrieme" to 4, "cinquieme" to 5,
        "sixieme" to 6, "septieme" to 7, "huitieme" to 8, "neuvieme" to 9,
    )
    private val SIMPLE_CARDINALS = listOf(
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6,
        "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12,
        "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20, "trente" to 30,
    )

    /** Ordinaux 1 à 30 ("dix septieme", "vingt et unieme"…), du plus long au plus court. */
    private val ORDINALS: List<Pair<String, Int>> = buildList {
        addAll(SIMPLE_ORDINALS)
        listOf("septieme" to 7, "huitieme" to 8, "neuvieme" to 9).forEach { (w, n) -> add("dix $w" to 10 + n) }
        add("vingt et unieme" to 21)
        UNIT_ORDINALS.forEach { (w, n) -> add("vingt $w" to 20 + n) }
    }.sortedByDescending { it.first.length }

    private val CARDINALS: List<Pair<String, Int>> = buildList {
        addAll(SIMPLE_CARDINALS)
        listOf("sept" to 7, "huit" to 8, "neuf" to 9).forEach { (w, n) -> add("dix $w" to 10 + n) }
        add("vingt et un" to 21)
        listOf("deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9)
            .forEach { (w, n) -> add("vingt $w" to 20 + n) }
    }.sortedByDescending { it.first.length }

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('’', '\'')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    fun parse(text: String): DriverCommand? {
        val t = normalize(text)
        if (t.isEmpty()) return null

        if (has(t, "repete", "repeter", "redis")) return DriverCommand.Repeat
        if (t == "oui" || t == "affirmatif" || t == "confirme" || t == "c'est ca" || t == "exact") return DriverCommand.Yes
        if (t == "non" || t == "negatif" || t == "non non") return DriverCommand.No
        tyresIn(t)?.let { return it }
        if (has(t, "plus de pluie", "pluie s'arrete", "pluie arrete", "ca seche", "piste seche", "seche") ||
            Regex("(^|\\s)sec($|\\s)").containsMatchIn(t)
        ) {
            return DriverCommand.Dry
        }
        if (has(t, "pleut", "pluie", "mouille", "humide", "averse")) return DriverCommand.Rain
        if (has(t, "silence", "tais-toi", "tais toi", "chut")) return DriverCommand.Quiet
        if (has(t, "essentiel", "moins de messages", "moins de radio")) return DriverCommand.Essential
        if (has(t, "tu peux parler", "reparle", "parle-moi", "parle moi", "radio complete", "plus de messages")) return DriverCommand.Talk

        ruleIn(t)?.let { return it }
        if (has(t, "annule l'arret", "annule arret", "annuler l'arret", "pas fait d'arret", "pas d'arret fait", "pas arrete")) {
            return DriverCommand.CancelStop
        }
        if (has(t, "arret fait", "arret effectue", "fait mon arret", "fait l'arret", "je me suis arrete", "j'ai fait l'arret", "stand fait")) {
            return DriverCommand.DeclareStop
        }

        // "Il m'a doublé" avant "j'ai doublé". "adieu une place" : erreur réelle de reconnaissance de "perdu une place".
        if (has(
                t, "fait doubler", "fait passer", "fait depasser", "m'a double", "m'a passe", "m'a depasse",
                "ma double", "ma passe", "ma depasse", "perdu une place", "perdu la place", "perdu", "adieu une place",
                "perds une place",
            )
        ) {
            return DriverCommand.Overtaken
        }
        if (has(
                t, "j'ai double", "j'ai passe", "j'ai depasse", "jai double", "jai passe", "jai depasse",
                "gagne une place", "pris une place", "gagne",
            )
        ) {
            return DriverCommand.Overtook
        }

        // "dernier tour" = question chrono ; "dernier" seul / "je suis dernier" = position.
        if (has(t, "dernier tour", "dernier chrono", "dernier temps")) return DriverCommand.Ask(DriverQuery.LAST_LAP)
        if (Regex("(^|\\s)derniere?($|\\s)").containsMatchIn(t) && !has(t, "tour", "chrono", "temps", "secteur")) {
            return DriverCommand.ReportLast
        }

        positionIn(t)?.let { return DriverCommand.ReportPosition(it) }

        val query = when {
            has(t, "position", "quelle place", "je suis combien") -> DriverQuery.POSITION
            has(t, "essence", "carburant", "fuel", "reservoir", "conso") -> DriverQuery.FUEL
            has(t, "secteur") -> DriverQuery.SECTORS
            has(t, "pneu", "gomme", "temperature") -> DriverQuery.TYRES
            has(t, "strategie", "plan") -> DriverQuery.PLAN
            has(t, "obligation", "reglement") -> DriverQuery.OBLIGATIONS
            has(t, "detail") -> DriverQuery.DETAILS
            has(t, "meilleur", "record") -> DriverQuery.BEST_LAP
            has(t, "dernier tour", "chrono", "temps") -> DriverQuery.LAST_LAP
            has(t, "rythme", "regularite", "moyenne") -> DriverQuery.PACE
            has(t, "tour", "course", "reste") -> DriverQuery.RACE_STATUS
            else -> null
        }
        return query?.let { DriverCommand.Ask(it) }
    }

    /** Gommes déclarées : il faut un mot de dureté / type de gomme ET un verbe ou le mot pneus. */
    private fun tyresIn(t: String): DriverCommand.Tyres? {
        val compound = Regex("(^|\\s)(durs?|dures?|hard|mediums?|moyens?|moyennes?|tendres?|softs?|intermediaires?|ch|cm|cs|sh|sm|ss|rh|rm|rs|im)($|\\s)")
            .containsMatchIn(t) || Regex("(pneus?|gommes?) (pluie|wet)").containsMatchIn(t)
        if (!compound) return null
        val context = has(t, "mis ", "monte", "chausse", "pneus", "pneu ", "gomme", "depart", "je pars", "change")
        if (!context) return null
        return DriverCommand.Tyres(t, atStart = has(t, "depart", "je pars", "on part"))
    }

    private val BIG_NUMBERS = listOf(
        "quarante cinq" to 45, "quarante" to 40, "cinquante" to 50, "soixante" to 60, "quatre vingt dix" to 90,
    )

    /** Nombre en chiffres ou en lettres au début de [s] ("30", "trente", "vingt deux", "quarante cinq"). */
    private fun numberAt(s: String): Int? {
        val padded = "${s.trim()} "
        padded.substringBefore(' ').toIntOrNull()?.let { return it }
        for ((phrase, n) in (BIG_NUMBERS + CARDINALS).sortedByDescending { it.first.length }) {
            if (padded.startsWith("$phrase ")) return n
        }
        return null
    }

    /** Règlement : "course de 30 minutes", "une heure", "arrêt obligatoire", "consommation fois deux"… */
    private fun ruleIn(t: String): DriverCommand.Rule? {
        Regex("(consommation|essence|carburant|fuel) (fois|x) ?(.+)$").find(t)?.let { m ->
            numberAt(m.groupValues[3])?.let { return DriverCommand.SetFuelMultiplier(it) }
        }
        Regex("(usure|pneus?|gommes?) (fois|x) ?(.+)$").find(t)?.let { m ->
            numberAt(m.groupValues[3])?.let { return DriverCommand.SetTyreMultiplier(it) }
        }
        if (has(t, "obligatoire")) {
            if (has(t, "pas d'arret", "pas d arret", "aucun arret", "sans arret")) return DriverCommand.SetMandatoryStops(0)
            val before = t.substringBefore("arret").trim().split(' ').lastOrNull().orEmpty()
            return DriverCommand.SetMandatoryStops(numberAt(before) ?: 1)
        }
        if (has(t, "deux heures")) return DriverCommand.SetDuration(120)
        if (has(t, "heure et demie")) return DriverCommand.SetDuration(90)
        if (has(t, "une heure")) return DriverCommand.SetDuration(60)
        Regex("(^|\\s)(course de |course en )?(.+?) minutes?($|\\s)").find(t)?.let { m ->
            val words = m.groupValues[3].split(' ')
            // On cherche le nombre juste avant "minutes" (jusqu'à 3 mots : "quarante cinq").
            for (n in minOf(3, words.size) downTo 1) numberAt(words.takeLast(n).joinToString(" "))?.let { return DriverCommand.SetDuration(it) }
        }
        Regex("course de (.+?) tours?($|\\s)").find(t)?.let { m ->
            numberAt(m.groupValues[1])?.let { return DriverCommand.SetLaps(it) }
        }
        return null
    }

    /** "je suis troisième", "je suis dix-septième", "je suis 3e", "en position vingt deux", "P3". */
    private fun positionIn(t: String): Int? {
        val padded = " $t "
        // "suis" seul : la reconnaissance avale souvent le "je" (test réel du 13/10 : « suis quatorzieme »).
        val mentionsPosition = has(t, "suis", "position", "place", "je roule", "p ")
        val otherTopic = has(t, "secteur", "tour", "rapport", "vitesse")
        for ((phrase, n) in ORDINALS) {
            if (" $phrase " in padded && (mentionsPosition || t == phrase || !otherTopic)) return n
        }
        // Réponse brève à « Position ? » : un nombre seul ("huit").
        CARDINALS.firstOrNull { it.first == t }?.let { return it.second }
        Regex("(^|\\s)(\\d{1,2})(e|eme|er|re)($|\\s)").find(t)?.let { if (mentionsPosition) return it.groupValues[2].toInt() }
        Regex("(^|\\s)p ?(\\d{1,2})($|\\s)").find(t)?.let { return it.groupValues[2].toInt() }
        Regex("(position|place) (.+)$").find(t)?.let { m ->
            val after = " ${m.groupValues[2]} "
            for ((phrase, n) in CARDINALS) if (after.startsWith(" $phrase ")) return n
            m.groupValues[2].substringBefore(' ').toIntOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Vocabulaire de la radio, avec accents, pour restreindre la reconnaissance vocale
     * (beaucoup plus fiable dans le bruit du jeu). Les mots inconnus du modèle sont ignorés par Vosk.
     */
    fun vocabulary(wakeWord: String): List<String> {
        val words = wakeWord.lowercase().split(' ').filter { it.isNotBlank() } + listOf(
            "je", "suis", "j'ai", "il", "elle", "on", "m'a", "me", "fait", "doubler", "doublé", "passer", "passé",
            "dépasser", "dépassé", "perdu", "gagné", "pris", "une", "un", "place", "position", "quelle", "quel",
            "combien", "essence", "d'essence", "carburant", "réservoir", "secteur", "secteurs", "mes", "mon", "le", "la",
            "en", "de", "dernier", "dernière", "tour", "tours", "meilleur", "chrono", "temps", "rythme", "course",
            "reste", "course", "minutes", "minute", "heure", "heures", "demie", "obligatoire", "obligatoires", "arrêt",
            "arrêts", "d'arrêt", "pas", "aucun", "fois", "consommation", "usure", "stratégie", "plan", "quarante",
            "cinquante", "soixante", "oui", "non", "fait", "effectué", "annule", "mis", "monté", "montés", "départ",
            "pars", "durs", "dures", "mediums", "medium", "moyens", "tendres", "softs", "intermédiaires", "obligations",
            "règlement", "détails", "essentielle", "pleut", "pluie", "mouillé", "mouillée", "piste", "sèche", "sec", "plus", "arrête", "pneus", "pneu", "gommes", "température", "températures", "silence", "chut", "tu", "peux", "parler", "répète", "répéter",
            "premier", "première", "deuxième", "second", "troisième", "quatrième", "cinquième", "sixième", "septième",
            "huitième", "neuvième", "dixième", "onzième", "douzième", "treizième", "quatorzième", "quinzième",
            "seizième", "dix-septième", "dix-huitième", "dix-neuvième", "vingtième", "vingt", "et", "unième",
            "vingt-deuxième", "vingt-troisième", "vingt-quatrième", "vingt-cinquième", "trentième",
            "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize",
            "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf", "trente",
        )
        return (words.distinct() + "[unk]")
    }

    private fun has(t: String, vararg keys: String) = keys.any { it in t }
}
