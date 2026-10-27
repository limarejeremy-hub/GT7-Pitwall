package com.gt7pitwall.core.race

import com.gt7pitwall.core.telemetry.TelemetryPacket

/** Ce que l'ingénieur sait de la position mesurée, à un instant donné. */
data class MeasuredPosition(
    /** Position mesurée utilisable maintenant (null : indisponible, la position déclarée prend le relais). */
    val position: Int? = null,
    val cars: Int? = null,
    val startPosition: Int? = null,
    val startCars: Int? = null,
    val finalPosition: Int? = null,
    val finalCars: Int? = null,
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
 */
class PositionTracker(
    private val stableMs: Long = STABLE_MS,
    private val finishStableMs: Long = FINISH_STABLE_MS,
    private val unavailableMs: Long = UNAVAILABLE_MS,
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

    /** Position figée à l'arrivée. */
    var finalPosition: Int? = null
        private set
    var finalCars: Int? = null
        private set

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

    /** Ce qui vient de changer, pour l'ingénieur. */
    sealed interface Change {
        data class Moved(val from: Int?, val to: Int, val cars: Int) : Change
        data class CarsChanged(val from: Int?, val to: Int) : Change
        data class Finished(val position: Int, val cars: Int) : Change
        data object Lost : Change
    }

    /** Position mesurée utilisable maintenant : établie, et GT7 envoie encore des valeurs valides. */
    fun reliable(nowMs: Long): Boolean =
        enabled && finalPosition == null && position != null && lastValidMs?.let { nowMs - it <= unavailableMs } == true

    fun onPacket(p: TelemetryPacket, nowMs: Long): List<Change> {
        if (!enabled || finalPosition != null) return emptyList()
        val out = mutableListOf<Change>()
        val pos = p.preRacePosition
        val n = p.preRaceCarCount
        val valid = n in 1..MAX_CARS && pos in 1..n
        // Une course réellement lancée : nombre de tours connu, sur la grille (tour 0) ou en piste.
        val inRace = p.totalLaps > 0 && p.currentLap >= 0 && !p.isLoading
        if (!inRace || p.isPaused) {
            // Pause : la valeur affichée reste vraie, sans servir à un changement.
            if (p.isPaused && valid && pos == position) lastValidMs = nowMs
            candidate = null
            return out
        }
        val finished = p.currentLap > p.totalLaps
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
            // Arrivée : le classement de la ligne, s'il reste stable un instant ; sinon la dernière position établie.
            val seen = finishSeenMs ?: nowMs.also { finishSeenMs = it }
            if (nowMs - candidateSinceMs >= finishStableMs || nowMs - seen >= stableMs) {
                val (fp, fc) = if (nowMs - candidateSinceMs >= finishStableMs) value else (position ?: return out) to (cars ?: n)
                finalPosition = fp
                finalCars = fc
                out += Change.Finished(fp, fc)
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
        finishSeenMs = null
        tracked = false
    }

    private fun clearLive() {
        position = null
        cars = null
        candidate = null
        lastValidMs = null
        invalidSinceMs = null
    }

    companion object {
        /** Une valeur doit tenir 3 s : un roue dans roue fait alterner 0x84 en quelques dixièmes. */
        /** Place constante 2 s avant d'être retenue (27/10 : 3 s, places annoncées trop tard). */
        const val STABLE_MS = 2_000L
        const val FINISH_STABLE_MS = 1_000L
        const val UNAVAILABLE_MS = 5_000L
        const val MAX_CARS = 40
    }
}
