package com.gt7pitwall.app.settings

import android.content.Context
import com.gt7pitwall.app.engine.RaceStorage
import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.strategy.RaceRules
import org.json.JSONArray
import org.json.JSONObject

/** Règlement de la course et mémoire voiture × circuit, conservés sur le téléphone. */
class RaceStore(context: Context) : RaceStorage {
    private val prefs = context.getSharedPreferences("race", Context.MODE_PRIVATE)

    override fun loadRules(): RaceRules = try {
        prefs.getString(KEY_RULES, null)?.let { j ->
            val o = JSONObject(j)
            RaceRules(
                totalLaps = o.optInt("totalLaps", 0).takeIf { it > 0 },
                durationMin = o.optInt("durationMin", 0).takeIf { it > 0 },
                fuelMultiplier = o.optInt("fuelMultiplier", 1).coerceAtLeast(1),
                tyreMultiplier = o.optInt("tyreMultiplier", 1).coerceAtLeast(1),
                mandatoryStops = o.optInt("mandatoryStops", 0).coerceAtLeast(0),
                requiredTyres = o.optString("requiredTyres", ""),
                trackId = o.optInt("trackId", 0).takeIf { it > 0 },
            )
        } ?: RaceRules()
    } catch (e: Exception) {
        RaceRules()
    }

    override fun saveRules(r: RaceRules) {
        val o = JSONObject()
            .put("totalLaps", r.totalLaps ?: 0)
            .put("durationMin", r.durationMin ?: 0)
            .put("fuelMultiplier", r.fuelMultiplier)
            .put("tyreMultiplier", r.tyreMultiplier)
            .put("mandatoryStops", r.mandatoryStops)
            .put("requiredTyres", r.requiredTyres)
            .put("trackId", r.trackId ?: 0)
        prefs.edit().putString(KEY_RULES, o.toString()).apply()
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
                    .put("samples", r.fuelSamples),
            )
        }
        prefs.edit().putString(KEY_MEMORY, arr.toString()).apply()
    }

    private companion object {
        const val KEY_RULES = "rules"
        const val KEY_MEMORY = "memory"
    }
}
