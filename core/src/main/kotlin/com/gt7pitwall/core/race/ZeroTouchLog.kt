package com.gt7pitwall.core.race

/**
 * Issue #30 (spécification zéro touch Sport du 29/10) : jalons horodatés d'une session, pour mesurer sur de vraies
 * courses le délai entre le premier paquet et le briefing, et les détections justes ou fausses. Chaque jalon dit d'où
 * il vient et avec quelle certitude ; aucun ne prouve à lui seul qu'une course est officielle.
 */
enum class Milestone(val label: String) {
    UDP_CONNECTED("premier paquet GT7 reçu"),
    CARCODE_CONFIRMED("voiture confirmée par les paquets"),
    TRACK_CANDIDATE("circuit probable d'après la position à l'arrêt"),
    TRACK_MEASURED("circuit reconnu par le tracé mesuré"),
    SPORT_CANDIDATE("épreuve Sport candidate"),
    QUALIFYING_SUSPECTED("qualification ou essais supposés (GT7 n'annonce pas de tours)"),
    GRID_DETECTED("grille de départ"),
    RACE_STARTED("course lancée"),
    STRATEGY_READY("stratégie prête"),
    TYRE_UNCONFIRMED("gomme de départ non confirmée"),
    PIT_VISIT_STARTED("passage aux stands commencé"),
    PIT_VISIT_ENDED("passage aux stands terminé"),
}

data class MilestoneEvent(
    val milestone: Milestone,
    val atMs: Long,
    /** Délai depuis [Milestone.UDP_CONNECTED] (null avant le premier paquet). */
    val sinceUdpMs: Long?,
    /** « télémétrie », « fiche de la semaine », « plan »… */
    val source: String,
    /** « mesurée », « probable », « supposée »… */
    val confidence: String,
    val detail: String? = null,
)

class ZeroTouchLog(private val clock: () -> Long) {
    private val events = mutableListOf<MilestoneEvent>()
    private val once = mutableSetOf<Milestone>()
    private var udpMs: Long? = null

    val all: List<MilestoneEvent> get() = synchronized(events) { events.toList() }

    fun first(m: Milestone): MilestoneEvent? = all.firstOrNull { it.milestone == m }

    /**
     * Note [m] ; [unique] : une seule fois par session (le briefing ne se déclenche pas deux fois, la grille non plus).
     * Rend l'événement noté, ou null s'il l'était déjà.
     */
    fun mark(m: Milestone, source: String, confidence: String, detail: String? = null, unique: Boolean = true): MilestoneEvent? {
        if (unique && !once.add(m)) return null
        val now = clock()
        if (m == Milestone.UDP_CONNECTED) udpMs = now
        val e = MilestoneEvent(m, now, udpMs?.let { now - it }, source, confidence, detail)
        synchronized(events) {
            events += e
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
        return e
    }

    /** Nouvelle session : les jalons de session repartent ; la connexion UDP et la voiture restent acquises. */
    fun newSession() {
        once.retainAll(setOf(Milestone.UDP_CONNECTED, Milestone.CARCODE_CONFIRMED))
    }

    /** Nouvelle voiture confirmée : on la note à nouveau. */
    fun carChanged() {
        once.remove(Milestone.CARCODE_CONFIRMED)
    }

    companion object {
        const val MAX_EVENTS = 200
    }
}
