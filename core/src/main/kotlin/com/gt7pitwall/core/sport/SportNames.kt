package com.gt7pitwall.core.sport

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative equality keys for names published by different GT7 sites.
 *
 * Do not use fuzzy string similarity: layout or car-generation mismatches are real
 * strategy errors. Unknown/meaningless vehicle descriptions provide no evidence.
 */
object SportNames {
    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()
            .replace(Regex("\\s+"), " ")

    /** Only this known alternative spelling is merged; Streets/Horse Thief stay distinct. */
    fun trackKey(name: String): String {
        val n = normalize(name)
        val aliases = setOf(
            "willow springs international raceway big willow",
            "willow springs big willow",
            "willow springs international raceway the big willow",
            "willow springs raceway big willow",
        )
        return if (n in aliases) "willow springs big willow" else n
    }

    /** Reject SEO boilerplate, not real car classes or named cars. */
    fun meaningfulCar(name: String): Boolean {
        val n = normalize(name)
        return n.isNotBlank() && n !in setOf(
            "the race", "race", "this race", "the event", "event", "cars",
            "car", "vehicles", "all cars", "any cars", "unknown", "tbd",
        )
    }

    fun carKey(name: String): String {
        val n = normalize(name)
        // Gr.3 / GR3 / Gr 3 / Group 3 / Group Gr.3 / Gr.3 cars
        // are equivalent, but Gr.3 and Gr.4 can NEVER be merged.
        val cls = Regex("^(?:(?:group|class) )?(?:gr|group) ?([1-4])(?: (?:cars?|vehicles?|class|category|only))?$")
            .matchEntire(n)
        if (cls != null) return "gr" + cls.groupValues[1]
        // Controlled one-make spelling variants for Race A of 26 October 2026.
        // A different model year or trim remains a true conflict.
        if (n in setOf(
                "nissan fairlady z z34 08",
                "nissan fairlady z z34 2008",
                "nissan fairlady z 08",
                "nissan z34 08",
            )
        ) return "nissan fairlady z z34 08"
        return n
    }

    fun sameTrack(a: String, b: String): Boolean = trackKey(a) == trackKey(b)
    fun sameCar(a: String, b: String): Boolean = carKey(a) == carKey(b)
}
