package com.gt7pitwall.app.settings

import android.content.Context
import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.race.ProfileBook
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.race.RaceStorage
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.strategy.RaceRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Règlements par épreuve, mémoire voiture × circuit et sessions archivées, conservés sur le téléphone. Une session
 * par fichier, écrit de façon atomique : un fichier abîmé ne fait pas perdre les autres.
 */
class RaceStore(context: Context) : RaceStorage {
    private val prefs = context.getSharedPreferences("race", Context.MODE_PRIVATE)
    private val sessionDir = File(context.filesDir, "sessions").apply { mkdirs() }

    /** Journaux de course : un fichier par session (même identifiant que l'archive), une ligne JSON par événement. */
    private val journalDir = File(context.filesDir, "journal").apply { mkdirs() }

    override fun loadRules(): RaceRules = try {
        prefs.getString(KEY_RULES, null)?.let { RaceJson.rules(JSONObject(it)) } ?: RaceRules()
    } catch (e: Exception) {
        RaceRules()
    }

    override fun saveRules(r: RaceRules) {
        prefs.edit().putString(KEY_RULES, RaceJson.rules(r).toString()).apply()
    }

    override fun loadProfiles(): ProfileBook? = prefs.getString(KEY_PROFILES, null)?.let(RaceJson::book)

    override fun saveProfiles(book: ProfileBook) {
        prefs.edit().putString(KEY_PROFILES, RaceJson.book(book)).apply()
    }

    override fun loadMemory(): Map<String, CarTrackRecord> = try {
        val arr = JSONArray(prefs.getString(KEY_MEMORY, "[]"))
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            val rec = CarTrackRecord(
                carCode = o.getInt("car"),
                trackId = o.getInt("track"),
                bestLapMs = o.optInt("best", 0).takeIf { it > 0 },
                typicalLapMs = o.optInt("typical", 0).takeIf { it > 0 },
                fuelPerLapX1 = o.optDouble("fuel", 0.0).toFloat().takeIf { it > 0f },
                fuelSamples = o.optInt("samples", 0),
                // Ancienne mémoire, sans épreuve : rangée dans « Autre, course ».
                scope = o.optString("scope", "").ifBlank { CarTrackRecord.LEGACY_SCOPE },
                gameVersion = o.optString("gameVersion", "").ifBlank { null },
            )
            rec.key to rec
        }
    } catch (e: Exception) {
        emptyMap()
    }

    override fun saveMemory(records: Collection<CarTrackRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject()
                    .put("car", r.carCode)
                    .put("track", r.trackId)
                    .put("best", r.bestLapMs ?: 0)
                    .put("typical", r.typicalLapMs ?: 0)
                    .put("fuel", (r.fuelPerLapX1 ?: 0f).toDouble())
                    .put("samples", r.fuelSamples)
                    .put("scope", r.scope)
                    .put("gameVersion", r.gameVersion ?: ""),
            )
        }
        prefs.edit().putString(KEY_MEMORY, arr.toString()).apply()
    }

    override fun loadSessions(): List<SessionRecord> =
        sessionDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { RaceJson.session(f.readText()) }.getOrNull()
        }

    override fun saveSession(record: SessionRecord) {
        val name = record.id.filter { it.isLetterOrDigit() || it == '-' } + ".json"
        val tmp = File(sessionDir, "$name.tmp")
        runCatching {
            tmp.writeText(RaceJson.session(record))
            if (!tmp.renameTo(File(sessionDir, name))) tmp.delete()
        }
    }

    private val dnaFile = File(context.filesDir, "driver_dna.json")
    private val dnaTmp = File(context.filesDir, "driver_dna.json.tmp")

    override fun loadDriverBook(): String? = runCatching { dnaFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()

    /** Profils de pilotage : écriture atomique (fichier temporaire puis renommage). */
    @Synchronized
    override fun saveDriverBook(json: String) {
        runCatching {
            dnaTmp.writeText(json, Charsets.UTF_8)
            if (!dnaTmp.renameTo(dnaFile)) dnaTmp.delete()
        }
    }

    private fun journalFile(sessionId: String) = File(journalDir, sessionId.filter { it.isLetterOrDigit() || it == '-' } + ".jsonl")

    /**
     * Ajout immédiat d'une ligne, vidée sur le disque : si Android ferme l'application, seule la ligne en cours
     * d'écriture peut manquer (elle est ignorée à la lecture). Au-delà de [MAX_JOURNALS], les plus anciens sont retirés.
     */
    @Synchronized
    override fun appendJournal(sessionId: String, line: String) {
        if (sessionId.isBlank()) return
        runCatching {
            val f = journalFile(sessionId)
            val fresh = !f.exists()
            java.io.FileOutputStream(f, true).use { out ->
                out.write((line.replace('\n', ' ') + "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (fresh) prune()
        }
    }

    @Synchronized
    override fun loadJournal(sessionId: String): List<String> =
        runCatching { journalFile(sessionId).takeIf { it.exists() }?.readLines(Charsets.UTF_8).orEmpty().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())

    override fun journalSessionIds(): List<String> =
        journalDir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().map { it.name.removeSuffix(".jsonl") }

    private fun prune() {
        val files = journalDir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedByDescending { it.lastModified() }
        files.drop(MAX_JOURNALS).forEach { it.delete() }
    }

    private companion object {
        const val MAX_JOURNALS = 200
        const val KEY_RULES = "rules"
        const val KEY_PROFILES = "profiles"
        const val KEY_MEMORY = "memory"
    }
}
