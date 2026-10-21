package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.race.ProfileBook
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.race.RaceStorage
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stockage en mémoire pour vérifier que le règlement, les profils et les sessions sont bien enregistrés. Profils et
 * sessions passent par leur JSON, comme sur le téléphone.
 */
class FakeStorage(
    var rules: RaceRules = RaceRules(),
    val memory: MutableMap<String, CarTrackRecord> = mutableMapOf(),
    var profilesJson: String? = null,
    val sessionsJson: MutableMap<String, String> = linkedMapOf(),
) : RaceStorage {
    var sessionSaves = 0
    override fun loadProfiles(): ProfileBook? = profilesJson?.let(RaceJson::book)
    override fun saveProfiles(book: ProfileBook) {
        profilesJson = RaceJson.book(book)
    }
    override fun loadSessions(): List<SessionRecord> = sessionsJson.values.mapNotNull(RaceJson::session)
    override fun saveSession(record: SessionRecord) {
        sessionsJson[record.id] = RaceJson.session(record)
        sessionSaves++
    }
    val sessions: List<SessionRecord> get() = loadSessions()

    var saves = 0
    override fun loadRules() = rules
    override fun saveRules(r: RaceRules) {
        rules = r
        saves++
    }
    /** Journal de course : une liste de lignes JSON par session, ajoutées comme sur le téléphone. */
    val journals: MutableMap<String, MutableList<String>> = linkedMapOf()
    override fun appendJournal(sessionId: String, line: String) {
        journals.getOrPut(sessionId) { mutableListOf() } += line
    }
    override fun loadJournal(sessionId: String): List<String> = journals[sessionId].orEmpty()
    override fun journalSessionIds(): List<String> = journals.keys.toList()

    override fun loadMemory(): Map<String, CarTrackRecord> = memory
    override fun saveMemory(records: Collection<CarTrackRecord>) {
        memory.clear()
        records.forEach { memory[it.key] = it }
    }
}

/**
 * Course simulée à 60 paquets/s sur un circuit circulaire (rayon 480 m, ~3 km).
 * La ligne de départ est à l'angle 0 ; la voie des stands longe la piste 15 m à l'extérieur autour de la ligne.
 */
class RaceSim(
    val controller: RaceController = RaceController(),
    val totalLaps: Int = 10,
    var fuel: Float = 100f,
    val capacity: Float = 100f,
    /** Consommation réelle par tour (ce que GT7 mesurerait). */
    var perLap: Float = 5f,
    val carCode: Int = 3607,
) {
    val radius = 480f
    val circumference = (2 * PI * radius).toFloat()
    private var id = 0
    var lap = 0
        private set
    private var theta = (2 * PI - 0.02).toFloat() // juste avant la ligne, sur la grille
    private var lastLapMs = -1
    private var bestLapMs = -1
    private var lapTicks = 0
    val said = mutableListOf<EngineerMessage>()
    val texts: List<String> get() = said.map { it.text }
    val lastAnswer: String get() = said.last { it.category == com.gt7pitwall.core.engineer.MessageCategory.ANSWER }.text

    private fun send(speed: Float, r: Float, paused: Boolean = false) {
        val flags = 1 or (if (paused) 2 else 0)
        val plain = TestPackets.plain(
            lap = lap, totalLaps = totalLaps, fuel = fuel, capacity = capacity, speedMps = speed,
            lastLapMs = lastLapMs, bestLapMs = bestLapMs, carCode = carCode, packetId = ++id, flags = flags,
            posX = r * cos(theta), posZ = r * sin(theta),
        )
        val p: TelemetryPacket = TelemetryParser.parseDecrypted(plain)!!
        said += controller.onPacket(p)
    }

    /** Un paquet : avance de speed/60 mètres, consomme en proportion, franchit la ligne si besoin. */
    fun step(speed: Float, r: Float = radius) {
        val d = speed / 60f
        theta += d / r
        if (lap >= 1) fuel = (fuel - perLap * d / circumference).coerceAtLeast(0f)
        lapTicks++
        if (theta >= 2 * PI) {
            theta -= (2 * PI).toFloat()
            if (lap >= 1) {
                // Quelques millisecondes d'écart d'un tour à l'autre, comme en vrai (GT7 ne change le
                // « dernier tour » que si le chrono change).
                lastLapMs = lapTicks * 1000 / 60 + (lap * 37) % 250
                if (bestLapMs < 0 || lastLapMs < bestLapMs) bestLapMs = lastLapMs
            }
            lap++
            lapTicks = 0
        }
        send(speed, r)
    }

    fun stand(seconds: Float, r: Float = radius, addFuel: Float = 0f) {
        val n = (seconds * 60).toInt()
        repeat(n) {
            fuel = (fuel + addFuel / n).coerceAtMost(capacity)
            lapTicks++
            send(0f, r)
        }
    }

    /** Grille puis départ : quelques secondes immobile au tour 0, puis franchissement de la ligne. */
    fun start() {
        stand(3f)
        while (lap == 0) step(30f)
    }

    /** Roule jusqu'à l'angle donné (radians, 0 = ligne) ; passe au tour suivant si l'angle est déjà dépassé. */
    fun driveTo(angle: Float, speed: Float = 80f, r: Float = radius) {
        if (theta >= angle) {
            val l = lap
            while (lap == l) step(speed, r)
        }
        while (theta < angle) step(speed, r)
    }

    fun driveLaps(n: Int, speed: Float = 80f) {
        val target = lap + n
        while (lap < target) step(speed)
    }

    /**
     * Arrêt au stand réaliste : entrée 400 m avant la ligne, 72 km/h dans la voie (15 m à l'extérieur),
     * franchit la ligne, s'arrête 60 m après, ravitaille [fuel] (unités GT7), ressort et accélère.
     */
    fun pitStop(fuel: Float, stopSeconds: Float = 6f) {
        val pitR = radius + 15f
        val entry = (2 * PI - 400.0 / radius).toFloat()
        while (theta < entry) step(80f)
        repeat(60) { step(40f - it * 0.33f) } // décélération douce jusqu'au limiteur
        val startLap = lap
        while (lap == startLap) step(20f, pitR)
        while (theta < 60f / radius) step(20f, pitR)
        repeat(30) { step(20f - it * 0.66f, pitR) }
        stand(stopSeconds, pitR, addFuel = fuel)
        repeat(240) { step(5f + it * 0.25f, pitR) } // sortie et ré-accélération
        repeat(120) { step(65f) }
    }

    /** Accident : de 80 m/s à 0 en 3 paquets à mi-tour, immobile 5 s, puis repart. */
    fun crash() {
        driveTo(PI.toFloat())
        step(50f)
        step(20f)
        step(0f)
        stand(5f)
        repeat(240) { step(it / 3f) }
    }

    /** Arrêt en piste « doux » à mi-tour (sortie de route lente, panne) puis repart. */
    fun stopOnTrack(seconds: Float = 6f) {
        driveTo(PI.toFloat())
        repeat(360) { step(80f - it * 0.22f) }
        stand(seconds)
        repeat(240) { step(it / 3f) }
    }

    /** Arrêt lent sur la trajectoire, 200 m après la ligne (au limiteur, mais pas dans la voie des stands). */
    fun slowStopNearLineOnRacingLine() {
        driveTo(200f / radius)
        repeat(600) { step(20f) }
        repeat(30) { step(20f - it * 0.66f) }
        stand(5f)
        repeat(240) { step(it / 3f) }
    }

    fun say(text: String) {
        said += controller.utterance(text)
    }
}
