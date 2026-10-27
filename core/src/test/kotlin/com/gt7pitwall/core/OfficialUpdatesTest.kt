package com.gt7pitwall.core

import com.gt7pitwall.core.analysis.NumberedCorners
import com.gt7pitwall.core.knowledge.DatabaseCoverage
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.knowledge.OfficialUpdates
import com.gt7pitwall.core.sport.ScheduleBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #33 : SUGO (GT7 1.72) absent de course.csv, ajouté depuis les notes officielles, sans rien inventer. */
class OfficialUpdatesTest {
    private fun asset(name: String) = File("../app/src/main/assets/gt7db/$name").readText()
    private fun db() = Gt7Database.parse(asset("cars.csv"), asset("maker.csv"), asset("course.csv")).also {
        it.updates = OfficialUpdates.parse(asset("official_updates.json"))
    }

    @Test
    fun sugoComesFromTheOfficialNotesWithItsSourceAndNothingInvented() {
        val bundled = Gt7Database.parse(asset("cars.csv"), asset("maker.csv"), asset("course.csv"))
        assertTrue(bundled.courses.none { "sugo" in Gt7Database.key(it.name) }, "absent de la base communautaire au 27/10")
        val sugo = db().courses.single { "sugo" in Gt7Database.key(it.name) }
        assertEquals("SUGO International Racing Course", sugo.name)
        assertEquals(3586, sugo.lengthM)
        assertEquals(13, sugo.officialCorners)
        assertNull(sugo.pitLaneDeltaS, "perte aux stands inconnue : jamais inventée")
        assertTrue(sugo.id <= Gt7Database.UPDATES_FIRST_ID, "aucun identifiant GT7 inventé")
        assertEquals("notes officielles GT7 1.72 (15/10/2026)", sugo.source)
        assertTrue(NumberedCorners.parseAll(asset("track_corners.json")).values.none { "sugo" in Gt7Database.key(it.name) }, "aucun virage placé")
    }

    @Test
    fun theLearnedSugoKeepsItsIdAndMemory() {
        // Le téléphone avait appris SUGO sous l'identifiant -1 (journal du 27/10), avec sa mémoire.
        val d = Gt7Database.parse(asset("cars.csv"), asset("maker.csv"), asset("course.csv"))
        d.restoreLearned(Gt7Database.learnedFrom("""[{"id":-1,"name":"Sportsland SUGO","lengthM":3540}]"""))
        d.updates = OfficialUpdates.parse(asset("official_updates.json"))
        val sugo = d.course(-1)!!
        assertEquals("SUGO International Racing Course", sugo.name)
        assertEquals(3586, sugo.lengthM, "longueur officielle, pas la mesure de 3 540 m")
        assertEquals(1, d.courses.count { "sugo" in Gt7Database.key(it.name) }, "pas de doublon")
        assertEquals(listOf(-1), d.learned.map { it.id }, "toujours enregistré : l'historique reste attaché")
        assertEquals(-1, d.learn("SUGO International Racing Course", 3540).id)
        assertEquals(listOf(-1), ScheduleBuilder.matchTrack("SUGO International Racing Course", d).map { it.id })
    }

    @Test
    fun theCommunityEntryWinsOnceItIsPublished() {
        val csv = asset("course.csv").trimEnd() + "\n9999,SUGO International Racing Course,99,2,circuit,3586,700,70.0,0,0,0,0,0,0,0,1,0,20.0,0,13,0\n"
        val d = Gt7Database.parse(asset("cars.csv"), asset("maker.csv"), csv)
        d.restoreLearned(Gt7Database.learnedFrom("""[{"id":-1,"name":"SUGO International Racing Course","lengthM":3540}]"""))
        d.updates = OfficialUpdates.parse(asset("official_updates.json"))
        val sugo = d.courses.filter { "sugo" in Gt7Database.key(it.name) }
        assertEquals(listOf(9999), sugo.map { it.id })
        assertNull(sugo.single().source)
        assertEquals(9999, d.course(-1)?.id, "ancienne mémoire rattachée par le nom")
    }

    @Test
    fun theLengthAloneStaysAmbiguousWithLagunaSeca() {
        // Longueur mesurée le 27/10 : ~3 540 m. Laguna Seca (3 602 m) est aussi proche : pas de choix forcé.
        val names = db().coursesMatching(3540f).map { it.name }
        assertTrue(names.any { "SUGO" in it } && names.any { "Laguna" in it }, names.toString())
    }

    @Test
    fun theWorkshopShowsCoverageAndSources() {
        val lines = DatabaseCoverage.lines(db())
        assertTrue(lines.any { "voitures ajoutées présentes dans la base : 6 sur 6" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("SUGO International Racing Course : reconnu par son nom") && "13 virages officiels" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("Annoncé, pas encore dans le jeu : Spec IV partie 2") })
        assertTrue(lines.any { it.startsWith("Source : Notes de mise à jour GT7") })
    }
}
