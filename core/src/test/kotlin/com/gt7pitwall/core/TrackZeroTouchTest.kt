package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.knowledge.OfficialUpdates
import com.gt7pitwall.core.knowledge.TrackGeometryRecognizer
import com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict
import com.gt7pitwall.core.knowledge.TrackSignature
import com.gt7pitwall.core.race.Milestone
import com.gt7pitwall.core.race.RaceController
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #58, zéro touch circuits : signatures ouvertes (gt7-datalogger-track-data, commit 80d0a11 : 72 utilisables dont
 * SUGO), reconnaissance anticipée en roulant et candidat probable dès la position à l'arrêt.
 *
 * Toutes les trajectoires sont SYNTHÉTIQUES (tirées des tracés de référence, décalage et bruit ajoutés) : aucune trace
 * réelle de grille PS5 n'est disponible. Rien ici ne valide la grille sur console.
 */
class TrackZeroTouchTest {
    private fun asset(name: String) = File("../app/src/main/assets/gt7db/$name").readText()

    /** Base réelle : gt7info + notes officielles (SUGO 1.72) + signatures. */
    private val db = Gt7Database.parse(asset("cars.csv"), asset("maker.csv"), asset("course.csv")).also {
        it.updates = OfficialUpdates.parse(asset("official_updates.json"))
        it.trackSignatures = TrackSignature.parseAll(asset("track_signatures.json"))
    }
    private val sigs get() = db.geometrySignatures

    private fun drive(sig: TrackSignature, metres: Float, reverse: Boolean = false, offset: Float = 6f, noise: Float = 3f, seed: Int = 1, start: Int = 0): List<Pair<Float, Float>> {
        val rnd = Random(seed)
        val n = sig.points
        val out = mutableListOf<Pair<Float, Float>>()
        var travelled = 0f
        var k = 0
        while (travelled < metres) {
            val i = ((if (reverse) start - k else start + k) % n + n) % n
            val j = ((if (reverse) i - 1 else i + 1) % n + n) % n
            val ax = sig.path[2 * i]; val az = sig.path[2 * i + 1]
            val bx = sig.path[2 * j]; val bz = sig.path[2 * j + 1]
            val len = hypot(bx - ax, bz - az).coerceAtLeast(0.1f)
            val nx = -(bz - az) / len; val nz = (bx - ax) / len
            var t = 0f
            while (t < len) {
                val f = t / len
                out += (ax + f * (bx - ax) + nx * offset + (rnd.nextFloat() - 0.5f) * 2 * noise) to
                    (az + f * (bz - az) + nz * offset + (rnd.nextFloat() - 0.5f) * 2 * noise)
                t += 5f
            }
            travelled += len
            k++
        }
        return out
    }

    private fun firstIdentified(points: List<Pair<Float, Float>>): Pair<Verdict, Float?> {
        val r = TrackGeometryRecognizer(sigs)
        var at: Float? = null
        for ((x, z) in points) {
            val v = r.onPosition(x, z, moving = true)
            if (v is Verdict.Identified && at == null) at = r.distanceM
        }
        return r.verdict to at
    }

    @Test fun sugoIsRattachedToTheOfficialUpdateCourseWithoutInventingAnId() {
        assertEquals(72, sigs.size)
        val sugo = sigs.single { it.name == "SUGO International Racing Course" }
        val course = assertNotNull(sugo.course, "SUGO rattaché à la fiche des notes officielles")
        assertEquals("SUGO International Racing Course", db.course(course)!!.name)
        assertTrue(course < 0, "identifiant interne Pitwall des notes officielles, jamais un identifiant GT7 inventé")
        assertNotNull(sugo.resolvedBy)
        // Signatures sans fiche correspondante (longueur à plus de 3 %) : gardées sans circuit, jamais rattachées au hasard.
        val unresolved = sigs.filter { it.course == null && it.reverseCourse == null }.map { it.name }.sorted()
        assertEquals(listOf("Circuit de Barcelona-Catalunya GP Layout", "Circuit de Barcelona-Catalunya GP Layout No Chicane", "Eiger Nordwand", "Yas Marina Circuit"), unresolved)
    }

    @Test fun sugoIsRecognisedWellBeforeTheEndOfTheFirstLap() {
        val sugo = sigs.single { it.name == "SUGO International Racing Course" }
        // Départs à différents endroits du tour (grille, sortie des stands, départ lancé).
        val at = (0 until 6).map { k ->
            val (v, d) = firstIdentified(drive(sugo, 1200f, start = k * sugo.points / 6, seed = 40 + k))
            assertIs<Verdict.Identified>(v, "départ $k")
            assertEquals(sugo.course, v.course)
            assertNotNull(d)
        }
        // Avant : SUGO restait inconnu après 702 m (28/10). Ici, sur trajectoires synthétiques, bien avant.
        assertTrue(at.max() < 700f, at.toString())
        println("SUGO (synthétique) reconnu à ${at.map { it.toInt() }} m")
    }

    @Test fun earlyRecognitionNeverNamesTheWrongCircuit() {
        val wrong = mutableListOf<String>()
        val distances = mutableListOf<Float>()
        var total = 0
        for (s in sigs) for (rev in listOf(false, true)) {
            val expected = (if (rev) s.reverseCourse else s.course) ?: continue
            for (seed in 0 until 3) {
                total++
                val start = (s.lengthM * (seed + 3)) % s.points
                val (v, d) = firstIdentified(drive(s, s.lengthM.toFloat(), reverse = rev, offset = 8f, noise = 4f, seed = s.lengthM + seed, start = start))
                when (v) {
                    is Verdict.Identified -> if (v.course != expected) wrong += "${s.name}${if (rev) " inv." else ""} → ${v.course}" else distances += d!!
                    is Verdict.Ambiguous -> if (expected !in v.courses) wrong += "${s.name} → ${v.courses}"
                    else -> Unit
                }
            }
        }
        val sorted = distances.sorted()
        println("Anticipé : ${distances.size}/$total reconnus ; médiane ${sorted[sorted.size / 2].toInt()} m ; 90 % ${sorted[sorted.size * 9 / 10].toInt()} m ; max ${sorted.last().toInt()} m")
        assertTrue(wrong.isEmpty(), wrong.toString())
        assertTrue(sorted[sorted.size / 2] < 700f, "médiane ${sorted[sorted.size / 2]}")
    }

    @Test fun aVenueWithAnUnsignedVariantWaitsForTheFullDistance() {
        // Fuji Short et Suzuka East n'ont pas de signature : leur début ressemble à Fuji et Suzuka. Pas d'anticipation.
        for (name in listOf("Fuji International Speedway", "Suzuka Circuit")) {
            val s = sigs.single { it.name == name }
            assertTrue(s.venueIncomplete, name)
            val (v, d) = firstIdentified(drive(s, s.lengthM.toFloat(), seed = 5))
            if (v is Verdict.Identified) {
                assertEquals(s.course, v.course)
                assertTrue(d!! >= TrackGeometryRecognizer.MIN_DISTANCE_M, "$name à $d m")
            }
        }
        assertTrue(!sigs.single { it.name == "SUGO International Racing Course" }.venueIncomplete)
    }

    @Test fun lagunaSecaIsNeverTakenForSugo() {
        // Longueurs voisines (3 602 m / 3 586 m) : la longueur ne tranche pas, le tracé si.
        val laguna = sigs.single { it.name == "WeatherTech Raceway Laguna Seca" }
        val sugo = sigs.single { it.name == "SUGO International Racing Course" }
        val (v, _) = firstIdentified(drive(laguna, laguna.lengthM.toFloat(), seed = 9))
        assertTrue(v !is Verdict.Identified || v.course != sugo.course, v.toString())
        assertIs<Verdict.Identified>(v)
        assertEquals(laguna.course, v.course)
    }

    @Test fun aStandingPositionOnlyEverGivesCandidatesThatIncludeTheTrueTrack() {
        // Positions à l'arrêt sur le tracé (grille fictive : tous les 100 m, 6 m de décalage latéral).
        var unique = 0
        var total = 0
        val ambiguity = mutableMapOf<Int, Int>()
        for (s in sigs) {
            val step = maxOf(1, s.points / 20)
            for (i in 0 until s.points step step) {
                val (x, z) = drive(s, 5f, start = i, offset = 6f, noise = 0f).first()
                val m = TrackGeometryRecognizer.positionCandidates(sigs, x, z)
                total++
                assertTrue(m.any { it.sig === s }, "${s.name} absent de ses propres candidats à $i")
                if (m.size == 1) unique++
                ambiguity.merge(m.size, 1, Int::plus)
            }
        }
        println("Position à l'arrêt : $unique/$total positions désignent un seul tracé ; répartition ${ambiguity.toSortedMap()}")
    }

    @Test fun aPositionFarFromAnyKnownTrackGivesNoCandidate() {
        assertTrue(TrackGeometryRecognizer.positionCandidates(sigs, 50_000f, 50_000f).isEmpty())
    }

    @Test fun recognitionStaysCheapEnoughForTheTelemetryLoop() {
        val sugo = sigs.single { it.name == "SUGO International Racing Course" }
        val pts = drive(sugo, 3000f, seed = 3)
        val r = TrackGeometryRecognizer(sigs)
        val t0 = System.nanoTime()
        for ((x, z) in pts) r.onPosition(x, z, moving = true)
        val perPointUs = (System.nanoTime() - t0) / 1000.0 / pts.size
        val t1 = System.nanoTime()
        repeat(20) { TrackGeometryRecognizer.positionCandidates(sigs, pts[it * 10].first, pts[it * 10].second) }
        val probeMs = (System.nanoTime() - t1) / 1e6 / 20
        println("Coût JVM : ${"%.1f".format(perPointUs)} µs par position en roulant, ${"%.2f".format(probeMs)} ms par examen à l'arrêt")
        // Garde-fou large (machine de CI) : un paquet arrive toutes les 16,7 ms.
        assertTrue(probeMs < 50.0, "$probeMs ms")
    }

    // ---------------------------------------------------------------- Course simulée (RaceSim : cercle de 480 m)

    private fun ring(name: String, course: Int?, cx: Float = 0f): TrackSignature {
        val n = (2 * PI * 480 / 20).toInt()
        val path = FloatArray(2 * n)
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            path[2 * i] = cx + 480f * cos(a).toFloat()
            path[2 * i + 1] = 480f * sin(a).toFloat()
        }
        return TrackSignature(name, course, null, 3016, cx - 480f, cx + 480f, -480f, 480f, path)
    }

    private fun ringDb(vararg sigs: TrackSignature) = Gt7Database.parse(
        "ID,ShortName,Maker\n3607,Test GT,1\n",
        "ID,Name,Country\n1,Testeur,1\n",
        "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain\n" +
            "900,Anneau de Test,900,3,circuit,3016,500,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n" +
            "901,Autre Circuit,901,3,circuit,5800,900,10.0,8.0,0,0,0,0,0,0,1,0,20.0,0,16,0\n",
    ).also { it.trackSignatures = sigs.toList() }

    @Test fun onTheGridTheCircuitIsProbableThenMeasuredEarlyInTheFirstLap() {
        val c = RaceController(database = ringDb(ring("Anneau de Test", 900), ring("Autre Circuit", 901, cx = 5000f)))
        val sim = RaceSim(c, totalLaps = 5, perLap = 3f)
        sim.stand(3f)
        assertEquals(900, c.rules.trackId, "circuit probable dès la grille, sans tour roulé")
        assertTrue(sim.texts.any { it == "Circuit probable d'après la position sur la grille : Anneau de Test." }, sim.texts.toString())
        val cand = assertNotNull(c.zeroTouch.first(Milestone.TRACK_CANDIDATE))
        assertEquals("probable", cand.confidence)
        assertNull(c.zeroTouch.first(Milestone.TRACK_MEASURED), "un point à l'arrêt ne prouve pas le circuit")
        // En roulant : tracé mesuré bien avant la fin du tour.
        while (sim.lap == 0) sim.step(30f)
        while (c.zeroTouch.first(Milestone.TRACK_MEASURED) == null && sim.lap == 1) sim.step(60f)
        val measured = assertNotNull(c.zeroTouch.first(Milestone.TRACK_MEASURED))
        val metres = Regex("à (\\d+) m").find(measured.detail!!)!!.groupValues[1].toInt()
        assertTrue(metres < 700, measured.detail)
        assertEquals(1, sim.texts.count { it.startsWith("Circuit probable d'après la position") }, "annoncé une seule fois")
    }

    @Test fun aGridPositionOffEveryKnownTrackChangesNothing() {
        // Seul tracé connu loin de la grille : aucun candidat, rien n'est appliqué ni annoncé.
        val c = RaceController(database = ringDb(ring("Autre Circuit", 901, cx = 5000f)))
        val sim = RaceSim(c, totalLaps = 5, perLap = 3f)
        sim.stand(3f)
        assertNull(c.rules.trackId)
        assertNull(c.zeroTouch.first(Milestone.TRACK_CANDIDATE))
        assertTrue(sim.texts.none { "Circuit probable" in it }, sim.texts.toString())
    }

    @Test fun twoTracksThroughTheGridPositionGiveNoConclusionUntilDriving() {
        // Deux tracés confondus au point de grille : pas de candidat unique, aucune conclusion à l'arrêt.
        val c = RaceController(database = ringDb(ring("Anneau de Test", 900), ring("Autre Circuit", 901)))
        val sim = RaceSim(c, totalLaps = 5, perLap = 3f)
        sim.stand(3f)
        assertNull(c.rules.trackId)
        assertNull(c.zeroTouch.first(Milestone.TRACK_CANDIDATE))
    }
}
