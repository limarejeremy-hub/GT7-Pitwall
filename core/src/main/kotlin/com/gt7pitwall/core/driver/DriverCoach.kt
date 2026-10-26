package com.gt7pitwall.core.driver

import com.gt7pitwall.core.analysis.CornerMap
import com.gt7pitwall.core.analysis.CornerMeasure
import com.gt7pitwall.core.analysis.CornerPass
import com.gt7pitwall.core.analysis.LapRecorder
import com.gt7pitwall.core.analysis.LapSample
import com.gt7pitwall.core.analysis.TrackProjection
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Coach de pilotage par virage, alimenté par Driver DNA.
 *
 * - **Entraînement** : après quelques passages comparables, une seule consigne concrète sur la principale perte de temps,
 *   puis vérification sur les passages suivants avant toute nouvelle consigne.
 * - **Course** : seulement une perte nette et répétée dont la cause est claire, en phrase courte, rarement (jamais au
 *   départ, au dernier tour ou juste après un changement de position).
 *
 * Aucun conseil sans cause identifiée par la télémétrie, ni sur un virage dont la trajectoire est incomplète.
 */
class DriverCoach(
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * État vérifiable de l'apprentissage (course du 26/10 : presque aucun conseil, sans raison visible). Ce que le profil a
     * réellement mesuré, enregistré et rendu ; pourquoi des tours ont été écartés ou aucun conseil donné.
     */
    data class Diagnostics(
        val profileKey: String?,
        val lapsSeen: Int,
        val lapsUsable: Int,
        /** Tours écartés de l'analyse, par raison. */
        val rejected: Map<String, Int>,
        val cornersRecognized: Int,
        val passesThisSession: Int,
        val passesStored: Int,
        val sessionsStored: Int,
        val adviceGiven: Int,
        val adviceEvaluated: Int,
        val adviceImproved: Int,
        /** Pourquoi aucun conseil n'a été donné au dernier tour analysé (null : un conseil a été donné ou rien d'analysé). */
        val lastNoAdviceReason: String?,
        val lastSaveMs: Long?,
        val lastError: String?,
    ) {
        /** Lignes lisibles pour l'écran et pour « qu'est-ce que tu as appris ? ». */
        fun lines(): List<String> = buildList {
            add("Tours analysés : $lapsUsable exploitables sur $lapsSeen.")
            if (rejected.isNotEmpty()) add("Écartés : " + rejected.entries.joinToString(", ") { "${it.value} ${it.key}" } + ".")
            add("Virages reconnus : $cornersRecognized ; passages mesurés : $passesThisSession cette session, $passesStored enregistrés.")
            add("Sessions enregistrées pour cette voiture et ce circuit : $sessionsStored.")
            add("Conseils : $adviceGiven donnés, $adviceEvaluated évalués, $adviceImproved suivis d'une amélioration mesurée.")
            lastNoAdviceReason?.let { add("Dernier tour sans conseil : $it.") }
            add(lastSaveMs?.let { "Profil enregistré le ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(it))}." } ?: "Profil pas encore enregistré (il l'est à la fin de la session).")
            lastError?.let { add("Erreur interne : $it.") }
        }
    }

    private val rejectedCount = linkedMapOf<String, Int>()
    private var lapsSeen = 0
    private var lastNoAdvice: String? = null
    private var lastSaveMs: Long? = null
    private var lastError: String? = null

    private fun reject(reason: String): List<EngineerMessage> {
        rejectedCount[reason] = (rejectedCount[reason] ?: 0) + 1
        return emptyList()
    }

    private fun noAdvice(reason: String): List<EngineerMessage> {
        lastNoAdvice = reason
        return emptyList()
    }

    fun diagnostics(): Diagnostics {
        val p = profile
        val checked = adviceLog.filter { it.lossAfterMs != null }
        return Diagnostics(
            profileKey = p?.key,
            lapsSeen = lapsSeen,
            lapsUsable = cleanLaps.size,
            rejected = rejectedCount.toMap(),
            cornersRecognized = p?.map?.corners?.size ?: 0,
            passesThisSession = sessionPasses.size,
            passesStored = p?.passes?.values?.sumOf { it.size } ?: 0,
            sessionsStored = p?.sessionCount ?: 0,
            adviceGiven = adviceLog.size,
            adviceEvaluated = checked.size,
            adviceImproved = checked.count { it.lossAfterMs!! < it.lossBeforeMs - DriverProfile.IMPROVED_MS },
            lastNoAdviceReason = lastNoAdvice,
            lastSaveMs = lastSaveMs,
            lastError = lastError,
        )
    }

    /** Constat mesuré du virage où la perte est la plus nette (sans attendre une consigne), vide sinon. */
    fun findings(): List<String> {
        val prof = profile ?: return emptyList()
        val f = CornerCoach.worst(history(prof), recent(), CornerCoach.MIN_LOSS_PRACTICE_MS) ?: return emptyList()
        return listOf(
            CornerCoach.advice(f, race = false)
                ?: "Virage ${f.corner} : tu y perds environ ${CornerCoach.tenths(f.lossMs)} par passage sur tes meilleurs passages, sans cause mesurable.",
        )
    }
    /** Contexte d'un tour bouclé, fourni par le contrôleur de course. */
    data class LapContext(
        val profileKey: String,
        val race: Boolean,
        val totalLaps: Int,
        /** Changement de position récent (bataille probable) : pas de coaching. */
        val positionChangedRecently: Boolean,
        /** Le tour a croisé la voie des stands, un arrêt ou un ravitaillement : hors comparaison. */
        val pitLap: Boolean,
        val wheelBaseM: Float? = null,
    )

    private val book: DriverBook by lazy {
        DriverBook.parse(
            runCatching(load).getOrElse { e ->
                lastError = "lecture du profil impossible (${e.javaClass.simpleName})"
                null
            },
        )
    }
    private val recorder = LapRecorder()
    private val finished = HashMap<Int, List<LapSample>>()
    private var lastLap = -1
    private var pitThisLap = false

    private var sessionId: String? = null
    var profile: DriverProfile? = null
        private set
    private val sessionPasses = mutableListOf<CornerPass>()
    private val cleanLaps = mutableListOf<LapRecord>()
    private val adviceLog = mutableListOf<AdviceOutcome>()
    private var pending: Pending? = null
    private var lastAdviceLap = -100
    private var raceAdvice = 0
    private var incompatibleNoted = false

    private class Pending(val corner: Int, val issue: CornerIssue, val lossBefore: Int, val passesBefore: Int)

    /** Consignes données dans cette session (journal, tests). */
    val advices: List<AdviceOutcome> get() = adviceLog.toList()
    val passes: List<CornerPass> get() = sessionPasses.toList()

    fun start(id: String) {
        sessionId = id
        profile = null
        sessionPasses.clear()
        cleanLaps.clear()
        adviceLog.clear()
        finished.clear()
        pending = null
        waiting = null
        lastAdviceLap = -100
        raceAdvice = 0
        incompatibleNoted = false
        lastLap = -1
        recorder.invalidate()
        rejectedCount.clear()
        lapsSeen = 0
        lastNoAdvice = null
        stalled.clear()
    }

    /** Virages dont la consigne n'a pas produit d'amélioration mesurée : plus conseillés dans cette session. */
    private val stalled = mutableSetOf<Int>()

    private var waiting: Pair<LapRecord, LapContext>? = null

    /**
     * Chaque paquet : enregistre le tour en cours, le termine au passage de la ligne. Un tour bouclé est analysé une fois
     * la sortie du dernier virage parcourue (environ 200 m après la ligne) : renvoie alors la consigne éventuelle.
     */
    fun onPacket(p: TelemetryPacket, inPit: Boolean): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        if (lastLap >= 0 && p.currentLap > lastLap) {
            // Nouveau passage de ligne avant la fin de l'attente : le tour précédent est analysé avec ce qui a été vu.
            waiting?.let { (l, c) -> waiting = null; out += analyze(l, c, recorder.current()) }
            // Tour bouclé : gardé pour l'analyse quand son chrono officiel arrivera (seulement s'il était propre et suivi).
            if (!pitThisLap && p.currentLap == lastLap + 1) recorder.finish()?.let { finished[lastLap] = it }
            while (finished.size > 3) finished.remove(finished.keys.min())
            recorder.startLap(p)
            pitThisLap = inPit
        } else if (p.currentLap < lastLap) {
            recorder.invalidate()
            finished.clear()
        } else {
            recorder.add(p)
            if (inPit) pitThisLap = true
        }
        lastLap = p.currentLap
        waiting?.let { (l, c) ->
            if (!recorder.valid || recorder.distanceM >= CONTINUATION_M) {
                waiting = null
                out += analyze(l, c, recorder.current())
            }
        }
        return out
    }

    /** Tour bouclé avec son chrono : analysé dès que la sortie du dernier virage est parcourue (voir [onPacket]). */
    fun onLap(lap: LapRecord, ctx: LapContext): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        waiting?.let { (l, c) -> out += analyze(l, c, recorder.current()) }
        waiting = lap to ctx
        return out
    }

    private fun analyze(lap: LapRecord, ctx: LapContext, continuation: List<LapSample>): List<EngineerMessage> {
        val samples = finished.remove(lap.lapNumber)
        lapsSeen++
        val time = lap.timeMs ?: return reject("sans chrono")
        if (lap.lapNumber < 2) return reject("tour de départ")
        if (lap.refuelled || lap.pitLane || ctx.pitLap) return reject("arrêt ou stands")
        if (samples == null) return reject("non suivis en entier")
        val best = (cleanLaps.mapNotNull { it.timeMs } + time).min()
        if (time > best * 1.07) return reject("perturbés (plus de 7 % du meilleur)") // trafic, incident : hors comparaison
        val prof = resolve(ctx.profileKey, samples) ?: return reject("carte des virages impossible")
        cleanLaps += lap
        val map = prof.map ?: return emptyList()
        val passes = CornerMeasure.passes(map, lap.lapNumber, samples, ctx.wheelBaseM, continuation)
        sessionPasses += passes
        return advise(lap.lapNumber, ctx, prof)
    }

    private fun incompatible(): List<EngineerMessage> = emptyList()

    /**
     * Profil de cette voiture × circuit × type de session, avec une carte des virages qui correspond au tracé roulé.
     * Une carte qui ne se superpose pas (autre configuration du circuit) donne un autre profil : rien n'est mélangé.
     */
    private fun resolve(base: String, samples: List<LapSample>): DriverProfile? {
        profile?.let { return it }
        for (v in book.variants(base)) {
            val m = v.map ?: continue
            val off = TrackProjection.medianOffset(m, samples)
            if (off != null && off <= MAX_MAP_OFFSET_M) return v.also { profile = it }
        }
        val map = CornerMap.build(samples) ?: return null
        val n = book.variants(base).size
        val p = DriverProfile(if (n == 0) base else "$base#${n + 1}", map)
        profile = p
        return p
    }

    private fun history(prof: DriverProfile): Map<Int, List<CornerPass>> = prof.passes
    private fun recent(): Map<Int, List<CornerPass>> = sessionPasses.groupBy { it.corner }

    private fun advise(lap: Int, ctx: LapContext, prof: DriverProfile): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        val rec = recent()
        // Vérification de la consigne précédente sur les passages suivants.
        pending?.let { pd ->
            val after = rec[pd.corner].orEmpty().filter { it.offTrack != true }
            if (after.size - pd.passesBefore >= CornerCoach.RECENT) {
                pending = null
                val loss = CornerCoach.losses(history(prof), mapOf(pd.corner to after))[pd.corner]
                adviceLog[adviceLog.lastIndex] = adviceLog.last().copy(lossAfterMs = loss)
                val gained = loss?.let { pd.lossBefore - it }
                if (gained == null || gained < DriverProfile.IMPROVED_MS) stalled += pd.corner
                val text = when {
                    gained != null && gained >= DriverProfile.IMPROVED_MS ->
                        if (ctx.race) "Virage ${pd.corner}, c'est mieux." else "Virage ${pd.corner} : c'est mieux, tu as repris environ ${CornerCoach.tenths(gained)}."
                    ctx.race -> null
                    // Pas d'amélioration : la consigne n'est pas répétée ; le virage n'est plus proposé dans cette session.
                    gained == null -> "Virage ${pd.corner} : pas assez de passages comparables pour mesurer l'effet de la consigne."
                    else -> "Virage ${pd.corner} : pas d'amélioration mesurée, je ne te le redis pas ; on regardera ce virage en fin de session."
                }
                text?.let { out += msg(it, ctx, lap) }
                return out
            }
            return noAdvice("consigne précédente en cours de vérification") // une seule consigne à la fois
        }
        val allowed = if (ctx.race) {
            raceAdvice < MAX_RACE_ADVICE && lap >= 3 && (ctx.totalLaps <= 0 || lap < ctx.totalLaps - 1) &&
                lap - lastAdviceLap >= RACE_ADVICE_GAP_LAPS && !ctx.positionChangedRecently
        } else {
            lap - lastAdviceLap >= 2
        }
        if (!allowed) {
            return noAdvice(
                when {
                    ctx.race && raceAdvice >= MAX_RACE_ADVICE -> "trois conseils déjà donnés en course"
                    ctx.race && ctx.positionChangedRecently -> "bataille en cours (place changée récemment)"
                    else -> "conseil récent, on laisse le temps de l'appliquer"
                },
            )
        }
        val usable = rec.filterKeys { it !in stalled }
        val f = CornerCoach.worst(history(prof), usable, if (ctx.race) CornerCoach.MIN_LOSS_RACE_MS else CornerCoach.MIN_LOSS_PRACTICE_MS)
            ?: return noAdvice(
                if (usable.values.none { it.size >= CornerCoach.RECENT }) "pas encore trois passages comparables par virage" else "aucune perte nette et répétée dans un virage",
            )
        val text = CornerCoach.advice(f, ctx.race) ?: return noAdvice("perte au virage ${f.corner} sans cause mesurable")
        lastNoAdvice = null
        lastAdviceLap = lap
        if (ctx.race) raceAdvice++
        pending = Pending(f.corner, f.issue!!, f.lossMs, rec[f.corner].orEmpty().count { it.offTrack != true })
        adviceLog += AdviceOutcome(f.corner, f.issue, f.lossMs, null, sessionId.orEmpty())
        out += msg(text, ctx, lap)
        return out
    }

    private fun msg(text: String, ctx: LapContext, lap: Int) = EngineerMessage(
        text,
        if (ctx.race) Priority.INFO else Priority.NORMAL,
        MessageCategory.RADIO,
        topic = "coach",
        // Une consigne n'a de sens que pour le tour qui suit : périmée au-delà.
        validUntilLap = lap + 1,
        ttlMs = 45_000L,
    )

    /**
     * Fin de session : passages, style et conseils rejoignent le profil, qui est enregistré. Une session sans tour propre
     * mesuré ne change rien.
     */
    fun close(fuelPerLap: Float?): DriverProfile? {
        val prof = profile ?: return null
        val id = sessionId ?: return null
        if (cleanLaps.isEmpty()) return null
        val times = cleanLaps.mapNotNull { it.timeMs?.toDouble() }
        val mean = times.average()
        val spread = if (times.size >= 3) sqrt(times.sumOf { (it - mean) * (it - mean) } / (times.size - 1)).roundToInt() else null
        val counted = cleanLaps.filter { it.lockups != null }
        val fade = if (times.size >= 6) (times.takeLast(3).average() - times.take(3).average()).roundToInt() else null
        val style = SessionStyle(
            id, cleanLaps.size, spread,
            counted.takeIf { it.isNotEmpty() }?.let { c -> c.sumOf { it.lockups!! }.toFloat() / c.size },
            counted.takeIf { it.isNotEmpty() }?.let { c -> c.sumOf { it.wheelspins ?: 0 }.toFloat() / c.size },
            fade, fuelPerLap,
        )
        val updated = prof.withPasses(sessionPasses).copy(
            sessions = (prof.sessions.filter { it.sessionId != id } + style).takeLast(MAX_SESSIONS),
            advice = (prof.advice.filter { it.sessionId != id } + adviceLog).takeLast(MAX_ADVICE),
        )
        book.put(updated)
        try {
            save(book.toJson())
            lastSaveMs = clock()
        } catch (e: Exception) {
            // Un échec d'enregistrement n'interrompt pas la course, mais il est visible dans le diagnostic.
            lastError = "enregistrement du profil impossible (${e.javaClass.simpleName})"
        }
        profile = updated
        return updated
    }

    /** Profils connus (sans session en cours) pour une clé de base : synthèse avant la course. */
    fun known(base: String): DriverProfile? = book.variants(base).maxByOrNull { it.sessionCount }

    /** Faits de pilotage pour la radio et l'IA : seulement ce qui est mesuré, avec sa base. */
    fun facts(base: String): List<String> {
        val p = profile ?: known(base) ?: return listOf("Profil de pilotage : aucune session mesurée sur cette voiture et ce circuit.")
        val out = mutableListOf<String>()
        out += "Profil de pilotage : ${p.sessionCount} session(s) mesurée(s), ${p.map?.corners?.size ?: 0} virages reconnus (corde estimée, pas officielle)."
        p.traits().text?.let { out += it }
        p.progress()?.let { out += it }
        val (ok, n) = p.adviceRecord()
        if (n > 0) out += "Conseils vérifiés : $ok sur $n suivis d'une amélioration mesurée."
        return out
    }

    companion object {
        const val MAX_MAP_OFFSET_M = 8f

        /** Distance parcourue après la ligne avant d'analyser le tour : sortie du dernier virage comprise. */
        const val CONTINUATION_M = 220f
        const val MAX_RACE_ADVICE = 3
        const val RACE_ADVICE_GAP_LAPS = 4
        const val MAX_SESSIONS = 30
        const val MAX_ADVICE = 60
    }
}
