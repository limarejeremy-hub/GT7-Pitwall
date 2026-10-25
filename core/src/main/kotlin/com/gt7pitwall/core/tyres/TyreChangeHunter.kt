package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.session.PitAssessment
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.abs

/**
 * État d'un changement de pneus. GT7 ne transmet pas le changement : seul le pilote le confirme. Le diagnostic thermique
 * ne fait que le suspecter, et ne remet jamais l'âge des pneus à zéro de lui-même.
 */
enum class TyreChangeVerdict(val label: String) {
    CONFIRMED("changement confirmé par le pilote"),
    SUSPECTED("changement suspecté, à confirmer"),
    UNDETERMINED("changement non déterminé"),
}

enum class ThermalPhase(val code: Char) { BEFORE('A'), STOP('S'), EXIT('E') }

/** Un échantillon par seconde : températures des quatre pneus (AVG, AVD, ARG, ARD, en °C), vitesse et carburant. */
data class ThermalSample(val second: Int, val phase: ThermalPhase, val temps: List<Float>, val speedKmh: Float, val fuel: Float) {
    /** Températures plausibles (capteurs non initialisés, chargement et valeurs aberrantes écartés). */
    val valid: Boolean get() = temps.size == 4 && temps.all { it.isFinite() && it in MIN_C..MAX_C }

    companion object {
        const val MIN_C = -20f
        const val MAX_C = 250f
    }
}

/**
 * Saut thermique simultané des quatre pneus autour de la seconde [atSecond] : écart (°C, avant → après) de chaque pneu
 * entre la moyenne des échantillons qui précèdent et celle qui suivent. [persisted] : le nouveau niveau tient sur au moins
 * deux échantillons (une valeur aberrante isolée ne compte pas).
 */
data class ThermalStep(val atSecond: Int, val deltas: List<Float>, val persisted: Boolean) {
    val minAbs: Float get() = deltas.minOf { abs(it) }
    val sameSign: Boolean get() = deltas.all { it > 0f } || deltas.all { it < 0f }
}

/** Analyse d'un passage aux stands : séries mesurées, saut thermique trouvé, verdict et sa raison. */
data class ThermalCapture(
    val lap: Int,
    val assessment: PitAssessment,
    val samples: List<ThermalSample>,
    val step: ThermalStep?,
    val verdict: TyreChangeVerdict,
    val reason: String,
    val thresholdC: Float,
)

/**
 * Tyre Change Hunter : diagnostic expérimental d'un changement de pneus pendant un passage aux stands, à partir des
 * températures des quatre pneus (0x60, 0x64, 0x68, 0x6C). Il garde une fenêtre avant l'arrêt, enregistre l'arrêt et la
 * sortie, et cherche une variation brusque, simultanée et durable des quatre pneus.
 *
 * Ce n'est qu'un indice : un ravitaillement ne prouve pas un changement de pneus, une variation de température non plus.
 * Le seuil ([thresholdC], 10 °C par défaut) est expérimental, à valider sur des arrêts réels étiquetés par le pilote
 * (voir [TyreHunterCalibration]). Seuls les passages aux stands sont analysés : jamais un arrêt en piste, jamais une
 * variation hors stands, jamais un passage après l'arrivée ou pendant une transition de session.
 */
class TyreChangeHunter(val thresholdC: Float = DEFAULT_THRESHOLD_C) {
    private val before = ArrayDeque<ThermalSample>()
    private var capture: MutableList<ThermalSample>? = null
    private var concluded: ThermalCapture? = null
    private var exitLeft = 0
    private var lastSampleId: Int? = null
    private var second = 0

    fun reset() {
        before.clear()
        capture = null
        concluded = null
        exitLeft = 0
        lastSampleId = null
        second = 0
    }

    /**
     * À chaque paquet. [inVisit] : passage aux stands en cours (détecteur de stands) ; [active] : course en cours (tour 1
     * à l'arrivée, ni pause, ni chargement). Renvoie l'analyse complète (sortie comprise) d'un passage, une seule fois,
     * pour l'export.
     */
    fun onPacket(p: TelemetryPacket, inVisit: Boolean, active: Boolean): ThermalCapture? {
        if (!active) {
            // Pause, chargement, arrivée, menu : rien n'est mesuré, une capture en cours est abandonnée.
            if (capture != null && !inVisit) abandon()
            return null
        }
        val id = p.packetId
        val last = lastSampleId
        if (last != null && id - last in 0 until SAMPLE_PACKETS) return null
        lastSampleId = id
        second++
        val t = p.tyreTemps
        fun sample(phase: ThermalPhase) = ThermalSample(second, phase, listOf(t.fl, t.fr, t.rl, t.rr), p.speedKmh, p.fuelLevel)

        val c = capture
        when {
            // Nouveau passage avant la fin de la fenêtre de sortie du précédent : celui-ci est exporté tel quel.
            inVisit && c != null && concluded != null -> {
                val done = concluded!!.copy(samples = c.toList())
                capture = mutableListOf(sample(ThermalPhase.STOP))
                concluded = null
                return done
            }
            inVisit && c == null -> capture = (before.map { it } + sample(ThermalPhase.STOP)).toMutableList()
            inVisit -> c!! += sample(ThermalPhase.STOP)
            c != null && concluded != null -> {
                c += sample(ThermalPhase.EXIT)
                if (--exitLeft <= 0) {
                    val done = concluded!!.copy(samples = c.toList())
                    capture = null
                    concluded = null
                    return done
                }
            }
            else -> {
                before.addLast(sample(ThermalPhase.BEFORE))
                while (before.size > BEFORE_SAMPLES) before.removeFirst()
            }
        }
        return null
    }

    /**
     * Fin du passage (le détecteur vient de le clore) : verdict sur ce qui a été mesuré avant et pendant l'arrêt. La
     * capture continue quelques secondes pour l'export de la sortie.
     */
    fun conclude(lap: Int, assessment: PitAssessment): ThermalCapture {
        val samples = capture?.toList().orEmpty()
        val result = analyze(lap, assessment, samples, thresholdC)
        if (capture != null) {
            concluded = result
            exitLeft = EXIT_SAMPLES
        }
        return result
    }

    private fun abandon() {
        capture = null
        concluded = null
        exitLeft = 0
        before.clear()
    }

    companion object {
        const val DEFAULT_THRESHOLD_C = 10f
        const val SAMPLE_PACKETS = 60 // une mesure par seconde
        const val BEFORE_SAMPLES = 20
        const val EXIT_SAMPLES = 15
        const val WINDOW = 3

        /** Analyse pure (testable) d'une série : la variation doit être simultanée, de même sens et durable. */
        fun analyze(lap: Int, assessment: PitAssessment, samples: List<ThermalSample>, thresholdC: Float = DEFAULT_THRESHOLD_C): ThermalCapture {
            fun result(step: ThermalStep?, verdict: TyreChangeVerdict, reason: String) =
                ThermalCapture(lap, assessment, samples, step, verdict, reason, thresholdC)
            if (assessment == PitAssessment.TRACK_STOP) {
                return result(null, TyreChangeVerdict.UNDETERMINED, "arrêt en piste, pas un passage aux stands : non analysé")
            }
            val valid = samples.filter { it.valid }
            val stop = valid.filter { it.phase == ThermalPhase.STOP }
            if (stop.size < WINDOW || valid.size < 2 * WINDOW) {
                return result(null, TyreChangeVerdict.UNDETERMINED, "mesures insuffisantes pendant l'arrêt")
            }
            val step = bestStep(valid)
            uniformReset(valid)?.let { (reset, level) ->
                return result(
                    reset, TyreChangeVerdict.SUSPECTED,
                    "les quatre pneus repartent d'un coup à la même température (${level.toInt()} °C) pendant l'arrêt : signature de pneus " +
                        "neufs relevée sur un arrêt réel, à confirmer par le pilote",
                )
            }
            return when {
                step == null -> result(null, TyreChangeVerdict.UNDETERMINED, "aucune variation simultanée des quatre pneus")
                step.minAbs < thresholdC || !step.sameSign ->
                    result(step, TyreChangeVerdict.UNDETERMINED, "variation simultanée inférieure à ${thresholdC.toInt()} °C sur au moins un pneu")
                !step.persisted -> result(step, TyreChangeVerdict.UNDETERMINED, "variation brève, non maintenue : valeur aberrante probable")
                else -> result(step, TyreChangeVerdict.SUSPECTED, "variation brusque, simultanée et durable des quatre pneus pendant l'arrêt")
            }
        }

        /**
         * Remise à niveau uniforme : d'un échantillon au suivant, voiture à l'arrêt, les quatre pneus passent à une même
         * température (écart entre eux ≤ [UNIFORM_SPREAD_C]) alors qu'ils étaient différents avant (≥ [UNIFORM_BEFORE_SPREAD_C]),
         * et y restent. Course réelle du 25/10 (Porsche 963, Le Mans, pneus changés confirmés par le pilote) : 70,0 / 69,7 /
         * 74,9 / 76,6 °C → 60,0 / 60,0 / 60,0 / 60,0 °C en une seconde, avant le plein. Des pneus conservés refroidissent
         * peu à peu, chacun à sa température : ils ne devraient pas produire ce saut. Un seul exemple réel : c'est un indice
         * fort, jamais une confirmation (le pilote répond toujours).
         */
        private fun uniformReset(valid: List<ThermalSample>): Pair<ThermalStep, Float>? {
            for (j in 1 until valid.size - UNIFORM_HOLD) {
                val now = valid[j]
                if (now.phase != ThermalPhase.STOP || now.speedKmh > STILL_KMH) continue
                val prev = valid[j - 1]
                val spreadBefore = prev.temps.max() - prev.temps.min()
                if (spreadBefore < UNIFORM_BEFORE_SPREAD_C) continue
                val after = valid.subList(j, j + UNIFORM_HOLD)
                if (after.any { it.temps.max() - it.temps.min() > UNIFORM_SPREAD_C }) continue
                val deltas = (0 until 4).map { w -> now.temps[w] - prev.temps[w] }
                val step = ThermalStep(now.second, deltas, persisted = true)
                if (!step.sameSign || step.minAbs < UNIFORM_MIN_STEP_C) continue
                return step to now.temps.average().toFloat()
            }
            return null
        }

        /** Les quatre pneus à la même température, à 0,3 °C près. */
        const val UNIFORM_SPREAD_C = 0.3f

        /** Avant le saut, des pneus roulés diffèrent nettement entre eux. */
        const val UNIFORM_BEFORE_SPREAD_C = 1.5f

        /** Saut minimal de chaque pneu (une seconde). */
        const val UNIFORM_MIN_STEP_C = 3f

        /** Le nouveau niveau, commun aux quatre pneus, tient au moins ce nombre d'échantillons. */
        const val UNIFORM_HOLD = 3

        /** Voiture immobilisée au stand. */
        const val STILL_KMH = 3f

        /**
         * Plus grand saut simultané : pour chaque instant de l'arrêt, moyenne des [WINDOW] échantillons précédents contre
         * moyenne des [WINDOW] suivants (une seconde d'écart entre les deux, le temps du changement).
         */
        private fun bestStep(valid: List<ThermalSample>): ThermalStep? {
            var best: ThermalStep? = null
            for (j in WINDOW until valid.size - 1) {
                if (valid[j].phase != ThermalPhase.STOP) continue
                val pre = valid.subList(j - WINDOW, j)
                val post = valid.subList(j + 1, minOf(valid.size, j + 1 + WINDOW))
                if (post.size < 2) continue
                val deltas = (0 until 4).map { w -> post.map { it.temps[w] }.average().toFloat() - pre.map { it.temps[w] }.average().toFloat() }
                val step = ThermalStep(valid[j].second, deltas, persisted = false)
                if (best == null || score(step) > score(best)) {
                    // Durable : chaque échantillon d'après reste au-delà de 70 % du saut, sur chaque pneu.
                    val persisted = post.all { s ->
                        (0 until 4).all { w ->
                            val d = s.temps[w] - pre.map { it.temps[w] }.average().toFloat()
                            d * deltas[w] > 0 && abs(d) >= 0.7f * abs(deltas[w])
                        }
                    }
                    best = step.copy(persisted = persisted)
                }
            }
            return best
        }

        /** Phases par plages consécutives : « A20 S35 E15 » (20 s avant, 35 s de passage, 15 s de sortie). */
        fun phaseRuns(samples: List<ThermalSample>): String = buildList {
            var i = 0
            while (i < samples.size) {
                val ph = samples[i].phase
                var j = i
                while (j < samples.size && samples[j].phase == ph) j++
                add("${ph.code}${j - i}")
                i = j
            }
        }.joinToString(" ")

        private fun score(s: ThermalStep): Float = if (s.sameSign) s.minAbs else 0f

        /** Séries compactes pour l'export : une valeur par seconde, une décimale (quelques centaines d'octets par série). */
        fun exportDetails(c: ThermalCapture, label: Boolean? = null): Map<String, String> {
            fun f(v: Float) = if (v.isFinite()) String.format(java.util.Locale.ROOT, "%.1f", v) else "nan"
            val s = c.samples
            return linkedMapOf(
                "hunter_verdict" to c.verdict.name,
                "hunter_raison" to c.reason,
                "hunter_seuil_c" to f(c.thresholdC),
                "hunter_type_passage" to c.assessment.name,
                "hunter_saut_c" to (c.step?.deltas?.joinToString(",") { f(it) } ?: "aucun"),
                "hunter_saut_seconde" to (c.step?.atSecond?.toString() ?: "-"),
                "hunter_saut_durable" to (c.step?.persisted?.toString() ?: "-"),
                // Par plages (« A20 S35 E15 ») : une longue suite de lettres serait masquée comme un jeton par le journal.
                "phases" to phaseRuns(s),
                "avg" to s.joinToString(",") { f(it.temps[0]) },
                "avd" to s.joinToString(",") { f(it.temps[1]) },
                "arg" to s.joinToString(",") { f(it.temps[2]) },
                "ard" to s.joinToString(",") { f(it.temps[3]) },
                "kmh" to s.joinToString(",") { it.speedKmh.toInt().toString() },
                "carburant" to s.joinToString(",") { f(it.fuel) },
            ).also { m -> label?.let { m["hunter_etiquette"] = if (it) "pneus_changes" else "pneus_conserves" } }
        }
    }
}

/**
 * Fiabilité du diagnostic, mesurée sur des arrêts réels étiquetés par le pilote (« pneus neufs » / « mêmes pneus »).
 * Une confirmation automatique ne pourra être envisagée qu'avec assez d'exemples positifs ET négatifs, sans aucune erreur.
 * Elle n'est pas activée dans cette version : le pilote confirme toujours.
 */
object TyreHunterCalibration {
    /**
     * [stepC] : saut de température mesuré à ce passage, par pneu (AVG, AVD, ARG, ARD) ; vide si aucun saut net.
     * C'est la signature qui doit séparer pneus neufs et pneus conservés sur les essais réels.
     */
    data class Example(val suspected: Boolean, val changed: Boolean, val stepC: List<Float> = emptyList()) {
        /** Le plus petit saut des quatre pneus (en valeur absolue) : un vrai changement les touche tous. 0 sans saut. */
        val weakestStepC: Float get() = if (stepC.size == 4) stepC.minOf { kotlin.math.abs(it) } else 0f
    }

    data class Status(
        val positives: Int,
        val negatives: Int,
        val falsePositives: Int,
        val falseNegatives: Int,
        /** Plus petit « saut le plus faible » parmi les pneus changés (null sans exemple). */
        val changedMinStepC: Float? = null,
        /** Plus grand « saut le plus faible » parmi les pneus conservés (null sans exemple). */
        val keptMaxStepC: Float? = null,
    ) {
        /** Les sauts mesurés séparent nettement les deux cas sur ces essais (marge d'au moins [MIN_MARGIN_C]). */
        val separated: Boolean
            get() = changedMinStepC != null && keptMaxStepC != null && changedMinStepC - keptMaxStepC >= MIN_MARGIN_C

        val ready: Boolean
            get() = positives >= MIN_EACH && negatives >= MIN_EACH && falsePositives == 0 && falseNegatives == 0 && separated

        val text: String
            get() = "$positives arrêt(s) avec pneus changés et $negatives sans, étiquetés par le pilote ; " +
                "$falsePositives faux positif(s), $falseNegatives faux négatif(s). " + signature() +
                if (ready) "Fiabilité démontrée sur ces essais (confirmation automatique toujours désactivée)." else
                    "Pas encore assez d'essais : la confirmation du pilote reste nécessaire."

        private fun signature(): String {
            fun f(v: Float) = String.format(java.util.Locale.ROOT, "%.1f", v)
            if (changedMinStepC == null || keptMaxStepC == null) return ""
            return "Saut thermique le plus faible des quatre pneus : au moins ${f(changedMinStepC)} °C pneus changés, " +
                "au plus ${f(keptMaxStepC)} °C pneus conservés (" + (if (separated) "séparation nette" else "pas de séparation nette") + "). "
        }
    }

    const val MIN_EACH = 3

    /** Écart minimal entre les deux familles de sauts pour parler de signature fiable. */
    const val MIN_MARGIN_C = 5f

    fun evaluate(examples: List<Example>): Status = Status(
        positives = examples.count { it.changed },
        negatives = examples.count { !it.changed },
        falsePositives = examples.count { it.suspected && !it.changed },
        falseNegatives = examples.count { !it.suspected && it.changed },
        changedMinStepC = examples.filter { it.changed }.minOfOrNull { it.weakestStepC },
        keptMaxStepC = examples.filter { !it.changed }.maxOfOrNull { it.weakestStepC },
    )

    /** Sauts relus dans le journal (« 12.5,11.0,9.8,10.2 », ou « aucun »). */
    fun parseStep(raw: String?): List<Float> =
        raw?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 4 }.orEmpty()
}
