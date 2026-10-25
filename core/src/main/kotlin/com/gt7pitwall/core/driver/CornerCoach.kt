package com.gt7pitwall.core.driver

import com.gt7pitwall.core.analysis.CornerPass
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ce qui fait perdre du temps dans un virage, quand la télémétrie permet de l'identifier. */
enum class CornerIssue(val label: String) {
    EARLY_BRAKE("freinage trop précoce"),
    ENTRY_TOO_FAST("entrée trop rapide, freinage trop tardif"),
    LOW_MIN_SPEED("vitesse minimale trop faible"),
    LATE_THROTTLE("réaccélération tardive"),
    WHEELSPIN("patinage en sortie"),
    LINE("trajectoire moins efficace"),
}

/**
 * Perte répétée dans un virage par rapport à la référence personnelle, et sa cause si elle est démontrée. [issue] null :
 * perte mesurée mais cause non identifiable (aucun conseil n'est alors donné).
 */
data class CornerFinding(
    val corner: Int,
    val lossMs: Int,
    val issue: CornerIssue?,
    /** Écart de la mesure en cause (mètres, km/h…), pour la phrase. */
    val amount: Float?,
    /** Écart de vitesse en sortie (km/h, négatif = plus lent), pour la phrase. */
    val exitDeltaKmh: Float,
    val passes: Int,
)

/**
 * Analyse pure des passages : référence personnelle = médiane des trois meilleurs passages du virage ; passages récents
 * = les [RECENT] derniers passages propres. Une perte n'est retenue que si elle est répétée, et une cause que si elle se
 * retrouve dans la majorité des passages perdants. Une perte qui touche tous les virages à la fois (pneus, carburant,
 * conditions) n'est pas un défaut de pilotage : aucun conseil.
 */
object CornerCoach {
    const val RECENT = 3
    const val MIN_REFERENCE = 3
    const val LOSING_MS = 60

    /** Perte minimale pour un conseil : entraînement (coach) ou course (stratège, seulement ce qui vaut la peine). */
    const val MIN_LOSS_PRACTICE_MS = 100
    const val MIN_LOSS_RACE_MS = 250

    private fun median(xs: List<Float>): Float? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    fun reference(passes: List<CornerPass>): List<CornerPass> =
        passes.filter { it.offTrack != true }.sortedBy { it.timeMs }.take(MIN_REFERENCE)

    /** Perte moyenne récente de chaque virage (ms), quand elle est mesurable. */
    fun losses(history: Map<Int, List<CornerPass>>, recent: Map<Int, List<CornerPass>>): Map<Int, Int> =
        recent.mapNotNull { (c, rec) ->
            val ref = reference(history[c].orEmpty() + rec)
            val last = rec.filter { it.offTrack != true }.takeLast(RECENT)
            if (ref.size < MIN_REFERENCE || last.size < RECENT) return@mapNotNull null
            val refMs = median(ref.map { it.timeMs.toFloat() })!!
            c to last.map { it.timeMs - refMs }.average().roundToInt()
        }.toMap()

    /**
     * Le virage où la perte est la plus nette, s'il y en a un. [history] : passages archivés (même voiture, circuit et type
     * de session) ; [recent] : passages de la session en cours.
     */
    fun worst(history: Map<Int, List<CornerPass>>, recent: Map<Int, List<CornerPass>>, minLossMs: Int): CornerFinding? {
        val losses = losses(history, recent)
        if (losses.isEmpty()) return null
        // Perte généralisée : tous les virages perdent autant, ce n'est pas un virage à travailler.
        val typical = losses.values.sorted()[losses.size / 2]
        val candidates = losses.filter { (_, l) -> l >= minLossMs && (losses.size < 3 || l >= 2 * typical.coerceAtLeast(0) + LOSING_MS) }
        val (corner, loss) = candidates.maxByOrNull { it.value } ?: return null
        val ref = reference(history[corner].orEmpty() + recent[corner].orEmpty())
        val refMs = median(ref.map { it.timeMs.toFloat() })!!
        val losing = recent[corner].orEmpty().filter { it.offTrack != true }.takeLast(RECENT).filter { it.timeMs - refMs >= LOSING_MS }
        if (losing.size < 2) return null
        val (issue, amount) = cause(ref, losing)
        val exit = (median(losing.map { it.exitKmh }) ?: 0f) - (median(ref.map { it.exitKmh }) ?: 0f)
        return CornerFinding(corner, loss, issue, amount, exit, losing.size)
    }

    /** Une cause n'est retenue que si elle se retrouve dans la majorité des passages perdants. */
    fun cause(ref: List<CornerPass>, losing: List<CornerPass>): Pair<CornerIssue?, Float?> {
        val need = losing.size / 2 + 1
        fun med(sel: (CornerPass) -> Float?, list: List<CornerPass>) = median(list.mapNotNull(sel))
        val refBrake = med({ it.brakeStartM }, ref)
        val refMin = med({ it.minKmh }, ref)!!
        val refEntry = med({ it.entryKmh }, ref)!!
        val refThrottle = med({ it.throttleOnM }, ref)
        val refExit = med({ it.exitKmh }, ref)!!
        val refLat = med({ it.apexLateralM }, ref)!!
        val refSpin = ref.count { it.wheelspin == true }

        fun count(cond: (CornerPass) -> Boolean) = losing.count(cond)
        // Entrée trop rapide : freinage plus tardif (ou entrée plus rapide) qui fait chuter la vitesse minimale ou la sortie.
        if (refBrake != null) {
            val late = count { p ->
                val b = p.brakeStartM ?: return@count false
                (b - refBrake >= 8f || p.entryKmh - refEntry >= 5f) && (p.minKmh - refMin <= -3f || p.exitKmh - refExit <= -3f || p.lockup == true)
            }
            if (late >= need) return CornerIssue.ENTRY_TOO_FAST to (med({ it.brakeStartM }, losing)!! - refBrake)
            // Freinage plus précoce à vitesse minimale égale : il ne sert à rien.
            val early = count { p -> p.brakeStartM?.let { refBrake - it >= 8f } == true && abs(p.minKmh - refMin) < 2.5f }
            if (early >= need) return CornerIssue.EARLY_BRAKE to (refBrake - med({ it.brakeStartM }, losing)!!)
        }
        // Vitesse minimale plus basse, sans freinage plus tardif (souvent avec un freinage plus précoce).
        val slow = count { p -> p.minKmh - refMin <= -4f && (refBrake == null || p.brakeStartM == null || p.brakeStartM - refBrake < 8f) }
        if (slow >= need) return CornerIssue.LOW_MIN_SPEED to (refMin - med({ it.minKmh }, losing)!!)
        if (refThrottle != null) {
            val late = count { p -> p.throttleOnM?.let { it - refThrottle >= 10f } == true }
            if (late >= need) return CornerIssue.LATE_THROTTLE to (med({ it.throttleOnM }, losing)!! - refThrottle)
        }
        val spin = count { p -> p.wheelspin == true && p.exitKmh - refExit <= -3f }
        if (spin >= need && refSpin <= ref.size / 3) return CornerIssue.WHEELSPIN to (refExit - med({ it.exitKmh }, losing)!!)
        val line = count { p -> abs(p.apexLateralM - refLat) >= 1.5f && p.exitKmh - refExit <= -3f }
        if (line >= need) return CornerIssue.LINE to abs(med({ it.apexLateralM }, losing)!! - refLat)
        return null to null
    }

    /** Dixièmes de seconde, à la française : « 2 dixièmes », « un dixième ». */
    fun tenths(ms: Int): String {
        val t = (ms / 100.0).roundToInt().coerceAtLeast(1)
        return if (t == 1) "un dixième" else "$t dixièmes"
    }

    /** Une seule consigne, concrète. [race] : version courte pour la course. */
    fun advice(f: CornerFinding, race: Boolean): String? {
        val issue = f.issue ?: return null
        val a = (f.amount ?: 0f).roundToInt()
        val v = "Virage ${f.corner}"
        val loss = " Environ ${tenths(f.lossMs)} par passage."
        return if (race) {
            when (issue) {
                CornerIssue.EARLY_BRAKE -> "$v : freine un peu plus tard."
                CornerIssue.ENTRY_TOO_FAST -> "$v : tu arrives trop vite, freine un peu plus tôt."
                CornerIssue.LOW_MIN_SPEED -> "$v : porte plus de vitesse au milieu."
                CornerIssue.LATE_THROTTLE -> "$v : remets les gaz plus tôt."
                CornerIssue.WHEELSPIN -> "$v : dose l'accélérateur en sortie, ça patine."
                CornerIssue.LINE -> "$v : reprends ta trajectoire habituelle."
            } + loss
        } else {
            when (issue) {
                CornerIssue.EARLY_BRAKE -> "$v : tu freines environ $a mètres plus tôt que sur tes meilleurs passages. Retarde un peu le freinage."
                CornerIssue.ENTRY_TOO_FAST -> "$v : tu freines environ $a mètres plus tard que sur tes meilleurs passages et tu perds de la vitesse au milieu. Freine un peu plus tôt."
                CornerIssue.LOW_MIN_SPEED -> "$v : ta vitesse minimale est plus basse de $a km/h que sur tes meilleurs passages. Garde plus de vitesse au milieu du virage."
                CornerIssue.LATE_THROTTLE -> "$v : tu remets les gaz environ $a mètres plus tard que sur tes meilleurs passages. Travaille la sortie."
                CornerIssue.WHEELSPIN -> "$v : les roues arrière patinent en sortie et tu sors $a km/h moins vite. Dose l'accélérateur."
                CornerIssue.LINE -> "$v : près de la corde estimée, tu passes à $a mètres de ta trajectoire des meilleurs tours et tu sors moins vite. Reprends cette trajectoire."
            } + loss
        }
    }
}
