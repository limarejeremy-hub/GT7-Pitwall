package com.gt7pitwall.core.blackbox

import com.gt7pitwall.core.race.JournalEvent
import com.gt7pitwall.core.race.JournalExport
import com.gt7pitwall.core.race.JournalJson
import com.gt7pitwall.core.race.JournalSource
import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.race.SessionJournal
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.telemetry.TelemetryParser
import com.gt7pitwall.core.telemetry.TelemetryTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pack de course « Black Box » : UN fichier ZIP standard qui réunit tout ce que Pitwall sait d'une course (ou d'une
 * séance de plusieurs courses), lisible par une IA sans décoder le binaire, et rejouable dans Pitwall Lab.
 *
 * Écrit en flux (la trace n'est jamais chargée en mémoire), puis vérifié en le rouvrant ([verify]). Chaque fichier est
 * listé dans `manifest.json` avec sa taille et son SHA-256. Format documenté dans `docs/BLACK_BOX.md`.
 *
 * Rien n'est inventé : chaque ligne porte une nature (MESURE, CALCUL, ESTIMATION, DECLARATION, SIMULATION, INCONNU) et
 * ce qui manque est listé explicitement. Aucun secret : les textes sont re-masqués ([SessionJournal.redact]) et vérifiés.
 */
object BlackBoxPack {
    const val FORMAT = "gt7pitwall-blackbox"
    const val FORMAT_VERSION = 1

    /** Fichiers obligatoires de chaque course (même vides : leur absence de contenu est dite dans README et manifeste). */
    val REQUIRED = listOf("race.json", "timeline.jsonl", "strategy.jsonl", "radio.jsonl", "diagnostics.json")
    const val TRACE = "telemetry.gt7trace"

    enum class Nature(val label: String) {
        MESURE("lu tel quel dans les paquets GT7"),
        CALCUL("produit par le moteur Pitwall à partir des mesures et déclarations"),
        ESTIMATION("valeur approchée par Pitwall (incertitude non nulle)"),
        DECLARATION("dit ou saisi par le pilote (transcription ou écran)"),
        SIMULATION("recalculé hors course (rejeu, Lab) : jamais un message réellement émis en course"),
        INCONNU("origine non établie"),
    }

    /** Une course à mettre dans le pack. Les textes viennent du journal et de l'archive existants, jamais recalculés. */
    class Course(
        val sessionId: String,
        val record: SessionRecord?,
        val events: List<JournalEvent>,
        /** Segments `.gt7trace` dans l'ordre (vide : boîte noire inactive pour cette course). */
        val traceSegments: List<File> = emptyList(),
        val meta: BlackBox.Meta? = null,
        val carName: String? = null,
        val trackName: String? = null,
    )

    /** Informations techniques fournies par l'application (liste blanche : aucun secret, aucune adresse). */
    class Device(
        val appVersion: String,
        val appBuild: String,
        val phone: String?,
        val android: String?,
        val settings: Map<String, String> = emptyMap(),
        val experimental: Map<String, String> = emptyMap(),
        val udp: Map<String, String> = emptyMap(),
    )

    data class Result(val file: File, val bytes: Long, val durationMs: Long, val missing: List<String>, val verification: Verification)

    data class Verification(val ok: Boolean, val problems: List<String>, val files: Int, val frames: Map<String, Int>) {
        val status: String get() = if (ok) "vérifié (contenu et empreintes SHA-256 conformes)" else "ÉCHEC : " + problems.joinToString(" ; ")
    }

    // ------------------------------------------------------------------------------------------------ nom du fichier

    /** « Pitwall_2026-11-26_LeMans_Course1.zip » ; séance : « Pitwall_2026-11-26_Seance_3courses.zip ». */
    fun fileName(startMs: Long, track: String?, kind: String, index: Int, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(Instant.ofEpochMilli(startMs))
        return "Pitwall_${day}_${slug(track ?: "Circuit")}_$kind$index.zip"
    }

    fun seanceName(startMs: Long, courses: Int, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(Instant.ofEpochMilli(startMs))
        return "Pitwall_${day}_Seance_${courses}courses.zip"
    }

    fun slug(s: String): String {
        val plain = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val words = plain.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        val joined = words.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        return joined.take(40).ifEmpty { "Circuit" }
    }

    /** « Course », « Qualif » ou « Essai » selon l'épreuve archivée. */
    fun kindLabel(c: Course): String = when {
        c.meta?.manual == true -> "Essai"
        c.record?.context?.profile?.id?.kind == SessionKind.QUALIFYING -> "Qualif"
        c.record == null -> "Session"
        else -> "Course"
    }

    // ------------------------------------------------------------------------------------------------ écriture

    /**
     * Écrit le pack dans [target] (fichier temporaire puis renommage : jamais de pack à moitié écrit), le rouvre et le
     * vérifie. Une seule course : fichiers à la racine. Plusieurs : un dossier par course, README et manifeste communs.
     */
    fun write(target: File, courses: List<Course>, device: Device, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Result {
        require(courses.isNotEmpty()) { "aucune course à exporter" }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val files = JSONArray()
        val sessionsJson = JSONArray()
        val allMissing = mutableListOf<String>()
        val summaries = mutableListOf<CourseSummary>()
        ZipOutputStream(tmp.outputStream().buffered(64 * 1024)).use { zip ->
            val single = courses.size == 1
            courses.forEachIndexed { i, c ->
                val folder = if (single) "" else "course-%02d_%s/".format(i + 1, c.sessionId.filter { it.isLetterOrDigit() || it == '-' })
                val s = writeCourse(zip, folder, c, device, files, zone)
                summaries += s
                allMissing += s.missing.map { if (single) it else "course ${i + 1} : $it" }
                sessionsJson.put(s.json.put("folder", folder.ifEmpty { "." }))
            }
            val readme = readme(summaries, courses.size > 1, device, nowMs, zone, files)
            put(zip, "README.md", readme.toByteArray(), files, Nature.CALCUL, "résumé lisible et inventaire")
            val manifest = JSONObject()
                .put("format", FORMAT).put("formatVersion", FORMAT_VERSION)
                .put("kind", if (courses.size > 1) "seance" else "course")
                .put("createdAt", Instant.ofEpochMilli(nowMs).toString())
                .put("generator", JSONObject().put("application", "GT7 Pitwall").put("version", device.appVersion).put("build", device.appBuild))
                .put("sessions", sessionsJson)
                .put("files", files)
                .put("missing", JSONArray(allMissing))
                .put("natures", JSONObject(Nature.entries.associate { it.name to it.label }))
                .put("timeBase", "t_ms = millisecondes depuis le début de la session (horloge du téléphone, même horloge pour la trace et le journal) ; seq = ordre d'écriture du journal")
                .put("compatibility", "telemetry.gt7trace = format GT7PWTR1 inchangé (docs/TELEMETRY_TRACE.md), rejouable par Pitwall Lab ; ZIP standard")
                .put("integrity", "SHA-256 de chaque fichier ci-dessus ; manifest.json lui-même exclu")
                .put("privacy", "données locales partagées volontairement ; aucun son, aucune clé API, aucun jeton, aucune adresse réseau")
            val m = manifest.toString(2).toByteArray()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(m)
            zip.closeEntry()
        }
        val v = verify(tmp)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return Result(target, target.length(), summaries.sumOf { it.durationMs }, allMissing, v)
    }

    private class CourseSummary(
        val sessionId: String,
        val title: String,
        val durationMs: Long,
        val missing: List<String>,
        val lines: List<String>,
        val json: JSONObject,
    )

    private fun writeCourse(zip: ZipOutputStream, folder: String, c: Course, device: Device, files: JSONArray, zone: ZoneId): CourseSummary {
        val r = c.record
        val events = c.events.sortedBy { it.seq }
        val missing = mutableListOf<String>()
        val begin = events.firstOrNull { it.type == JournalType.SESSION }
        val t0 = begin?.timeMs ?: r?.startedAtMs ?: c.meta?.traceStartMs ?: c.meta?.startedAtMs ?: 0L
        val traceStart = c.meta?.traceStartMs ?: c.meta?.startedAtMs

        // A. Télémétrie brute : copie des segments (concaténation = une seule trace valide), puis lecture unique.
        val segments = c.traceSegments.filter { it.isFile && it.length() > 0 }
        var scan: TraceScan? = null
        if (segments.isNotEmpty() && traceStart != null) {
            zip.setLevel(Deflater.NO_COMPRESSION) // déjà compressée (gzip)
            putStream(zip, folder + TRACE, concat(segments), files, Nature.MESURE, "télémétrie originale GT7 (paquets déchiffrés + heure de réception), rejouable")
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            val positionsAt = events.map { it.timeMs }.sorted()
            scan = TraceScan(traceStart, t0, positionsAt)
            val csv = DigestingEntry(zip, folder + "telemetry_10hz.csv")
            csv.out.write(TraceScan.CSV_HEADER.toByteArray())
            runCatching { concat(segments).use { scan.run(it, csv.out) } }.onFailure { missing += "trace illisible au-delà de ${scan.frames} échantillons (${it.javaClass.simpleName})" }
            csv.finish(files, Nature.MESURE, "télémétrie lisible à 10 Hz (une ligne toutes les 100 ms), extraite de la trace")
        } else {
            missing += "télémétrie brute absente : boîte noire inactive pendant cette course (journal et archive seuls)"
        }
        when (c.meta?.state) {
            BlackBox.State.RECOVERED -> missing += "trace récupérée après une fermeture brutale : les 2 dernières secondes au plus manquent"
            BlackBox.State.RECORDING -> missing += "enregistrement encore en cours au moment du partage : trace partielle"
            else -> {}
        }
        c.meta?.stats?.let { st ->
            if (st.optLong("dropped") > 0) missing += "${st.optLong("dropped")} paquet(s) reçus mais perdus par Pitwall (disque trop lent)"
            if (st.optLong("decimated") > 0) missing += "${st.optLong("decimated")} paquet(s) écartés par l'échantillonnage choisi"
        }
        c.meta?.stopReason?.takeIf { c.meta.state == BlackBox.State.STOPPED && ("budget" in it || "durée" in it || "impossible" in it) }
            ?.let { missing += "enregistrement arrêté avant la fin : $it" }
        scan?.let { s ->
            if (s.lostUdp > 0) missing += "${s.lostUdp} paquet(s) GT7 jamais reçus (pertes Wi-Fi d'après les numéros de paquet)"
            if (s.gaps.isNotEmpty()) missing += "${s.gaps.size} coupure(s) de flux de plus de 0,5 s (la plus longue : ${s.gaps.maxOf { it.second } / 1000.0} s)"
        }
        if (events.isEmpty()) missing += "journal de course absent"
        if (r == null) missing += "archive de la course absente (aucun tour bouclé)"
        missing += "secteurs : non transmis par GT7, non calculés"
        missing += "pénalités : GT7 ne transmet aucun champ de pénalité ; seules les déclarations du pilote et les révisions du classement après la ligne sont connues"
        missing += "version de GT7 : non transmise par la PS5"
        missing += "aucun son ni vidéo : seulement les transcriptions de la reconnaissance vocale"

        // B-E. Course.
        val race = raceJson(c, scan, events, t0)
        put(zip, folder + "race.json", race.toString(2).toByteArray(), files, Nature.CALCUL, "déroulement et résultat (voiture, circuit, règlement, tours, stands, pneus, positions)")
        r?.takeIf { it.laps.isNotEmpty() }?.let { put(zip, folder + "laps.csv", lapsCsv(it).toByteArray(), files, Nature.MESURE, "un tour par ligne (archive)") }

        // 4. Chronologie commune.
        val timeline = timeline(c.sessionId, events, scan, t0)
        put(zip, folder + "timeline.jsonl", timeline.joinToString("\n", postfix = "\n") { it.toString() }.toByteArray(), files, Nature.CALCUL,
            "chronologie commune (journal + événements mesurés dans la trace), triée par t_ms")
        put(zip, folder + "timeline.csv", timelineCsv(timeline).toByteArray(), files, Nature.CALCUL, "même chronologie, en tableau")

        // F. Stratégie et radio.
        val strat = strategy(c, events, t0)
        put(zip, folder + "strategy.jsonl", strat.joinToString("\n", postfix = if (strat.isEmpty()) "" else "\n") { it.toString() }.toByteArray(), files, Nature.CALCUL,
            "décisions de stratégie, plans successifs, verdicts V515, carburant")
        val radio = radio(events, t0)
        put(zip, folder + "radio.jsonl", radio.joinToString("\n", postfix = if (radio.isEmpty()) "" else "\n") { it.toString() }.toByteArray(), files, Nature.CALCUL,
            "questions du pilote (transcriptions) et réponses de Véga réellement émises en course")
        if (strat.isEmpty()) missing += "aucune décision de stratégie journalisée"
        if (radio.isEmpty()) missing += "aucun échange radio journalisé"

        // G. Diagnostics.
        val diag = diagnostics(c, device, scan, events)
        put(zip, folder + "diagnostics.json", diag.toString(2).toByteArray(), files, Nature.MESURE, "état technique et qualité des mesures")

        val duration = scan?.durationMs?.toLong()
            ?: ((events.lastOrNull()?.timeMs ?: t0) - t0).coerceAtLeast(0)
        val title = listOfNotNull(c.carName, c.trackName ?: begin?.text?.substringAfter(" — ", "")?.substringBefore(" — ")?.ifEmpty { null }).joinToString(" — ")
            .ifEmpty { "Session ${c.sessionId}" }
        val lines = summaryLines(c, scan, race, strat.size, radio)
        val json = JSONObject().put("sessionId", c.sessionId).put("title", title).put("durationMs", duration)
            .put("outcome", r?.outcome?.name ?: "INCONNU").put("laps", r?.laps?.size ?: 0)
            .put("frames", scan?.frames ?: 0).put("blackBox", c.meta?.state?.name ?: "absente")
        return CourseSummary(c.sessionId, title, duration, missing, lines, json)
    }

    // ------------------------------------------------------------------------------------------------ analyse de trace

    /**
     * Lecture unique de la trace : CSV 10 Hz, qualité du flux, changements de classement, immobilisations et
     * ravitaillements mesurés, températures des pneus par tour, et position (x, z) au moment de chaque événement du journal.
     */
    class TraceScan(private val traceStartWallMs: Long, private val t0: Long, eventWallTimes: List<Long>) {
        var frames = 0; private set
        var decoded = 0; private set
        var durationMs = 0; private set
        var lostUdp = 0L; private set
        var outOfOrder = 0; private set
        var firstId: Int? = null; private set
        var lastId: Int? = null; private set
        val formats = sortedMapOf<String, Int>()
        /** (t_ms depuis le début de session, durée ms). */
        val gaps = mutableListOf<Pair<Long, Int>>()
        val positionChanges = mutableListOf<JSONObject>()
        val stops = mutableListOf<JSONObject>()
        val refuels = mutableListOf<JSONObject>()
        val lapMarks = mutableListOf<JSONObject>()
        /** Par tour : somme et max des 4 températures, nombre d'échantillons, échantillons à fort glissement. */
        private val tyre = sortedMapOf<Int, DoubleArray>()
        private val eventTimes = eventWallTimes
        private var nextEvent = 0
        /** Position mesurée la plus proche de chaque instant du journal (heure murale → x, z, vitesse). */
        val positionAt = HashMap<Long, Triple<Float, Float, Float>>()

        private var lastAt: Int? = null
        private var lastCsv = Int.MIN_VALUE
        private var lastPos = 0
        private var lastCars = 0
        private var lastLap = -1
        private var stillSince: Int? = null
        private var stillFuel = 0f
        private var stillX = 0f
        private var stillZ = 0f
        private var stillLap = 0
        private var refuelStart: Int? = null
        private var refuelFrom = 0f
        private var lastFuel: Float? = null
        private var lastRise: Int = 0
        private var lastX = 0f
        private var lastZ = 0f
        private var lastSpeed = 0f
        private var inPitPhase = false

        private fun t(atMs: Int): Long = traceStartWallMs + atMs - t0

        fun run(input: InputStream, csv: OutputStream) {
            val w = csv.bufferedWriter(Charsets.UTF_8)
            TelemetryTrace.stream(input) { f ->
                frames++
                lastAt?.let { if (f.atMs - it > 500) gaps += t(it) to (f.atMs - it) }
                lastAt = f.atMs
                durationMs = f.atMs
                val p = TelemetryParser.parseDecrypted(f.plain) ?: return@stream true
                decoded++
                formats.merge(p.format.label, 1, Int::plus)
                lastId?.let { prev ->
                    if (p.packetId > prev + 1) lostUdp += (p.packetId - prev - 1).coerceAtMost(10_000).toLong()
                    if (p.packetId <= prev) outOfOrder++
                }
                if (firstId == null) firstId = p.packetId
                lastId = p.packetId
                val wall = traceStartWallMs + f.atMs
                while (nextEvent < eventTimes.size && eventTimes[nextEvent] <= wall) {
                    if (wall - eventTimes[nextEvent] <= 1_000) positionAt[eventTimes[nextEvent]] = Triple(p.positionX, p.positionZ, p.speedKmh)
                    nextEvent++
                }
                lastX = p.positionX; lastZ = p.positionZ; lastSpeed = p.speedKmh
                val racing = p.currentLap >= 1 && !p.isLoading
                // Tours.
                if (p.currentLap != lastLap) {
                    if (lastLap >= 0 && p.currentLap > lastLap) lapMarks += JSONObject().put("t_ms", t(f.atMs)).put("tour", p.currentLap)
                        .put("tours", p.totalLaps).put("dernier_tour_ms", p.lastLapMs.takeIf { it > 0 } ?: JSONObject.NULL)
                        .put("carburant", r1(p.fuelLevel)).put("x", r1(p.positionX)).put("z", r1(p.positionZ))
                    lastLap = p.currentLap
                }
                // Changements de classement (0x84), mesurés ; leur cause n'est jamais transmise par GT7.
                val pos = p.preRacePosition
                val cars = p.preRaceCarCount
                if (racing && pos in 1..cars && cars > 0) {
                    if (lastPos > 0 && pos != lastPos && positionChanges.size < MAX_EVENTS) {
                        positionChanges += JSONObject().put("t_ms", t(f.atMs)).put("tour", p.currentLap).put("avant", lastPos).put("apres", pos)
                            .put("voitures", cars).put("x", r1(p.positionX)).put("z", r1(p.positionZ)).put("vitesse_kmh", r1(p.speedKmh))
                            .put("pendant_arret", inPitPhase || stillSince != null || refuelStart != null)
                    }
                    lastPos = pos
                    lastCars = cars
                }
                // Immobilisations (≥ 2 s) et ravitaillements (carburant qui monte) : MESURE.
                val still = racing && p.speedKmh < 1f
                if (still && stillSince == null) { stillSince = f.atMs; stillFuel = p.fuelLevel; stillX = p.positionX; stillZ = p.positionZ; stillLap = p.currentLap }
                if (!still && stillSince != null) {
                    val d = f.atMs - stillSince!!
                    if (d >= 2_000 && stops.size < MAX_EVENTS) stops += JSONObject().put("t_ms", t(stillSince!!)).put("tour", stillLap).put("duree_ms", d)
                        .put("carburant_avant", r1(stillFuel)).put("carburant_apres", r1(p.fuelLevel)).put("x", r1(stillX)).put("z", r1(stillZ))
                    stillSince = null
                }
                lastFuel?.let { prev ->
                    if (p.fuelCapacity > 0f && p.fuelLevel > prev + 0.0001f && p.speedKmh < 20f) {
                        if (refuelStart == null) { refuelStart = f.atMs; refuelFrom = prev }
                        lastRise = f.atMs
                    } else if (refuelStart != null && (f.atMs - lastRise > 1_000 || p.speedKmh >= 20f)) {
                        if (refuels.size < MAX_EVENTS) refuels += JSONObject().put("t_ms", t(refuelStart!!)).put("tour", p.currentLap)
                            .put("duree_ms", lastRise - refuelStart!!).put("carburant_avant", r1(refuelFrom)).put("carburant_apres", r1(prev))
                            .put("ajoute", r1(prev - refuelFrom)).put("debit_par_s", if (lastRise > refuelStart!!) r2((prev - refuelFrom) * 1000f / (lastRise - refuelStart!!)) else JSONObject.NULL)
                        refuelStart = null
                    }
                }
                lastFuel = p.fuelLevel
                inPitPhase = racing && p.speedKmh < 85f && (stillSince != null || refuelStart != null || inPitPhase && p.speedKmh < 85f)
                // Pneus par tour.
                if (racing) {
                    val a = tyre.getOrPut(p.currentLap) { DoubleArray(10) }
                    val temps = p.tyreTemps.toList()
                    temps.forEachIndexed { i, v -> a[i] += v.toDouble(); a[4 + i] = maxOf(a[4 + i], v.toDouble()) }
                    a[8] += 1.0
                    val slip = p.wheelSlip
                    if (slip != null && p.speedKmh > 30f && slip.toList().any { abs(it - 1f) > 0.1f }) a[9] += 1.0
                }
                // CSV 10 Hz.
                if (f.atMs - lastCsv >= 100) {
                    lastCsv = f.atMs
                    val slip = p.wheelSlip?.toList()
                    w.write(listOf(
                        t(f.atMs), p.packetId, p.format.label, p.currentLap, p.totalLaps, pos, cars, r1(p.speedKmh), p.throttle, p.brake,
                        p.currentGear, p.engineRpm.roundToInt(), r2(p.fuelLevel), r1(p.fuelCapacity),
                        r1(p.tyreTempFrontLeft), r1(p.tyreTempFrontRight), r1(p.tyreTempRearLeft), r1(p.tyreTempRearRight),
                        slip?.get(0)?.let(::r2) ?: "", slip?.get(1)?.let(::r2) ?: "", slip?.get(2)?.let(::r2) ?: "", slip?.get(3)?.let(::r2) ?: "",
                        r1(p.positionX), r1(p.positionY), r1(p.positionZ), if (p.isOnTrack) 1 else 0, if (p.isPaused) 1 else 0,
                        p.currentLapTimeMs ?: "",
                    ).joinToString(","))
                    w.write("\n")
                }
                true
            }
            w.flush()
        }

        /** Températures moyennes et maximales par tour (MESURE), part d'échantillons à fort glissement (CALCUL). */
        fun tyreLaps(): JSONArray = JSONArray(tyre.map { (lap, a) ->
            val n = a[8].coerceAtLeast(1.0)
            JSONObject().put("tour", lap).put("echantillons", a[8].toInt())
                .put("temp_moy", JSONArray((0..3).map { r1((a[it] / n).toFloat()) }))
                .put("temp_max", JSONArray((4..7).map { r1(a[it].toFloat()) }))
                .put("part_glissement_10pc", r2((a[9] / n).toFloat()))
        })

        fun quality(): JSONObject = JSONObject()
            .put("echantillons", frames).put("decodes", decoded).put("illisibles", frames - decoded)
            .put("formats", JSONObject(formats)).put("duree_s", durationMs / 1000)
            .put("frequence_mesuree_hz", if (durationMs > 0) r1(decoded * 1000f / durationMs) else JSONObject.NULL)
            .put("premier_paquet", firstId ?: JSONObject.NULL).put("dernier_paquet", lastId ?: JSONObject.NULL)
            .put("paquets_gt7_manquants", lostUdp).put("paquets_hors_ordre", outOfOrder)
            .put("coupures_plus_500ms", JSONArray(gaps.take(MAX_EVENTS).map { JSONObject().put("t_ms", it.first).put("duree_ms", it.second) }))

        companion object {
            const val MAX_EVENTS = 2_000
            const val CSV_HEADER = "t_ms,paquet,format,tour,tours,position,voitures,vitesse_kmh,accelerateur,frein,rapport,regime," +
                "carburant,reservoir,temp_avg,temp_avd,temp_arg,temp_ard,glis_avg,glis_avd,glis_arg,glis_ard,x,y,z,en_piste,pause,chrono_tour_ms\n"
        }
    }

    // ------------------------------------------------------------------------------------------------ contenus

    private fun raceJson(c: Course, scan: TraceScan?, events: List<JournalEvent>, t0: Long): JSONObject {
        val r = c.record
        val o = JSONObject().put("sessionId", c.sessionId)
            .put("legende", "Chaque bloc porte sa nature. Un gain de position ne prouve PAS un dépassement en piste (arrêt ou abandon adverse possibles).")
        o.put("voiture", JSONObject().put("nature", Nature.MESURE.name).put("code_gt7", r?.context?.carCode ?: JSONObject.NULL).put("nom", c.carName ?: JSONObject.NULL))
        o.put("circuit", JSONObject().put("nature", Nature.DECLARATION.name).put("id", r?.context?.rules?.trackId ?: JSONObject.NULL).put("nom", c.trackName ?: JSONObject.NULL)
            .put("note", "circuit choisi ou reconnu par Pitwall ; GT7 ne transmet pas son nom"))
        r?.let { rec ->
            val p = rec.context.profile
            o.put("epreuve", JSONObject().put("serie", p.id.series.name).put("type", p.id.kind.name).put("mode", p.id.mode.name).put("libelle", p.id.label))
            o.put("reglement", JSONObject().put("nature", Nature.DECLARATION.name)
                .put("tours_gt7", rec.context.gameTotalLaps ?: JSONObject.NULL).put("tours_reglement", rec.context.rules.totalLaps ?: JSONObject.NULL)
                .put("duree_min", rec.context.rules.durationMin ?: JSONObject.NULL)
                .put("multiplicateur_carburant", rec.context.rules.fuelMultiplier).put("multiplicateur_pneus", rec.context.rules.tyreMultiplier)
                .put("arrets_obligatoires", rec.context.rules.mandatoryStops)
                .put("source", events.firstOrNull { it.type == JournalType.SESSION }?.details?.get("source_reglement") ?: JSONObject.NULL)
                .put("contradiction", rec.rulesQuarantine ?: JSONObject.NULL))
            o.put("issue", rec.outcome.name)
            o.put("depart", JSONObject().put("nature", Nature.MESURE.name).put("grille", rec.gridPosition ?: JSONObject.NULL))
            o.put("arrivee", JSONObject()
                .put("ligne", JSONObject().put("nature", Nature.MESURE.name).put("position", rec.lineFinishPosition ?: JSONObject.NULL).put("voitures", rec.lineFinishCars ?: JSONObject.NULL))
                .put("classement_archive", JSONObject().put("nature", Nature.CALCUL.name).put("position", rec.finishPosition ?: JSONObject.NULL)
                    .put("voitures", rec.finishCars ?: JSONObject.NULL).put("consolide", rec.finishConsolidated))
                .put("derniere_position_en_course", JSONObject().put("nature", Nature.MESURE.name).put("position", rec.lastRacePosition ?: JSONObject.NULL)
                    .put("voitures", rec.lastRaceCars ?: JSONObject.NULL).put("tour", rec.lastRaceLap ?: JSONObject.NULL)))
            o.put("tours", JSONObject().put("nature", Nature.MESURE.name).put("liste", JSONArray(rec.laps.map { l ->
                JSONObject().put("tour", l.lap).put("temps_ms", l.timeMs ?: JSONObject.NULL).put("temps", l.timeMs?.let(JournalExport::lapTime) ?: JSONObject.NULL)
                    .put("carburant_consomme", l.fuelUsed ?: JSONObject.NULL).put("carburant_fin", l.fuelAfter ?: JSONObject.NULL)
                    .put("ravitaillement", l.refuelled).put("voie_des_stands", l.pitLane)
            })))
        } ?: o.put("issue", "INCONNU")
        o.put("secteurs", JSONObject().put("nature", Nature.INCONNU.name).put("note", "GT7 ne transmet pas les temps de secteur"))
        o.put("penalites", JSONObject().put("nature", Nature.INCONNU.name)
            .put("note", "GT7 ne transmet aucun champ de pénalité ; ci-dessous : révisions du classement après la ligne (cause non transmise) et déclarations du pilote")
            .put("revisions_apres_ligne", JSONArray(r?.postFinishChanges.orEmpty().map { it.text }))
            .put("declarations", JSONArray(events.filter { (it.source == JournalSource.TRANSCRIPT || it.source == JournalSource.SCREEN) && "pénal" in it.text.lowercase(Locale.FRANCE) }
                .map { JSONObject().put("t_ms", it.timeMs - t0).put("tour", it.lap ?: JSONObject.NULL).put("texte", SessionJournal.redact(it.text)) })))

        // C. Changements de position.
        val declarations = events.filter { it.type == JournalType.DRIVER }
        o.put("changements_de_position", JSONObject().put("nature", Nature.MESURE.name)
            .put("note", "classement 0x84 mesuré ; « cause » est une classification prudente, jamais une preuve de dépassement en piste")
            .put("liste", JSONArray(scan?.positionChanges.orEmpty().map { ch ->
                val t = ch.getLong("t_ms")
                val gain = ch.getInt("avant") > ch.getInt("apres")
                val cause = when {
                    ch.getBoolean("pendant_arret") -> "pendant notre arrêt ou à basse vitesse dans les stands : effet du passage aux stands, pas un dépassement"
                    r?.lineFinishPosition != null && ch.getInt("tour") > (r.context.gameTotalLaps ?: Int.MAX_VALUE) -> "après la ligne : révision du classement par GT7"
                    gain -> "gain mesuré en roulant : dépassement en piste possible, NON prouvé (arrêt ou abandon adverse possible)"
                    else -> "perte mesurée en roulant : dépassement subi possible, NON prouvé (pénalité ou révision possible)"
                }
                val said = declarations.filter { abs(it.timeMs - t0 - t) <= 30_000 && Regex("dépass|doubl|passé|devant|derrière").containsMatchIn(it.text.lowercase(Locale.FRANCE)) }
                ch.put("gain", gain).put("cause", cause).put("cause_nature", Nature.INCONNU.name)
                    .put("declarations_pilote", JSONArray(said.map { SessionJournal.redact(it.text) }))
            }))
            .put("journal", JSONArray(events.filter { it.type == JournalType.POSITION }.map { JSONObject().put("t_ms", it.timeMs - t0).put("tour", it.lap ?: JSONObject.NULL).put("texte", it.text) })))

        // D. Stands.
        o.put("stands", JSONObject()
            .put("arrets_archives", JSONArray(r?.stops.orEmpty().map { s ->
                JSONObject().put("tour", s.lap).put("preuve", s.evidence.name)
                    .put("preuve_nature", if (s.evidence.name == "REFUEL") Nature.MESURE.name else Nature.DECLARATION.name)
                    .put("carburant_ajoute", s.fuelAdded ?: JSONObject.NULL)
                    .put("pneus", s.tyres.label).put("pneus_nature", if (s.tyres.name == "UNKNOWN") Nature.INCONNU.name else Nature.DECLARATION.name)
                    .put("verdict_thermique", s.thermal ?: JSONObject.NULL).put("verdict_thermique_nature", Nature.ESTIMATION.name)
            }))
            .put("zone_arret_fraction_tour", JSONArray(r?.pitBoxProgress.orEmpty()))
            .put("immobilisations_mesurees", JSONArray(scan?.stops.orEmpty()))
            .put("ravitaillements_mesures", JSONArray(scan?.refuels.orEmpty()))
            .put("journal", JSONArray(events.filter { it.type == JournalType.PIT }.map { JSONObject().put("t_ms", it.timeMs - t0).put("tour", it.lap ?: JSONObject.NULL)
                .put("nature", nature(it).name).put("texte", it.text).put("details", JSONObject(it.details)) }))
            .put("limites", JSONArray(listOf(
                "GT7 ne transmet ni l'entrée ni la sortie de la voie des stands : seuls l'immobilisation et le ravitaillement sont mesurés.",
                "Le changement de pneus n'est jamais mesuré : déclaré par le pilote, ou suspecté par le verdict thermique (ESTIMATION).",
                "Perte de temps au stand : estimation du plan (strategy.jsonl), pas une mesure.",
            ))))

        // E. Pneus.
        o.put("pneus", JSONObject()
            .put("par_tour_archive", JSONArray(r?.laps.orEmpty().map { l ->
                JSONObject().put("tour", l.lap).put("temp_moy", l.wheelTemps?.let { JSONArray(it) } ?: JSONObject.NULL)
                    .put("blocages", l.lockups ?: JSONObject.NULL).put("patinages", l.wheelspins ?: JSONObject.NULL).put("tcs", l.tcsShare ?: JSONObject.NULL)
                    .put("gomme", l.tyreCompound?.name ?: JSONObject.NULL).put("gomme_source", l.tyreSource?.label ?: "inconnue")
                    .put("age_tours", l.tyreAge ?: JSONObject.NULL).put("age_certain", l.tyreAgeCertain)
            }))
            .put("par_tour_trace", scan?.tyreLaps() ?: JSONArray())
            .put("gomme_depart", r?.startTyre?.name ?: JSONObject.NULL)
            .put("changements_declares", JSONArray(r?.tyres.orEmpty().map { JSONObject().put("tour", it.lap).put("gomme", it.compound?.name ?: JSONObject.NULL) }))
            .put("declarations_pilote", JSONArray(r?.notes.orEmpty().map { JSONObject().put("tour", it.lap).put("texte", SessionJournal.redact(it.text)) }))
            .put("bilan", JSONArray(r?.report.orEmpty()))
            .put("limites", "GT7 ne transmet ni la gomme ni l'usure : températures et glissements sont mesurés, l'âge vient des déclarations."))
        r?.let { o.put("archive_brute", JSONObject(RaceJson.session(it))) }
        return o
    }

    private fun nature(e: JournalEvent): Nature = when (e.source) {
        JournalSource.TELEMETRY -> Nature.MESURE
        JournalSource.TRANSCRIPT, JournalSource.SCREEN -> Nature.DECLARATION
        JournalSource.ENGINE, JournalSource.GEMINI -> if (Regex("estim|prévi|environ").containsMatchIn(e.text.lowercase(Locale.FRANCE))) Nature.ESTIMATION else Nature.CALCUL
        JournalSource.SYSTEM -> Nature.CALCUL
    }

    private fun eventJson(sessionId: String, e: JournalEvent, t0: Long, pos: Triple<Float, Float, Float>?): JSONObject {
        val j = JournalJson.toJson(e)
        return JSONObject().put("session", sessionId).put("t_ms", e.timeMs - t0).put("heure", Instant.ofEpochMilli(e.timeMs).toString())
            .put("seq", e.seq).put("tour", e.lap ?: JSONObject.NULL).put("type", e.type.name).put("source", e.source.name)
            .put("nature", nature(e).name).put("origine", "EN_COURSE")
            .put("texte", SessionJournal.redact(e.text))
            .put("decision", e.decision?.let(SessionJournal::redact) ?: JSONObject.NULL)
            .put("x", pos?.first?.let(::r1) ?: JSONObject.NULL).put("z", pos?.second?.let(::r1) ?: JSONObject.NULL)
            .apply {
                if (e.details.isNotEmpty()) put("details", JSONObject(e.details.mapValues { SessionJournal.redact(it.value) }))
                j.optJSONObject("telemetry")?.let { put("instantane", it) }
            }
    }

    private fun timeline(sessionId: String, events: List<JournalEvent>, scan: TraceScan?, t0: Long): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        events.forEach { out += eventJson(sessionId, it, t0, scan?.positionAt?.get(it.timeMs)) }
        fun measured(type: String, list: List<JSONObject>, text: (JSONObject) -> String) = list.forEach { m ->
            out += JSONObject().put("session", sessionId).put("t_ms", m.getLong("t_ms")).put("seq", JSONObject.NULL)
                .put("tour", m.opt("tour") ?: JSONObject.NULL).put("type", type).put("source", "TRACE").put("nature", Nature.MESURE.name)
                .put("origine", "EN_COURSE").put("texte", text(m)).put("x", m.opt("x") ?: JSONObject.NULL).put("z", m.opt("z") ?: JSONObject.NULL)
        }
        scan?.let { s ->
            measured("TOUR_MESURE", s.lapMarks) { "Début du tour ${it.getInt("tour")} (carburant ${it.get("carburant")})" }
            measured("POSITION_MESUREE", s.positionChanges) { "Classement P${it.getInt("avant")} → P${it.getInt("apres")} sur ${it.getInt("voitures")} (cause non transmise par GT7)" }
            measured("IMMOBILISATION", s.stops) { "Voiture immobile ${it.getInt("duree_ms") / 1000.0} s (carburant ${it.get("carburant_avant")} → ${it.get("carburant_apres")})" }
            measured("RAVITAILLEMENT", s.refuels) { "Carburant ${it.get("carburant_avant")} → ${it.get("carburant_apres")} en ${it.getInt("duree_ms") / 1000.0} s" }
            s.gaps.forEach { (t, d) ->
                out += JSONObject().put("session", sessionId).put("t_ms", t).put("seq", JSONObject.NULL).put("tour", JSONObject.NULL).put("type", "COUPURE_FLUX")
                    .put("source", "TRACE").put("nature", Nature.MESURE.name).put("origine", "EN_COURSE").put("texte", "Aucun paquet reçu pendant ${d / 1000.0} s")
                    .put("x", JSONObject.NULL).put("z", JSONObject.NULL)
            }
        }
        // Tri stable : par temps, puis l'ordre du journal.
        return out.sortedWith(compareBy<JSONObject> { it.getLong("t_ms") }.thenBy { it.optInt("seq", Int.MAX_VALUE) })
    }

    private fun timelineCsv(rows: List<JSONObject>): String = buildString {
        append("t_ms,seq,tour,type,source,nature,x,z,texte\n")
        rows.forEach { o ->
            append(listOf("t_ms", "seq", "tour", "type", "source", "nature", "x", "z").joinToString(",") { k -> o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString() ?: "" })
            append(",\"").append(o.optString("texte").replace("\"", "\"\"").replace('\n', ' ')).append("\"\n")
        }
    }

    private fun strategy(c: Course, events: List<JournalEvent>, t0: Long): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val strategic = setOf(JournalType.STRATEGY, JournalType.DECISION)
        events.filter {
            it.type in strategic || (it.type == JournalType.PIT && it.source == JournalSource.ENGINE) ||
                (it.type == JournalType.ALERT && "box" in it.text.lowercase(Locale.FRANCE)) ||
                (it.type == JournalType.RADIO && it.text.startsWith("Rappel critique"))
        }.forEach { e ->
            val k = when {
                e.text.startsWith("Stratégie V515") -> "VERDICT_STRATEGY_ASSURANCE"
                "carburant" in e.text.lowercase(Locale.FRANCE) -> "CARBURANT"
                "box" in e.text.lowercase(Locale.FRANCE) || e.text.startsWith("Rappel critique") -> "RAPPEL_BOX"
                e.text.startsWith("Bilan") -> "BILAN"
                e.type == JournalType.PIT -> "STAND"
                else -> e.type.name
            }
            out += eventJson(c.sessionId, e, t0, null).put("categorie", k).apply {
                e.details["plan_propose"]?.let { prop ->
                    val chosen = e.details["plan_retenu"]
                    if (chosen != prop) put("strategie_rejetee", prop).put("raison_rejet", e.details["controles"] ?: e.decision ?: "non précisée")
                }
            }
        }
        c.record?.plans.orEmpty().forEachIndexed { i, p ->
            out += JSONObject().put("session", c.sessionId).put("t_ms", JSONObject.NULL).put("tour", p.lap).put("categorie", "PLAN")
                .put("nature", Nature.ESTIMATION.name).put("origine", "EN_COURSE").put("rang", i + 1)
                .put("texte", "Plan ${i + 1} : arrêts aux tours " + p.stopLaps.ifEmpty { listOf("aucun") }.joinToString(", "))
                .put("arrets_prevus", JSONArray(p.stopLaps)).put("raison", p.reason ?: JSONObject.NULL)
                .put("conso_par_tour", p.fuelPerLap ?: JSONObject.NULL).put("source_conso", p.fuelSource ?: JSONObject.NULL)
                .put("perte_stand_ms", p.pitLossMs ?: JSONObject.NULL)
        }
        return out
    }

    private fun radio(events: List<JournalEvent>, t0: Long): List<JSONObject> = events.filter {
        it.type == JournalType.DRIVER || it.type == JournalType.ENGINEER || it.type == JournalType.ALERT || it.type == JournalType.RADIO
    }.map { e ->
        val role = when (e.type) {
            JournalType.DRIVER -> "pilote"
            JournalType.RADIO -> "système radio"
            else -> "Véga"
        }
        eventJson("", e, t0, null).put("role", role).apply { remove("session") }
            .put("dit_a_la_radio", e.details["dit_a_la_radio"] ?: JSONObject.NULL)
    }

    private fun diagnostics(c: Course, d: Device, scan: TraceScan?, events: List<JournalEvent>): JSONObject {
        val errors = events.filter { Regex("erreur|impossible|échec|refus|perdu|indisponible").containsMatchIn(it.text.lowercase(Locale.FRANCE)) }
        return JSONObject()
            .put("pitwall", JSONObject().put("version", d.appVersion).put("build", d.appBuild).put("format_pack", FORMAT_VERSION))
            .put("gt7", JSONObject().put("version", JSONObject.NULL).put("note", "non transmise par la PS5"))
            .put("telephone", JSONObject().put("modele", d.phone ?: JSONObject.NULL).put("android", d.android ?: JSONObject.NULL))
            .put("reglages", JSONObject(d.settings.mapValues { SessionJournal.redact(it.value) }))
            .put("fonctions_experimentales", JSONObject(d.experimental.mapValues { SessionJournal.redact(it.value) }))
            .put("udp", JSONObject(d.udp.mapValues { SessionJournal.redact(it.value) }))
            .put("trace", scan?.quality() ?: JSONObject().put("note", "aucune trace pour cette course"))
            .put("boite_noire", c.meta?.let { m ->
                JSONObject().put("etat", m.state.name).put("essai_manuel", m.manual).put("raison_arret", m.stopReason ?: JSONObject.NULL)
                    .put("segments", m.segments.size).put("octets", m.bytes).put("recorder", m.stats ?: JSONObject.NULL)
                    .put("echantillonnage", "tous les paquets reçus (aucune décimation) ; 10 s de pré-enregistrement avant la session")
            } ?: JSONObject().put("etat", "absente"))
            .put("defaillances", JSONArray(errors.take(200).map { JSONObject().put("seq", it.seq).put("tour", it.lap ?: JSONObject.NULL).put("type", it.type.name).put("texte", SessionJournal.redact(it.text)) }))
            .put("confidentialite", "aucun son, aucune clé API, aucun jeton Discord, aucune adresse réseau ; textes re-masqués à l'export")
    }

    private fun lapsCsv(r: SessionRecord): String = buildString {
        append("tour,temps_ms,carburant_consomme,carburant_fin,ravitaillement,voie_des_stands,temp_avg,temp_avd,temp_arg,temp_ard,blocages,patinages,tcs,gomme,age_pneus,age_certain\n")
        r.laps.forEach { l ->
            val t = l.wheelTemps
            append(listOf(l.lap, l.timeMs ?: "", l.fuelUsed ?: "", l.fuelAfter ?: "", if (l.refuelled) 1 else 0, if (l.pitLane) 1 else 0,
                t?.getOrNull(0) ?: "", t?.getOrNull(1) ?: "", t?.getOrNull(2) ?: "", t?.getOrNull(3) ?: "",
                l.lockups ?: "", l.wheelspins ?: "", l.tcsShare ?: "", l.tyreCompound?.name ?: "", l.tyreAge ?: "", if (l.tyreAgeCertain) 1 else 0).joinToString(","))
            append('\n')
        }
    }

    private fun summaryLines(c: Course, scan: TraceScan?, @Suppress("UNUSED_PARAMETER") race: JSONObject, decisions: Int, radio: List<JSONObject>): List<String> = buildList {
        val r = c.record
        r?.let { rec ->
            add("Issue : " + when (rec.outcome) {
                SessionOutcome.FINISHED -> "course terminée"
                SessionOutcome.INTERRUPTED -> "interrompue"
                SessionOutcome.LAST_LAP_CUT -> "télémétrie coupée dans le dernier tour (arrivée non confirmée)"
                SessionOutcome.IN_PROGRESS -> "en cours ou fermée brutalement"
            } + " ; ${rec.laps.size} tour(s) mesuré(s).")
            add("Départ : " + (rec.gridPosition?.let { "P$it" } ?: "inconnu") + " ; ligne : " + (rec.lineFinishPosition?.let { "P$it/${rec.lineFinishCars}" } ?: "inconnue") +
                " ; classement archivé : " + (rec.finishPosition?.let { "P$it/${rec.finishCars ?: "?"}" + if (rec.finishConsolidated) " (consolidé)" else " (non consolidé)" } ?: "inconnu") + ".")
            rec.laps.mapNotNull { it.timeMs }.minOrNull()?.let { add("Meilleur tour mesuré : ${JournalExport.lapTime(it)}.") }
            add("Arrêts : " + rec.stops.ifEmpty { null }?.joinToString(", ") { "tour ${it.lap} (${it.evidence.name.lowercase()}, ${it.tyres.label})" }.orEmpty().ifEmpty { "aucun archivé" } + ".")
            rec.plannedStops?.let { add("Plan initial : arrêts aux tours ${it.ifEmpty { listOf("aucun") }.joinToString(", ")} ; ${rec.plans.size} plan(s) successif(s).") }
        } ?: add("Aucune archive de tours pour cette session.")
        scan?.let { s ->
            val ch = s.positionChanges
            add("Changements de classement mesurés : ${ch.size} (${ch.count { it.optBoolean("pendant_arret") }} pendant un arrêt) ; aucun n'est présenté comme un dépassement prouvé.")
            add("Ravitaillements mesurés : ${s.refuels.size} ; immobilisations de 2 s ou plus : ${s.stops.size}.")
            add("Télémétrie : ${s.decoded} paquets décodés sur ${s.durationMs / 1000} s (${s.formats.entries.joinToString(", ") { "${it.key} × ${it.value}" }}), ${s.lostUdp} manquant(s), ${s.gaps.size} coupure(s) > 0,5 s.")
        }
        add("Décisions de stratégie : $decisions ; échanges radio : ${radio.size} (${radio.count { it.optString("role") == "pilote" }} du pilote).")
    }

    private fun readme(courses: List<CourseSummary>, seance: Boolean, d: Device, nowMs: Long, zone: ZoneId, files: JSONArray): String = buildString {
        val day = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone)
        appendLine("# GT7 Pitwall — ${if (seance) "séance de ${courses.size} courses" else "pack de course"}")
        appendLine()
        appendLine("Généré le ${day.format(Instant.ofEpochMilli(nowMs))} par GT7 Pitwall ${d.appVersion} (build ${d.appBuild}). Format `$FORMAT` v$FORMAT_VERSION.")
        appendLine()
        appendLine("## À lire avant l'analyse")
        appendLine("- Chaque donnée porte une **nature** : " + Nature.entries.joinToString(" ; ") { "`${it.name}` = ${it.label}" } + ".")
        appendLine("- Tout ce qui est ici a été **réellement produit en course** (`origine: EN_COURSE`). Un rejeu dans Pitwall Lab produit des données `SIMULATION`, jamais mélangées.")
        appendLine("- Un gain de position ne prouve pas un dépassement en piste : il peut venir d'un arrêt ou d'un abandon adverse.")
        JournalExport.LEGEND.forEach { appendLine("- $it") }
        appendLine()
        courses.forEachIndexed { i, c ->
            appendLine("## ${if (seance) "Course ${i + 1} : " else ""}${c.title}")
            appendLine("Session `${c.sessionId}` · durée ${c.durationMs / 60_000} min ${(c.durationMs / 1000) % 60} s")
            appendLine()
            c.lines.forEach { appendLine("- $it") }
            appendLine()
            appendLine("**Données manquantes ou limitées :**")
            c.missing.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## Inventaire")
        appendLine("| Fichier | Nature | Contenu | Octets |")
        appendLine("|---|---|---|---|")
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            appendLine("| `${f.getString("path")}` | ${f.getString("nature")} | ${f.getString("description")} | ${f.getLong("bytes")} |")
        }
        appendLine("| `README.md` | CALCUL | ce résumé | — |")
        appendLine("| `manifest.json` | CALCUL | format, métadonnées, SHA-256 de chaque fichier, manquants | — |")
        appendLine()
        appendLine("## Comment lire")
        appendLine("- `timeline.jsonl` : un événement par ligne, trié par `t_ms` (ms depuis le début de la session) ; `tour`, `x`/`z` (position sur le circuit si mesurée), `nature`.")
        appendLine("- `race.json` : voiture, circuit, règlement, tours, stands (D), pneus (E), changements de position (C).")
        appendLine("- `strategy.jsonl` et `radio.jsonl` : décisions et échanges réellement émis en course.")
        appendLine("- `telemetry_10hz.csv` : la télémétrie lisible ; `telemetry.gt7trace` : l'original binaire (gzip, `GT7PWTR1`), pour Pitwall Lab (`lab import-course <ce fichier.zip>`).")
    }

    // ------------------------------------------------------------------------------------------------ zip

    private fun concat(files: List<File>): InputStream =
        SequenceInputStream(Collections.enumeration(files.map { it.inputStream().buffered(64 * 1024) }))

    private class DigestingEntry(private val zip: ZipOutputStream, val path: String) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val out: OutputStream

        init {
            zip.putNextEntry(ZipEntry(path))
            out = object : FilterOutputStream(zip) {
                override fun write(b: Int) { zip.write(b); digest.update(b.toByte()); bytes++ }
                override fun write(b: ByteArray, off: Int, len: Int) { zip.write(b, off, len); digest.update(b, off, len); bytes += len }
                override fun close() {}
            }
        }

        fun finish(files: JSONArray, nature: Nature, description: String) {
            out.flush()
            zip.closeEntry()
            files.put(JSONObject().put("path", path).put("bytes", bytes).put("sha256", hex(digest.digest())).put("nature", nature.name).put("description", description))
        }
    }

    private fun put(zip: ZipOutputStream, path: String, data: ByteArray, files: JSONArray, nature: Nature, description: String) {
        val e = DigestingEntry(zip, path)
        e.out.write(data)
        e.finish(files, nature, description)
    }

    private fun putStream(zip: ZipOutputStream, path: String, input: InputStream, files: JSONArray, nature: Nature, description: String) {
        val e = DigestingEntry(zip, path)
        input.use { it.copyTo(e.out, 64 * 1024) }
        e.finish(files, nature, description)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------------------------------------ vérification

    private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val SECRET = Regex("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{20,}|(?i)(api[_-]?key|token|bearer|password)\\s*[:=]\\s*\\S{8,}")

    /**
     * Rouvre le pack et contrôle : manifeste lisible et du bon format, présence des fichiers obligatoires de chaque
     * course, taille et SHA-256 de chaque fichier, JSON et JSONL lisibles, trace relue jusqu'au bout, aucun secret visible.
     */
    fun verify(zipFile: File): Verification {
        val problems = mutableListOf<String>()
        val frames = linkedMapOf<String, Int>()
        var count = 0
        runCatching {
            ZipFile(zipFile).use { z ->
                val manifest = JSONObject(z.getInputStream(z.getEntry("manifest.json") ?: error("manifest.json absent")).readBytes().toString(Charsets.UTF_8))
                if (manifest.optString("format") != FORMAT) problems += "format inconnu"
                if (manifest.optInt("formatVersion") > FORMAT_VERSION) problems += "version de format plus récente que ce lecteur"
                val listed = manifest.getJSONArray("files")
                val paths = (0 until listed.length()).map { listed.getJSONObject(it) }
                val sessions = manifest.getJSONArray("sessions")
                for (i in 0 until sessions.length()) {
                    val folder = sessions.getJSONObject(i).getString("folder").let { if (it == ".") "" else it }
                    for (req in REQUIRED) {
                        if (paths.none { it.getString("path") == folder + req }) problems += "fichier obligatoire absent : $folder$req"
                    }
                }
                if (z.getEntry("README.md") == null) problems += "README.md absent"
                for (f in paths) {
                    val path = f.getString("path")
                    val entry = z.getEntry(path)
                    if (entry == null) { problems += "absent : $path"; continue }
                    count++
                    val md = MessageDigest.getInstance("SHA-256")
                    var n = 0L
                    z.getInputStream(entry).use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val k = ins.read(buf); if (k < 0) break; md.update(buf, 0, k); n += k }
                    }
                    if (n != f.getLong("bytes")) problems += "taille incorrecte : $path"
                    if (hex(md.digest()) != f.getString("sha256")) problems += "empreinte SHA-256 incorrecte : $path"
                    when {
                        path.endsWith(TRACE) -> frames[path] = z.getInputStream(entry).use { TelemetryTrace.stream(it) { true } }
                        path.endsWith(".json") -> if (runCatching { JSONObject(z.getInputStream(entry).readBytes().toString(Charsets.UTF_8)) }.isFailure) problems += "JSON illisible : $path"
                        path.endsWith(".jsonl") -> z.getInputStream(entry).bufferedReader().useLines { lines ->
                            if (lines.filter { it.isNotBlank() }.any { runCatching { JSONObject(it) }.isFailure }) problems += "ligne JSON illisible : $path"
                        }
                    }
                    if (!path.endsWith(TRACE) && entry.size < 20_000_000) {
                        val text = z.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                        if (IPV4.containsMatchIn(text)) problems += "adresse IP visible : $path"
                        if (SECRET.containsMatchIn(text)) problems += "secret possible : $path"
                    }
                }
                for (i in 0 until sessions.length()) {
                    val s = sessions.getJSONObject(i)
                    val folder = s.getString("folder").let { if (it == ".") "" else it }
                    val expected = s.optInt("frames")
                    frames[folder + TRACE]?.let { if (it != expected) problems += "trace ${folder + TRACE} : $it échantillons relus, $expected attendus" }
                }
            }
        }.onFailure { problems += "pack illisible : ${it.message ?: it.javaClass.simpleName}" }
        return Verification(problems.isEmpty(), problems, count, frames)
    }

    private fun r1(f: Float): Double = Math.round(f * 10.0) / 10.0
    private fun r2(f: Float): Double = Math.round(f * 100.0) / 100.0
}
