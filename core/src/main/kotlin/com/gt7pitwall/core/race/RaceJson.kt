package com.gt7pitwall.core.race

import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lecture et écriture JSON du règlement, des profils et des sessions (org.json : fourni par Android, et par la
 * bibliothèque Maven pour les tests). Les champs absents prennent leur valeur par défaut : les anciennes données
 * se relisent sans perte.
 */
object RaceJson {
    // ------------------------------------------------------------------ Règlement

    fun rules(r: RaceRules): JSONObject = JSONObject()
        .put("totalLaps", r.totalLaps ?: 0)
        .put("durationMin", r.durationMin ?: 0)
        .put("fuelMultiplier", r.fuelMultiplier)
        .put("tyreMultiplier", r.tyreMultiplier)
        .put("mandatoryStops", r.mandatoryStops)
        .put("allowedTyres", TyreCompound.codes(r.allowedTyres))
        .put("requiredTyres", TyreCompound.codes(r.requiredTyres))
        .put("startTyre", r.startTyre?.name ?: "")
        .put("tyreStintLaps", r.tyreStintLaps ?: 0)
        .put("trackId", r.trackId ?: 0)

    fun rules(o: JSONObject): RaceRules = RaceRules(
        totalLaps = o.optInt("totalLaps", 0).takeIf { it > 0 },
        durationMin = o.optInt("durationMin", 0).takeIf { it > 0 },
        fuelMultiplier = o.optInt("fuelMultiplier", 1).coerceAtLeast(1),
        tyreMultiplier = o.optInt("tyreMultiplier", 1).coerceAtLeast(1),
        mandatoryStops = o.optInt("mandatoryStops", 0).coerceAtLeast(0),
        allowedTyres = TyreCompound.parseCodes(o.optString("allowedTyres", "")),
        // Ancien format : texte libre ; seuls les codes reconnus (RH, RM…) sont repris.
        requiredTyres = TyreCompound.parseCodes(o.optString("requiredTyres", "")),
        startTyre = TyreCompound.parseCodes(o.optString("startTyre", "")).firstOrNull(),
        tyreStintLaps = o.optInt("tyreStintLaps", 0).takeIf { it > 0 },
        trackId = o.optInt("trackId", 0).takeIf { it > 0 },
    )

    // ------------------------------------------------------------------ Profils

    fun profile(p: EventProfile): JSONObject = JSONObject()
        .put("id", p.id.code)
        .put("gameVersion", p.gameVersion ?: "")
        .put("setupNote", p.setupNote ?: "")
        .put("eventDate", p.eventDate ?: "")
        .put("confirmed", p.confirmed)

    fun profile(o: JSONObject): EventProfile = EventProfile(
        id = ProfileId.parse(o.optString("id", "")) ?: ProfileId.LEGACY,
        gameVersion = o.optString("gameVersion", "").ifBlank { null },
        setupNote = o.optString("setupNote", "").ifBlank { null },
        eventDate = o.optString("eventDate", "").ifBlank { null },
        confirmed = o.optBoolean("confirmed", false),
    )

    fun book(b: ProfileBook): String {
        val arr = JSONArray()
        b.entries.values.forEach { e -> arr.put(JSONObject().put("profile", profile(e.profile)).put("rules", rules(e.rules))) }
        return JSONObject().put("schema", 1).put("active", b.active.code).put("entries", arr).toString()
    }

    fun book(text: String): ProfileBook? = try {
        val o = JSONObject(text)
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            val p = profile(e.optJSONObject("profile") ?: JSONObject())
            p.id to ProfileEntry(p, rules(e.optJSONObject("rules") ?: JSONObject()))
        }.toMap()
        ProfileBook(entries, ProfileId.parse(o.optString("active", "")) ?: ProfileId.LEGACY)
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ Sessions

    fun session(r: SessionRecord): String {
        val ctx = JSONObject()
            .put("profile", profile(r.context.profile))
            .put("rules", rules(r.context.rules))
            .put("car", r.context.carCode ?: 0)
            .put("fuelCapacity", (r.context.fuelCapacity ?: 0f).toDouble())
        val laps = JSONArray()
        r.laps.forEach { l ->
            laps.put(
                JSONObject().put("lap", l.lap).put("timeMs", l.timeMs ?: 0).put("fuelUsed", l.fuelUsed?.toDouble() ?: -1.0)
                    .put("refuelled", l.refuelled).put("fuelAfter", l.fuelAfter?.toDouble() ?: -1.0),
            )
        }
        val stops = JSONArray()
        r.stops.forEach { s ->
            stops.put(JSONObject().put("lap", s.lap).put("evidence", s.evidence.name).put("fuelAdded", s.fuelAdded?.toDouble() ?: -1.0))
        }
        val tyres = JSONArray()
        r.tyres.forEach { t -> tyres.put(JSONObject().put("lap", t.lap).put("compound", t.compound?.name ?: "")) }
        val plans = JSONArray()
        r.plans.forEach { p ->
            plans.put(JSONObject().put("lap", p.lap).put("stops", JSONArray(p.stopLaps)).put("reason", p.reason ?: ""))
        }
        val notes = JSONArray()
        r.notes.forEach { n -> notes.put(JSONObject().put("lap", n.lap).put("text", n.text)) }
        return JSONObject()
            .put("schema", SessionRecord.SCHEMA)
            .put("id", r.id)
            .put("context", ctx)
            .put("startedAt", r.startedAtMs)
            .put("updatedAt", r.updatedAtMs)
            .put("outcome", r.outcome.name)
            .put("laps", laps)
            .put("stops", stops)
            .put("startTyre", r.startTyre?.name ?: "")
            .put("tyres", tyres)
            .put("plans", plans)
            .put("notes", notes)
            .toString()
    }

    fun session(text: String): SessionRecord? = try {
        val o = JSONObject(text)
        val ctx = o.optJSONObject("context") ?: JSONObject()
        fun floatOrNull(j: JSONObject, key: String) = j.optDouble(key, -1.0).takeIf { it >= 0 }?.toFloat()
        fun objects(key: String): List<JSONObject> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        SessionRecord(
            id = o.getString("id"),
            context = SessionContext(
                profile = profile(ctx.optJSONObject("profile") ?: JSONObject()),
                rules = rules(ctx.optJSONObject("rules") ?: JSONObject()),
                carCode = ctx.optInt("car", 0).takeIf { it > 0 },
                fuelCapacity = ctx.optDouble("fuelCapacity", 0.0).toFloat().takeIf { it > 0f },
            ),
            startedAtMs = o.optLong("startedAt", 0),
            updatedAtMs = o.optLong("updatedAt", 0),
            outcome = SessionOutcome.entries.firstOrNull { it.name == o.optString("outcome") } ?: SessionOutcome.INTERRUPTED,
            laps = objects("laps").map { l ->
                LapEntry(
                    lap = l.optInt("lap"),
                    timeMs = l.optInt("timeMs", 0).takeIf { it > 0 },
                    fuelUsed = floatOrNull(l, "fuelUsed"),
                    refuelled = l.optBoolean("refuelled", false),
                    fuelAfter = floatOrNull(l, "fuelAfter"),
                )
            },
            stops = objects("stops").mapNotNull { s ->
                val ev = StopEvidence.entries.firstOrNull { it.name == s.optString("evidence") } ?: return@mapNotNull null
                StopEntry(s.optInt("lap"), ev, floatOrNull(s, "fuelAdded"))
            },
            startTyre = TyreCompound.parseCodes(o.optString("startTyre", "")).firstOrNull(),
            tyres = objects("tyres").map { t -> TyreEntry(t.optInt("lap"), TyreCompound.parseCodes(t.optString("compound", "")).firstOrNull()) },
            plans = objects("plans").map { p ->
                val a = p.optJSONArray("stops") ?: JSONArray()
                PlanEntry(p.optInt("lap"), (0 until a.length()).map { a.getInt(it) }, p.optString("reason", "").ifBlank { null })
            },
            notes = objects("notes").map { n -> DriverNote(n.optInt("lap"), n.optString("text", "")) },
        )
    } catch (e: Exception) {
        null
    }
}
