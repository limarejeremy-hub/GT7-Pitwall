package com.gt7pitwall.core

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Course réelle du 26/10 (session 1793026710934-2186, Subaru WRX Gr.3, 14 tours, départ RS) : arrêt sans essence au
 * tour 8, « oui » (arrêt), « oui » (pneus changés), puis « medium » (vocabulaire radio : « mediums »). La gomme n'avait
 * jamais été enregistrée : « gomme_certaine=non » jusqu'à l'arrivée.
 */
class RealRace2610Test {
    private fun race(): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = FakeStorage(rules = RaceRules(totalLaps = 14, fuelMultiplier = 4, tyreMultiplier = 6, mandatoryStops = 1, startTyre = TyreCompound.RS)))
        return c to RaceSim(c, totalLaps = 14, perLap = 3f)
    }

    private suspend fun RadioConversation.say(vocab: String, free: String = vocab): List<EngineerMessage> = onUtterance(vocab, free, model = null)
    private fun List<EngineerMessage>.answer() = lastOrNull { it.category == MessageCategory.ANSWER }?.text.orEmpty()

    @Test
    fun theCompoundSaidAfterTheQuestionIsRecordedThroughTheRealRadioPath() = runBlocking<Unit> {
        val (c, s) = race()
        val conv = RadioConversation(c)
        s.start()
        s.driveLaps(7)
        s.pitStop(fuel = 0f, stopSeconds = 3.2f, changeTyres = true)
        // « Tu as bien effectué ton arrêt ? » ou « As-tu changé les pneus ? » : on répond jusqu'à la question de gomme.
        var guard = 0
        while (!c.ledger.awaitingTyreDeclaration && guard++ < 3) conv.say("oui")
        assertTrue(c.ledger.awaitingTyreDeclaration, "Pitwall demande la gomme montée")
        val out = conv.say("mediums", "medium")
        assertEquals(TyreCompound.RM, c.ledger.changes.last().compound, out.answer())
        assertTrue("course mediums" in out.answer(), out.answer())
        assertFalse(c.ledger.awaitingTyreDeclaration, "plus aucune question sur la gomme")
        s.driveLaps(2)
        assertTrue(c.ledger.tyreStint(s.lap, c.rules.startTyre).certain, "gomme et âge connus pour ce relais")
        assertTrue(s.texts.none { "quelle gomme" in it.lowercase() && it !in s.texts.take(1) } || s.texts.count { "quelle gomme" in it } == 1, s.texts.toString())
    }

    @Test
    fun naturalTyrePhrasesAndCorrectionsAreUnderstood() = runBlocking<Unit> {
        val (c, s) = race()
        val conv = RadioConversation(c)
        s.start()
        s.driveLaps(7)
        s.pitStop(fuel = 0f, stopSeconds = 3.2f, changeTyres = true)
        var guard = 0
        while (!c.ledger.awaitingTyreDeclaration && guard++ < 3) conv.say("oui")
        conv.say("j'ai mis des mediums")
        assertEquals(TyreCompound.RM, c.ledger.changes.last().compound)
        // Correction explicite : remplace la déclaration précédente, une seule monte pour cet arrêt.
        val fix = conv.say("non finalement j'ai mis des tendres")
        assertEquals(TyreCompound.RS, c.ledger.changes.last().compound, fix.answer())
        assertEquals(1, c.ledger.changes.size, "un seul changement pour cet arrêt : " + c.ledger.changes)
        assertTrue("Corrigé" in fix.answer() || "tendres" in fix.answer(), fix.answer())
    }

    @Test
    fun aStopWithoutTyresAndANoFuelStopWithTyresAreBothUnderstood() = runBlocking<Unit> {
        // « J'ai changé les pneus mais pas fait le plein » : un changement, pas une négation.
        val (c, s) = race()
        val conv = RadioConversation(c)
        s.start()
        s.driveLaps(7)
        s.pitStop(fuel = 0f, stopSeconds = 3.2f, changeTyres = true)
        conv.say("j'ai changé les pneus mais pas fait le plein")
        assertTrue(c.ledger.changes.size >= 1, c.ledger.changes.toString())

        // « J'ai gardé les mêmes pneus » après un plein : aucun changement, âge continu.
        val (c2, s2) = race()
        val conv2 = RadioConversation(c2)
        s2.start()
        s2.driveLaps(7)
        s2.pitStop(fuel = 20f)
        conv2.say("j'ai gardé les mêmes pneus")
        assertTrue(c2.ledger.changes.isEmpty(), c2.ledger.changes.toString())
        assertFalse(c2.ledger.awaitingTyreChangeAnswer)
    }

    /** Séries réelles du tour 8 (journal) : arrêt de 3,2 s sans essence, pneus changés confirmés par le pilote. */
    @Test
    fun theRealNoFuelTyreStopIsSuspectedNeverConfirmed() {
        fun series(x: String) = x.split(',').map { it.toFloat() }
        val avg = series("88.4,89.0,89.5,90.0,90.5,90.9,91.1,90.9,90.6,90.3,89.9,90.4,90.3,90.2,90.1,89.8,89.5,89.2,89.0,88.7,88.5,88.3,60.0,59.9,59.8,59.8,59.7,59.5,59.4,59.3,59.9,59.8,60.1,60.2,60.1,60.0,60.3,60.4,60.3,60.2,60.1,60.1,60.1,60.3,60.2,60.1,60.1,60.6,60.7,60.8,60.8,60.8,60.9,61.0,61.0,61.4,61.3,61.2"); val avd = series("80.2,80.3,80.4,80.5,80.6,80.6,80.6,80.4,80.1,79.8,79.5,80.0,80.1,80.3,80.1,79.9,79.7,79.4,79.2,79.0,78.8,78.6,60.0,59.9,59.8,59.8,59.7,59.5,59.4,59.3,59.9,59.8,60.3,60.3,60.2,60.1,60.2,60.2,60.2,60.1,60.0,60.1,60.5,61.5,61.6,61.5,61.6,62.2,62.6,63.3,63.4,63.5,64.0,64.5,64.5,64.5,64.4,64.3"); val arg = series("74.9,75.1,75.4,75.6,75.8,76.0,76.2,76.2,76.1,75.9,75.8,75.6,75.4,75.2,75.3,75.1,74.8,74.6,74.4,74.2,74.0,73.9,60.0,59.9,59.8,59.8,59.7,59.5,59.4,59.3,60.1,60.3,60.4,60.3,60.3,60.5,60.6,60.6,60.6,60.7,60.7,60.7,60.8,60.8,60.9,61.0,61.0,60.9,60.8,60.9,61.1,61.3,61.4,61.3,61.6,61.9,62.1,62.2"); val ard = series("69.6,69.7,69.7,69.7,69.7,69.7,69.7,69.7,69.6,69.5,69.4,69.2,69.1,69.1,69.1,68.9,68.7,68.5,68.3,68.2,68.0,67.8,60.0,59.9,59.8,59.8,59.7,59.5,59.4,59.3,60.1,60.3,60.3,60.2,60.4,60.5,60.6,60.5,60.7,60.7,60.8,60.9,61.2,61.5,61.7,61.7,61.8,61.9,62.0,62.2,62.8,63.1,63.4,63.5,63.7,63.9,64.0,64.1"); val kmh = series("159,165,171,170,174,179,185,191,197,199,202,142,108,89,59,59,44,14,0,0,0,0,0,10,40,60,60,59,59,59,94,114,59,58,73,95,72,65,84,97,115,119,125,123,137,153,137,94,86,85,100,116,120,114,118,129,140,153"); val fuel = series("78.9,78.9,78.8,78.8,78.8,78.7,78.6,78.6,78.5,78.5,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.4,78.3,78.3,78.3,78.3,78.3,78.2,78.2,78.2,78.2,78.2,78.2,78.1,78.1,78.1,78.0,78.0,78.0,78.0,78.0,77.9,77.9,77.9,77.8,77.8,77.8,77.8,77.7,77.7")
        val samples = avg.indices.map { i ->
            val phase = when { i < 20 -> com.gt7pitwall.core.tyres.ThermalPhase.BEFORE; i < 43 -> com.gt7pitwall.core.tyres.ThermalPhase.STOP; else -> com.gt7pitwall.core.tyres.ThermalPhase.EXIT }
            com.gt7pitwall.core.tyres.ThermalSample(i + 1, phase, listOf(avg[i], avd[i], arg[i], ard[i]), kmh[i], fuel[i])
        }
        val r = com.gt7pitwall.core.tyres.TyreChangeHunter.analyze(8, com.gt7pitwall.core.session.PitAssessment.PIT_LIKE_STOP, samples)
        // Variations −28,8 / −19,1 / −14,3 / −8,2 °C : le seuil de 10 °C échouait sur un pneu ; la remise uniforme à 60 °C
        // est la signature, même si la voiture repart une seconde plus tard.
        assertEquals(com.gt7pitwall.core.tyres.TyreChangeVerdict.SUSPECTED, r.verdict, r.reason)
    }

    private val header = "ID,Name,Base,Country,Category,Length,LongestStraight,ElevationDiff,Altitude,MinTimeH,MinTimeM,MinTimeS,MaxTimeH,MaxTimeM,MaxTimeS,LayoutNumber,IsReverse,PitLaneDelta,IsOval,NumCorners,NoRain"

    /** Le Mans mémorisé d'une course précédente, tour réel bien plus court : circuit écarté, jamais remplacé d'office. */
    @Test
    fun aRememberedTrackThatContradictsTheLapIsDiscardedAndTraced() {
        val db = com.gt7pitwall.core.knowledge.Gt7Database.parse(
            "ID,ShortName,Maker\n2186,WRX Gr.3,1\n", "ID,Name,Country\n1,Subaru,1\n",
            "$header\n454,24 Heures du Mans Racing Circuit,1,6,circuit,13629,1679,37.3,811.0,0,0,0,0,0,0,1,0,30.0,0,38,0\n",
        )
        val storage = FakeStorage(rules = RaceRules(totalLaps = 10, trackId = 454))
        val c = RaceController(db, storage)
        val s = RaceSim(c, totalLaps = 14, perLap = 3f)
        s.start()
        s.driveLaps(3)
        assertEquals(null, c.rules.trackId, "Le Mans écarté")
        assertTrue(s.texts.any { "ne correspond pas à ce tour" in it }, s.texts.toString())
        assertEquals(1, s.texts.count { "ne correspond pas à ce tour" in it }, "un seul message")
        val journal = storage.journals.values.single().joinToString("\n")
        assertTrue("Circuit incohérent" in journal, journal)
        // Nombre de tours : celui de GT7, noté à côté de l'ancien règlement.
        assertTrue("tours_gt7" in journal && "\"14\"" in journal, journal)
        // Aucun historique de pilotage ni mémoire n'est rangé sous le mauvais circuit.
        assertTrue(storage.memory.keys.none { "454" in it }, storage.memory.keys.toString())
        assertFalse("454" in c.driverKey)
    }

    @Test
    fun aConsistentTrackIsKept() {
        val db = com.gt7pitwall.core.knowledge.Gt7Database.parse(
            "ID,ShortName,Maker\n", "ID,Name,Country\n",
            "$header\n10,Sim Ring,1,1,circuit,3016,500,1.0,1.0,0,0,0,0,0,0,1,0,20.0,0,10,0\n",
        )
        val c = RaceController(db, FakeStorage(rules = RaceRules(totalLaps = 14, trackId = 10)))
        val s = RaceSim(c, totalLaps = 14, perLap = 3f)
        s.start()
        s.driveLaps(3)
        assertEquals(10, c.rules.trackId)
        assertTrue(s.texts.none { "ne correspond pas" in it })
    }
}
