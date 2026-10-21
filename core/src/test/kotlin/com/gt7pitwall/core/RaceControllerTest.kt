package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.engineer.RadioLevel
import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RaceControllerTest {

    @Test
    fun voiceRulesAreAppliedSavedReplannedAndNotRepeated() {
        val store = FakeStorage()
        // Course au temps : GT7 n'envoie pas de nombre de tours.
        val s = RaceSim(RaceController(storage = store), totalLaps = 0, perLap = 5f)
        s.start()
        s.driveLaps(2)

        s.say("course de 30 minutes")
        assertEquals(30, store.rules.durationMin)
        assertEquals(30, s.controller.rules.durationMin)
        assertTrue(s.texts.any { it.startsWith("Noté : course de 30 minutes") })
        val plan1 = assertNotNull(s.controller.plan)
        assertEquals(DataSource.ESTIMATED, plan1.lapsSource)
        val planAnnouncements = { s.texts.count { (it.contains("Stratégie :") || it.startsWith("Nouveau plan")) && !it.startsWith("Noté") } }
        assertEquals(1, planAnnouncements())

        s.say("deux arrêts obligatoires")
        assertEquals(2, store.rules.mandatoryStops)
        assertEquals(2, s.controller.plan!!.stops.size)
        // Les deux arrêts carburant comptent déjà pour l'obligation : mêmes tours d'arrêt, pas de nouvelle annonce.
        assertEquals(1, planAnnouncements())

        s.say("trois arrêts obligatoires")
        assertEquals(3, s.controller.plan!!.stops.size)
        // En course, le changement de plan tient en une phrase : prochain arrêt, sans réciter toute la course.
        val update = s.texts.lastOrNull { it.startsWith("Nouveau plan") } ?: error(s.texts.takeLast(6).toString())
        assertTrue(update.startsWith("Nouveau plan : prochain arrêt fin du tour") && "Puis encore 2 arrêts." in update, update)
        assertTrue("Course" !in update, update)
        assertEquals(2, planAnnouncements())
        s.say("deux arrêts obligatoires")
        assertEquals(3, planAnnouncements())

        // La consommation mesurée en course vient de GT7 : le multiplicateur ne doit pas la multiplier.
        val perLapBefore = s.controller.plan!!.perLap!!
        s.say("consommation fois trois")
        assertEquals(3, store.rules.fuelMultiplier)
        assertEquals(perLapBefore, s.controller.plan!!.perLap!!, 0.001f)
        assertEquals(DataSource.MEASURED, s.controller.plan!!.fuelSource)
        assertEquals(3, planAnnouncements(), "le plan n'a pas changé : pas de nouvelle annonce")

        // Même règle répétée : confirmée, mais pas de plan réannoncé.
        s.say("deux arrêts obligatoires")
        assertEquals(3, planAnnouncements())

        s.say("quelle stratégie")
        assertTrue(s.lastAnswer.contains("2 arrêts"), s.lastAnswer)
        assertTrue(store.saves >= 3)
    }

    @Test
    fun buttonsAndVoiceUseTheSamePath() {
        val viaVoice = RaceSim(RaceController(storage = FakeStorage()))
        viaVoice.start()
        viaVoice.say("arrêt obligatoire")
        val viaButton = RaceSim(RaceController(storage = FakeStorage()))
        viaButton.start()
        viaButton.said += viaButton.controller.command(DriverCommand.SetMandatoryStops(1))
        assertEquals(viaVoice.controller.rules, viaButton.controller.rules)
        assertEquals(viaVoice.controller.plan, viaButton.controller.plan)
    }

    @Test
    fun historyIsScaledByTheMultiplierButMeasuresAreNot() {
        val record = CarTrackRecord(3607, 462, 110_000, 111_000, fuelPerLapX1 = 4f, fuelSamples = 10)
        val store = FakeStorage(RaceRules(trackId = 462, fuelMultiplier = 3), mutableMapOf(record.key to record))
        val s = RaceSim(RaceController(storage = store), totalLaps = 12, perLap = 13f)
        s.start()
        // Avant tout tour mesuré : historique ×3 = 12 L/tour, annoncé comme tel.
        val before = assertNotNull(s.controller.plan)
        assertEquals(12f, before.perLap!!, 0.001f)
        assertEquals(DataSource.HISTORY, before.fuelSource)
        assertTrue(s.texts.any { "tirée de l'historique, à confirmer" in it }, s.texts.toString())
        // Après deux tours mesurés : la mesure GT7 (13 L/tour) remplace l'historique, sans multiplication.
        s.driveLaps(2)
        val after = s.controller.plan!!
        assertEquals(DataSource.MEASURED, after.fuelSource)
        assertEquals(13f, after.perLap!!, 0.3f)
    }

    @Test
    fun boxCallIsCriticalFirstAndSurvivesSilentMode() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10, perLap = 14.5f)
        s.start()
        s.say("silence")
        assertEquals(RadioLevel.CRITICAL_ONLY, s.controller.engineer.radioLevel)
        s.said.clear()
        s.driveLaps(5)
        val box = s.said.firstOrNull { it.text.startsWith("Box, box !") }
        assertNotNull(box, s.texts.toString())
        assertEquals(Priority.CRITICAL, box.priority)
        // En silence : aucun message d'information (chronos) n'est passé.
        assertTrue(s.said.none { it.category == MessageCategory.LAP && it.priority < Priority.HIGH }, s.texts.toString())
        // Les questions du pilote ont toujours une réponse.
        s.say("dernier tour")
        assertTrue(s.lastAnswer.startsWith("Dernier tour :"))
    }

    @Test
    fun criticalMessagesComeFirstAndAreNotRepeated() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10, perLap = 14.5f)
        s.start()
        s.said.clear()
        s.driveLaps(5)
        // Au tour du « Box », le message critique précède le compte rendu du tour.
        val idxBox = s.texts.indexOfFirst { it.startsWith("Box, box !") }
        val idxLap = s.texts.indexOfFirst { it.startsWith("Tour 5 :") }
        assertTrue(idxBox in 0 until idxLap, s.texts.toString())
        assertEquals(1, s.texts.count { it.startsWith("Box, box !") })
        // Une seule source de chiffres à la radio : le plan.
        val planned = s.controller.plan!!.stops.single()
        assertTrue(s.texts.none { "Il faudra ravitailler" in it }, s.texts.toString())
        assertTrue(s.texts.any { "Arrêt prévu à la fin du tour ${planned.endOfLap}, environ ${planned.fuel} % du réservoir" in it }, s.texts.toString())
    }

    @Test
    fun announcedPlanStaysStableAcrossLaps() {
        val rules = RaceRules(mandatoryStops = 1)
        val s = RaceSim(RaceController(storage = FakeStorage(rules)), totalLaps = 10, perLap = 3f)
        s.start()
        s.driveLaps(6)
        val announcements = s.texts.filter { it.contains("Stratégie :") }
        // Annonce initiale + au plus une précision quand la consommation est mesurée — puis plus rien.
        assertTrue(announcements.size <= 2, announcements.joinToString("\n"))
        assertEquals(1, s.texts.count { it.startsWith("Box, box !") }, s.texts.joinToString("\n"))
    }

    @Test
    fun essentialModeDropsStatisticsButKeepsStrategy() {
        val s = RaceSim(RaceController(storage = FakeStorage()), totalLaps = 10, perLap = 14.5f)
        s.start()
        s.say("radio essentielle")
        s.said.clear()
        s.driveLaps(3)
        assertTrue(s.said.none { it.text.startsWith("Tour ") }, s.texts.toString())
        s.say("détails")
        assertTrue(s.lastAnswer.startsWith("Tour 3 :"), s.lastAnswer)
    }

    @Test
    fun missingTyreWarnedBeforeTheLastStop() {
        val rules = RaceRules(
            mandatoryStops = 1,
            allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM),
            requiredTyres = setOf(TyreCompound.RH, TyreCompound.RM),
            startTyre = TyreCompound.RM,
        )
        val s = RaceSim(RaceController(storage = FakeStorage(rules)), totalLaps = 8, perLap = 5f)
        s.start()
        s.driveLaps(4)
        val plan = s.controller.plan!!
        assertEquals(TyreCompound.RH, plan.stops.single().tyreToFit)
        val warn = s.said.firstOrNull { it.text.startsWith("Stand au prochain tour") } ?: error(s.texts.joinToString("\n"))
        assertTrue("il te manque les course durs" in warn.text, warn.text)
        assertEquals(Priority.CRITICAL, warn.priority)
        val box = s.said.firstOrNull { it.text.startsWith("Box, box !") } ?: error(s.texts.joinToString("\n"))
        assertTrue("arrêt obligatoire, pas besoin d'essence" in box.text && "Monte les course durs" in box.text, box.text)
    }

    @Test
    fun startTyreByVoiceAndObligationsNeverClaimedWithoutProof() {
        val rules = RaceRules(mandatoryStops = 1, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM), requiredTyres = setOf(TyreCompound.RH, TyreCompound.RM))
        val store = FakeStorage(rules)
        val s = RaceSim(RaceController(storage = store), totalLaps = 8, perLap = 5f)
        s.stand(1f)
        assertTrue(s.controller.plan!!.startTyreUnknown)
        s.say("je pars en mediums")
        // Règlement non confirmé et aucun pneu connu : la famille est demandée, puis donnée d'un seul mot.
        assertEquals(null, store.rules.startTyre)
        s.say("course")
        assertEquals(TyreCompound.RM, store.rules.startTyre)
        s.start()
        s.driveLaps(1)
        s.say("obligations")
        val status = s.lastAnswer
        assertTrue(status.startsWith("Arrêts obligatoires : 0 sur 1."), status)
        assertTrue("il manque course durs" in status, status)
        assertTrue("respect" !in status)
    }
}
