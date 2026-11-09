package com.gt7pitwall.core.display

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SportNames
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Briefing (demande du pilote, 09/11) : une carte par course Sport A/B/C, lisible d'un coup d'œil — circuit (nom et
 * TRACÉ dessiné par Pitwall à partir des captures libres CC0 de `track_signatures.json`), voiture ou catégorie (badge
 * « Gr.3 », « N300 »… ; voiture précise : code GT7 pour la photo officielle), tours, gommes, multiplicateurs.
 *
 * Rien n'est deviné : un champ inconnu ou contradictoire reste null avec son statut ; un tracé absent n'est pas dessiné.
 */
data class SportCard(
    val series: Series,
    val letter: Char,
    val current: Boolean,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val trackName: String?,
    val trackStatus: FieldStatus,
    val lengthM: Int?,
    /** Tracé normalisé dans [0,1]² (x0, y0, x1, y1…), proportions conservées ; null sans signature du circuit. */
    val outline: FloatArray?,
    val laps: Int?,
    val carsText: String?,
    val carsStatus: FieldStatus,
    /** Catégories citées (« Gr.3 », « Gr.4 », « Gr.B », « N300 »). */
    val classes: List<String>,
    /** Voiture précise reconnue dans la base (code GT7, pour la photo officielle), null pour une catégorie. */
    val carCode: Int?,
    val carName: String?,
    val allowedTyres: Set<TyreCompound>?,
    val requiredTyres: Set<TyreCompound>?,
    val mandatoryStops: Int?,
    val fuelMultiplier: Int?,
    val tyreMultiplier: Int?,
    val sources: List<String>,
    /** Identifiant du tracé dans le catalogue officiel GT7 (fiche et logo du site), null sans signature. */
    val officialTrackId: String? = null,
) {
    /** Deux gommes obligatoires ou plus : passage aux stands nécessaire pour changer de gomme. */
    val tyreChangeNeeded: Boolean get() = (requiredTyres?.size ?: 0) >= 2

    override fun equals(other: Any?) = other is SportCard && other.series == series && other.validFrom == validFrom && other.trackName == trackName &&
        other.carsText == carsText && other.laps == laps && other.current == current && other.outline.contentEqualsNullable(outline)
    override fun hashCode() = listOf(series, validFrom, trackName, carsText, laps, current).hashCode()

    companion object {
        private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        private fun FloatArray?.contentEqualsNullable(o: FloatArray?) = if (this == null) o == null else o != null && contentEquals(o)

        /** Les cartes A, B, C de l'état du programme (dans l'ordre). */
        fun all(state: ScheduleState?, db: Gt7Database, nowMs: Long): List<SportCard> =
            state?.editions?.toSortedMap()?.values?.filter { it.series != Series.OTHER }?.map { of(it, db, nowMs, state.stale) }.orEmpty()

        fun of(ed: Edition, db: Gt7Database, nowMs: Long, stale: Boolean = false): SportCard {
            val today = Instant.ofEpochMilli(nowMs).atZone(PARIS).toLocalDate()
            val course = ed.trackId?.let(db::course)
            val sig = ed.trackId?.let { id -> db.geometrySignatures.firstOrNull { it.course == id || it.reverseCourse == id } }
            val carsText = ed.cars.value?.takeIf { ed.cars.known }
            val car = carsText?.let { matchCar(it, db) }
            return SportCard(
                series = ed.series,
                letter = ed.series.letter,
                current = !stale && !today.isBefore(ed.validFrom) && today.isBefore(ed.validTo),
                validFrom = ed.validFrom,
                validTo = ed.validTo,
                trackName = course?.label ?: ed.trackName.value?.takeIf { ed.trackName.known },
                trackStatus = ed.trackName.status,
                lengthM = course?.lengthM ?: sig?.lengthM,
                outline = sig?.let { normalize(it.path) },
                laps = ed.laps.value?.takeIf { ed.laps.known },
                carsText = carsText,
                carsStatus = ed.cars.status,
                classes = carsText?.let(::classes).orEmpty(),
                carCode = car?.id,
                carName = car?.fullName,
                allowedTyres = ed.allowedTyres.value?.takeIf { ed.allowedTyres.known },
                requiredTyres = ed.requiredTyres.value?.takeIf { ed.requiredTyres.known },
                mandatoryStops = ed.mandatoryStops.value?.takeIf { ed.mandatoryStops.known },
                fuelMultiplier = ed.fuelMultiplier.value?.takeIf { ed.fuelMultiplier.known },
                tyreMultiplier = ed.tyreMultiplier.value?.takeIf { ed.tyreMultiplier.known },
                sources = ed.sources.map { it.name }.distinct(),
                officialTrackId = sig?.officialId,
            )
        }

        /** « Gr.3 », « Gr.4 », « Gr.B », « N300 » cités dans le texte des voitures. */
        fun classes(text: String): List<String> {
            val n = SportNames.normalize(text)
            val out = linkedSetOf<String>()
            Regex("(?:^| )(?:gr|group|groupe) ?([1-4b])(?= |$)").findAll(n).forEach { out += "Gr." + it.groupValues[1].uppercase() }
            Regex("(?:^| )n ?(100|200|300|400|500|600|700|800|1000)(?= |$)").findAll(n).forEach { out += "N" + it.groupValues[1] }
            return out.toList()
        }

        /** Voiture précise de la base dont le nom correspond (même clé que la fusion des sources), sinon null. */
        fun matchCar(text: String, db: Gt7Database): com.gt7pitwall.core.knowledge.Car? {
            if (classes(text).isNotEmpty()) return null
            val key = SportNames.carKey(text)
            val all = db.cars.values
            all.firstOrNull { SportNames.carKey(it.fullName) == key || SportNames.carKey(it.name) == key }?.let { return it }
            // Nom publié plus long que le nom court de la base (« Audi TT Cup '16 » contient « TT Cup '16 ») : un seul candidat.
            val hits = all.filter { c -> SportNames.carKey(c.name).length >= 6 && key.contains(SportNames.carKey(c.name)) }
            return hits.singleOrNull()
        }

        /** Tracé ramené dans [0,1]² en gardant ses proportions (centré sur l'axe le plus court). */
        fun normalize(path: FloatArray): FloatArray? {
            if (path.size < 8) return null
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (i in path.indices step 2) {
                minX = minOf(minX, path[i]); maxX = maxOf(maxX, path[i])
                minZ = minOf(minZ, path[i + 1]); maxZ = maxOf(maxZ, path[i + 1])
            }
            val span = maxOf(maxX - minX, maxZ - minZ).takeIf { it > 0f } ?: return null
            val offX = (span - (maxX - minX)) / 2f
            val offZ = (span - (maxZ - minZ)) / 2f
            return FloatArray(path.size) { i ->
                if (i % 2 == 0) (path[i] - minX + offX) / span else (path[i] - minZ + offZ) / span
            }
        }
    }
}
