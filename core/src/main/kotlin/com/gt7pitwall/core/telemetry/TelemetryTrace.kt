package com.gt7pitwall.core.telemetry

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/**
 * Trace télémétrique réelle (issue #41, P1 ; issue #36, Sprint D). La sonde Black Box ne faisait que MESURER le coût
 * d'une trace ; ceci l'enregistre, sur demande du pilote seulement.
 *
 * - Chaque échantillon garde le paquet GT7 **déchiffré tel quel** (296 à 368 octets) et son heure de réception : pédale,
 *   frein appliqué (0x13D), couple (0x154), roues, surfaces, position… restent alignés, et un décodeur futur pourra
 *   relire les mêmes octets. Aucune adresse réseau, aucun identifiant, aucun son.
 * - Fréquence choisie (20, 30 ou 60 Hz) : les autres paquets sont écartés, jamais la télémétrie de Pitwall.
 * - Le fil de réception ne fait que poser l'échantillon dans une file bornée : l'écriture compressée (gzip) se fait sur
 *   un fil à part. File pleine : l'échantillon est perdu et compté, le flux 60 Hz n'attend jamais le disque.
 * - Bornes : taille compressée et durée maximales ; l'enregistrement s'arrête seul et dit pourquoi.
 *
 * Format : en-tête `GT7PWTR1`, puis pour chaque échantillon : millisecondes depuis le début (Int), longueur (Short),
 * octets du paquet. Le tout compressé en gzip.
 */
class TelemetryRecorder(
    private val clock: () -> Long = System::currentTimeMillis,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
) {
    data class Stats(
        val running: Boolean,
        val hz: Int,
        val durationMs: Long,
        val seen: Long,
        val kept: Long,
        /** Échantillons perdus faute de place dans la file (disque trop lent) : doit rester à 0. */
        val dropped: Long,
        /** Paquets GT7 manquants d'après leur numéro (pertes réseau, avant Pitwall). */
        val lostUdp: Long,
        /** Silences de plus de [PAUSE_MS] (pause, menus, coupure Wi-Fi). */
        val pauses: Int,
        val writtenBytes: Long,
        /** Délai réception → écriture compressée, 95e et 99e centiles (ms). */
        val p95Ms: Int?,
        val p99Ms: Int?,
        val stopReason: String?,
    ) {
        val lines: List<String>
            get() = buildList {
                add((if (running) "Enregistrement en cours" else "Arrêté") + " : ${durationMs / 1000} s à $hz Hz, $kept échantillons sur $seen paquets.")
                add("Taille compressée : ${"%.1f".format(java.util.Locale.FRANCE, writtenBytes / 1_048_576.0)} Mo" +
                    (if (durationMs >= 60_000) ", soit ${"%.1f".format(java.util.Locale.FRANCE, writtenBytes / 1_048_576.0 * 3_600_000 / durationMs)} Mo/h" else "") + ".")
                add("Pertes réseau GT7 : $lostUdp paquet(s) ; perdus par Pitwall (file pleine) : $dropped ; silences > ${PAUSE_MS / 1000} s : $pauses.")
                if (p95Ms != null) add("Délai réception → disque : p95 $p95Ms ms, p99 ${p99Ms ?: "?"} ms (écriture, pas latence radio).")
                stopReason?.let { add("Arrêt : $it.") }
            }
    }

    private class Sample(val atMs: Long, val plain: ByteArray)

    private val queue = ArrayBlockingQueue<Sample>(QUEUE)
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var writer: Thread? = null
    @Volatile private var startMs = 0L
    @Volatile private var hz = 20
    @Volatile private var stopReason: String? = null
    @Volatile private var stopping = false
    private var lastKeptMs = Long.MIN_VALUE / 2
    private var lastFrameMs = Long.MIN_VALUE / 2
    private var lastPacketId = -1
    @Volatile private var seen = 0L
    @Volatile private var kept = 0L
    @Volatile private var dropped = 0L
    @Volatile private var lostUdp = 0L
    @Volatile private var pauses = 0
    private val counting = CountingStream()
    /** Délais réception → écriture, en ms (0 à 1 000). */
    private val delays = LongArray(1_001)
    @Volatile private var endMs: Long? = null

    val running: Boolean get() = out != null

    /** Démarre vers [sink] (fermé à l'arrêt). [hz] : 20, 30 ou 60. */
    @Synchronized
    fun start(sink: OutputStream, hz: Int = 20) {
        stop("relancé")
        this.hz = hz.coerceIn(1, 60)
        seen = 0; kept = 0; dropped = 0; lostUdp = 0; pauses = 0; stopReason = null; endMs = null; stopping = false
        lastKeptMs = Long.MIN_VALUE / 2; lastFrameMs = Long.MIN_VALUE / 2; lastPacketId = -1
        delays.fill(0)
        queue.clear()
        counting.reset(sink)
        val o = DataOutputStream(GZIPOutputStream(counting, 8_192))
        TelemetryTrace.writeHeader(o)
        out = o
        startMs = clock()
        writer = Thread({ drain(o) }, "telemetry-trace").apply { isDaemon = true; start() }
    }

    /** Fil de réception : un paquet déchiffré valide (`null` : illisible, ignoré). Ne bloque jamais. */
    fun onFrame(plain: ByteArray?, nowMs: Long) {
        if (out == null || plain == null || plain.size < GT7Crypto.PACKET_SIZE_A) return
        seen++
        if (nowMs - lastFrameMs > PAUSE_MS && lastFrameMs > 0) pauses++
        lastFrameMs = nowMs
        val id = (plain[0x70].toInt() and 0xFF) or ((plain[0x71].toInt() and 0xFF) shl 8) or
            ((plain[0x72].toInt() and 0xFF) shl 16) or ((plain[0x73].toInt() and 0xFF) shl 24)
        if (lastPacketId >= 0 && id > lastPacketId) lostUdp += (id - lastPacketId - 1).coerceAtMost(10_000)
        lastPacketId = id
        if (nowMs - startMs > maxDurationMs) {
            // Jamais d'attente sur le fil de réception : l'arrêt (qui vide la file) se fait à part.
            if (!stopping) {
                stopping = true
                Thread { stop("durée maximale atteinte (${maxDurationMs / 3_600_000} h)") }.start()
            }
            return
        }
        // Écart minimal entre deux échantillons, avec 2 ms de marge pour le rythme irrégulier du Wi-Fi.
        if (nowMs - lastKeptMs < 1_000 / hz - 2) return
        lastKeptMs = nowMs
        if (queue.offer(Sample(nowMs, plain.copyOf()))) kept++ else dropped++
    }

    private fun drain(o: DataOutputStream) {
        try {
            while (true) {
                val s = queue.poll(200, TimeUnit.MILLISECONDS)
                if (s == null) {
                    if (out !== o) break else continue
                }
                TelemetryTrace.writeFrame(o, (s.atMs - startMs).toInt(), s.plain)
                delays[(clock() - s.atMs).coerceIn(0, 1_000).toInt()]++
                if (counting.count > maxBytes && !stopping) {
                    stopping = true
                    Thread { stop("taille maximale atteinte (${maxBytes / 1_048_576} Mo)") }.start()
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: java.io.IOException) {
            stopReason = "écriture impossible (${e.javaClass.simpleName})"
            endMs = clock()
            out = null
        } finally {
            runCatching { o.close() }
        }
    }

    @Synchronized
    fun stop(reason: String = "arrêté par le pilote") {
        val o = out ?: return
        out = null
        endMs = clock()
        if (stopReason == null) stopReason = reason
        // Le fil d'écriture vide la file puis ferme le flux.
        val w = writer
        if (w != null && w !== Thread.currentThread()) w.join(5_000)
        runCatching { o.close() }
        writer = null
    }

    private fun centile(q: Double): Int? {
        val total = delays.sum()
        if (total == 0L) return null
        var acc = 0L
        val target = (total * q).toLong().coerceAtLeast(1)
        for (i in delays.indices) {
            acc += delays[i]
            if (acc >= target) return i
        }
        return delays.size - 1
    }

    fun stats(): Stats = Stats(
        running, hz, ((endMs ?: clock()) - startMs).coerceAtLeast(0).takeIf { startMs > 0 } ?: 0,
        seen, kept, dropped, lostUdp, pauses, counting.count, centile(0.95), centile(0.99), stopReason,
    )

    /** Compte les octets compressés réellement écrits. */
    private class CountingStream : OutputStream() {
        private var sink: OutputStream? = null
        @Volatile var count = 0L
            private set

        fun reset(s: OutputStream) {
            sink = s
            count = 0
        }

        override fun write(b: Int) {
            sink!!.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            sink!!.write(b, off, len)
            count += len
        }

        override fun flush() = sink!!.flush()
        override fun close() = sink!!.close()
    }

    companion object {
        val MAGIC = "GT7PWTR1".toByteArray(Charsets.US_ASCII)
        const val QUEUE = 1_024
        const val PAUSE_MS = 2_000L
        const val DEFAULT_MAX_BYTES = 80L * 1_048_576
        const val DEFAULT_MAX_DURATION_MS = 4L * 3_600_000
    }
}

/**
 * Lecture et relecture d'une trace, **en flux** (revue PR #44) : un seul échantillon en mémoire à la fois. Une trace de
 * 4 h à 20 Hz fait ~288 000 échantillons, soit plus de 100 Mo une fois décompressée : la charger d'un bloc pourrait
 * saturer la mémoire du Fold.
 */
object TelemetryTrace {
    class Frame(val atMs: Int, val plain: ByteArray)

    fun writeHeader(out: java.io.DataOutput) = out.write(TelemetryRecorder.MAGIC)

    fun writeFrame(out: java.io.DataOutput, atMs: Int, plain: ByteArray) {
        out.writeInt(atMs)
        out.writeShort(plain.size)
        out.write(plain)
    }

    /**
     * Parcourt la trace échantillon par échantillon ; renvoie le nombre lu. Une fin tronquée (téléphone coupé) garde
     * les échantillons complets. [block] renvoie false pour arrêter la lecture.
     */
    fun stream(input: InputStream, block: (Frame) -> Boolean): Int {
        var n = 0
        DataInputStream(GZIPInputStream(input, 16 * 1024).buffered(16 * 1024)).use { d ->
            val magic = ByteArray(TelemetryRecorder.MAGIC.size)
            d.readFully(magic)
            require(magic.contentEquals(TelemetryRecorder.MAGIC)) { "pas une trace GT7 Pitwall" }
            try {
                while (true) {
                    val at = d.readInt()
                    val len = d.readUnsignedShort()
                    if (len !in GT7Crypto.PACKET_SIZE_A..MAX_FRAME) break
                    val b = ByteArray(len)
                    d.readFully(b)
                    n++
                    if (!block(Frame(at, b))) break
                }
            } catch (_: EOFException) {
            } catch (_: java.util.zip.ZipException) {
            }
        }
        return n
    }

    /** Lit toute la trace en mémoire : réservé aux petites traces (tests, diagnostic). La relecture passe par [replay]. */
    fun read(input: InputStream): List<Frame> = mutableListOf<Frame>().also { l -> stream(input) { l += it; true } }

    /** Horloge virtuelle de relecture : l'heure enregistrée de chaque échantillon, pas l'heure du téléphone. */
    class VirtualClock(var nowMs: Long = 0L) {
        fun now(): Long = nowMs
    }

    data class Replay(val frames: Int, val decoded: Int, val formats: Map<String, Int>, val durationMs: Int, val gapsOver500Ms: Int) {
        val lines: List<String>
            get() = listOf(
                "Trace relue : $frames échantillons sur ${durationMs / 1000} s, $decoded décodés.",
                "Formats : " + formats.entries.joinToString(", ") { "${it.key} × ${it.value}" } + ".",
                "Trous de plus de 0,5 s dans la trace : $gapsOver500Ms.",
            )
    }

    /**
     * Rejoue la trace hors course, en flux : avant chaque paquet, [clock] prend l'heure enregistrée ([startMs] + écart),
     * puis le paquet décodé est donné à [onPacket]. Un contrôleur construit avec `clock = clock::now` voit donc le même
     * rythme qu'en course, même si la relecture va plus vite. Jamais pendant une course : l'appelant le vérifie.
     */
    fun replay(
        input: InputStream,
        clock: VirtualClock = VirtualClock(),
        startMs: Long = clock.nowMs,
        onPacket: (TelemetryPacket) -> Unit = {},
    ): Replay {
        var decoded = 0
        var gaps = 0
        var last: Int? = null
        val formats = sortedMapOf<String, Int>()
        val n = stream(input) { f ->
            last?.let { if (f.atMs - it > 500) gaps++ }
            last = f.atMs
            clock.nowMs = startMs + f.atMs
            TelemetryParser.parseDecrypted(f.plain)?.let { p ->
                decoded++
                formats.merge(p.format.label, 1, Int::plus)
                onPacket(p)
            }
            true
        }
        return Replay(n, decoded, formats, last ?: 0, gaps)
    }

    /** Taille par heure annoncée à partir d'une trace (Mo/h), pour la doc et l'Atelier. */
    fun mbPerHour(bytes: Long, durationMs: Long): Double? =
        if (durationMs < 60_000) null else (bytes / 1_048_576.0 * 3_600_000 / durationMs * 10).roundToInt() / 10.0

    /** Taille maximale d'un échantillon relu (les formats GT7 connus font au plus 368 octets). */
    const val MAX_FRAME = 1_024
}
