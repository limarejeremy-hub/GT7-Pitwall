package com.gt7pitwall.core.radio

/**
 * Issue #73 (course Sport C du 06/11) : arbitre entre les deux transcriptions d'une même phrase.
 *
 * La reconnaissance « vocabulaire radio » (Vosk restreint) est fiable pour les ordres courts, mais déforme les phrases
 * longues : « il était préférable de partir en dur pour medium » devenait « dépasse faire départ tiennent durent bons
 * mediums », appliqué comme une déclaration de pneus ; « je perds du temps dans un virage » devenait un dépassement subi.
 * Règle : la transcription LIBRE décide du sens. Une modification de course (pneus, arrêt, règlement, position, météo)
 * n'est appliquée que si la phrase libre est bien une déclaration ET dit la même chose ; une question, une hypothèse,
 * une comparaison ou un sujet de pilotage ne modifient jamais rien ; un désaccord demande le « oui » du pilote.
 */
object IntentArbiter {

    sealed interface Decision {
        /** Rien à arbitrer : le chemin habituel s'applique. */
        data object Proceed : Decision

        /** Question, hypothèse, comparaison ou pilotage : réponse d'analyse, AUCUNE modification. [question] : texte libre. */
        data class Analysis(val question: String, val topic: Topic, val reason: String) : Decision

        /** Déclaration possible mais transcriptions en désaccord : appliquée seulement après « oui ». */
        data class Clarify(val command: DriverCommand, val reason: String) : Decision
    }

    enum class Topic { COACHING, TYRES, STRATEGY }

    /** Hypothèse, comparaison, regret ou demande d'avis : jamais une déclaration (« il était préférable de… »). */
    private val ANALYSIS_MARKERS = Regex(
        // Pas « pourquoi », « combien », « est-ce que » : des questions simples, que le moteur sait déjà traiter (26/10).
        "\\b(preferable|prefere|aurai[st]?|aurions|auriez|aurait|fallait|faudrait|vaudrait|valait|mieux|plutot|par rapport|" +
            "compare|comparaison|tu penses|tu crois|selon toi|a ton avis|tu conseilles|tu me conseilles|que faire|on fait quoi|" +
            "si je|et si)\\b",
    )

    /** Sujet de pilotage : temps perdu, virage, freinage, trajectoire… jamais un dépassement ni une position. */
    private val DRIVING = Regex(
        "\\b(perds?|perdu|perdre) (du|de) temps\\b|\\bvirages?\\b|\\bfreinage|\\bfreine|\\btrajectoire|\\bcorde\\b|\\bsortie de\\b|" +
            "\\bentree de\\b|\\bsecteur|\\bpatine|\\bbloque|\\bglisse",
    )

    /** Précision de tour (« au début du tour sept ») : un plan ou une intention, pas une déclaration au présent. */
    private val LAP_PLAN = Regex("\\b(au|a|du|fin du|debut du|avant le|apres le) (debut du |fin du )?tours? (\\d+|un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze)\\b")

    /** Passé composé ou « déjà » : une déclaration de fait (« j'ai changé mes pneus au tour sept »). */
    private val PAST = Regex("\\b(j ai|jai|ai|avons|suis|deja|change|changes|mis|monte|montes|fait)\\b")

    private val TYRES = Regex("\\b(pneus?|gommes?|durs?|mediums?|moyens?|tendres?|intermediaires?|pluie)\\b")

    private val POSITION_CMDS = setOf(DriverCommand.Overtook::class, DriverCommand.Overtaken::class, DriverCommand.SeveralPlaces::class,
        DriverCommand.ReportPosition::class, DriverCommand.ReportLast::class)

    /**
     * [vocabText] : transcription vocabulaire radio ; [freeText] : transcription libre (null si absente) ; [vocabCmd] :
     * commande que l'analyseur tire du vocabulaire radio.
     */
    fun decide(vocabText: String, freeText: String?, vocabCmd: DriverCommand?): Decision {
        val free = freeText?.takeIf { it.isNotBlank() } ?: return Decision.Proceed
        if (DriverCommandParser.normalize(free) == DriverCommandParser.normalize(vocabText)) return Decision.Proceed
        val f = DriverCommandParser.normalize(free)
        val words = f.split(' ').filter { it.isNotBlank() }.size
        val mode = StatementGuard.mode(free)
        val topic = when {
            DRIVING.containsMatchIn(f) -> Topic.COACHING
            TYRES.containsMatchIn(f) -> Topic.TYRES
            else -> Topic.STRATEGY
        }
        val freeCmd = DriverCommandParser.parse(free)
        val sensitive = vocabCmd != null && CommandGate.sensitive(vocabCmd)

        // 1. Pilotage dans la phrase libre, mais position/dépassement côté vocabulaire : c'est une question de pilotage.
        if (vocabCmd != null && vocabCmd::class in POSITION_CMDS && topic == Topic.COACHING) {
            return Decision.Analysis(free, Topic.COACHING, "phrase libre sur le pilotage, vocabulaire radio lu comme une position")
        }
        // 2. Question, hypothèse, comparaison, demande d'avis : analyse, jamais une modification.
        val analysisWords = ANALYSIS_MARKERS.containsMatchIn(f)
        val questionLike = mode == StatementGuard.Mode.QUESTION || mode == StatementGuard.Mode.HYPOTHETICAL
        if (words >= 4 && (analysisWords || questionLike) && (sensitive || vocabCmd is DriverCommand.Ask || vocabCmd == null)) {
            // Comparaison / avis : toujours une analyse. Simple question (« combien d'essence ») : seulement si elle
            // contredit une modification, ou si les deux transcriptions ne posent pas la même question.
            if ((analysisWords && vocabCmd != null) || sensitive || (vocabCmd is DriverCommand.Ask && freeCmd != vocabCmd)) {
                return Decision.Analysis(free, topic, if (analysisWords) "comparaison, hypothèse ou demande d'avis" else "question")
            }
        }
        // 3. Intention datée (« changer mes pneus au début du tour sept ») lue comme une question d'état : discussion du plan.
        if (words >= 4 && LAP_PLAN.containsMatchIn(f) && (vocabCmd is DriverCommand.Ask || vocabCmd == null) &&
            !PAST.containsMatchIn(f) && (TYRES.containsMatchIn(f) || Regex("\\b(arret|stand|rentrer|box)\\b").containsMatchIn(f))
        ) {
            return Decision.Analysis(free, if (topic == Topic.COACHING) Topic.COACHING else Topic.STRATEGY, "intention datée par le pilote")
        }
        // 4. Modification proposée par le vocabulaire radio, que la phrase libre ne confirme pas : « oui » exigé.
        if (sensitive && vocabCmd != null) {
            val agree = freeCmd != null && freeCmd::class == vocabCmd::class
            if (!agree) return Decision.Clarify(vocabCmd, "la phrase libre ne dit pas la même chose (« ${free.take(60)} »)")
        }
        return Decision.Proceed
    }
}
