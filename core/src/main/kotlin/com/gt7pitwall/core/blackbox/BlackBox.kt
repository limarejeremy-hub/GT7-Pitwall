package com.gt7pitwall.core.blackbox

import com.gt7pitwall.core.telemetry.TelemetryRecorder
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Boîte noire automatique (mission Black Box, 26/11) : la trace `.gt7trace` du recorder existant, rangée par session
 * de course et démarrée/arrêtée avec elle. Ce n'est PAS un second journal : chronologie, archive et radio restent dans
 * [com.gt7pitwall.core.race.SessionJournal] et l'archive des sessions ; elles sont réunies au moment du partage
 * ([BlackBoxPack]).
 *
 * - Activée volontairement par le pilote (réglage persistant, côté application) ; rien n'est envoyé, tout reste local.
 * - Réception UDP inchangée : [onFrame] est appelé par le branchement existant sur les paquets déchiffrés et ne bloque
 *   jamais (au pire quelques microsecondes de verrou au démarrage d'une session).
 * - Tous les paquets reçus sont gardés ([TelemetryRecorder.ALL_PACKETS]), y compris les [PRE_ROLL_MS] qui précèdent le
 *   début de la session (grille, départ).
 * - Segments de [SEGMENT_BYTES] : une course longue n'est jamais arrêtée par la taille d'un fichier ; seul le budget
 *   total [Limits.maxSessionBytes] l'arrête, et l'arrêt est toujours signalé ([listener]), jamais silencieux.
 * - Reprise : chaque session a un `meta.json` écrit dès le début ; une session trouvée « recording » au lancement suivant
 *   (fermeture brutale) est marquée « recovered » et reste partageable (trace tronquée lisible, vidée toutes les 2 s).
 * - Rétention : au plus [Limits.keepSessions] sessions et [Limits.maxTotalBytes] au total ; les plus anciennes partent,
 *   jamais la session en cours.
 */
class BlackBox(
    val root: File,
    @Volatile var limits: Limits = Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Fil unique des opérations de fichier (démarrage, arrêt, rétention) : jamais le fil de réception. */
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "black-box").apply { isDaemon = true } },
) {
    data class Limits(
        val keepSessions: Int = 10,
        val maxTotalBytes: Long = 2L * 1024 * 1_048_576,
        val maxSessionBytes: Long = 1024L * 1_048_576,
        val maxSessionDurationMs: Long = 30L * 3_600_000,
    )

    enum class State { RECORDING, FINISHED, STOPPED, RECOVERED }

    /** Ce qui est connu d'une session enregistrée (lu dans son `meta.json`). */
    data class Meta(
        val sessionId: String,
        val state: State,
        val startedAtMs: Long,
        val endedAtMs: Long?,
        val traceStartMs: Long?,
        val manual: Boolean,
        val stopReason: String?,
        val stats: JSONObject?,
        val dir: File,
    ) {
        val segments: List<File> get() = segmentFiles(dir)
        val bytes: Long get() = segments.sumOf { it.length() }
    }

    fun interface Listener {
        /** Événement à inscrire au journal de la session (démarrage, segment, arrêt anormal). */
        fun onEvent(sessionId: String, text: String, abnormal: Boolean)
    }

    @Volatile var listener: Listener? = null

    /** Le pilote a activé la boîte noire automatique (réglage persistant, côté application). */
    @Volatile var auto: Boolean = false
        set(value) {
            field = value
            if (!value && !manualArmed) synchronized(lock) { early.clear() }
        }

    /** Essai ou séance de test enregistré sur demande (la session suivante, ou celle en cours), boîte noire coupée. */
    @Volatile var manualArmed: Boolean = false
        private set

    val recorder = TelemetryRecorder(clock = clock, maxBytes = Long.MAX_VALUE, maxDurationMs = Long.MAX_VALUE)
    private val lock = Any()
    private val early = ArrayDeque<TelemetryRecorder.Early>()
    @Volatile var currentSessionId: String? = null
        private set
    private var currentManual = false
    private var watchdog: Thread? = null

    init {
        root.mkdirs()
        recover()
    }

    val armed: Boolean get() = auto || manualArmed

    /** Fil de réception : paquet déchiffré valide. Pré-enregistrement tant qu'aucune session n'enregistre. */
    fun onFrame(plain: ByteArray?, nowMs: Long) {
        if (plain == null || !armed) return
        if (recorder.running) {
            recorder.onFrame(plain, nowMs)
            return
        }
        synchronized(lock) {
            if (recorder.running) {
                recorder.onFrame(plain, nowMs)
                return
            }
            early.addLast(TelemetryRecorder.Early(nowMs, plain.copyOf()))
            while (early.isNotEmpty() && nowMs - early.first().atMs > PRE_ROLL_MS) early.removeFirst()
            while (early.size > PRE_ROLL_MAX) early.removeFirst()
        }
    }

    /** Une course (ou une séance) commence : enregistrement si la boîte noire est active ou armée à la main. */
    fun onSessionBegin(sessionId: String) {
        if (!armed) return
        val manual = !auto
        io.execute { startNow(sessionId, manual) }
    }

    /** La session se termine : la trace est finalisée (dernier segment fermé, `meta.json` à jour). */
    fun onSessionEnd(sessionId: String, finished: Boolean) {
        io.execute { stopNow(sessionId, if (finished) State.FINISHED else State.STOPPED, if (finished) "course terminée" else "session close") }
    }

    /** Télémétrie arrêtée ou application fermée proprement. */
    fun onTelemetryStopped() {
        io.execute {
            currentSessionId?.let { stopNow(it, State.STOPPED, "télémétrie arrêtée") }
            synchronized(lock) { early.clear() }
        }
    }

    /** Enregistrer un essai court ou une séance de test (boîte noire coupée) : la session en cours ou la suivante. */
    fun armManual(liveSessionId: String?) {
        manualArmed = true
        if (liveSessionId != null && currentSessionId == null) io.execute { startNow(liveSessionId, true) }
    }

    fun disarmManual() {
        manualArmed = false
        if (currentManual) currentSessionId?.let { id -> io.execute { stopNow(id, State.STOPPED, "arrêté par le pilote") } }
    }

    /** Attend que les opérations en file soient faites (tests, partage). */
    fun sync() {
        val done = java.util.concurrent.CountDownLatch(1)
        io.execute { done.countDown() }
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun startNow(sessionId: String, manual: Boolean) {
        if (currentSessionId == sessionId && recorder.running) return
        currentSessionId?.let { stopNow(it, State.STOPPED, "nouvelle session") }
        runCatching {
            val dir = sessionDir(sessionId).apply { mkdirs() }
            enforceRetention(keep = sessionId)
            val now = clock()
            writeMeta(dir, sessionId, State.RECORDING, now, null, null, manual, null, null)
            val sink = File(dir, segmentName(1)).outputStream().buffered(64 * 1024)
            synchronized(lock) {
                val pre = early.toList()
                early.clear()
                recorder.start(
                    sink, TelemetryRecorder.ALL_PACKETS,
                    rotate = { n ->
                        listener?.onEvent(sessionId, "Boîte noire : segment $n ouvert (course longue), enregistrement continu", false)
                        File(dir, segmentName(n)).outputStream().buffered(64 * 1024)
                    },
                    segmentBytes = SEGMENT_BYTES,
                    early = pre,
                )
            }
            currentSessionId = sessionId
            currentManual = manual
            writeMeta(dir, sessionId, State.RECORDING, now, null, recorder.traceStartMs, manual, null, null)
            listener?.onEvent(sessionId, "Boîte noire : enregistrement démarré (tous les paquets reçus" + (if (manual) ", essai demandé par le pilote" else "") + ")", false)
            startWatchdog(sessionId)
        }.onFailure {
            listener?.onEvent(sessionId, "Boîte noire : démarrage impossible (${it.javaClass.simpleName}) ; la course n'est PAS enregistrée", true)
        }
    }

    /** Surveille le budget de la session et les arrêts du recorder : jamais d'arrêt silencieux. */
    private fun startWatchdog(sessionId: String) {
        watchdog?.interrupt()
        watchdog = Thread({
            try {
                while (currentSessionId == sessionId) {
                    Thread.sleep(WATCH_MS)
                    if (currentSessionId != sessionId) break
                    val st = recorder.stats()
                    val reason = when {
                        !recorder.running -> st.stopReason ?: "arrêt inattendu"
                        st.writtenBytes > limits.maxSessionBytes -> "budget de la session atteint (${limits.maxSessionBytes / 1_048_576} Mo)"
                        st.durationMs > limits.maxSessionDurationMs -> "durée maximale atteinte (${limits.maxSessionDurationMs / 3_600_000} h)"
                        else -> null
                    }
                    if (reason != null) {
                        io.execute { stopNow(sessionId, State.STOPPED, reason, abnormal = true) }
                        break
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "black-box-watch").apply { isDaemon = true; start() }
    }

    private fun stopNow(sessionId: String, state: State, reason: String, abnormal: Boolean = false) {
        if (currentSessionId != sessionId) return
        currentSessionId = null
        // Jamais le fil courant (arrêt demandé par la surveillance elle-même) : l'interruption ferait échouer l'arrêt.
        watchdog?.takeIf { it !== Thread.currentThread() }?.interrupt()
        recorder.stop(reason)
        val st = recorder.stats()
        if (currentManual && state != State.RECORDING) manualArmed = false
        val dir = sessionDir(sessionId)
        val old = readMeta(dir)
        writeMeta(
            dir, sessionId, state, old?.startedAtMs ?: clock(), clock(), old?.traceStartMs, currentManual,
            st.stopReason ?: reason, statsJson(st),
        )
        val loss = st.dropped > 0
        listener?.onEvent(
            sessionId,
            "Boîte noire : enregistrement finalisé ($reason) : ${st.kept} paquets, ${"%.1f".format(java.util.Locale.FRANCE, st.writtenBytes / 1_048_576.0)} Mo" +
                (if (st.segments > 1) ", ${st.segments} segments" else "") +
                (if (loss) " ; ${st.dropped} paquet(s) perdus par Pitwall (disque trop lent)" else ""),
            abnormal || loss,
        )
        enforceRetention(keep = null)
    }

    /** Sessions enregistrées, la plus récente d'abord. */
    fun sessions(): List<Meta> = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull(::readMeta).sortedByDescending { it.startedAtMs }

    fun meta(sessionId: String): Meta? = readMeta(sessionDir(sessionId))

    fun sessionDir(sessionId: String) = File(root, safe(sessionId))

    /** Sessions laissées « en cours » par une fermeture brutale : marquées récupérées, la trace reste lisible. */
    private fun recover() {
        for (m in sessions()) {
            if (m.state == State.RECORDING) {
                writeMeta(m.dir, m.sessionId, State.RECOVERED, m.startedAtMs, m.segments.maxOfOrNull { it.lastModified() }, m.traceStartMs, m.manual,
                    "fermeture brutale de l'application : trace récupérée jusqu'au dernier vidage (2 s au plus perdues)", m.stats)
            }
        }
    }

    private fun enforceRetention(keep: String?) {
        val all = sessions().filter { it.sessionId != keep && it.sessionId != currentSessionId }
        val keepN = (limits.keepSessions - (if (keep != null) 1 else 0)).coerceAtLeast(0)
        val excess = all.drop(keepN).toMutableList()
        var total = sessions().sumOf { it.bytes } - excess.sumOf { it.bytes }
        for (m in all.take(keepN).reversed()) {
            if (total <= limits.maxTotalBytes) break
            excess += m
            total -= m.bytes
        }
        excess.forEach { it.dir.deleteRecursively() }
    }

    private fun readMeta(dir: File): Meta? = runCatching {
        val o = JSONObject(File(dir, META).readText())
        Meta(
            sessionId = o.getString("sessionId"),
            state = State.valueOf(o.getString("state")),
            startedAtMs = o.getLong("startedAtMs"),
            endedAtMs = if (o.has("endedAtMs")) o.getLong("endedAtMs") else null,
            traceStartMs = if (o.has("traceStartMs")) o.getLong("traceStartMs") else null,
            manual = o.optBoolean("manual"),
            stopReason = o.optString("stopReason", "").ifEmpty { null },
            stats = o.optJSONObject("stats"),
            dir = dir,
        )
    }.getOrNull()

    private fun writeMeta(dir: File, id: String, state: State, started: Long, ended: Long?, traceStart: Long?, manual: Boolean, reason: String?, stats: JSONObject?) {
        val o = JSONObject().put("format", 1).put("sessionId", id).put("state", state.name).put("startedAtMs", started)
            .put("manual", manual)
        ended?.let { o.put("endedAtMs", it) }
        traceStart?.let { o.put("traceStartMs", it) }
        reason?.let { o.put("stopReason", it) }
        stats?.let { o.put("stats", it) }
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(File(dir, META))) {
            File(dir, META).delete()
            tmp.renameTo(File(dir, META))
        }
    }

    companion object {
        const val META = "meta.json"
        const val PRE_ROLL_MS = 10_000L
        const val PRE_ROLL_MAX = 900
        const val SEGMENT_BYTES = 64L * 1_048_576
        const val WATCH_MS = 1_000L

        fun segmentName(n: Int) = "telemetry-%03d.gt7trace".format(n)

        fun segmentFiles(dir: File): List<File> =
            dir.listFiles { f -> f.name.startsWith("telemetry-") && f.name.endsWith(".gt7trace") }.orEmpty().sortedBy { it.name }

        private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }

        fun statsJson(st: TelemetryRecorder.Stats): JSONObject = JSONObject()
            .put("durationMs", st.durationMs).put("seen", st.seen).put("kept", st.kept).put("dropped", st.dropped)
            .put("decimated", st.decimated).put("lostUdp", st.lostUdp).put("pauses", st.pauses)
            .put("writtenBytes", st.writtenBytes).put("segments", st.segments)
            .put("p95Ms", st.p95Ms ?: JSONObject.NULL).put("p99Ms", st.p99Ms ?: JSONObject.NULL)
            .put("mode", if (st.hz == TelemetryRecorder.ALL_PACKETS) "tous les paquets" else "${st.hz} Hz")
    }
}
