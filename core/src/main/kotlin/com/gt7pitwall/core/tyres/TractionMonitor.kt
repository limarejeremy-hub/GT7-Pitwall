package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.analysis.LapSample
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Motricité comparée en sortie de virage (issues #35 et #36, Sprint B) : « à commande comparable, la voiture patine
 * davantage ou accélère moins quand l'adhérence baisse ». Uniquement la télémétrie UDP, aucun HUD.
 *
 * Mesure : après la vitesse minimale de chaque virage, chaque paquet en réaccélération (frein relâché, accélérateur à
 * 30 % ou plus) est rangé dans une fenêtre de **conditions comparables** : même virage, même tranche d'accélérateur
 * (15 %), même rapport, même tranche de vitesse (10 km/h) et de charge latérale (3 m/s²). On y moyenne le glissement des
 * roues motrices, la part de TCS et l'accélération longitudinale réellement obtenue.
 *
 * Exclu de la référence pneus : roue sur un vibreur, l'herbe, la terre (format C), tour d'arrêt ou d'incident, premier
 * tour du relais. Sans surface (formats A, B, ~), la comparaison reste possible mais la qualité est dite réduite.
 *
 * Verdict : une perte de motricité dans une fenêtre = accélération obtenue plus faible ET (glissement plus fort OU TCS
 * plus présent : le TCS peut masquer le patinage). Un virage est retenu s'il la montre sur les deux derniers tours ; le
 * niveau monte d'un cran à la fois (hystérésis). « Motricité en baisse » est mesurable ; une usure en pourcentage ne
 * l'est pas : aucun pourcentage n'est produit.
 */
class TractionMonitor {
    enum class Level(val label: String) {
        INSUFFICIENT("comparaison impossible pour l'instant"),
        STABLE("motricité stable"),
        DEGRADATION_SUSPECTED("perte de motricité suspectée"),
        DEGRADATION_REPEATED("perte de motricité répétée"),
    }

    enum class Axle(val label: String) { FRONT("avant"), REAR("arrière"), UNKNOWN("roues motrices non identifiées") }

    /** Conditions comparables d'une fenêtre de réaccélération. */
    /**
     * Pas de tranche de régime : à rapport et vitesse égaux, un patinage fait justement monter le régime ; le classer à
     * part empêcherait de comparer les sorties dégradées aux sorties de référence.
     */
    data class Key(val corner: Int, val throttleBin: Int, val gear: Int, val speedBin: Int, val lateralBin: Int)

    class Agg {
        var n = 0
        var front = 0.0
        var rear = 0.0
        var tcs = 0
        var accel = 0.0
        var temp = 0.0
        /** Couple moteur normalisé (0x154, expérimental) : somme et nombre de paquets qui le portent. */
        var torque = 0.0
        var torqueN = 0
        fun add(s: LapSample) {
            val sl = s.slips!!
            n++
            front += (sl.fl + sl.fr) / 2.0
            rear += (sl.rl + sl.rr) / 2.0
            if (s.tcs) tcs++
            accel += s.longAccel!!
            temp += s.tyreTemps?.toList()?.average() ?: 0.0
            s.engineTorque?.let { torque += it; torqueN++ }
        }
        fun merge(o: Agg) {
            n += o.n; front += o.front; rear += o.rear; tcs += o.tcs; accel += o.accel; temp += o.temp
            torque += o.torque; torqueN += o.torqueN
        }
        /** Couple moyen, seulement si tous les paquets le portent. */
        val meanTorque: Double? get() = if (torqueN == n && n > 0) torque / n else null
        val frontSlip get() = front / n
        val rearSlip get() = rear / n
        val tcsShare get() = tcs.toDouble() / n
        val meanAccel get() = accel / n
        val meanTemp get() = temp / n
    }

    /** Ce qu'un tour a donné : fenêtres comparables et échantillons écartés (raison visible). */
    class LapProfile(
        val lap: Int,
        val windows: Map<Key, Agg>,
        val excludedSurface: Int,
        val unknownSurface: Int,
        val used: Int,
        /** Paquets écartés parce que l'ASM corrigeait la voiture. */
        val excludedAsm: Int = 0,
    )

    data class Report(
        val level: Level,
        val reasons: List<String>,
        val comparedCorners: Int = 0,
        val flaggedCorners: List<Int> = emptyList(),
        val axle: Axle = Axle.UNKNOWN,
        /** Variation moyenne sur les fenêtres retenues : glissement des roues motrices, accélération (m/s²), TCS. */
        val slipDelta: Double? = null,
        val accelDelta: Double? = null,
        val tcsDelta: Double? = null,
        val refLaps: List<Int> = emptyList(),
        val recentLaps: List<Int> = emptyList(),
        val surfaceKnown: Boolean = false,
        /**
         * Dernier niveau **valide** quand la comparaison est suspendue (pluie, pneus froids, données manquantes) :
         * historique seulement, jamais présenté comme confirmé maintenant (revue PR #37, P0-A).
         */
        val previous: Level? = null,
        /** Fenêtres où la baisse d'accélération s'explique par un couple délivré plus faible sans TCS (0x154, expérimental). */
        val torqueExplained: Int = 0,
    ) {
        /**
         * La stratégie ne tient compte que d'une perte répétée, constatée sur la comparaison en cours, avec des surfaces de
         * roues connues (format C) : sinon un vibreur indétectable pourrait passer pour une usure (P0-A, P0-C).
         */
        val strategyEligible: Boolean get() = level == Level.DEGRADATION_REPEATED && surfaceKnown
        fun text(label: (Int) -> String = { "V$it" }): String = buildString {
            append(level.label.replaceFirstChar { it.uppercase() })
            if (flaggedCorners.isNotEmpty()) append(" en sortie de ${flaggedCorners.joinToString(", ") { label(it) }}")
            if (comparedCorners > 0) append(" ($comparedCorners virages comparés, tours ${refLaps.joinToString("-")} contre ${recentLaps.joinToString("-")})")
            append(".")
            if (slipDelta != null && accelDelta != null && level != Level.INSUFFICIENT) {
                append(" À commande égale : glissement ${signed(slipDelta * 100, 1)} points, accélération ${signed(accelDelta, 2)} m/s²")
                tcsDelta?.let { append(", TCS ${signed(it * 100, 0)} points") }
                if (axle != Axle.UNKNOWN) append(", essieu ${axle.label}")
                append(".")
            }
            if (reasons.isNotEmpty()) append(" ${reasons.joinToString(" ; ").replaceFirstChar { it.uppercase() }}.")
            if (!surfaceKnown && level != Level.INSUFFICIENT) append(" Surface des roues inconnue (format sans surface) : vibreurs non exclus, constat jamais confirmé.")
            previous?.let { append(" Dernier constat valide : ${it.label}, plus confirmé tant que la comparaison est suspendue.") }
        }

        private fun signed(v: Double, decimals: Int) =
            (if (v >= 0) "+" else "−") + String.format(java.util.Locale.FRANCE, "%.${decimals}f", abs(v))
    }

    private val laps = sortedMapOf<Int, LapProfile>()

    /** Augmente à chaque tour ajouté : l'appelant sait quand réévaluer. */
    var version = 0
        private set

    /** Augmente à chaque évaluation : la radio et la stratégie lisent le dernier constat sans le recalculer. */
    var evaluations = 0
        private set
    private var stintStart: Int? = null
    private var level = Level.INSUFFICIENT
    private var suspectStreak = 0
    private var stableStreak = 0
    private var lastEvaluatedLap = -1

    var last: Report = Report(Level.INSUFFICIENT, listOf("pas encore de tour comparé"))
        private set

    fun reset() {
        laps.clear()
        level = Level.INSUFFICIENT
        suspectStreak = 0
        stableStreak = 0
        lastEvaluatedLap = -1
        last = Report(Level.INSUFFICIENT, listOf("pas encore de tour comparé"))
    }

    /** Nouveau relais (pneus changés) : la comparaison repart de zéro. */
    fun newStint() {
        laps.clear()
        level = Level.INSUFFICIENT
        suspectStreak = 0
        stableStreak = 0
    }

    /**
     * Un tour propre mesuré : pour chaque virage, les échantillons qui suivent sa vitesse minimale jusqu'à la fin de sa
     * zone. Les tours trop anciens sont oubliés (mémoire bornée).
     */
    fun addLap(lap: Int, exits: Map<Int, List<LapSample>>) {
        laps[lap] = profile(lap, exits)
        version++
        while (laps.size > MAX_LAPS) laps.remove(laps.firstKey())
    }

    fun profile(lap: Int, exits: Map<Int, List<LapSample>>): LapProfile {
        val w = HashMap<Key, Agg>()
        var excluded = 0
        var unknown = 0
        var used = 0
        var asm = 0
        for ((corner, samples) in exits) {
            for (s in samples) {
                if (s.brake > BRAKE_OFF || s.throttle < THROTTLE_MIN || s.slips == null || s.longAccel == null || s.speedMps < MIN_SPEED_MPS) continue
                if (abs(s.longAccel) > MAX_ACCEL) continue // choc, téléportation : pas une accélération de la voiture
                if (s.asm) {
                    asm++
                    continue
                }
                val sf = s.surfaces
                if (sf != null && !sf.allTarmac) {
                    excluded++
                    continue
                }
                if (sf == null) unknown++
                val key = Key(
                    corner,
                    ((s.throttle * 100 / 255 - 30) / 15).coerceIn(0, 4),
                    s.gear,
                    (s.speedMps * 3.6f / 10f).toInt(),
                    (abs(s.yawRate * s.speedMps) / 3f).toInt(),
                )
                w.getOrPut(key) { Agg() }.add(s)
                used++
            }
        }
        return LapProfile(lap, w, excluded, unknown, used, asm)
    }

    /**
     * Compare le début du relais et ses deux derniers tours. [stintStartLap] : premier tour du relais (écarté : pneus
     * froids) ; [excludedLaps] : arrêts, tour suivant, incidents déclarés.
     */
    fun evaluate(stintStartLap: Int, excludedLaps: Set<Int>, raining: Boolean = false): Report {
        // Nouveau relais : l'hystérésis repart de zéro (les tours d'avant sont déjà écartés par le filtre).
        if (stintStart != stintStartLap) {
            stintStart = stintStartLap
            level = Level.INSUFFICIENT
            suspectStreak = 0
            stableStreak = 0
        }
        val usable = laps.values.filter { it.lap > stintStartLap && it.lap !in excludedLaps }
        val newest = usable.lastOrNull()?.lap
        // Pluie déclarée : l'adhérence change pour une autre raison que les pneus ; rien n'est conclu (l'UDP ne dit pas la météo).
        val r = if (raining) {
            Report(Level.INSUFFICIENT, listOf("pluie déclarée : adhérence modifiée par la piste, comparaison suspendue"))
        } else {
            compare(usable)
        }
        // Hystérésis : une seule évaluation par tour ; « répétée » après deux constats successifs ; retour au calme après
        // deux tours stables seulement.
        if (newest != null && newest != lastEvaluatedLap) {
            lastEvaluatedLap = newest
            when (r.level) {
                Level.INSUFFICIENT -> if (level != Level.DEGRADATION_REPEATED) level = Level.INSUFFICIENT
                Level.STABLE -> {
                    suspectStreak = 0
                    stableStreak++
                    if (level != Level.DEGRADATION_REPEATED || stableStreak >= 2) level = Level.STABLE
                }
                else -> {
                    stableStreak = 0
                    suspectStreak++
                    level = if (suspectStreak >= 2) Level.DEGRADATION_REPEATED else Level.DEGRADATION_SUSPECTED
                }
            }
        }
        // Sans surface (formats A, B, ~), une perte n'est jamais « répétée » : vibreurs indétectables (P0-C).
        if (r.level != Level.INSUFFICIENT && !r.surfaceKnown && level == Level.DEGRADATION_REPEATED) level = Level.DEGRADATION_SUSPECTED
        // Comparaison suspendue : le niveau actif est « insuffisant » ; l'ancien niveau n'est qu'un historique (P0-A).
        last = if (r.level == Level.INSUFFICIENT) {
            r.copy(previous = level.takeIf { it >= Level.DEGRADATION_SUSPECTED })
        } else {
            r.copy(level = level)
        }
        evaluations++
        return last
    }

    private fun compare(usable: List<LapProfile>): Report {
        if (usable.size < MIN_LAPS) {
            return Report(Level.INSUFFICIENT, listOf("${usable.size} tour(s) propre(s) dans ce relais, il en faut $MIN_LAPS"))
        }
        val ref = usable.take(2)
        val recent = usable.takeLast(2)
        val refAgg = HashMap<Key, Agg>()
        ref.forEach { lp -> lp.windows.forEach { (k, a) -> refAgg.getOrPut(k) { Agg() }.merge(a) } }
        val surfaceKnown = usable.all { it.unknownSurface == 0 }
        val axle = drivenAxle(refAgg)
        fun driven(a: Agg) = when (axle) {
            Axle.FRONT -> a.frontSlip
            Axle.REAR -> a.rearSlip
            Axle.UNKNOWN -> maxOf(a.frontSlip, a.rearSlip)
        }
        val reasons = mutableListOf<String>()
        // Pneus plus froids qu'au début du relais (relance après neutralisation, pluie) : rien n'est conclu.
        val tRef = refAgg.values.filter { it.n > 0 }.let { v -> v.sumOf { it.temp } / v.sumOf { it.n }.coerceAtLeast(1) }
        val tRecent = recent.flatMap { it.windows.values }.let { v -> v.sumOf { it.temp } / v.sumOf { it.n }.coerceAtLeast(1) }
        if (tRef > 0 && tRecent > 0 && tRecent < tRef - COLD_DROP_C) {
            return Report(Level.INSUFFICIENT, listOf("pneus ${(tRef - tRecent).roundToInt()} °C plus froids qu'en début de relais : comparaison suspendue"),
                refLaps = ref.map { it.lap }, recentLaps = recent.map { it.lap }, surfaceKnown = surfaceKnown)
        }
        val excluded = recent.sumOf { it.excludedSurface }
        if (excluded > 0) reasons += "$excluded mesures sur vibreur ou hors asphalte écartées"
        val asm = recent.sumOf { it.excludedAsm }
        if (asm > 0) reasons += "$asm mesures sous correction ASM écartées"
        // Pneus nettement plus chauds : la perte peut venir de la surchauffe autant que de l'usure (dit, pas exclu).
        if (tRef > 0 && tRecent > tRef + HOT_RISE_C) reasons += "pneus ${(tRecent - tRef).roundToInt()} °C plus chauds qu'en début de relais : surchauffe possible"

        data class Cmp(val corner: Int, val loss: Boolean, val dSlip: Double, val dAcc: Double, val dTcs: Double, val torqueExplained: Boolean = false)
        val perLap = recent.map { lp ->
            lp.windows.mapNotNull { (k, a) ->
                val r = refAgg[k] ?: return@mapNotNull null
                if (a.n < MIN_N || r.n < MIN_N) return@mapNotNull null
                val dSlip = driven(a) - driven(r)
                val dAcc = a.meanAccel - r.meanAccel
                val dTcs = a.tcsShare - r.tcsShare
                // 0x154 (expérimental, non validé sur PS5) : **observation explicative seulement** (revue PR #37, P0-2).
                // Une accélération plus faible avec moins de couple délivré, sans TCS ni patinage en hausse, est notée
                // « moteur ou commande ». Le verdict ne dépend jamais de 0x154 : il est identique avec ou sans ce champ.
                val ta = a.meanTorque
                val tr = r.meanTorque
                val torqueDrop = ta != null && tr != null && tr > TORQUE_MIN && (ta - tr) / tr <= -TORQUE_DROP
                val explained = torqueDrop && dAcc <= -ACCEL_DROP && dTcs < TCS_RISE && dSlip < SLIP_RISE
                Cmp(k.corner, dAcc <= -ACCEL_DROP && (dSlip >= SLIP_RISE || dTcs >= TCS_RISE), dSlip, dAcc, dTcs, explained)
            }
        }
        // Virage retenu s'il montre la perte sur chacun des deux derniers tours (une sortie ratée ne suffit pas).
        fun flagged(c: List<Cmp>) = c.groupBy { it.corner }.filter { (_, l) -> l.count { it.loss } * 2 >= l.size && l.any { it.loss } }.keys
        val compared = perLap.map { l -> l.map { it.corner }.toSet() }.reduce { a, b -> a intersect b }
        if (compared.size < MIN_CORNERS) {
            return Report(Level.INSUFFICIENT, reasons + "${compared.size} virage(s) avec des sorties comparables (même commande, rapport, vitesse), il en faut $MIN_CORNERS",
                comparedCorners = compared.size, axle = axle, refLaps = ref.map { it.lap }, recentLaps = recent.map { it.lap }, surfaceKnown = surfaceKnown)
        }
        val flags = perLap.map { flagged(it) }.reduce { a, b -> a intersect b }.intersect(compared).sorted()
        val all = perLap.flatten().filter { it.corner in compared }
        val level = if (flags.size >= MIN_CORNERS && flags.size * 3 >= compared.size) Level.DEGRADATION_SUSPECTED else Level.STABLE
        val torqueExplained = all.count { it.torqueExplained }
        if (torqueExplained > 0) reasons += "$torqueExplained fenêtres où le couple délivré (0x154, expérimental) baisse sans TCS ni patinage : moteur ou commande probables (observation, sans effet sur le verdict)"
        if (level == Level.STABLE && torqueExplained == 0 && all.count { it.dAcc <= -ACCEL_DROP } * 2 > all.size) {
            reasons += "accélération plus faible sans patinage ni TCS en hausse : pas une perte de motricité (aspiration, trajectoire, moteur ?)"
        }
        return Report(
            level, reasons, compared.size, flags, axle,
            all.map { it.dSlip }.average(), all.map { it.dAcc }.average(), all.map { it.dTcs }.average(),
            ref.map { it.lap }, recent.map { it.lap }, surfaceKnown, torqueExplained = torqueExplained,
        )
    }

    /** Roues motrices : celles qui glissent nettement plus en réaccélération (début de relais). */
    private fun drivenAxle(ref: Map<Key, Agg>): Axle {
        val strong = ref.values
        val n = strong.sumOf { it.n }
        if (n < MIN_N) return Axle.UNKNOWN
        val front = strong.sumOf { it.front } / n
        val rear = strong.sumOf { it.rear } / n
        return when {
            rear - front >= AXLE_MARGIN -> Axle.REAR
            front - rear >= AXLE_MARGIN -> Axle.FRONT
            else -> Axle.UNKNOWN
        }
    }

    companion object {
        const val BRAKE_OFF = 5
        const val THROTTLE_MIN = 77 // 30 %
        const val MIN_SPEED_MPS = 8f
        const val MAX_ACCEL = 25f
        const val MIN_N = 6 // 0,1 s
        const val MIN_LAPS = 4
        const val MIN_CORNERS = 2
        const val MAX_LAPS = 30
        /** Seuils de départ, à recalibrer sur des traces PS5 réelles : ce ne sont pas des valeurs de GT7. */
        const val ACCEL_DROP = 0.3
        const val SLIP_RISE = 0.03
        const val TCS_RISE = 0.15
        const val AXLE_MARGIN = 0.01
        const val COLD_DROP_C = 10.0
        const val HOT_RISE_C = 15.0
        /** Baisse relative du couple délivré (0x154) qui explique une moindre accélération sans les pneus. */
        const val TORQUE_DROP = 0.15
        const val TORQUE_MIN = 0.05
    }
}
