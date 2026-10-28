package com.gt7pitwall.core

import com.gt7pitwall.core.analysis.Corner
import com.gt7pitwall.core.analysis.CornerMeasure
import com.gt7pitwall.core.analysis.CornerPass
import com.gt7pitwall.core.analysis.LapRecorder
import com.gt7pitwall.core.analysis.Placed
import com.gt7pitwall.core.driver.CornerCoach
import com.gt7pitwall.core.race.Comparability
import com.gt7pitwall.core.race.Compatibility
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.strategy.HistoryModel
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import com.gt7pitwall.core.telemetry.TyreSurfaces
import com.gt7pitwall.core.tyres.TyreModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #36, Sprint A : intégrité des données avant toute analyse pneus. Surfaces par roue conservées, code inconnu ≠
 * sortie de piste, absence de surface ≠ « propre prouvé », règlement contredit par GT7 mis en quarantaine.
 */
class DataIntegrityTest {
    private fun packet(id: Int, surfaces: String?): TelemetryPacket =
        TelemetryParser.parseDecrypted(TestPackets.plain(lap = 2, speedMps = 30f, packetId = id))!!
            .copy(surfaces = surfaces?.let { TyreSurfaces.parse(it.toByteArray()) })

    /** Un tour de 120 paquets (2 s) sur une même surface. */
    private fun samples(surfaces: String?) = LapRecorder().run {
        startLap(packet(1, surfaces))
        (2..120).forEach { add(packet(it, surfaces)) }
        current()
    }

    @Test
    fun oneWheelOnAKerbStaysVisibleAndIsNotAnOffTrack() {
        val s = samples("TTTC")
        assertEquals(1, s.last().surfaces!!.kerbWheels)
        assertEquals(false, s.last().offTrack)
        assertEquals(30f, s.last().speedMps)
        // L'accélération réellement obtenue est mesurée entre paquets (vitesse constante ici).
        assertEquals(0f, s.last().longAccel)
    }

    @Test
    fun anUnknownSurfaceCodeIsUncertainNeverAConfirmedOffTrack() {
        assertNull(samples("TTXX").last().offTrack, "deux codes inconnus : incertain")
        assertEquals(false, samples("TTTX").last().offTrack, "un seul inconnu, trois sur la piste")
        assertEquals(true, samples("TTGG").last().offTrack, "deux roues dans l'herbe : sortie mesurée")
        assertNull(samples(null).last().offTrack, "format A (296 octets) : aucune surface, rien n'est prouvé")
    }

    private fun pass(surfaces: String?): CornerPass {
        val s = samples(surfaces)
        val placed = s.mapIndexed { i, x -> Placed(x, i * 0.5f, 0f) }
        return CornerMeasure.pass(Corner(1, 10f, 25f, 40f, 0f, 55f), 2, placed)!!
    }

    @Test
    fun cornerPassesKeepKerbAndSurfaceQuality() {
        val kerb = pass("TTTC")
        assertEquals(1f, kerb.kerbShare)
        assertEquals(false, kerb.offTrack)
        assertEquals(0f, kerb.surfaceUnknownShare)
        val formatA = pass(null)
        assertNull(formatA.offTrack, "format A : propreté non prouvée")
        assertNull(formatA.kerbShare)
        assertEquals(1f, formatA.surfaceUnknownShare)
    }

    @Test
    fun referencesPreferPassesProvenOnTrack() {
        fun p(t: Int, off: Boolean?) = pass("TTTT").copy(timeMs = t, offTrack = off)
        val pool = listOf(p(9_000, null), p(9_100, false), p(9_200, false), p(9_300, false), p(8_000, true))
        assertEquals(listOf(9_100, 9_200, 9_300), CornerCoach.reference(pool).map { it.timeMs }, "surface inconnue écartée quand assez de passages prouvés")
        val formatA = listOf(p(9_000, null), p(9_100, null), p(9_200, null), p(8_000, true))
        assertEquals(listOf(9_000, 9_100, 9_200), CornerCoach.reference(formatA).map { it.timeMs }, "format A : seule une sortie mesurée est écartée")
    }

    private val profile = ProfileId(Series.DAILY_B, SessionKind.RACE)
    private fun ctx(declared: Int?, game: Int?) =
        SessionContext(EventProfile(id = profile), RaceRules(totalLaps = declared, trackId = 51), 3607, 100f, game)

    private fun record(id: String, c: SessionContext, laps: Int) = SessionRecord(
        id, c, 0, 0, SessionOutcome.FINISHED,
        (1..laps).map { LapEntry(it, 81_600 + it * 100, 4f, false, tyreAge = it - 1) },
    )

    @Test
    fun aRuleOf6LapsContradictedBy14MeasuredLapsIsQuarantined() {
        // Journaux Interlagos du 26/10 : « Course Sport B, 6 tours » déclarés, 14 tours courus et annoncés par GT7.
        val contradicted = ctx(6, 14)
        assertEquals("règlement de 6 tours, GT7 en annonçait 14", contradicted.rulesContradiction)
        assertEquals(Comparability.SAME_CAR_TRACK, Compatibility.compare(ctx(6, 6), contradicted).first, "jamais « même épreuve »")
        // Archive ancienne sans le nombre de GT7 : la contradiction se voit aux tours courus.
        assertEquals("règlement de 6 tours, 14 tours courus", record("old", ctx(6, null), 14).rulesQuarantine)
        assertNull(record("ok", ctx(14, 14), 14).rulesQuarantine)

        val sessions = listOf(record("a", ctx(6, null), 14), record("b", ctx(6, null), 14), record("c", ctx(6, null), 14))
        val m = HistoryModel.learn(ctx(6, 6), sessions)
        assertEquals(3, m.quarantined.size)
        assertTrue(m.lapTimeMs != null, "le rythme reste appris")
        assertNull(m.paceSlopeMsPerLap, "pas de dégradation par âge de pneus tirée d'un règlement contredit")
        assertEquals(0, TyreModel.pool(ctx(6, 6), sessions).laps.size, "aucun relais pneus appris")
    }
}
