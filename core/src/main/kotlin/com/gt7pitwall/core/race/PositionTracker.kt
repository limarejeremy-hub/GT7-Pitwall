package com.gt7pitwall.core.race

import com.gt7pitwall.core.telemetry.TelemetryPacket

/** Ce que l'ingénieur sait de la position mesurée, à un instant donné. */
data class MeasuredPosition(
    /** Position mesurée utilisable maintenant (null : indisponible, la position déclarée prend le relais). */
    val position: Int? = null,
    val cars: Int? = null,
    val startPosition: Int? = null,
    val startCars: Int? = null,
    /**
     * Classement d'arrivée : révisable après la ligne (classement révisé par GT7) jusqu'à sa consolidation
     * ([finalConsolidated]). Au franchissement, il vaut le rang de la ligne.
     */
    val finalPosition: Int? = null,
    val finalCars: Int? = null,
    /** Rang lu sur le paquet de la ligne d'arrivée (« 8/7 »), jamais modifié ensuite. */
    val linePosition: Int? = null,
    val lineCars: Int? = null,
    /** Faux pendant la consolidation qui suit la ligne : le classement peut encore changer. */
    val finalConsolidated: Boolean = false,
    /** Changements reçus après la ligne, dans l'ordre (cause inconnue de Pitwall : aucun champ de pénalité identifié). */
    val postFinishChanges: List<PositionTracker.PostFinishChange> = emptyList(),
    /** Lecture active (course) ; false en qualification. */
    val enabled: Boolean = true,
    /** Une position a été mesurée pendant cette course (même si elle est momentanément indisponible). */
    val tracked: Boolean = false,
    /** La dernière valeur reçue confirme la position stable (pas de dépassement en cours). */
    val settled: Boolean = false,
) {
    val placesGained: Int?
        get() = startPosition?.let { s -> (finalPosition ?: position)?.let { s - it } }

    companion object {
        val NONE = MeasuredPosition()
    }
}

/**
 * Position en course mesurée par GT7 (champ 0x84, nombre de participants en 0x86).
 *
 * Essais PS5 (Course Sport A en ligne, course hors ligne) : en course, 0x84 suit la position du HUD ; pendant le
 * matchmaking (tours inconnus), il compte les voitures chargées, pas la position ; au chargement, il vaut un instant 1.
 * La valeur n'est donc lue que dans une course réellement lancée (nombre de tours connu, ni chargement ni pause),
 * cohérente (1 ≤ position ≤ participants), et seulement après être restée stable [stableMs]. Elle est figée à
 * l'arrivée. Rien sur les adversaires : GT7 ne donne que notre rang, ni écart ni distance.
 *
 * En qualification, rien n'est lu : 0x84 n'y a pas été validé.
 *
 * V508 (capture Course Sport du pilote : P10/15 au départ, P9 au tour 2, 0x86 15 → 14 en restant P8, P5/14 à « 8/7 ») :
 * - le rang de chaque passage de ligne est noté ([lapPositions], valeur brute du paquet de la ligne) ;
 * - le classement est figé dès le paquet de la ligne d'arrivée s'il est cohérent (sinon après [finishStableMs], ou la
 *   dernière place établie si la course se ferme avant) ; le « 8/0 » qui suit ne rouvre rien ;
 * - [liveRank] ne donne un rang que si le flux est frais, la course lancée, et le rang cohérent avec au moins 2 voitures.
 *
 * V508 révisé (trace du 22/11 : P12/16 à la ligne, P13/16 567 ms plus tard ; le pilote avait reçu une pénalité de 2 s
 * appliquée après la ligne) : GT7 met 0x84 à jour APRÈS l'arrivée quand le classement change. Le classement de la ligne
 * n'est donc plus définitif :
 * - [linePosition] garde le rang du paquet de la ligne ;
 * - après la ligne, tant que le compteur de tours reste celui de l'arrivée (« 8/7 », ou « 8/0 » quand GT7 remet le
 *   nombre de tours à 0), un nouveau rang stable [postStableMs] révise [finalPosition] et s'ajoute à
 *   [postFinishChanges] ;
 * - le classement est consolidé après [consolidationMs] sans changement, délai repris à chaque révision, ou dès que la
 *   session d'arrivée se termine (chargement, autre tour, nouvelle session, peloton en hausse = valeurs du salon).
 * Aucune cause n'est attribuée : GT7 ne transmet aucun champ de pénalité identifié.
 */
class PositionTracker(
    private val stableMs: Long = STABLE_MS,
    private val finishStableMs: Long = FINISH_STABLE_MS,
    private val unavailableMs: Long = UNAVAILABLE_MS,
    private val postStableMs: Long = POST_FINISH_STABLE_MS,
    private val consolidationMs: Long = CONSOLIDATION_MS,
) {
    /** Position mesurée et stable, null tant qu'aucune n'est établie (ou si elle est devenue indisponible). */
    var position: Int? = null
        private set

    /** Participants actuels (0x86 stable) : une déconnexion le fait baisser. */
    var cars: Int? = null
        private set

    /** Place sur la grille et participants au départ (tour 0, course lancée). */
    var startPosition: Int? = null
        private set
    var startCars: Int? = null
        private set

    /** Classement d'arrivée (révisable jusqu'à [finalConsolidated]). */
    var finalPosition: Int? = null
        private set
    var finalCars: Int? = null
        private set

    /** Rang du paquet de la ligne d'arrivée. */
    var linePosition: Int? = null
        private set
    var lineCars: Int? = null
        private set

    var finalConsolidated: Boolean = false
        private set

    /** Un changement du classement reçu après la ligne ([afterLineMs] : délai depuis le paquet de la ligne). */
    data class PostFinishChange(val fromPosition: Int, val fromCars: Int, val toPosition: Int, val toCars: Int, val afterLineMs: Long) {
        val text: String get() = "P$fromPosition/$fromCars → P$toPosition/$toCars, ${afterLineMs} ms après la ligne"
    }

    private val postChanges = mutableListOf<PostFinishChange>()
    val postFinishChanges: List<PostFinishChange> get() = postChanges.toList()
    private var finishLap: Int? = null
    private var lineMs = 0L
    private var lastRevisionMs = 0L
    private var postCandidate: Pair<Int, Int>? = null
    private var postCandidateSinceMs = 0L

    /** Lecture désactivée (qualification, ou épreuve dont 0x84 n'est pas validé). */
    var enabled: Boolean = true
        set(v) {
            field = v
            if (!v) clearLive()
        }

    private var candidate: Pair<Int, Int>? = null
    private var candidateSinceMs = 0L
    private var lastValidMs: Long? = null
    private var invalidSinceMs: Long? = null
    private var finishSeenMs: Long? = null
    private var lastLap: Int? = null
    private var lastPacketMs: Long? = null
    private var inRaceNow = false

    /** Rang au passage de la ligne de chaque tour bouclé (valeur brute du paquet de la ligne, cohérente). */
    data class LapPosition(val lap: Int, val position: Int, val cars: Int, val timeMs: Long, val packetId: Int)

    private val laps = mutableListOf<LapPosition>()
    val lapPositions: List<LapPosition> get() = laps.toList()

    /** Ce qui vient de changer, pour l'ingénieur. */
    sealed interface Change {
        data class Moved(val from: Int?, val to: Int, val cars: Int) : Change
        data class CarsChanged(val from: Int?, val to: Int) : Change
        /** Ligne d'arrivée : rang du paquet de la ligne (classement provisoire). */
        data class Finished(val position: Int, val cars: Int) : Change
        data object Lost : Change

        /** Classement révisé par GT7 après la ligne (cause non transmise). */
        data class Revised(val change: PostFinishChange) : Change

        /** Classement consolidé : plus de changement attendu ([line] : rang de la ligne, pour comparer). */
        data class Consolidated(val position: Int, val cars: Int, val linePosition: Int, val lineCars: Int, val reason: String) : Change

        /** Tour [lap] bouclé : rang lu sur le paquet de la ligne (journal seulement, rien n'est annoncé). */
        data class LapCrossed(val lap: Int, val position: Int, val cars: Int) : Change
    }

    /** Position mesurée utilisable maintenant : établie, et GT7 envoie encore des valeurs valides. */
    fun reliable(nowMs: Long): Boolean =
        enabled && finalPosition == null && position != null && lastValidMs?.let { nowMs - it <= unavailableMs } == true

    /**
     * Rang à dire maintenant (« Position ? »), ou null : flux frais (dernier paquet il y a au plus [FRESH_MS]), course
     * lancée (tours connus, ni chargement ni menu), pas encore arrivée, rang établi cohérent avec au moins 2 voitures.
     */
    fun liveRank(nowMs: Long): Pair<Int, Int>? {
        val pos = position ?: return null
        val n = cars ?: return null
        if (!inRaceNow || n < 2 || pos !in 1..n || !reliable(nowMs)) return null
        if (lastPacketMs?.let { nowMs - it in 0..FRESH_MS } != true) return null
        return pos to n
    }

    fun onPacket(p: TelemetryPacket, nowMs: Long): List<Change> {
        if (!enabled || finalConsolidated) return emptyList()
        if (finalPosition != null) return afterFinish(p, nowMs)
        val out = mutableListOf<Change>()
        val pos = p.preRacePosition
        val n = p.preRaceCarCount
        val valid = n in 1..MAX_CARS && pos in 1..n
        // Une course réellement lancée : nombre de tours connu, sur la grille (tour 0) ou en piste.
        val inRace = p.totalLaps > 0 && p.currentLap >= 0 && !p.isLoading
        lastPacketMs = nowMs
        inRaceNow = inRace
        if (!inRace || p.isPaused) {
            // Pause : la valeur affichée reste vraie, sans servir à un changement.
            if (p.isPaused && valid && pos == position) lastValidMs = nowMs
            candidate = null
            // Ligne d'arrivée vue, puis la course se ferme (« 8/0 », chargement) avant le classement : dernière place établie.
            if (!p.isPaused && finishSeenMs != null) position?.let { fp -> freeze(fp, cars ?: return out, out, p.currentLap, nowMs) }
            return out
        }
        val finished = p.currentLap > p.totalLaps
        // Passage de ligne : le rang du paquet de la ligne, noté pour le tour qui vient de se terminer.
        val prevLap = lastLap
        lastLap = p.currentLap
        if (prevLap != null && prevLap >= 1 && p.currentLap == prevLap + 1 && valid && laps.none { it.lap == prevLap }) {
            laps += LapPosition(prevLap, pos, n, nowMs, p.packetId)
            out += Change.LapCrossed(prevLap, pos, n)
        }
        if (!valid) {
            candidate = null
            val since = invalidSinceMs ?: nowMs.also { invalidSinceMs = it }
            if (position != null && nowMs - since > unavailableMs) {
                clearLive()
                out += Change.Lost
            }
            return out
        }
        invalidSinceMs = null
        lastValidMs = nowMs
        val value = pos to n
        if (candidate != value) {
            candidate = value
            candidateSinceMs = nowMs
        }
        if (finished) {
            // Arrivée : le classement du paquet de la ligne (ce que montre le HUD à « 8/7 »), figé tout de suite pour que
            // le message d'arrivée le dise ; si la ligne est franchie sans valeur cohérente, la valeur stable qui suit,
            // sinon la dernière position établie.
            val firstFinishPacket = finishSeenMs == null
            val seen = finishSeenMs ?: nowMs.also { finishSeenMs = it }
            when {
                firstFinishPacket && prevLap != null && prevLap <= p.totalLaps -> freeze(pos, n, out, p.currentLap, nowMs)
                nowMs - candidateSinceMs >= finishStableMs -> freeze(pos, n, out, p.currentLap, nowMs)
                nowMs - seen >= stableMs -> freeze(position ?: return out, cars ?: n, out, p.currentLap, nowMs)
            }
            return out
        }
        if (nowMs - candidateSinceMs < stableMs) return out
        if (cars != n) {
            out += Change.CarsChanged(cars, n)
            cars = n
        }
        if (position != pos) {
            out += Change.Moved(position, pos, n)
            position = pos
        }
        tracked = true
        // « P1 sur 1 » avant le départ : grille pas encore chargée, jamais retenu comme place de départ.
        if (p.currentLap == 0 && n >= 2) {
            startPosition = pos
            startCars = n
        }
        return out
    }

    private fun freeze(fp: Int, fc: Int, out: MutableList<Change>, lap: Int, nowMs: Long) {
        if (finalPosition != null) return
        finalPosition = fp
        finalCars = fc
        linePosition = fp
        lineCars = fc
        finishLap = lap
        lineMs = nowMs
        lastRevisionMs = nowMs
        out += Change.Finished(fp, fc)
    }

    /** Après la ligne : révisions du classement, puis consolidation. */
    private fun afterFinish(p: TelemetryPacket, nowMs: Long): List<Change> {
        val out = mutableListOf<Change>()
        lastPacketMs = nowMs
        inRaceNow = false
        // Fin de la session d'arrivée : chargement (menu, résultats), autre tour (nouvelle course).
        if (p.isLoading || p.currentLap != finishLap) {
            consolidate("fin de la session d'arrivée", out)
            return out
        }
        if (p.isPaused) return out
        val pos = p.preRacePosition
        val n = p.preRaceCarCount
        // Le peloton ne grossit jamais après l'arrivée : 0x86 en hausse = valeurs du salon ou du matchmaking (0x84 = 0x86
        // = voitures chargées, essais du 20/10), plus le classement. La session d'arrivée est terminée.
        if (n in 1..MAX_CARS && n > (finalCars ?: n)) {
            consolidate("valeurs hors classement (peloton en hausse : salon)", out)
            return out
        }
        if (n in 1..MAX_CARS && pos in 1..n) {
            val value = pos to n
            if (value == finalPosition to finalCars) {
                postCandidate = null
            } else {
                if (postCandidate != value) {
                    postCandidate = value
                    postCandidateSinceMs = nowMs
                }
                if (nowMs - postCandidateSinceMs >= postStableMs) {
                    val c = PostFinishChange(finalPosition!!, finalCars!!, pos, n, postCandidateSinceMs - lineMs)
                    postChanges += c
                    finalPosition = pos
                    finalCars = n
                    lastRevisionMs = nowMs
                    postCandidate = null
                    out += Change.Revised(c)
                }
            }
        }
        // Consolidation : aucun changement pendant [consolidationMs], délai repris à chaque révision ; jamais pendant
        // qu'une nouvelle valeur attend sa confirmation.
        if (postCandidate == null && nowMs - lastRevisionMs >= consolidationMs) consolidate("aucun changement depuis ${consolidationMs / 1000} s", out)
        return out
    }

    /**
     * Consolide le classement d'arrivée maintenant (fin de session, nouvelle course, clôture) ; liste vide s'il n'y a
     * pas d'arrivée en attente.
     */
    fun consolidate(reason: String): List<Change> = mutableListOf<Change>().also { consolidate(reason, it) }

    private fun consolidate(reason: String, out: MutableList<Change>) {
        val fp = finalPosition ?: return
        if (finalConsolidated) return
        finalConsolidated = true
        postCandidate = null
        out += Change.Consolidated(fp, finalCars ?: return, linePosition ?: fp, lineCars ?: finalCars!!, reason)
    }

    /** Une position a été établie pendant cette course. */
    var tracked: Boolean = false
        private set

    fun snapshot(nowMs: Long) = MeasuredPosition(
        position = if (reliable(nowMs)) position else null,
        cars = if (reliable(nowMs)) cars else null,
        startPosition = startPosition,
        startCars = startCars,
        finalPosition = finalPosition,
        finalCars = finalCars,
        linePosition = linePosition,
        lineCars = lineCars,
        finalConsolidated = finalConsolidated,
        postFinishChanges = postChanges.toList(),
        enabled = enabled,
        tracked = tracked,
        settled = candidate?.first == position,
    )

    fun reset() {
        clearLive()
        startPosition = null
        startCars = null
        finalPosition = null
        finalCars = null
        linePosition = null
        lineCars = null
        finalConsolidated = false
        postChanges.clear()
        finishLap = null
        postCandidate = null
        finishSeenMs = null
        tracked = false
        lastLap = null
        laps.clear()
    }

    private fun clearLive() {
        position = null
        cars = null
        candidate = null
        lastValidMs = null
        invalidSinceMs = null
        inRaceNow = false
    }

    companion object {
        /** Une valeur doit tenir 3 s : un roue dans roue fait alterner 0x84 en quelques dixièmes. */
        /** Place constante 2 s avant d'être retenue (27/10 : 3 s, places annoncées trop tard). */
        const val STABLE_MS = 2_000L
        const val FINISH_STABLE_MS = 1_000L

        /**
         * Après la ligne, un nouveau rang doit tenir 0,5 s (plus de roue dans roue possible ; la révision du 22/11 est
         * arrivée 567 ms après la ligne).
         */
        const val POST_FINISH_STABLE_MS = 500L

        /**
         * Consolidation : 30 s sans changement après la ligne ou la dernière révision. Une seule mesure réelle existe
         * (révision 567 ms après la ligne, pénalité de 2 s) : le délai n'est PAS calé sur elle. Une révision liée au
         * temps peut dépendre de voitures encore en piste ; 30 s couvrent des écarts et des pénalités de plusieurs
         * secondes, et chaque révision relance le délai. La fin de la session d'arrivée consolide plus tôt. À
         * recalibrer avec d'autres traces.
         */
        const val CONSOLIDATION_MS = 30_000L
        const val UNAVAILABLE_MS = 5_000L

        /** « Position ? » : plus de 1,5 s sans paquet (gel UDP, menu), le rang n'est plus dit. */
        const val FRESH_MS = 1_500L
        const val MAX_CARS = 40
    }
}
