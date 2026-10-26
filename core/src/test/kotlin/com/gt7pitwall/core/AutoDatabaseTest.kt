package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.sport.DailyRaceInfo
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.sport.WeeklyPost
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voitures et circuits automatiques. SUGO (sorti le 15/10 avec la mise à jour Spec IV) manquait à la base communautaire :
 * l'épreuve Sport qui l'utilise ne doit pas être prise pour un circuit de longueur voisine, et le circuit est appris.
 */
class AutoDatabaseTest {
    private val header = "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain"

    /** Circuit officiel de même longueur que le circuit simulé (~3 016 m) : la longueur seule le désignerait à tort. */
    private fun db(extra: String = "") = Gt7Database.parse(
        "ID,ShortName,Maker\n3607,963 '24,136\n",
        "ID,Name,Country\n136,Porsche,6\n",
        "$header\n10,Sim Ring,1,1,circuit,3016,500,1.0,1.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n$extra",
    )

    private fun sportWeek(track: String, database: Gt7Database) = ScheduleBuilder.build(
        listOf(
            WeeklyPost(
                SourceRef("GTPlanet", null, 1), LocalDate.of(2026, 10, 26), "Daily Races",
                mapOf(Series.DAILY_A to DailyRaceInfo(Series.DAILY_A, SourceRef("GTPlanet", null, 1), trackName = track, laps = 5)),
            ),
        ),
        database, LocalDate.of(2026, 10, 27), 0, null,
    )

    @Test
    fun aSportTrackMissingFromTheDatabaseIsLearnedNotConfused() {
        val storage = FakeStorage()
        val database = db()
        val c = RaceController(database, storage)
        c.selectProfile(ProfileId(Series.DAILY_A, SessionKind.RACE))
        c.applySchedule(sportWeek("SUGO International Racing Course", database))
        val s = RaceSim(c, totalLaps = 5, perLap = 5f)
        s.start()
        s.driveLaps(2)
        val course = database.course(c.rules.trackId!!)
        assertEquals("SUGO International Racing Course", course?.name, "jamais « Sim Ring » d'après la seule longueur")
        assertTrue(course!!.learned && course.lengthM in 2_900..3_100, course.toString())
        assertTrue(s.texts.any { "Nouveau circuit enregistré : SUGO" in it }, s.texts.toString())
        assertNotNull(storage.learnedTracks)

        // Relancement : le circuit appris est relu, et le programme Sport le reconnaît désormais par son nom.
        val database2 = db()
        RaceController(database2, storage)
        assertEquals(course.id, ScheduleBuilder.matchTrack("SUGO International Racing Course", database2).single().id)
        assertEquals(course.id, sportWeek("SUGO International Racing Course", database2).editions[Series.DAILY_A]?.trackId)

        // La base communautaire publie SUGO : la fiche officielle remplace la fiche apprise, même identifiant ancien compris.
        database2.replace(db("471,SUGO International Racing Course,1,2,circuit,3737,700,70.0,1.0,0,0,0,0,0,0,1,0,25.0,0,17,0\n"))
        assertEquals(471, database2.course(course.id)?.id)
        assertTrue(database2.learned.isEmpty())
    }

    @Test
    fun withoutAPublishedNameNothingIsInvented() {
        // Course personnalisée sur un circuit absent de la base : aucun nom n'est inventé, aucun circuit n'est créé
        // (un tour déformé par les stands créerait un faux circuit). Le circuit se choisit dans la carte Course.
        val database = Gt7Database.parse("ID,ShortName,Maker\n", "ID,Name,Country\n", "$header\n")
        val c = RaceController(database, FakeStorage())
        val s = RaceSim(c, totalLaps = 5, perLap = 5f)
        s.start()
        s.driveLaps(2)
        assertNull(c.rules.trackId)
        assertTrue(database.learned.isEmpty())
    }

    @Test
    fun aTruncatedDownloadNeverReplacesTheDatabase() {
        val current = Gt7Database.parse(
            "ID,ShortName,Maker\n" + (1..100).joinToString("\n") { "$it,Car $it,1" },
            "ID,Name,Country\n1,Maker,1\n",
            "$header\n10,Sim Ring,1,1,circuit,3016,500,1.0,1.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n",
        )
        val truncated = "ID,ShortName,Maker\n" + (1..40).joinToString("\n") { "$it,Car $it,1" }
        assertNull(Gt7Database.parseChecked(truncated, "ID,Name,Country\n1,Maker,1\n", "$header\n10,Sim Ring,1,1,circuit,3016,500,1.0,1.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n", current))
        assertNull(Gt7Database.parseChecked("<html>404</html>", "", "", current))
        val more = "ID,ShortName,Maker\n" + (1..106).joinToString("\n") { "$it,Car $it,1" }
        val fresh = Gt7Database.parseChecked(more, "ID,Name,Country\n1,Maker,1\n", "$header\n10,Sim Ring,1,1,circuit,3016,500,1.0,1.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n", current)
        assertEquals(106, fresh?.cars?.size)
    }
}
