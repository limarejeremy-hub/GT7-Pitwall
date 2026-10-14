package com.gt7pitwall.core.knowledge

import kotlin.math.abs

/**
 * Base de données GT7 de la communauté (gt7info de ddm999, licence MIT-0) :
 * noms des voitures (le code est transmis par la télémétrie) et circuits (longueur, perte dans la voie des stands).
 */
data class Car(val id: Int, val name: String, val maker: String?) {
    val fullName: String get() = listOfNotNull(maker, name).joinToString(" ")
}

data class Course(
    val id: Int,
    val name: String,
    val category: String,
    val lengthM: Int,
    /** Temps perdu en passant par la voie des stands (s), d'après la base. */
    val pitLaneDeltaS: Float?,
    val layout: Int,
    val reverse: Boolean,
    val noRain: Boolean,
) {
    val label: String get() = if (reverse) "$name (inversé)" else name
}

class Gt7Database(val cars: Map<Int, Car>, val courses: List<Course>) {

    fun car(code: Int): Car? = cars[code]

    fun course(id: Int): Course? = courses.firstOrNull { it.id == id }

    /**
     * Circuits dont la longueur officielle est proche de la distance mesurée sur un tour
     * (la trajectoire du pilote diffère un peu de la longueur officielle).
     */
    fun coursesMatching(measuredM: Float, tolerance: Float = 0.04f): List<Course> =
        courses.filter { it.lengthM > 0 && abs(measuredM - it.lengthM) <= it.lengthM * tolerance }
            .sortedBy { abs(measuredM - it.lengthM) }

    fun search(query: String): List<Course> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) courses.sortedBy { it.label } else courses.filter { q in it.label.lowercase() }.sortedBy { it.label }
    }

    companion object {
        val EMPTY = Gt7Database(emptyMap(), emptyList())

        fun parse(carsCsv: String, makersCsv: String, coursesCsv: String): Gt7Database {
            val makers = rows(makersCsv).associate { it[0].toInt() to it[1] }
            val cars = rows(carsCsv).associate { r ->
                val id = r[0].toInt()
                id to Car(id, r[1], r.getOrNull(2)?.toIntOrNull()?.let { makers[it] })
            }
            val courses = rows(coursesCsv).mapNotNull { r ->
                if (r.size < 21) return@mapNotNull null
                Course(
                    id = r[0].toInt(),
                    name = r[1],
                    category = r[4],
                    lengthM = r[5].toIntOrNull() ?: 0,
                    pitLaneDeltaS = r[17].toFloatOrNull()?.takeIf { it > 0f },
                    layout = r[15].toIntOrNull() ?: 1,
                    reverse = r[16] == "1",
                    noRain = r[20] == "1",
                )
            }
            return Gt7Database(cars, courses)
        }

        /** CSV simple (pas de guillemets dans ces fichiers), en-tête ignoré. */
        private fun rows(csv: String): List<List<String>> =
            csv.lineSequence().drop(1).map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.split(',') }
                .filter { it.firstOrNull()?.toIntOrNull() != null }
                .toList()
    }
}
