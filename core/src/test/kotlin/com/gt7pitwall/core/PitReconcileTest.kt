package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.session.PitAssessment
import com.gt7pitwall.core.session.PitDetector
import com.gt7pitwall.core.strategy.PitLedger
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #73 P0-B (Watkins Glen, 06/11) : arrêt prévu fin du tour 6, passage au tour 7, immobile 4,8 s, mais détecteur « arrêt
 * en piste » ; Pitwall l'écartait, reproposait un arrêt au tour 10, puis la déclaration du tour 9 devenait le moment de
 * l'arrêt. FIXTURES SYNTHÉTIQUES (aucune trace réelle de cette course dans le dépôt) reprenant ces événements.
 */
class PitReconcileTest {
    private val rules = RaceRules(mandatoryStops = 0, requiredTyres = setOf(TyreCompound.RM, TyreCompound.RH), startTyre = TyreCompound.RM)

    /** Course jusqu'à la fin du tour d'arrêt du plan ; renvoie le simulateur et ce tour. */
    private fun toPlannedStop(): Pair<RaceSim, Int> {
        val s = RaceSim(RaceController(storage = FakeStorage(rules)), totalLaps = 10)
        s.start()
        s.driveLaps(2) // trajectoire d'un tour complet connue
        val stopLap = s.controller.plan?.stops?.firstOrNull()?.endOfLap
        assertNotNull(stopLap, "deux gommes imposées : un arrêt au plan")
        while (s.lap < stopLap) s.driveLaps(1)
        return s to stopLap
    }

    private fun RaceSim.journal(): List<String> = controller.journal.recent(2000).map { it.text }

    @Test fun aPlannedStopReadAsTrackStopBecomesAProbableStandWithOneShortQuestion() {
        val (s, stopLap) = toPlannedStop()
        s.pitStopReadAsTrackStop()
        val visitLap = stopLap + 1
        val c = s.controller.ledger.pending
        assertNotNull(c, s.texts.takeLast(6).toString())
        assertTrue(c.contested)
        assertEquals(visitLap, c.lap, "le passage garde son tour (début du tour ${stopLap + 1} = fin du tour $stopLap)")
        assertEquals(0, s.controller.ledger.stopCount, "rien n'est compté sans le pilote")
        assertTrue(s.texts.any { it == "Tour $visitLap : arrêt de 5 secondes, mes indices se contredisent. C'était ton arrêt au stand ?" }, s.texts.takeLast(6).toString())
        assertTrue(s.texts.none { it.startsWith("Arrêt en piste") }, "plus d'arrêt écarté sur un seul indice")
        assertTrue(s.journal().any { it.startsWith("Indices contradictoires au tour $visitLap : passage au stand probable, gomme non confirmée") })
    }

    @Test fun noNewStopIsAnnouncedWhileTheContradictionIsOpen() {
        val (s, _) = toPlannedStop()
        s.pitStopReadAsTrackStop()
        val before = s.said.size
        val planBefore = s.controller.plan
        s.driveLaps(2)
        val later = s.texts.drop(before)
        assertTrue(later.none { "Box" in it || "arrêt devient nécessaire" in it || "arrêt de plus" in it || "Arrêt prévu fin du tour" in it }, later.toString())
        assertEquals(planBefore, s.controller.plan, "plan suspendu, pas recalculé")
        assertTrue(s.journal().any { it.startsWith("Plan suspendu : arrêt du tour") })
        assertEquals(1, later.count { it.startsWith("Sans réponse : tour") }, "un seul rappel : $later")
    }

    @Test fun aLateDeclarationKeepsThePassageLapAndDatesTheTyresFromIt() {
        val (s, stopLap) = toPlannedStop()
        s.pitStopReadAsTrackStop()
        val visitLap = stopLap + 1
        s.driveLaps(2) // déclaration deux tours plus tard, comme au tour 9
        val sayLap = s.lap
        s.say("arrêt fait")
        val stop = s.controller.ledger.stopList.single()
        assertEquals(visitLap, stop.lap, "tour du passage, pas celui de la phrase")
        assertEquals(sayLap, stop.declaredLap)
        assertEquals(StopEvidence.DRIVER_CONFIRMED, stop.evidence)
        assertTrue(s.texts.any { "C'est le passage du tour $visitLap, prévu fin du tour $stopLap." in it }, s.texts.takeLast(4).toString())
        s.say("j'ai mis les durs")
        val change = s.controller.ledger.changes.single()
        assertEquals(TyreCompound.RH, change.compound, s.texts.takeLast(4).toString())
        assertEquals(visitLap + 1, change.lap, "train daté du passage (premier tour après), pas du tour de la déclaration")
        assertTrue(s.controller.ledger.missingRequired(rules).isEmpty())
        s.driveLaps(1)
        assertTrue(s.controller.plan?.stops.orEmpty().none { it.endOfLap >= s.lap }, "plus aucun arrêt à faire : ${s.controller.plan?.stops}")
    }

    @Test fun declaringTheTyresAloneConfirmsTheContestedStopLaterOn() {
        val (s, stopLap) = toPlannedStop()
        s.pitStopReadAsTrackStop()
        s.driveLaps(2)
        s.say("j'ai mis les durs")
        // Une question attend sa réponse : la déclaration de pneus est d'abord confirmée (protection V494), puis « oui ».
        assertTrue(s.texts.last().startsWith("Tu confirmes des pneus course durs"), s.texts.takeLast(3).toString())
        assertEquals(0, s.controller.ledger.stopCount, "rien d'appliqué avant le oui")
        s.say("oui")
        val stop = s.controller.ledger.stopList.single()
        assertEquals(stopLap + 1, stop.lap)
        assertNull(s.controller.ledger.pending)
        assertEquals(stopLap + 2, s.controller.ledger.changes.single().lap)
    }

    @Test fun noMeansNoStopAndThePlanResumes() {
        val (s, _) = toPlannedStop()
        s.pitStopReadAsTrackStop()
        s.say("non")
        assertNull(s.controller.ledger.pending)
        assertEquals(0, s.controller.ledger.stopCount)
        s.driveLaps(1)
        assertTrue(s.controller.plan?.stops.orEmpty().any { it.endOfLap >= s.lap - 1 }, "l'arrêt reste à faire : plan recalculé")
    }

    @Test fun aCrashAtThePlannedLapStaysATrackStop() {
        val (s, _) = toPlannedStop()
        s.crash()
        assertNull(s.controller.ledger.pending, "choc : arrêt en piste, aucune question")
        assertTrue(s.texts.any { it.startsWith("Arrêt en piste : je ne le compte pas") })
    }

    @Test fun theDetectorSaysWhyItWasNotAPitStop() {
        val (s, _) = toPlannedStop()
        s.pitStopReadAsTrackStop()
        val pit = s.controller.journal.recent(2000).last { it.text.contains("immobile") }
        assertEquals(PitAssessment.TRACK_STOP.name.lowercase(), pit.details["analyse"])
        assertEquals(PitDetector.TrackReason.ON_RACING_LINE.label, s.controller.journal.recent(2000).last { it.text.startsWith("Indices contradictoires") }.details["detecteur"]?.substringAfter("(")?.removeSuffix(")"))
    }

    @Test fun ledgerDatesLateTyresFromTheLastRecordedPassage() {
        val l = PitLedger()
        l.recordRefuel(7, 20f)
        assertEquals(8, l.passageLapForLateTyres(9), "déclarés au tour 9 pour le passage du tour 7")
        assertNull(l.passageLapForLateTyres(8), "juste après le passage : comportement habituel")
        l.fitTyres(8, TyreCompound.RH)
        assertNull(l.passageLapForLateTyres(10), "gomme déjà notée pour ce passage")
        val m = PitLedger()
        assertNull(m.passageLapForLateTyres(5), "aucun passage : tour courant")
    }
}
