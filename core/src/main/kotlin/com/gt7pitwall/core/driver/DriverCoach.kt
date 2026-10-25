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
) {
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

    private val book: DriverBook by lazy { DriverBook.parse(runCatching(load).getOrNull()) }
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
    }

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
        val time = lap.timeMs ?: return emptyList()
        if (lap.lapNumber < 2 || lap.refuelled || lap.pitLane || ctx.pitLap || samples == null) return emptyList()
        val best = (cleanLaps.mapNotNull { it.timeMs } + time).min()
        if (time > best * 1.07) return emptyList() // tour perturbé (trafic, incident) : hors comparaison
        val prof = resolve(ctx.profileKey, samples) ?: return incompatible()
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
                val text = when {
                    gained != null && gained >= DriverProfile.IMPROVED_MS ->
                        if (ctx.race) "Virage ${pd.corner}, c'est mieux." else "Virage ${pd.corner} : c'est mieux, tu as repris environ ${CornerCoach.tenths(gained)}."
                    ctx.race -> null
                    else -> "Virage ${pd.corner} : pas encore d'amélioration mesurée. Garde la même consigne."
                }
                text?.let { out += msg(it, ctx, lap) }
                return out
            }
            return out // une seule consigne à la fois
        }
        val allowed = if (ctx.race) {
            raceAdvice < MAX_RACE_ADVICE && lap >= 3 && (ctx.totalLaps <= 0 || lap < ctx.totalLaps - 1) &&
                lap - lastAdviceLap >= RACE_ADVICE_GAP_LAPS && !ctx.positionChangedRecently
        } else {
            lap - lastAdviceLap >= 2
        }
        if (!allowed) return out
        val f = CornerCoach.worst(history(prof), rec, if (ctx.race) CornerCoach.MIN_LOSS_RACE_MS else CornerCoach.MIN_LOSS_PRACTICE_MS) ?: return out
        val text = CornerCoach.advice(f, ctx.race) ?: return out
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
        runCatching { save(book.toJson()) }
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
