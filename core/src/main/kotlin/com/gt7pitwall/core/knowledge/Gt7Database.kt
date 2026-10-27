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
    /**
     * Circuit appris par Pitwall (absent de la base communautaire, par exemple SUGO à sa sortie) : nom tiré du programme
     * Sport, longueur mesurée sur un tour complet. Remplacé par la fiche officielle dès qu'elle paraît.
     */
    val learned: Boolean = false,
) {
    val label: String get() = if (reverse) "$name (inversé)" else name
}

class Gt7Database(cars: Map<Int, Car>, courses: List<Course>) {

    /**
     * Voitures et circuits courants. La base est mise à jour sans redémarrer ([replace]) : nouvelles voitures et nouveaux
     * circuits de la base communautaire, circuits appris ([learn]).
     */
    @Volatile
    var cars: Map<Int, Car> = cars
        private set

    @Volatile
    private var official: List<Course> = courses

    @Volatile
    private var learnedCourses: List<Course> = emptyList()

    /** Circuits officiels, puis circuits appris qui n'ont pas (encore) de fiche officielle du même nom. */
    @Volatile
    var courses: List<Course> = courses
        private set

    /** Liste combinée recalculée à chaque changement, jamais à chaque lecture (elle sert à chaque paquet). */
    private fun recompute() {
        val o = official
        val names = o.map { key(it.name) }.toSet()
        courses = o + learnedCourses.filter { key(it.name) !in names }
    }

    /**
     * Catégorie de chaque voiture (« 1 », « 2 », « 3 », « 4 », « B », « N », « X ») : fichier `cargrp.csv` de la même
     * base communautaire. Vide si le fichier manque : aucune preuve de catégorie, jamais une catégorie devinée.
     */
    @Volatile
    var carGroups: Map<Int, String> = emptyMap()

    /** Catégorie GT7 de la voiture [code] (« 3 » pour Gr.3, « N » pour les voitures de route), si la base la donne. */
    fun carGroup(code: Int): String? = carGroups[code]

    /** Circuits appris encore utiles (sans fiche officielle du même nom), pour l'enregistrement. */
    val learned: List<Course> get() = courses.filter { it.learned }

    /** Nouvelle version de la base communautaire : remplace voitures et circuits officiels, garde les circuits appris. */
    fun replace(fresh: Gt7Database) {
        cars = fresh.cars
        if (fresh.carGroups.isNotEmpty()) carGroups = fresh.carGroups
        official = fresh.official
        recompute()
    }

    /** Circuits appris relus au lancement. */
    fun restoreLearned(list: List<Course>) {
        learnedCourses = list.filter { it.learned && it.id < 0 }.distinctBy { it.id }
        recompute()
    }

    /**
     * Nouveau circuit appris : identifiant négatif (jamais celui d'un circuit officiel). Un circuit appris du même nom
     * est réutilisé.
     */
    fun learn(name: String, lengthM: Int): Course {
        courses.firstOrNull { key(it.name) == key(name) }?.let { return it }
        val id = minOf(LEARNED_FIRST_ID, (learnedCourses.minOfOrNull { it.id } ?: 0) - 1)
        val c = Course(id, name, "circuit", lengthM, null, 1, reverse = false, noRain = false, learned = true)
        learnedCourses = learnedCourses + c
        recompute()
        return c
    }

    fun car(code: Int): Car? = cars[code]

    /** Circuit par identifiant ; un circuit appris devenu officiel renvoie la fiche officielle du même nom. */
    fun course(id: Int): Course? = courses.firstOrNull { it.id == id }
        ?: learnedCourses.firstOrNull { it.id == id }?.let { l -> official.firstOrNull { key(it.name) == key(l.name) && !it.reverse } }

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
        /** Base vide, neuve à chaque appel (la base peut apprendre des circuits). */
        val EMPTY: Gt7Database get() = Gt7Database(emptyMap(), emptyList())

        /** Premier identifiant d'un circuit appris (les identifiants officiels sont positifs). */
        const val LEARNED_FIRST_ID = -1

        /** Une nouvelle version publiée ne remplace la base que si elle n'a pas perdu plus de 10 % de son contenu. */
        const val MIN_KEPT = 0.9

        /**
         * Version téléchargée de la base communautaire, si elle est lisible et complète par rapport à [current]
         * (fichier tronqué ou format changé : null, la base actuelle reste).
         */
        fun parseChecked(carsCsv: String, makersCsv: String, coursesCsv: String, current: Gt7Database): Gt7Database? {
            val fresh = runCatching { parse(carsCsv, makersCsv, coursesCsv) }.getOrNull() ?: return null
            if (fresh.cars.isEmpty() || fresh.official.isEmpty()) return null
            if (fresh.cars.size < current.cars.size * MIN_KEPT || fresh.official.size < current.official.size * MIN_KEPT) return null
            return fresh
        }

        /** Circuits appris, enregistrés sur le téléphone (JSON). */
        fun learnedJson(list: List<Course>): String = org.json.JSONArray(
            list.map { org.json.JSONObject().put("id", it.id).put("name", it.name).put("lengthM", it.lengthM) },
        ).toString()

        fun learnedFrom(json: String?): List<Course> = runCatching {
            val a = org.json.JSONArray(json ?: return emptyList())
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val id = o.optInt("id", 0).takeIf { it < 0 } ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Course(id, name, "circuit", o.optInt("lengthM", 0), null, 1, reverse = false, noRain = false, learned = true)
            }
        }.getOrDefault(emptyList())

        /** Nom comparable (« SUGO International Racing Course » = « sugo international racing course »). */
        fun key(name: String): String =
            java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ").trim()

        /** `cargrp.csv` (ID,Group) ; lignes illisibles ignorées. */
        fun parseGroups(csv: String): Map<Int, String> = csv.lineSequence().drop(1).mapNotNull { line ->
            val parts = line.trim().split(',')
            val id = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val g = parts.getOrNull(1)?.trim()?.uppercase()?.takeIf { it.matches(Regex("[1-4BNX]")) } ?: return@mapNotNull null
            id to g
        }.toMap()

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
