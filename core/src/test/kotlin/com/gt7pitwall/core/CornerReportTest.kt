package com.gt7pitwall.core

import com.gt7pitwall.core.analysis.Corner
import com.gt7pitwall.core.analysis.CornerLabels
import com.gt7pitwall.core.analysis.CornerMap
import com.gt7pitwall.core.analysis.CornerPass
import com.gt7pitwall.core.analysis.CornerReport
import com.gt7pitwall.core.analysis.NumberedCorners
import com.gt7pitwall.core.knowledge.TrackGeometryRecognizer
import com.gt7pitwall.core.knowledge.TrackSignature
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #30-C : virages numérotés (source communautaire CC0, couverture vérifiée) et bilan par virage mesuré. */
class CornerReportTest {
    private val numbered = NumberedCorners.parseAll(File("../app/src/main/assets/gt7db/track_corners.json").readText())
    private val sigs = TrackSignature.parseAll(File("../app/src/main/assets/gt7db/track_signatures.json").readText())

    @Test
    fun theRealCoverageIsKnownAndNumberingMismatchesAreFlagged() {
        assertEquals(10, numbered.size)
        val suzuka = numbered.getValue(10)
        assertEquals(19, suzuka.corners.size)
        assertFalse(suzuka.matchesOfficial, "19 virages relevés pour 20 officiels : numérotation à vérifier")
        assertTrue(numbered.getValue(469).matchesOfficial, "Monza : 11 sur 11")
        assertFalse(numbered.getValue(471).matchesOfficial, "Tsukuba : 12 relevés pour 8 officiels")
    }

    @Test
    fun suzukaCornersLieOnTheSignatureThatRecognisedSuzukaOnThe27th() {
        // Deux jeux de données différents de la même source, et la signature qui a reconnu Suzuka le 27/10 (1,4 m) :
        // chaque apex numéroté doit être sur le tracé (même repère X/Z).
        val path = sigs.first { it.course == 10 }.path
        for (r in numbered.getValue(10).corners) {
            val d = TrackGeometryRecognizer.nearest(path, r.x, r.z).second
            assertTrue(d < 30f, "T${r.n} à ${d} m du tracé")
        }
    }

    private fun map(): CornerMap {
        // Ligne droite le long de X, trois virages à 100 m, 300 m et 500 m.
        val n = 140
        val xs = FloatArray(n) { it * CornerMap.STEP_M }
        val zs = FloatArray(n)
        fun c(k: Int, apex: Float) = Corner(k, apex - 20, apex, apex + 20, apex - 100, apex + 80)
        return CornerMap(xs, zs, listOf(c(1, 100f), c(2, 300f), c(3, 500f)))
    }

    @Test
    fun detectedCornersTakeTheCircuitNumberOnlyWhenTheApexMatches() {
        val refs = NumberedCorners(999, "Test", true, listOf(
            NumberedCorners.Ref(4, "Épingle", 102f, 5f), NumberedCorners.Ref(7, "", 310f, -8f), NumberedCorners.Ref(8, "", 312f, 0f),
        ))
        val labels = CornerLabels.of(map(), refs)
        assertEquals("T4", labels.getValue(1).text)
        assertEquals("Épingle", labels.getValue(1).name)
        // Deux apex proches du virage 2 : le plus proche seulement (T8 à 12 m contre T7 à 12,8 m), une fois.
        assertEquals("T8", labels.getValue(2).text)
        assertEquals("V3", labels.getValue(3).text, "aucun apex relevé : numéro estimé")
        assertFalse(labels.getValue(3).community)
        // Sens inverse : la numérotation du sens normal ne s'applique pas.
        assertTrue(CornerLabels.of(map(), refs, reverse = true).values.none { it.community })
    }

    private fun pass(corner: Int, lap: Int, timeMs: Int, brake: Float?, minKmh: Float, throttle: Float?, lock: Boolean = false) = CornerPass(
        corner = corner, lap = lap, timeMs = timeMs, brakeStartM = brake, brakePeak = 0.9f, brakeRampMs = 200,
        entryKmh = 210f, minKmh = minKmh, minAtM = 100f, throttleOnM = throttle, exitKmh = 150f, lockup = lock, wheelspin = false,
        tcsShare = 0f, apexLateralM = 0f, rotationRatio = null, offTrack = false,
    )

    @Test
    fun theReportComparesEquivalentPassesAndNamesAMeasuredCause() {
        val passes = listOf(
            pass(1, 2, 5_000, brake = 300f, minKmh = 95f, throttle = 380f),
            pass(1, 3, 5_250, brake = 285f, minKmh = 92f, throttle = 382f),
            pass(1, 4, 5_300, brake = 284f, minKmh = 91f, throttle = 381f),
            pass(1, 5, 5_220, brake = 286f, minKmh = 93f, throttle = 380f),
            pass(1, 6, 5_280, brake = 283f, minKmh = 92f, throttle = 383f),
            pass(2, 2, 3_000, brake = 600f, minKmh = 140f, throttle = 650f),
            pass(2, 3, 3_010, brake = 601f, minKmh = 140f, throttle = 650f),
            pass(3, 2, 4_000, brake = 800f, minKmh = 120f, throttle = 850f),
        )
        val labels = mapOf(1 to CornerLabels.Label(1, "T5", true), 2 to CornerLabels.Label(2, "V2", false))
        val report = CornerReport.of(passes, labels)
        assertEquals(listOf("T5", "V2"), report.map { it.label }, "un seul passage : pas de comparaison")
        val t5 = report.first()
        assertEquals(263, t5.meanLossMs) // (250 + 300 + 220 + 280) / 4 = 262,5
        assertEquals("freinage 16 m plus tôt", t5.cause)
        assertEquals(CornerReport.Confidence.HIGH, t5.confidence)
        assertTrue(t5.text.startsWith("T5 : 5 passages, 0,26 s perdus en moyenne"), t5.text)
        val v2 = report[1]
        assertNull(v2.cause, "écart de 10 ms : aucune cause inventée")
        assertTrue("régulier" in v2.text, v2.text)
    }
}
