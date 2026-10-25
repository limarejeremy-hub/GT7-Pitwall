package com.gt7pitwall.core

import com.gt7pitwall.core.analysis.CornerMap
import com.gt7pitwall.core.analysis.CornerMeasure
import com.gt7pitwall.core.analysis.LapRecorder
import com.gt7pitwall.core.analysis.LapSample
import com.gt7pitwall.core.analysis.TrackProjection
import com.gt7pitwall.core.driver.CornerCoach
import com.gt7pitwall.core.driver.CornerIssue
import com.gt7pitwall.core.driver.DriverBook
import com.gt7pitwall.core.driver.DriverCoach
import com.gt7pitwall.core.driver.DriverProfile
import com.gt7pitwall.core.session.LapRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Race Engineer V3, phase 2 : virages reconnus, passages mesurés, profil Driver DNA et coaching par zone. */
class DriverDnaTest {

    private val normal = CornerSim.Style()
    private fun styles(c1: CornerSim.Style = normal, c2: CornerSim.Style = normal) = listOf(c1, c2)

    /** Échantillons d'un tour complet du circuit simulé. */
    private fun lapSamples(st: List<CornerSim.Style> = styles(), speed: Float = 1f): List<LapSample> = lapWithNext(st, speed).first

    /** Le tour 2 et le début du tour 3 (sortie du dernier virage au-delà de la ligne). */
    private fun lapWithNext(st: List<CornerSim.Style> = styles(), speed: Float = 1f): Pair<List<LapSample>, List<LapSample>> {
        val sim = CornerSim()
        val rec = LapRecorder()
        val next = LapRecorder()
        var started = false
        var nextStarted = false
        sim.sink = { p ->
            when {
                p.currentLap == 2 && !started -> { rec.startLap(p); started = true }
                p.currentLap == 2 -> rec.add(p)
                p.currentLap == 3 && !nextStarted -> { next.startLap(p); nextStarted = true }
                p.currentLap == 3 -> next.add(p)
            }
        }
        sim.start()
        sim.lap()
        sim.lap(st, speed)
        sim.settle()
        return rec.finish()!! to next.current()
    }

    private val key = DriverProfile.key(3607, "462", "RACE")

    /** Le coach seul, alimenté par le circuit simulé, avec le chrono de chaque tour. */
    private class Session(val coach: DriverCoach, val race: Boolean = false, val key: String) {
        val said = mutableListOf<String>()
        val sim = CornerSim().also { s -> s.sink = { said += coach.onPacket(it, inPit = false).map { m -> m.text } } }
        var positionChanged = false

        fun lap(st: List<CornerSim.Style>, speed: Float = 1f) {
            sim.lap(st, speed)
            val l = sim.lap - 1
            said += coach.onLap(
                LapRecord(l, sim.lastLapMs, 3f, false),
                DriverCoach.LapContext(key, race, totalLaps = 0, positionChangedRecently = positionChanged, pitLap = false),
            ).map { it.text }
            sim.settle()
        }
    }

    private fun session(coach: DriverCoach = DriverCoach(), race: Boolean = false): Session {
        val s = Session(coach, race, key)
        coach.start("s-${System.nanoTime()}")
        s.sim.start()
        s.sim.lap() // tour 1 : départ, jamais comparé
        return s
    }

    @Test
    fun cornersAreRecognisedFromTheTrajectoryAndSpeed() {
        val map = assertNotNull(CornerMap.build(lapSamples()))
        assertEquals(2, map.corners.size, map.corners.toString())
        val c1 = map.corners[0]
        // Épingle entre 600 m et 757 m ; zone d'analyse avec le freinage avant et la sortie après.
        assertTrue(c1.entryM in 560f..640f && c1.exitM in 720f..800f, c1.toString())
        assertTrue(c1.apexM in c1.entryM..c1.exitM)
        assertTrue(c1.zoneStartM < c1.entryM - 150f && c1.zoneEndM > c1.exitM + 100f, c1.toString())
        // Le même tracé roulé à nouveau se superpose à la carte.
        assertTrue(TrackProjection.medianOffset(map, lapSamples())!! < 1f)
    }

    @Test
    fun passesMeasureBrakingMinimumSpeedAndThrottle() {
        val map = CornerMap.build(lapSamples())!!
        val ref = CornerMeasure.passes(map, 2, lapSamples()).first { it.corner == 1 }
        val early = CornerMeasure.passes(map, 2, lapSamples(styles(c1 = CornerSim.Style(brakeShiftM = -25f)))).first { it.corner == 1 }
        val late = CornerMeasure.passes(map, 2, lapSamples(styles(c1 = CornerSim.Style(throttleDelayM = 40f)))).first { it.corner == 1 }
        assertTrue(ref.brakeStartM!! - early.brakeStartM!! in 18f..32f, "${ref.brakeStartM} ${early.brakeStartM}")
        assertTrue(late.throttleOnM!! - ref.throttleOnM!! in 30f..50f, "${ref.throttleOnM} ${late.throttleOnM}")
        assertTrue(early.timeMs > ref.timeMs && late.timeMs > ref.timeMs)
        assertEquals(22f * 3.6f, ref.minKmh, 2f)
    }

    @Test
    fun anIncompleteTrajectoryIsNeverAnalysed() {
        val (samples, next) = lapWithNext()
        val map = CornerMap.build(samples)!!
        // Trou dans la zone du virage 1 (télémétrie perdue) : ce virage n'est pas mesuré, l'autre si.
        val holed = samples.filterNot { it.x > 450f && it.x < 560f && it.z < 5f }
        val passes = CornerMeasure.passes(map, 2, holed, continuation = next)
        assertNull(passes.firstOrNull { it.corner == 1 })
        assertNotNull(passes.firstOrNull { it.corner == 2 })
        // Sans le début du tour suivant, la sortie du dernier virage manque : il n'est pas mesuré.
        assertNull(CornerMeasure.passes(map, 2, samples).firstOrNull { it.corner == 2 })
        // Échantillons hors du tracé (téléportation, autre circuit) : rien.
        val elsewhere = samples.map { it.copy(x = it.x + 200f) }
        assertTrue(CornerMeasure.passes(map, 2, elsewhere).isEmpty())
        assertNull(TrackProjection.medianOffset(map, elsewhere))
    }

    @Test
    fun practiceModeGivesOneConcreteCorrectionThenChecksIt() {
        val s = session()
        repeat(3) { s.lap(styles()) }
        assertTrue(s.said.isEmpty(), "aucune perte : rien à dire ${s.said}")
        // Réaccélération tardive au virage 2, trois tours de suite.
        repeat(3) { s.lap(styles(c2 = CornerSim.Style(throttleDelayM = 45f))) }
        val advice = s.said.single()
        assertTrue(advice.startsWith("Virage 2 : tu remets les gaz environ") && "Travaille la sortie." in advice, advice)
        assertEquals(CornerIssue.LATE_THROTTLE, s.coach.advices.single().issue)
        // Correction appliquée : vérifiée sur les trois passages suivants, une seule nouvelle phrase.
        repeat(3) { s.lap(styles()) }
        assertEquals(2, s.said.size, s.said.toString())
        assertTrue(s.said.last().startsWith("Virage 2 : c'est mieux"), s.said.last())
        assertNotNull(s.coach.advices.single().lossAfterMs)
    }

    @Test
    fun eachCauseIsIdentifiedOnlyWhenTheTelemetryShowsIt() {
        val early = session()
        repeat(3) { early.lap(styles()) }
        repeat(3) { early.lap(styles(c1 = CornerSim.Style(brakeShiftM = -30f))) }
        assertEquals(CornerIssue.EARLY_BRAKE, early.coach.advices.single().issue, early.said.toString())
        assertTrue(early.said.single().startsWith("Virage 1 : tu freines environ"), early.said.toString())

        val slow = session()
        repeat(3) { slow.lap(styles()) }
        repeat(3) { slow.lap(styles(c1 = CornerSim.Style(cornerSpeedDelta = -2.5f))) }
        assertEquals(CornerIssue.LOW_MIN_SPEED, slow.coach.advices.single().issue, slow.said.toString())

        // Tout est plus lent partout (pneus usés, carburant, conditions) : ce n'est pas un défaut de pilotage.
        val worn = session()
        repeat(3) { worn.lap(styles()) }
        repeat(4) { worn.lap(styles(), speed = 0.96f) }
        assertTrue(worn.said.isEmpty(), worn.said.toString())
    }

    @Test
    fun raceModeIsShortRareAndNeverDuringABattle() {
        val s = session(race = true)
        repeat(3) { s.lap(styles()) }
        s.positionChanged = true
        repeat(3) { s.lap(styles(c2 = CornerSim.Style(throttleDelayM = 60f))) }
        assertTrue(s.said.isEmpty(), "bataille : pas de coaching ${s.said}")
        s.positionChanged = false
        s.lap(styles(c2 = CornerSim.Style(throttleDelayM = 60f)))
        assertEquals("Virage 2 : remets les gaz plus tôt. Environ ${CornerCoach.tenths(s.coach.advices.single().lossBeforeMs)} par passage.", s.said.single())
    }

    @Test
    fun theProfileIsLearnedAcrossSessionsAndNeverMixed() {
        var stored: String? = null
        fun coach() = DriverCoach(load = { stored }, save = { stored = it })
        repeat(2) {
            val s = session(coach())
            repeat(4) { s.lap(styles()) }
            repeat(3) { s.lap(styles(c1 = CornerSim.Style(brakeShiftM = -30f))) }
            assertNotNull(s.coach.close(3f))
        }
        val book = DriverBook.parse(stored)
        val p = book[key]!!
        assertEquals(2, p.sessionCount)
        assertEquals(2, p.map!!.corners.size)
        val traits = p.traits()
        assertTrue(traits.weak.any { it.first == 1 }, traits.toString())
        assertTrue("à travailler : virage 1" in traits.text!!, traits.text)
        // Les meilleurs passages restent la référence, même au-delà des derniers passages gardés.
        assertTrue(p.passes[1]!!.any { it.timeMs == p.passes[1]!!.minOf { x -> x.timeMs } })

        // Même clé, autre tracé (configuration différente) : un nouveau profil, rien n'est mélangé.
        val other = DriverCoach(load = { stored }, save = { stored = it })
        other.start("autre")
        val shifted = lapSamples().map { it.copy(x = it.x + 300f, z = it.z + 300f) }
        assertNull(TrackProjection.medianOffset(p.map!!, shifted))
        val facts = coach().facts(key)
        assertTrue(facts.first().startsWith("Profil de pilotage : 2 session(s) mesurée(s), 2 virages reconnus"), facts.toString())
    }

    @Test
    fun aSessionWithoutCleanLapsChangesNothing() {
        val coach = DriverCoach()
        coach.start("vide")
        assertNull(coach.close(null))
        assertFalse(coach.facts(key).isEmpty())
    }

    @Test
    fun theRaceControllerCoachesAndSavesTheProfile() {
        val storage = FakeStorage()
        val c = com.gt7pitwall.core.race.RaceController(storage = storage)
        c.selectProfile(com.gt7pitwall.core.race.ProfileId.of(com.gt7pitwall.core.race.SessionMode.SPORT_PRACTICE, com.gt7pitwall.core.race.Series.DAILY_B))
        c.setRules(c.rules.copy(trackId = 462))
        assertFalse(c.raceMode, "entraînement : coach")
        val sim = CornerSim(c)
        sim.start()
        sim.lap()
        repeat(3) { sim.lap(styles()) }
        repeat(4) { sim.lap(styles(c1 = CornerSim.Style(throttleDelayM = 45f))) }
        val coaching = sim.texts.filter { it.startsWith("Virage") }
        assertEquals(1, coaching.size, sim.texts.toString())
        assertTrue("Virage 1 : tu remets les gaz" in coaching.single(), coaching.toString())
        assertTrue(c.journal.recent().any { it.text.startsWith("Coaching : Virage 1") })
        // Fin de session (nouvelle course) : le profil est enregistré, l'IA reçoit les faits mesurés.
        c.onPacket(TestPackets.packet(lap = 0, totalLaps = 5, carCode = 3607))
        assertNotNull(storage.driverBook)
        assertTrue(DriverBook.parse(storage.driverBook).profiles.single().sessionCount == 1)
        assertTrue(c.aiFacts().lines.any { it.startsWith("Profil de pilotage") }, c.aiFacts().lines.toString())
    }
}
