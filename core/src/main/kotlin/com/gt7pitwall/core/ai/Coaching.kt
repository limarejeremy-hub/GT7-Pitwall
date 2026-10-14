package com.gt7pitwall.core.ai

import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.engineer.FrenchFormat
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ce que la télémétrie prouve sur le pilotage. Un défaut n'est reproché au pilote que s'il est prouvé ici. */
enum class Evidence {
    NO_TELEMETRY,
    LOCKUPS,
    WHEELSPIN,
    TCS,
    INCONSISTENT,
    CONSISTENT,
    SECTOR_LOSS,
    TYRES_HOT,
    TYRES_COLD,
    FRONT_HOTTER,
    REAR_HOTTER,
}

/** Constat tiré de la télémétrie, avec le chiffre qui le prouve. */
data class Observation(val evidence: Evidence, val text: String)

/**
 * Constats de pilotage calculés à partir des tours complets mesurés (blocages, patinages, antipatinage, régularité,
 * secteurs) et des températures de surface des pneus transmises par GT7. Seuils prudents : mieux vaut ne rien
 * conclure que reprocher un défaut qui n'existe pas.
 */
object CoachingObservations {
    const val WINDOW = 3
    const val MIN_EVENTS = 2
    const val TCS_SHARE = 0.08f
    const val INCONSISTENT_MS = 800
    const val CONSISTENT_MS = 250
    const val SECTOR_LOSS_MS = 300
    const val HOT_C = 105f
    const val COLD_C = 45f
    const val IMBALANCE_C = 15f

    fun from(laps: List<LapRecord>, packet: TelemetryPacket?): List<Observation> {
        val complete = laps.filter { it.lockups != null && it.wheelspins != null }.takeLast(WINDOW)
        if (packet == null && complete.isEmpty()) {
            return listOf(Observation(Evidence.NO_TELEMETRY, "Aucune télémétrie de course : aucun défaut de pilotage n'est prouvé."))
        }
        val out = mutableListOf<Observation>()
        if (complete.isNotEmpty()) {
            val span = if (complete.size == 1) "au tour ${complete[0].lapNumber}" else "sur les ${complete.size} derniers tours"
            val lockups = complete.sumOf { it.lockups ?: 0 }
            if (lockups >= MIN_EVENTS) {
                out += Observation(Evidence.LOCKUPS, "$lockups blocages de roue au freinage $span (${perLap(complete) { it.lockups }}).")
            }
            val spins = complete.sumOf { it.wheelspins ?: 0 }
            if (spins >= MIN_EVENTS) {
                out += Observation(Evidence.WHEELSPIN, "$spins patinages à l'accélération $span (${perLap(complete) { it.wheelspins }}).")
            }
            val tcs = complete.mapNotNull { it.tcsShare }.takeIf { it.isNotEmpty() }?.average()
            if (tcs != null && tcs >= TCS_SHARE) {
                out += Observation(Evidence.TCS, "Antipatinage actif ${(tcs * 100).roundToInt()} % du tour en moyenne $span.")
            }
        }
        val stats = LapAnalyzer.analyze(laps)
        val timed = laps.count { (it.timeMs ?: 0) > 0 }
        stats.recentStdDevMs?.takeIf { timed >= WINDOW }?.let { std ->
            when {
                std >= INCONSISTENT_MS -> out += Observation(
                    Evidence.INCONSISTENT,
                    "Chronos irréguliers : écart-type de ${FrenchFormat.deltaSpeech(std)} sur les derniers tours.",
                )
                std <= CONSISTENT_MS -> out += Observation(
                    Evidence.CONSISTENT,
                    "Chronos réguliers : écart-type de ${FrenchFormat.deltaSpeech(std)} sur les derniers tours.",
                )
            }
        }
        sectorLoss(laps)?.let { out += it }
        packet?.let { p -> out += tyres(p, laps.size) }
        return out
    }

    private fun perLap(laps: List<LapRecord>, value: (LapRecord) -> Int?) =
        laps.joinToString(", ") { "tour ${it.lapNumber} : ${value(it) ?: 0}" }

    /** Secteur du dernier tour le plus loin de ton meilleur temps dans ce secteur. */
    private fun sectorLoss(laps: List<LapRecord>): Observation? {
        val withSectors = laps.filter { it.sectorsMs != null }
        val last = withSectors.lastOrNull() ?: return null
        val count = last.sectorsMs!!.size
        val best = (0 until count).map { i -> withSectors.mapNotNull { it.sectorsMs?.getOrNull(i) }.minOrNull() }
        val losses = last.sectorsMs.mapIndexed { i, ms -> i to (best[i]?.let { ms - it } ?: 0) }
        val (index, loss) = losses.maxByOrNull { it.second } ?: return null
        if (loss < SECTOR_LOSS_MS) return null
        return Observation(
            Evidence.SECTOR_LOSS,
            "Au tour ${last.lapNumber}, le secteur ${index + 1} est à plus ${FrenchFormat.deltaSpeech(loss)} de ton meilleur temps dans ce secteur.",
        )
    }

    private fun tyres(p: TelemetryPacket, lapsDone: Int): List<Observation> {
        val t = p.tyreTemps
        val all = listOf(t.fl, t.fr, t.rl, t.rr)
        if (all.any { it <= 0f } || p.speedKmh < 30f) return emptyList()
        val out = mutableListOf<Observation>()
        val hottest = all.max()
        if (hottest >= HOT_C) out += Observation(Evidence.TYRES_HOT, "Pneus très chauds : jusqu'à ${hottest.roundToInt()} degrés en surface.")
        if (lapsDone >= 2 && all.max() <= COLD_C) {
            out += Observation(Evidence.TYRES_COLD, "Pneus froids : ${all.max().roundToInt()} degrés au plus en surface.")
        }
        val front = (t.fl + t.fr) / 2f
        val rear = (t.rl + t.rr) / 2f
        if (abs(front - rear) >= IMBALANCE_C) {
            val diff = abs(front - rear).roundToInt()
            out += if (front > rear) {
                Observation(Evidence.FRONT_HOTTER, "Les pneus avant sont $diff degrés plus chauds que l'arrière.")
            } else {
                Observation(Evidence.REAR_HOTTER, "Les pneus arrière sont $diff degrés plus chauds que l'avant.")
            }
        }
        return out
    }
}

/**
 * Principes de pilotage sur circuit, vérifiés, donnés à l'IA selon le sujet. Sans chiffre : ils expliquent le
 * pourquoi et le geste, jamais un temps ni une valeur.
 */
object DrivingPrinciples {
    enum class Topic(val keywords: List<String>, val text: String) {
        BRAKING(
            listOf("frein", "freine", "freinage", "freiner", "degressif", "trail"),
            "Freinage : appuie fort et tôt, roues droites, quand la voiture est stable ; puis relâche la pédale " +
                "progressivement en tournant le volant (freinage dégressif) jusqu'à l'approche du point de corde. " +
                "La charge reste sur l'avant, qui accroche mieux et fait tourner la voiture.",
        ),
        LOCKUP(
            listOf("bloque", "blocage", "bloquer", "bloquent"),
            "Blocage de roue : trop de pression de frein pour l'adhérence disponible, surtout en braquant. " +
                "Remède : un peu moins de pression au début, relâcher plus tôt dès que tu tournes le volant, " +
                "et ne pas freiner fort sur une bosse ou un vibreur.",
        ),
        APEX(
            listOf("corde", "apex", "point de corde"),
            "Point de corde : le point le plus à l'intérieur du virage. Un point de corde tardif (entrée un peu " +
                "plus large, la voiture pivote avant) permet de réaccélérer plus tôt, volant plus droit : c'est " +
                "prioritaire avant une longue ligne droite.",
        ),
        LINE(
            listOf("trajectoire", "ligne", "virage", "virages", "enchainement", "chicane"),
            "Trajectoire : extérieur à l'entrée, intérieur au point de corde, extérieur en sortie, pour le plus " +
                "grand rayon possible. Dans un enchaînement, sacrifie le premier virage pour bien sortir du dernier.",
        ),
        WEIGHT(
            listOf("transfert", "masse", "poids", "charge", "instable", "nerveuse"),
            "Transfert de masse : freiner charge l'avant et allège l'arrière (l'arrière peut décrocher si tu " +
                "freines fort en braquant) ; accélérer charge l'arrière et allège l'avant (l'avant élargit si tu " +
                "accélères tôt en braquant). Des gestes progressifs gardent la voiture équilibrée.",
        ),
        EXIT(
            listOf("sortie", "reaccelere", "accelerer", "accelere", "gaz", "accelerateur", "remettre"),
            "Réaccélération : remets les gaz progressivement après le point de corde, en ouvrant le volant ; " +
                "plus le volant s'ouvre, plus tu peux accélérer. Pleine charge seulement roues presque droites.",
        ),
        WHEELSPIN(
            listOf("patine", "patinage", "patiner", "antipatinage", "tcs", "motricite"),
            "Patinage ou antipatinage actif : trop de gaz pour l'adhérence, ou volant encore braqué. Remède : " +
                "doser l'accélérateur, ouvrir le volant avant d'accélérer fort, et en virage lent un rapport plus long.",
        ),
        UNDERSTEER(
            listOf("sous vire", "sous virage", "sous-vire", "l'avant glisse", "avant glisse", "tout droit", "elargi"),
            "Sous-virage (l'avant glisse tout droit) : souvent entrée trop rapide ou gaz trop tôt. Freine un peu " +
                "plus tôt, garde un filet de frein en entrée pour charger l'avant, et attends que la voiture ait " +
                "tourné avant d'accélérer.",
        ),
        OVERSTEER(
            listOf("survire", "survirage", "l'arriere glisse", "arriere glisse", "tete a queue", "decroche"),
            "Survirage (l'arrière glisse) : freinage fort en braquant, ou trop de gaz en sortie. Relâche le frein " +
                "plus progressivement, dose l'accélérateur et contre-braque doucement sans à-coup.",
        ),
        TYRES(
            listOf("pneu", "pneus", "gomme", "gommes", "temperature", "chauffe", "surchauffe", "usure", "degradation"),
            "Pneus : ils surchauffent quand ils glissent (sous-virage, patinage, blocages) et perdent alors de " +
                "l'adhérence. Pour les préserver : moins de glissade, gestes doux, trajectoires propres ; froids, " +
                "ils accrochent moins en début de relais.",
        ),
        CONSISTENCY(
            listOf("regulier", "regularite", "constant", "irregulier", "erreur", "erreurs"),
            "Régularité : prends des repères fixes à chaque virage (point de freinage, point de corde, remise des " +
                "gaz) et travaille un virage à la fois plutôt que tout le tour.",
        ),
    }

    const val MAX_TOPICS = 3

    /** Principes utiles pour la question, la conversation et les constats ; par défaut freinage, corde et sortie. */
    fun select(question: String, history: List<RadioAi.Exchange>, evidence: Set<Evidence>): List<Topic> {
        val text = " " + DriverCommandParser.normalize(question + " " + history.joinToString(" ") { it.question }) + " "
        val picked = linkedSetOf<Topic>()
        // La question du moment d'abord, puis la conversation.
        val now = " " + DriverCommandParser.normalize(question) + " "
        Topic.entries.filter { t -> t.keywords.any { " $it" in now } }.forEach { picked += it }
        Topic.entries.filter { t -> t.keywords.any { " $it" in text } }.forEach { picked += it }
        if (Evidence.LOCKUPS in evidence) picked += listOf(Topic.LOCKUP, Topic.BRAKING)
        if (Evidence.WHEELSPIN in evidence || Evidence.TCS in evidence) picked += listOf(Topic.WHEELSPIN, Topic.EXIT)
        if (Evidence.TYRES_HOT in evidence || Evidence.FRONT_HOTTER in evidence || Evidence.REAR_HOTTER in evidence) picked += Topic.TYRES
        if (Evidence.INCONSISTENT in evidence) picked += Topic.CONSISTENCY
        if (picked.isEmpty()) picked += listOf(Topic.BRAKING, Topic.APEX, Topic.EXIT)
        return picked.take(MAX_TOPICS)
    }
}

/**
 * Contrôle d'un conseil, phrase par phrase : absurdités de pilotage, banalités sans geste concret, et défauts
 * reprochés au pilote sans preuve (télémétrie ou ce qu'il a dit lui-même).
 */
object AdviceCheck {
    enum class Problem { NONSENSE, BANALITY, UNPROVEN_DEFECT }

    /** Conseils faux ou dangereux en pilotage sur circuit. */
    private val NONSENSE = listOf(
        Regex("\\brails?\\b"),
        Regex("glissiere"),
        Regex("(contact|toucher|frotter|longer|appuie[rz]?)( \\S+){0,3} (avec |contre |sur )?(le |les |un )?(mur|murs|barriere|barrieres)"),
        Regex("frein a main"),
        Regex("freine[rz]? (fort|a fond|au maximum)( \\S+){0,2} (au|en plein|dans le|a la) (point de corde|corde|apex|milieu du virage|virage)"),
        Regex("accelere[rz]? a fond( \\S+){0,3} (en entree|avant le point de corde|avant la corde|au freinage|en freinant)"),
        Regex("(rouler|roule|passe[rz]?|coupe[rz]?)( \\S+){0,3} (dans |par )?(l'herbe|le gravier|les graviers|la terre)"),
        Regex("(freine[rz]?|freinage) (et|en) accel"),
        Regex("leve[rz]? le pied( \\S+){0,3} (en plein|au milieu du) virage rapide"),
    )

    /** Formules creuses : acceptées seulement si la phrase donne aussi un geste ou un repère concret. */
    private val BANAL = Regex(
        "fluide|fluidite|equilibre parfait|reste concentre|concentre toi|donne tout|fais de ton mieux|" +
            "trouve le bon rythme|garde le rythme|reste calme|fais confiance|sois regulier",
    )
    private val CONCRETE = Regex(
        "frein|freine|pedale|degressi|relach|accel|gaz|volant|braqu|corde|apex|trajectoire|entree|sortie|rapport|" +
            "repere|point de|transfert|charge|tour \\d|secteur|blocage|patin|pneu|temperature|vibreur|ligne droite",
    )

    /** Défaut reproché au pilote → preuve attendue (constat de télémétrie) ou mot qu'il a dû dire lui-même. */
    private class Claim(val pattern: Regex, val evidence: Set<Evidence>, val driverWords: List<String>)

    private val CLAIMS = listOf(
        Claim(Regex("\\btu (as )?bloqu|\\btu fais bloquer|\\btes (roues|pneus) (ont )?bloqu|\\btes blocages"), setOf(Evidence.LOCKUPS), listOf("bloqu")),
        Claim(
            Regex("\\btu (fais )?patin|\\btu as patine|\\btes roues patinent|\\btu abuses de l'antipatinage|\\btes patinages"),
            setOf(Evidence.WHEELSPIN, Evidence.TCS),
            listOf("patin", "antipatinage", "tcs"),
        ),
        Claim(Regex("\\btu freines (trop|pas assez|beaucoup trop)|\\bton freinage est (trop|mal)"), emptySet(), listOf("frein")),
        Claim(Regex("\\btu (sous vires|sous-vires|survires)|\\bta voiture (sous vire|survire)"), emptySet(), listOf("vire", "glisse", "tout droit", "decroche")),
        Claim(
            Regex("\\btu (rates|manques|prends mal|loupes) (la |le |ton |ta )?(point de )?corde|\\bta trajectoire est|\\btu elargis"),
            emptySet(),
            listOf("corde", "trajectoire", "elargi", "large"),
        ),
        Claim(Regex("\\btu (es|roules de maniere) irreguli|\\btu manques de regularite|\\btes chronos (sont )?irreguli"), setOf(Evidence.INCONSISTENT), listOf("regul")),
        Claim(Regex("\\btes pneus (sont trop chauds|surchauffent|chauffent trop)"), setOf(Evidence.TYRES_HOT, Evidence.FRONT_HOTTER, Evidence.REAR_HOTTER), listOf("chauff", "chaud")),
        Claim(Regex("\\btes pneus (sont )?(trop )?froids"), setOf(Evidence.TYRES_COLD), listOf("froid")),
        Claim(Regex("\\btu perds (du temps|beaucoup)"), setOf(Evidence.SECTOR_LOSS, Evidence.INCONSISTENT), listOf("perd")),
        Claim(Regex("\\btu (accelere|remets les gaz|reaccelere)s? trop (tot|fort)"), setOf(Evidence.WHEELSPIN, Evidence.TCS), listOf("accel", "gaz", "patin")),
    )

    /** [driverSaid] : ce que le pilote a dit dans la conversation (ses propres constats sont des preuves). */
    fun check(sentence: String, evidence: Set<Evidence>, driverSaid: String): Problem? {
        val s = " " + DriverCommandParser.normalize(sentence) + " "
        if (NONSENSE.any { it.containsMatchIn(s) }) return Problem.NONSENSE
        if (BANAL.containsMatchIn(s) && !CONCRETE.containsMatchIn(s)) return Problem.BANALITY
        // Une question (« Tu bloques au freinage ? ») n'affirme rien : c'est ainsi qu'on approfondit.
        if (sentence.trim().endsWith("?")) return null
        val said = DriverCommandParser.normalize(driverSaid)
        for (c in CLAIMS) {
            if (!c.pattern.containsMatchIn(s)) continue
            val proven = c.evidence.any { it in evidence } || c.driverWords.any { it in said }
            if (!proven) return Problem.UNPROVEN_DEFECT
        }
        return null
    }
}
