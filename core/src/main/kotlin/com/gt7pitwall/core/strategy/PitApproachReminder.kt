package com.gt7pitwall.core.strategy

/**
 * Optional second pit call, once a confirmed planned pit lap reaches the last
 * DISTANCE-based Pitwall sector. Not GT7's official sector timing.
 *
 * The early "Box this lap" call is unchanged. No approach announcement before
 * a trustworthy full-lap trace exists, or when the active Sport regulations
 * contradict the actual lap count.
 */
class PitApproachReminder {
    private var called: Pair<Int, Int>? = null

    fun reset() { called = null }

    fun onProgress(
        lap: Int,
        totalLaps: Int,
        referenceLaps: Int?,
        progress: Float?,
        plannedStop: PlannedStop?,
        pitVisitOpen: Boolean,
    ): PlannedStop? {
        if (lap < 2 || totalLaps <= 0 || pitVisitOpen || plannedStop == null) return null
        if (referenceLaps != null && referenceLaps != totalLaps) return null
        if (plannedStop.endOfLap != lap || lap >= totalLaps) return null
        // Last third of the lap, but still before the pit entry for most tracks.
        // Progress is normalized to a previously completed, uninterrupted lap.
        if (progress == null || progress < 0.70f || progress > 0.90f) return null
        val identity = lap to plannedStop.endOfLap
        if (called == identity) return null
        called = identity
        return plannedStop
    }
}
