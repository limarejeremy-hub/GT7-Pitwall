package com.gt7pitwall.core.knowledge

import org.json.JSONObject

/**
 * Compléments tirés des notes de mise à jour officielles de GT7 (fichier versionné `official_updates.json`), pour les
 * contenus que la base communautaire n'a pas encore (issue #33 : SUGO, ajouté en 1.72, absent de course.csv, dont la
 * mise à jour est manuelle et lente). Chaque élément porte sa source et sa date ; aucun identifiant GT7 n'est inventé
 * (circuits sans identifiant officiel connu), aucun point de virage n'est placé : seul le nombre officiel est gardé.
 * Dès que la base communautaire publie le circuit, sa fiche remplace celle-ci.
 */
data class OfficialUpdates(
    val gameVersion: String,
    val published: String,
    val source: String,
    val tracks: List<Track>,
    /** Identifiants des voitures ajoutées par cette mise à jour (contrôle de couverture de cars.csv). */
    val cars: List<Int>,
    /** Mises à jour annoncées, pas encore dans le jeu : affichées, jamais utilisées. */
    val upcoming: List<String>,
) {
    data class Track(
        val name: String,
        /** Mots qui désignent ce circuit dans un autre nom (« sugo » dans « Sportsland SUGO »). */
        val aliases: List<String>,
        val lengthM: Int,
        val corners: Int?,
        val category: String,
    ) {
        /** Le nom [other] désigne ce circuit (même nom, ou contient un de ses alias comme mot entier). */
        fun matches(other: String): Boolean {
            val k = Gt7Database.key(other)
            if (k == Gt7Database.key(name)) return true
            val words = k.split(' ')
            return aliases.any { a -> Gt7Database.key(a).let { it.isNotEmpty() && it in words } }
        }
    }

    val label: String get() = "notes officielles GT7 $gameVersion ($published)"

    companion object {
        const val FORMAT = "gt7pitwall-official-updates"

        fun parse(json: String): OfficialUpdates {
            val o = JSONObject(json)
            require(o.optString("format") == FORMAT) { "format de mise à jour inconnu" }
            val t = o.optJSONArray("tracks")
            val tracks = (0 until (t?.length() ?: 0)).map { i ->
                val x = t!!.getJSONObject(i)
                val al = x.optJSONArray("aliases")
                Track(
                    name = x.getString("name"),
                    aliases = (0 until (al?.length() ?: 0)).map { al!!.getString(it) },
                    lengthM = x.getInt("length_m"),
                    corners = x.optInt("corners", -1).takeIf { it > 0 },
                    category = x.optString("category", "circuit"),
                )
            }
            val c = o.optJSONArray("cars")
            val u = o.optJSONArray("upcoming")
            return OfficialUpdates(
                gameVersion = o.getString("game_version"),
                published = o.getString("published"),
                source = o.getString("source"),
                tracks = tracks,
                cars = (0 until (c?.length() ?: 0)).map { c!!.getJSONObject(it).getInt("id") },
                upcoming = (0 until (u?.length() ?: 0)).map { u!!.getString(it) },
            )
        }
    }
}

/** Couverture de la base, lisible dans l'Atelier : ce que Pitwall sait, et d'où. */
object DatabaseCoverage {
    fun lines(db: Gt7Database): List<String> {
        val out = mutableListOf<String>()
        val cars = db.cars
        val noGroup = cars.keys.count { db.carGroup(it) == null }
        out += "Voitures : ${cars.size} connues (base communautaire gt7info), ${noGroup} sans catégorie connue."
        val courses = db.courses
        val fromUpdates = courses.filter { it.source != null }
        val learned = courses.filter { it.learned }
        out += "Circuits : ${courses.count { !it.learned && it.source == null }} configurations de la base communautaire" +
            (if (fromUpdates.isNotEmpty()) " + ${fromUpdates.size} des notes officielles (${fromUpdates.joinToString { it.name }})" else "") +
            (if (learned.isNotEmpty()) " + ${learned.size} appris par Pitwall (${learned.joinToString { it.name }})" else "") + "."
        val sigCourses = db.geometrySignatures.flatMap { listOfNotNull(it.course, it.reverseCourse) }.toSet()
        out += "Reconnaissance par la géométrie : ${sigCourses.size} configurations ; virages numérotés : ${db.numberedCorners.size} circuits."
        val missingGeometry = fromUpdates.filter { it.id !in sigCourses }
        if (missingGeometry.isNotEmpty()) {
            out += "${missingGeometry.joinToString { it.name }} : reconnu par son nom (programme Sport) ou sa longueur, pas encore par la géométrie ni de virages placés" +
                missingGeometry.mapNotNull { it.officialCorners?.let { n -> " ($n virages officiels)" } }.firstOrNull().orEmpty() + "."
        }
        db.updates?.let { u ->
            val present = u.cars.count { it in cars }
            out += "Dernière mise à jour GT7 connue : ${u.gameVersion} du ${u.published} — voitures ajoutées présentes dans la base : $present sur ${u.cars.size}."
            out += "Source : ${u.source}"
            u.upcoming.forEach { out += "Annoncé, pas encore dans le jeu : $it" }
        }
        return out
    }
}
