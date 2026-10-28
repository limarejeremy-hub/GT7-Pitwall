package com.gt7pitwall.core.race

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Nature d'un événement du journal de course. */
enum class JournalType(val label: String) {
    SESSION("session"),
    LAP("tour"),
    DRIVER("pilote"),
    ENGINEER("ingénieur"),
    ALERT("alerte"),
    DECISION("décision"),
    STRATEGY("stratégie"),
    PIT("stand"),
    POSITION("position"),
    RADIO("radio"),
}

/**
 * D'où vient l'information. Une phrase réellement prononcée n'est jamais connue (aucun son n'est enregistré) : le
 * journal garde seulement ce que la reconnaissance vocale en a transcrit, séparément de ce que Gemini a rédigé, de ce
 * que le moteur a calculé et de ce que GT7 a mesuré.
 */
enum class JournalSource(val label: String) {
    TRANSCRIPT("transcription de la reconnaissance vocale"),
    SCREEN("saisie à l'écran"),
    ENGINE("calcul du moteur Pitwall"),
    GEMINI("texte rédigé par Gemini, contrôlé"),
    TELEMETRY("mesure de la télémétrie GT7"),
    SYSTEM("application"),
}

/** Instantané léger de la télémétrie au moment de l'événement (jamais le flux brut de 60 paquets par seconde). */
data class JournalTelemetry(
    val lap: Int? = null,
    val totalLaps: Int? = null,
    val speedKmh: Float? = null,
    val fuel: Float? = null,
    val fuelCapacity: Float? = null,
    val position: Int? = null,
    val cars: Int? = null,
    /** Chrono du tour en cours (format C), ms. */
    val lapTimeMs: Int? = null,
    /** Températures moyennes des pneus AVG, AVD, ARG, ARD (°C). */
    val tyreTemps: List<Float>? = null,
    val gear: Int? = null,
    val inPit: Boolean? = null,
)

data class JournalEvent(
    val seq: Int,
    val timeMs: Long,
    val sessionId: String,
    val lap: Int?,
    val type: JournalType,
    val source: JournalSource,
    val text: String,
    val telemetry: JournalTelemetry? = null,
    /** Décision stratégique ou du garde des commandes, le cas échéant. */
    val decision: String? = null,
    /** Données utiles à l'analyse (mesures du tour, reconnaissance utilisée, priorité…). */
    val details: Map<String, String> = emptyMap(),
)

/** Une session du journal, pour la page « Historique des courses ». */
data class JournalSessionInfo(
    val id: String,
    val startedAtMs: Long,
    val title: String,
    val events: Int,
    val laps: Int,
    val outcome: SessionOutcome?,
)

/**
 * Journal chronologique de la session, écrit au fil de l'eau (une ligne JSON par événement, ajoutée aussitôt) dans le
 * même stockage que l'archive des sessions ([RaceStorage]) et sous le même identifiant que [SessionRecord] : une
 * fermeture brutale d'Android ne perd au plus que l'événement en cours d'écriture.
 *
 * Avant le début d'une session (radio dans les menus), les événements attendent en mémoire, au plus [MAX_PENDING],
 * puis rejoignent la session suivante. Les textes sont filtrés : adresses IP et chaînes ressemblant à un jeton
 * (Discord ou autre) sont masquées, même si aucune ne devrait jamais y passer.
 */
class SessionJournal(
    private val storage: RaceStorage?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val telemetry: () -> JournalTelemetry? = { null },
) {
    var sessionId: String? = null
        private set
    private var seq = 0
    private val pending = ArrayDeque<JournalEvent>()
    private val live = ArrayDeque<JournalEvent>()

    @Synchronized
    fun begin(id: String, text: String, details: Map<String, String> = emptyMap()) {
        if (sessionId == id) return
        sessionId = id
        seq = 0
        live.clear()
        record(JournalType.SESSION, JournalSource.SYSTEM, text, details = details)
        // La radio des menus rejoint la course qui commence, dans l'ordre et marquée comme antérieure. Au-delà de
        // [PENDING_MAX_AGE_MS], elle appartient à un autre moment (menus, session abandonnée) et n'est pas rattachée.
        val now = clock()
        while (pending.isNotEmpty()) {
            val e = pending.removeFirst()
            if (now - e.timeMs > PENDING_MAX_AGE_MS) continue
            write(e.copy(seq = ++seq, sessionId = id, details = e.details + ("avant_session" to "oui")))
        }
    }

    @Synchronized
    fun end(text: String, details: Map<String, String> = emptyMap()) {
        val id = sessionId ?: return
        record(JournalType.SESSION, JournalSource.SYSTEM, text, details = details)
        lastClosedId = id
        lastClosedSeq = seq
        sessionId = null
    }

    /** Dernière session close et son dernier numéro d'événement (pour y rattacher un événement tardif). */
    var lastClosedId: String? = null
        private set
    private var lastClosedSeq = 0

    /**
     * Événement qui appartient à la session [id], même si elle est déjà close (bilan d'une mesure arrêtée depuis le
     * menu, par exemple). Jamais mis en attente : il ne peut pas glisser vers la course suivante. Session en cours :
     * équivalent à [record]. Session close : ajouté à son journal enregistré, à la suite de ses événements, marqué
     * `apres_cloture`.
     */
    @Synchronized
    fun recordFor(
        id: String,
        type: JournalType,
        source: JournalSource,
        text: String,
        decision: String? = null,
        details: Map<String, String> = emptyMap(),
    ): JournalEvent {
        if (id == sessionId) return record(type, source, text, decision, details)
        val next = when (id) {
            lastClosedId -> ++lastClosedSeq
            else -> (runCatching { storage?.loadJournal(id)?.size }.getOrNull() ?: 0) + 1
        }
        val e = JournalEvent(
            seq = next,
            timeMs = clock(),
            sessionId = id,
            lap = null,
            type = type,
            source = source,
            text = redact(text).take(MAX_TEXT),
            telemetry = null,
            decision = decision?.let { redact(it).take(MAX_TEXT) },
            details = details.mapValues { redact(it.value).take(MAX_TEXT) } + ("apres_cloture" to "oui"),
        )
        runCatching { storage?.appendJournal(id, JournalJson.encode(e)) }
        return e
    }

    /** Ajoute un événement ; le tour et la télémétrie viennent de l'instantané courant, sauf [lap] imposé. */
    @Synchronized
    fun record(
        type: JournalType,
        source: JournalSource,
        text: String,
        decision: String? = null,
        details: Map<String, String> = emptyMap(),
        lap: Int? = null,
    ): JournalEvent {
        val t = runCatching { telemetry() }.getOrNull()
        val e = JournalEvent(
            seq = 0,
            timeMs = clock(),
            sessionId = sessionId ?: "",
            lap = lap ?: t?.lap,
            type = type,
            source = source,
            text = redact(text).take(MAX_TEXT),
            telemetry = t,
            decision = decision?.let { redact(it).take(MAX_TEXT) },
            details = details.mapValues { redact(it.value).take(MAX_TEXT) },
        )
        if (sessionId == null) {
            pending.addLast(e)
            while (pending.size > MAX_PENDING) pending.removeFirst()
            return e
        }
        return e.copy(seq = ++seq).also(::write)
    }

    /** Événements de la session en cours (les plus récents), pour l'écran. */
    @Synchronized
    fun recent(n: Int = 200): List<JournalEvent> = live.toList().takeLast(n)

    private fun write(e: JournalEvent) {
        live.addLast(e)
        while (live.size > MAX_LIVE) live.removeFirst()
        runCatching { storage?.appendJournal(e.sessionId, JournalJson.encode(e)) }
    }

    companion object {
        const val MAX_PENDING = 200
        const val PENDING_MAX_AGE_MS = 10 * 60_000L
        const val MAX_LIVE = 2_000
        const val MAX_TEXT = 2_000

        private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")

        // Jeton de bot Discord (trois segments base64) ou toute chaîne opaque très longue.
        private val TOKEN = Regex("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{20,}|[A-Za-z0-9_\\-]{40,}")

        /** Masque adresses IP et jetons : rien de sensible ne doit sortir dans un export. */
        fun redact(text: String): String = TOKEN.replace(IPV4.replace(text, "[adresse masquée]"), "[masqué]")

        /** Sessions connues : archive (tours) et journal (radio), réunies par identifiant, la plus récente d'abord. */
        fun sessions(storage: RaceStorage): List<JournalSessionInfo> {
            val records = storage.loadSessions().associateBy { it.id }
            val ids = (records.keys + storage.journalSessionIds()).distinct()
            return ids.map { id ->
                val r = records[id]
                val events = storage.loadJournal(id)
                val first = events.firstOrNull()?.let(JournalJson::decode)
                JournalSessionInfo(
                    id = id,
                    startedAtMs = r?.startedAtMs ?: first?.timeMs ?: id.substringBefore('-').toLongOrNull() ?: 0L,
                    title = first?.text?.takeIf { first.type == JournalType.SESSION } ?: "Session $id",
                    events = events.size,
                    laps = r?.laps?.size ?: 0,
                    outcome = r?.outcome,
                )
            }.sortedByDescending { it.startedAtMs }
        }

        /** Événements d'une session, dans l'ordre d'écriture ; une ligne abîmée (écriture interrompue) est ignorée. */
        fun load(storage: RaceStorage, id: String): List<JournalEvent> =
            storage.loadJournal(id).mapNotNull(JournalJson::decode).sortedBy { it.seq }
    }
}

/** Une ligne JSON par événement. */
object JournalJson {
    fun encode(e: JournalEvent): String = toJson(e).toString()

    fun toJson(e: JournalEvent): JSONObject = JSONObject().apply {
        put("seq", e.seq)
        put("time", e.timeMs)
        put("session", e.sessionId)
        e.lap?.let { put("lap", it) }
        put("type", e.type.name)
        put("source", e.source.name)
        put("text", e.text)
        e.decision?.let { put("decision", it) }
        if (e.details.isNotEmpty()) put("details", JSONObject(e.details))
        e.telemetry?.let { put("telemetry", telemetry(it)) }
    }

    private fun telemetry(t: JournalTelemetry) = JSONObject().apply {
        t.lap?.let { put("lap", it) }
        t.totalLaps?.let { put("totalLaps", it) }
        t.speedKmh?.let { put("speedKmh", round(it)) }
        t.fuel?.let { put("fuel", round(it)) }
        t.fuelCapacity?.let { put("fuelCapacity", round(it)) }
        t.position?.let { put("position", it) }
        t.cars?.let { put("cars", it) }
        t.lapTimeMs?.let { put("lapTimeMs", it) }
        t.tyreTemps?.let { temps -> put("tyreTemps", JSONArray(temps.map { round(it) })) }
        t.gear?.let { put("gear", it) }
        t.inPit?.let { put("inPit", it) }
    }

    private fun round(f: Float): Double = Math.round(f * 10.0) / 10.0

    fun decode(line: String): JournalEvent? = runCatching {
        val o = JSONObject(line)
        val details = o.optJSONObject("details")?.let { d -> d.keys().asSequence().associateWith { d.getString(it) } } ?: emptyMap()
        JournalEvent(
            seq = o.getInt("seq"),
            timeMs = o.getLong("time"),
            sessionId = o.getString("session"),
            lap = if (o.has("lap")) o.getInt("lap") else null,
            type = JournalType.valueOf(o.getString("type")),
            source = JournalSource.valueOf(o.getString("source")),
            text = o.getString("text"),
            telemetry = o.optJSONObject("telemetry")?.let { t ->
                fun f(k: String) = if (t.has(k)) t.getDouble(k).toFloat() else null
                fun i(k: String) = if (t.has(k)) t.getInt(k) else null
                JournalTelemetry(
                    lap = i("lap"), totalLaps = i("totalLaps"), speedKmh = f("speedKmh"), fuel = f("fuel"),
                    fuelCapacity = f("fuelCapacity"), position = i("position"), cars = i("cars"), lapTimeMs = i("lapTimeMs"),
                    tyreTemps = t.optJSONArray("tyreTemps")?.let { a -> List(a.length()) { a.getDouble(it).toFloat() } },
                    gear = i("gear"), inPit = if (t.has("inPit")) t.getBoolean("inPit") else null,
                )
            },
            decision = o.optString("decision", "").ifEmpty { null },
            details = details,
        )
    }.getOrNull()
}

/**
 * Export complet d'une session pour l'analyse (ChatGPT ou autre) : JSON structuré, ou texte lisible. Les deux disent
 * ce que chaque source signifie, pour que l'analyse ne confonde pas mesure, transcription, calcul et texte de Gemini.
 */
object JournalExport {
    private const val SCHEMA = 1

    val LEGEND: List<String> = listOf(
        "Aucun son n'est enregistré : la phrase réellement prononcée par le pilote n'est pas connue.",
        "« transcription » : ce que la reconnaissance vocale a compris (vocabulaire radio et transcription libre), qui peut " +
            "différer de ce qui a été dit.",
        "« calcul du moteur » : réponse ou décision calculée par Pitwall à partir des mesures et des déclarations.",
        "« Gemini » : texte rédigé par Gemini puis contrôlé par Pitwall (refusé s'il invente un chiffre).",
        "« mesure GT7 » : valeur transmise par la télémétrie du jeu (tours, carburant, position 0x84).",
        "GT7 ne transmet ni les écarts avec les adversaires, ni la gomme montée, ni l'usure des pneus.",
    )

    fun json(id: String, record: SessionRecord?, events: List<JournalEvent>, exportedAtMs: Long): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("application", "GT7 Pitwall")
        put("exportedAt", exportedAtMs)
        put("sessionId", id)
        put("legend", JSONArray(LEGEND))
        put("sources", JSONObject(JournalSource.entries.associate { it.name to it.label }))
        put("types", JSONObject(JournalType.entries.associate { it.name to it.label }))
        record?.let { put("archive", JSONObject(RaceJson.session(it))) }
        put("events", JSONArray(events.map(JournalJson::toJson)))
    }.toString(2)

    fun text(id: String, record: SessionRecord?, events: List<JournalEvent>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
        val day = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone)
        appendLine("GT7 Pitwall — journal de course")
        appendLine("Session : $id")
        events.firstOrNull()?.let { appendLine("Début : ${day.format(Instant.ofEpochMilli(it.timeMs))}") }
        record?.let { r ->
            appendLine("Issue : ${outcome(r.outcome)} ; ${r.laps.size} tour(s) mesuré(s) ; arrêts aux tours ${r.actualStops.ifEmpty { listOf("aucun") }.joinToString(", ")}")
            r.plannedStops?.let { appendLine("Plan initial : arrêts aux tours ${it.ifEmpty { listOf("aucun") }.joinToString(", ")}") }
        }
        appendLine()
        appendLine("À lire avant l'analyse :")
        LEGEND.forEach { appendLine("- $it") }
        record?.takeIf { it.laps.isNotEmpty() }?.let { r ->
            appendLine()
            appendLine("Tours (mesures GT7) :")
            for (l in r.laps) {
                val parts = listOfNotNull(
                    "T${l.lap}",
                    l.timeMs?.let { lapTime(it) } ?: "temps inconnu",
                    l.fuelUsed?.let { "carburant %.1f".format(it).replace('.', ',') },
                    if (l.refuelled) "ravitaillement" else null,
                    l.tcsShare?.let { "TCS ${Math.round(it * 100)} %" },
                    l.lockups?.let { "blocages $it" },
                    l.wheelspins?.let { "patinages $it" },
                    l.tyreCompound?.let { "pneus ${it.name} déclarés${if (l.tyreCertain) "" else " (incertain)"}, ${l.tyreAge ?: "?"} tour(s)" },
                )
                appendLine("  " + parts.joinToString(" · "))
            }
        }
        appendLine()
        appendLine("Chronologie :")
        for (e in events) {
            val lap = e.lap?.let { "T$it" } ?: "—"
            val pos = e.telemetry?.position?.let { " P$it" } ?: ""
            val line = buildString {
                append("[${clock.format(Instant.ofEpochMilli(e.timeMs))}] $lap$pos · ${e.type.label} · ${e.source.label} : ${e.text.replace('\n', ' ')}")
                e.decision?.let { append(" → décision : ${it.replace('\n', ' ')}") }
                if (e.details.isNotEmpty()) append(" {" + e.details.entries.joinToString("; ") { "${it.key}=${it.value.replace('\n', ' ')}" } + "}")
            }
            appendLine(line)
        }
    }

    private fun outcome(o: SessionOutcome) = when (o) {
        SessionOutcome.FINISHED -> "course terminée"
        SessionOutcome.INTERRUPTED -> "session interrompue"
        SessionOutcome.IN_PROGRESS -> "en cours ou application fermée brutalement"
    }

    fun lapTime(ms: Int): String = "%d:%02d.%03d".format(ms / 60_000, (ms / 1000) % 60, ms % 1000)
}
