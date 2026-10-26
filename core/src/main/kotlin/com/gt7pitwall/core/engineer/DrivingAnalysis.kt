package com.gt7pitwall.core.engineer

import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.session.LapRecord
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Questions sur le pilotage (« mes tours sont bons ? », « où je perds du temps ? », « je freine trop tôt ? ») et leurs
 * réponses, calculées uniquement sur les tours mesurés de la session. Course du 26/10 : « puisque mes tours sont bons »
 * recevait « Tour 10 sur 14 », la question avait été rangée avec l'avancement de la course à cause du mot « tours ».
 *
 * Rien n'est inventé : sans assez de tours propres, la réponse le dit et donne ce qui est mesuré. Une cause (freinage,
 * trajectoire) n'est jamais affirmée sans mesure par virage (profil de pilotage).
 */
object DrivingAnalysis {

    /** Classe une phrase normalisée (sans accents) ; null si ce n'est pas une question sur le pilotage. */
    fun classify(text: String): DriverQuery? {
        val t = DriverCommandParser.normalize(text).replace(Regex("[.,!;:?«»\"]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        // Les autres moteurs gardent leurs sujets : pneus, essence, stratégie, place.
        if (OTHER.containsMatchIn(t)) return null
        // « Comment améliorer mon freinage ? », « explique-moi comment progresser » : une question de technique (principes
        // de pilotage, conversation), pas une mesure de ma course.
        if (TECHNIQUE.containsMatchIn(t)) return null
        return when {
            LEARNED.containsMatchIn(t) -> DriverQuery.DRIVING_LEARNED
            BRAKING.containsMatchIn(t) -> DriverQuery.DRIVING_BRAKING
            LOSS.containsMatchIn(t) -> DriverQuery.DRIVING_LOSS
            PROGRESS.containsMatchIn(t) -> DriverQuery.DRIVING_PROGRESS
            ADVICE.containsMatchIn(t) -> DriverQuery.DRIVING_ADVICE
            QUALITY.containsMatchIn(t) -> DriverQuery.DRIVING_QUALITY
            else -> null
        }
    }

    val QUERIES = setOf(
        DriverQuery.DRIVING_QUALITY, DriverQuery.DRIVING_LOSS, DriverQuery.DRIVING_BRAKING, DriverQuery.DRIVING_PROGRESS,
        DriverQuery.DRIVING_ADVICE, DriverQuery.DRIVING_LEARNED,
    )

    private val LEARNED = Regex("(tu as appris|t'as appris|as tu appris|tu as retenu|tu sais sur (moi|mon pilotage)|mon profil de pilotage)")

    private val TECHNIQUE = Regex("(^|\\s)(comment|explique|expliquer|c'est quoi|qu'est ce que c'est)($|\\s)")
    private val OTHER = Regex("(^|\\s)(pneus?|gommes?|essence|carburant|fuel|strategie|plan|position|place|places|arret|stand|stands|chauffent)($|\\s)")
    private val QUALITY = Regex(
        "(tours? (sont|est|etaient|etait) (bons?|bien|corrects?|propres?|rapides?))|((^|\\s)(je conduis|je pilote|mon pilotage|ma conduite)($|\\s))|" +
            "(conduis bien|pilote bien|conduis mal)|(c'est bon mon rythme)",
    )
    private val LOSS = Regex(
        "(perds?|perdre|perdu)( du| de)? (temps|du temps)|(ou (est ce que )?je perds)|(sur quel(s)? virages?)|(quel(s)? virages?)|(plus lent|moins rapide|je suis lent)",
    )
    private val BRAKING = Regex("(freine|freinage|freiner)")
    private val PROGRESS = Regex("(progresse|progression|progres|amelioration|je m'ameliore|ca s'ameliore|mieux qu'avant)")
    private val ADVICE = Regex("(je dois changer|dois je changer|que (dois|devrais) je|qu'est ce que je (dois|peux) (changer|ameliorer)|m'ameliorer|ameliorer|conseil de pilotage)")

    /** Tours comparables : chronométrés, ni tour de départ, ni ravitaillement, ni passage par les stands, ni tour perturbé. */
    fun cleanLaps(laps: List<LapRecord>, pitLaps: Set<Int>): List<LapRecord> {
        val timed = laps.filter { (it.timeMs ?: 0) > 0 && it.lapNumber >= 2 && !it.refuelled && !it.pitLane && it.lapNumber !in pitLaps }
        val best = timed.minOfOrNull { it.timeMs!! } ?: return emptyList()
        return timed.filter { it.timeMs!! <= best * PERTURBED_RATIO }
    }

    /** Tour théorique : somme des meilleurs secteurs comparables (jamais présenté comme un chrono réalisé). */
    fun theoreticalMs(clean: List<LapRecord>): Int? {
        val withSectors = clean.mapNotNull { it.sectorsMs?.takeIf { s -> s.size >= 2 && s.all { x -> x > 0 } } }
        val n = withSectors.firstOrNull()?.size ?: return null
        val same = withSectors.filter { it.size == n }
        if (same.size < 2) return null
        return (0 until n).sumOf { i -> same.minOf { it[i] } }
    }

    /**
     * Réponse mesurée. [pitLaps] : tours d'arrêt (hors comparaison) ; [cornerFacts] : constats du profil de pilotage par
     * virage, s'il existe (sinon la réponse reste au niveau des secteurs et le dit).
     */
    fun answer(query: DriverQuery, laps: List<LapRecord>, pitLaps: Set<Int>, cornerFacts: List<String> = emptyList()): String {
        val clean = cleanLaps(laps, pitLaps)
        if (clean.size < MIN_LAPS) {
            return "Pas encore assez de tours propres pour juger ton pilotage (${clean.size} sur $MIN_LAPS) : tour de départ, arrêts et tours perturbés sont écartés."
        }
        val times = clean.map { it.timeMs!!.toDouble() }
        val best = clean.minByOrNull { it.timeMs!! }!!
        val mean = times.average()
        val spread = sqrt(times.sumOf { (it - mean) * (it - mean) } / times.size)
        val gap = mean - best.timeMs!!
        val sectors = sectorLosses(clean)
        val worst = sectors?.withIndex()?.maxByOrNull { it.value }
        val corner = cornerFacts.firstOrNull()
        return when (query) {
            DriverQuery.DRIVING_QUALITY -> {
                val verdict = when {
                    gap <= 500 && spread <= 400 -> "Oui, très bons"
                    gap <= 1_000 && spread <= 800 -> "Oui, bons"
                    else -> "Corrects mais irréguliers"
                }
                "$verdict : ${clean.size} tours propres, moyenne ${t(mean)}, à ${s(gap)} de ton meilleur (${t(best.timeMs.toDouble())}), régularité à plus ou moins ${s(spread)}."
            }
            DriverQuery.DRIVING_LOSS -> buildString {
                if (worst != null) {
                    append("Tu perds le plus dans le secteur ${worst.index + 1} : ${s(worst.value)} par tour en moyenne sur ton meilleur passage.")
                } else {
                    append("Pas de secteurs mesurés sur assez de tours : je vois seulement ${s(gap)} entre ta moyenne et ton meilleur tour.")
                }
                append(" ").append(corner ?: "Pas encore de mesure virage par virage pour dire où exactement.")
            }
            DriverQuery.DRIVING_BRAKING -> buildString {
                val lock = trend(clean) { it.lockups }
                if (lock != null) append("Blocages : ${n(lock.first)} par tour au début, ${n(lock.second)} sur les derniers tours. ")
                append(corner ?: "Je ne mesure pas encore ton point de freinage virage par virage : je ne peux pas dire si tu freines trop tôt ou trop tard.")
            }
            DriverQuery.DRIVING_LEARNED -> "Profil de pilotage : ${clean.size} tours propres dans cette session ; le détail est dans le diagnostic de l'écran Pilotage."
            DriverQuery.DRIVING_PROGRESS -> {
                val firsts = times.take(3).average()
                val lasts = times.takeLast(3).average()
                val d = lasts - firsts
                when {
                    clean.size < 6 -> "Trop tôt pour parler de progression : ${clean.size} tours propres. Ta moyenne est à ${s(gap)} de ton meilleur."
                    d <= -200 -> "Oui : tes trois derniers tours propres sont ${s(-d)} plus rapides que les trois premiers."
                    d >= 200 -> "Non : tes trois derniers tours propres sont ${s(d)} plus lents que les trois premiers (pneus, carburant ou pilotage : je ne sais pas départager)."
                    else -> "Stable : moins de deux dixièmes d'écart entre tes premiers et tes derniers tours propres."
                }
            }
            else -> buildString {
                // Une seule priorité, mesurée.
                when {
                    corner != null -> append(corner)
                    worst != null && worst.value >= 200 -> append("Priorité : le secteur ${worst.index + 1}, où tu laisses ${s(worst.value)} par tour sur ton meilleur passage.")
                    spread > 600 -> append("Priorité : la régularité, tes tours varient de plus ou moins ${s(spread)}.")
                    else -> append("Rien de net à changer : moyenne à ${s(gap)} de ton meilleur, régularité à plus ou moins ${s(spread)}.")
                }
                theoreticalMs(clean)?.takeIf { it < best.timeMs }?.let {
                    append(" Tour théorique avec tes meilleurs secteurs : ${t(it.toDouble())}, à titre indicatif.")
                }
            }
        }
    }

    /** Perte moyenne par secteur (ms) sur le meilleur passage de chaque secteur, null sans secteurs comparables. */
    fun sectorLosses(clean: List<LapRecord>): List<Double>? {
        val withSectors = clean.mapNotNull { it.sectorsMs?.takeIf { s -> s.size >= 2 && s.all { x -> x > 0 } } }
        val n = withSectors.firstOrNull()?.size ?: return null
        val same = withSectors.filter { it.size == n }
        if (same.size < MIN_LAPS) return null
        return (0 until n).map { i -> same.map { it[i].toDouble() }.average() - same.minOf { it[i] } }
    }

    private fun trend(clean: List<LapRecord>, get: (LapRecord) -> Int?): Pair<Double, Double>? {
        val v = clean.mapNotNull(get)
        if (v.size < 4) return null
        val h = v.size / 2
        return v.take(h).average() to v.drop(h).average()
    }

    private fun t(ms: Double): String {
        val total = ms.roundToInt()
        val m = total / 60_000
        val sec = (total % 60_000) / 1000.0
        return if (m > 0) "$m:${String.format(java.util.Locale.FRANCE, "%04.1f", sec)}" else String.format(java.util.Locale.FRANCE, "%.1f s", sec)
    }

    private fun s(ms: Double): String = if (abs(ms) < 1_000) "${(ms / 100).roundToInt()} dixième${if (abs(ms) >= 150) "s" else ""}" else String.format(java.util.Locale.FRANCE, "%.1f s", ms / 1000)
    private fun n(x: Double): String = String.format(java.util.Locale.FRANCE, "%.1f", x)

    /** Tours propres minimum pour juger. */
    const val MIN_LAPS = 3

    /** Un tour plus lent de 7 % que le meilleur est perturbé (trafic, incident) : hors comparaison. */
    const val PERTURBED_RATIO = 1.07
}
