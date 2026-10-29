package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.TrackGeometryRecognizer
import com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict
import com.gt7pitwall.core.knowledge.TrackSignature
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reconnaissance du circuit par la géométrie, sur les 72 signatures embarquées. Trajectoires SIMULÉES à partir des
 * tracés de référence (écart latéral et bruit ajoutés) : aucune trace réelle de Pitwall ne contient encore de
 * positions. Le repère X/Z reste à confirmer en course PS5.
 */
class TrackGeometryTest {
    private val sigs = TrackSignature.parseAll(File("../app/src/main/assets/gt7db/track_signatures.json").readText())

    /** Points tous les ~10 m le long du tracé, depuis l'index [start], sur [metres], avec décalage et bruit. */
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

    private fun recognise(points: List<Pair<Float, Float>>): Pair<Verdict, Float?> {
        val r = TrackGeometryRecognizer(sigs)
        var firstId: Float? = null
        for ((x, z) in points) {
            val v = r.onPosition(x, z, moving = true)
            if (v is Verdict.Identified && firstId == null) firstId = r.distanceM
        }
        return r.verdict to firstId
    }

    @Test
    fun everyEmbeddedLayoutIsRecognisedWithinOneLapAndNeverConfused() {
        assertEquals(72, sigs.size)
        val wrong = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val distances = mutableListOf<Float>()
        for (s in sigs) {
            for (rev in listOf(false, true)) {
                val expected = (if (rev) s.reverseCourse else s.course) ?: continue
                val (v, at) = recognise(drive(s, s.lengthM * 1.0f, reverse = rev, seed = s.lengthM))
                when (v) {
                    is Verdict.Identified -> if (v.course != expected) wrong += "${s.name}${if (rev) " (inverse)" else ""} → ${v.course}" else distances += at!!
                    is Verdict.Ambiguous -> if (expected !in v.courses) wrong += "${s.name} → ambigu ${v.courses}" else unresolved += "${s.name}${if (rev) " (inverse)" else ""} ${v.courses}"
                    else -> unresolved += "${s.name}${if (rev) " (inverse)" else ""} : $v"
                }
            }
        }
        println("Géométrie : ${distances.size} reconnus, médiane ${distances.sorted()[distances.size / 2].toInt()} m, max ${distances.max().toInt()} m ; non tranchés : $unresolved")
        // Jamais un autre circuit : c'est le critère absolu.
        assertTrue(wrong.isEmpty(), wrong.toString())
        // Configurations voisines (tracé commun sur un tour entier) : ambiguës, jamais tranchées au hasard.
        assertTrue(unresolved.size <= 12, unresolved.toString())
    }

    @Test
    fun harderDrivingNeverNamesTheWrongCircuit() {
        // Départ n'importe où (départ lancé, sortie des stands), 12 m du tracé, bruit ±5 m, une sortie de piste de 40 m.
        val wrong = mutableListOf<String>()
        var named = 0
        var total = 0
        for (s in sigs) {
            for (rev in listOf(false, true)) {
                val expected = (if (rev) s.reverseCourse else s.course) ?: continue
                total++
                val start = (s.lengthM * 7) % s.points
                val pts = drive(s, s.lengthM * 1.0f, reverse = rev, offset = 12f, noise = 5f, seed = s.lengthM + 7, start = start).toMutableList()
                val at = pts.size / 3
                for (k in at until minOf(at + 6, pts.size)) pts[k] = (pts[k].first + 40f) to pts[k].second
                val (v, _) = recognise(pts)
                if (v is Verdict.Identified) { if (v.course != expected) wrong += "${s.name} → ${v.course}" else named++ }
                if (v is Verdict.Ambiguous && expected !in v.courses) wrong += "${s.name} → ${v.courses}"
            }
        }
        println("Conduite difficile : $named reconnus sur $total")
        assertTrue(wrong.isEmpty(), wrong.toString())
        assertTrue(named >= total * 9 / 10, "$named sur $total")
    }

    @Test
    fun aReverseLayoutIsToldApartFromItsForwardTwin() {
        val s = sigs.first { it.reverseCourse != null && it.course != null }
        val (fwd, _) = recognise(drive(s, 1500f))
        val (rev, _) = recognise(drive(s, 1500f, reverse = true))
        assertIs<Verdict.Identified>(fwd)
        assertIs<Verdict.Identified>(rev)
        assertEquals(s.course, fwd.course)
        assertEquals(s.reverseCourse, rev.course)
        assertTrue(rev.reverse)
    }

    @Test
    fun leMansWithAndWithoutChicanesStayAmbiguousUntilTheyDiffer() {
        val full = sigs.first { it.course == 454 }
        // Départ : les deux configurations partagent la ligne droite des stands.
        val (early, _) = recognise(drive(full, 800f))
        assertTrue(early is Verdict.Ambiguous || (early is Verdict.Identified && early.course == 454), early.toString())
        if (early is Verdict.Ambiguous) assertTrue(454 in early.courses && 854 in early.courses, early.toString())
    }

    @Test
    fun anUnknownCircuitIsNeverNamed() {
        // Cercle de 1,5 km loin de tout tracé connu, puis le même cercle au centre du repère.
        for (cx in listOf(50_000f, 0f)) {
            val r = TrackGeometryRecognizer(sigs)
            var v: Verdict = Verdict.Pending
            for (k in 0 until 600) {
                val a = k * 2 * Math.PI / 300
                v = r.onPosition(cx + 240f * cos(a).toFloat(), 240f * sin(a).toFloat(), moving = true)
            }
            assertTrue(v is Verdict.Unknown || v is Verdict.Pending, "cercle à $cx : $v")
        }
    }

    @Test
    fun nothingIsDecidedStandingStillOrBeforeEnoughDistance() {
        val s = sigs.first()
        val r = TrackGeometryRecognizer(sigs)
        repeat(500) { r.onPosition(s.path[0], s.path[1], moving = true) }
        assertEquals(Verdict.Pending, r.verdict)
        for ((x, z) in drive(s, 200f)) r.onPosition(x, z, moving = true)
        assertEquals(Verdict.Pending, r.verdict)
    }

    @Test
    fun aMirroredFrameIsDetectedByTheDiagnostic() {
        // Si la PS5 envoyait Z inversé par rapport aux signatures : le circuit ne serait pas reconnu, mais le
        // diagnostic montrerait le bon tracé tout proche dans le repère « z inversé ».
        val s = sigs.first { it.course == 10 } // Suzuka
        val r = TrackGeometryRecognizer(sigs)
        for ((x, z) in drive(s, 1500f)) r.onPosition(x, -z, moving = true)
        assertIs<Verdict.Unknown>(r.verdict)
        val byFrame = r.closest().associateBy { it.frame }
        assertEquals("Suzuka Circuit", byFrame.getValue("z inversé").name)
        assertTrue(byFrame.getValue("z inversé").meanDevM < 15f, byFrame.toString())
        assertTrue(byFrame.getValue("normal").meanDevM > 25f, byFrame.toString())
    }
}
