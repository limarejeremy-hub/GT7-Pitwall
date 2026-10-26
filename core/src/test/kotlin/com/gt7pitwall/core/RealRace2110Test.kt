package com.gt7pitwall.core

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.analysis.SectorTrace
import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.SessionJournal
import com.gt7pitwall.core.radio.CommandGate
import com.gt7pitwall.core.radio.CommandSource
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.radio.GateContext
import com.gt7pitwall.core.radio.Vetting
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.PitLedger
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreChange
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Non-régression tirée de la course réelle du 21/10/2026 (session 1792612877035-3600 : Porsche 911 GT3 R, Gilles-Villeneuve,
 * 12 tours, départ RH, un arrêt au tour 6 avec +66,7 de carburant et des course tendres, arrivée P1/20). Les valeurs
 * reprennent le journal exporté ; les paquets sont reconstruits, pas rejoués octet par octet.
 */
class RealRace2110Test {

    /** Règlement de la session : non confirmé, gommes CS|CM|CH|RH (les RS montées n'y figurent pas). */
    private val rules = RaceRules(
        totalLaps = 12,
        fuelMultiplier = 7,
        mandatoryStops = 1,
        allowedTyres = setOf(TyreCompound.CS, TyreCompound.CM, TyreCompound.CH, TyreCompound.RH),
        startTyre = TyreCompound.RH,
    )

    private fun race(): Pair<FakeStorage, RaceSim> {
        val store = FakeStorage(rules = rules)
        return store to RaceSim(RaceController(storage = store), totalLaps = 12, perLap = 14f)
    }

    // ------------------------------------------------------------------ P0 : pneus

    @Test
    fun ambiguousSoftsAreAskedThenCourseSoftsAreRecorded() {
        val (_, s) = race()
        s.start()
        s.driveLaps(5)
        // Tour 6, au stand : Vosk a transcrit « je les pneus tendres ».
        s.say("je les pneus tendres")
        assertTrue(s.controller.ledger.changes.isEmpty(), "rien n'est noté sur une gomme ambiguë")
        // Une seule question (26/10) : l'hypothèse de la famille des pneus actuels, à confirmer.
        assertEquals("Tu confirmes des pneus course tendres actuellement ?", s.lastAnswer)
        assertTrue(s.said.last().expectsReply)
        s.say("course tendres")
        assertEquals(listOf(TyreChange(6, TyreCompound.RS)), s.controller.ledger.changes)
    }

    @Test
    fun aSingleFamilyWordOrYesAnswersTheQuestion() {
        val (_, s) = race()
        s.start()
        s.driveLaps(5)
        s.say("je les pneus tendres")
        s.say("course")
        assertEquals(TyreCompound.RS, s.controller.ledger.changes.single().compound)

        val (_, t) = race()
        t.start()
        t.driveLaps(5)
        t.say("je les pneus tendres")
        t.say("oui") // l'hypothèse (famille des pneus actuels) est retenue
        assertEquals(TyreCompound.RS, t.controller.ledger.changes.single().compound)
    }

    @Test
    fun theStopAfterADeclaredChangeDoesNotAskAgainAndYesKeepsTheCompound() {
        val (store, s) = race()
        s.start()
        s.driveLaps(5)
        s.say("course tendres")
        s.pitStop(fuel = 66.7f)
        val stopMsg = s.said.last { it.text.startsWith("Arrêt 1 enregistré") }.text
        assertFalse("As-tu changé les pneus" in stopMsg, stopMsg)
        assertTrue("Pneus déjà notés pour cet arrêt : course tendres." in stopMsg, stopMsg)
        s.say("oui")
        s.driveLaps(1)
        assertEquals(listOf(TyreChange(6, TyreCompound.RS)), s.controller.ledger.changes)
        val r = store.sessions.single()
        assertEquals(TyreCompound.RS, r.tyres.single().compound)
        assertEquals(1, r.stops.size)
        assertTrue(r.laps.filter { it.lap >= 6 }.all { it.tyreCompound == TyreCompound.RS && it.tyreCertain })
    }

    @Test
    fun ledgerEnrichesOneChangePerPassageAndTracesCorrections() {
        val l = PitLedger()
        assertNull(l.fitTyres(6, TyreCompound.CS))
        l.recordRefuel(6, 66.7f)
        // « Oui » à « As-tu changé les pneus ? » au tour 7, même passage : ni second changement, ni gomme effacée.
        assertNull(l.fitTyres(7, null))
        assertEquals(listOf(TyreChange(6, TyreCompound.CS)), l.changes)
        // « Non, course tendres » : la déclaration erronée est remplacée, l'ancienne gomme est rendue pour la trace.
        assertEquals(TyreCompound.CS, l.fitTyres(7, TyreCompound.RS))
        assertEquals(listOf(TyreChange(6, TyreCompound.RS)), l.changes)
        // Un autre arrêt plus tard est un autre passage.
        l.recordRefuel(10, 20f)
        l.fitTyres(10, TyreCompound.RM)
        assertEquals(2, l.changes.size)
    }

    @Test
    fun aCorrectionIsJournaledWithBeforeAndAfter() {
        val (store, s) = race()
        s.start()
        s.driveLaps(5)
        s.say("confort tendres")
        s.say("non course tendres")
        assertEquals(TyreCompound.RS, s.controller.ledger.changes.single().compound)
        val id = store.journals.keys.single()
        val e = SessionJournal.load(store, id).single { it.type == JournalType.DECISION && it.text.startsWith("Gomme corrigée") }
        assertEquals("CS", e.details["avant"])
        assertEquals("RS", e.details["apres"])
    }

    @Test
    fun anUnknownCompoundIsNeverCertain() {
        val (store, s) = race()
        s.start()
        s.driveLaps(5)
        s.pitStop(fuel = 66.7f)
        s.say("oui") // « As-tu changé les pneus ? » : oui, sans gomme
        s.driveLaps(2)
        val laps = store.sessions.single().laps.filter { it.lap >= 7 }
        assertTrue(laps.isNotEmpty())
        assertTrue(laps.all { it.tyreCompound == null && !it.tyreCertain && it.tyreAgeCertain }, laps.toString())
    }

    // ------------------------------------------------------------------ P0 : ravitaillement à cheval sur la ligne

    private var id = 0

    private fun packet(lap: Int, fuel: Float, speedMps: Float, x: Float): TelemetryPacket {
        val plain = TestPackets.plain(lap = lap, totalLaps = 12, fuel = fuel, packetId = ++id, speedMps = speedMps, posX = x)
        return TelemetryParser.parseDecrypted(plain)!!
    }

    @Test
    fun aRefuelThatStraddlesTheLineIsOneEventAndTheNextLapIsMeasured() {
        val t = SessionTracker()
        val events = mutableListOf<SessionEvent>()
        var x = 0f
        var fuel = 100f
        fun drive(lap: Int, n: Int, mps: Float, burn: Float) = repeat(n) {
            x += mps / 60f
            fuel -= burn
            events += t.update(packet(lap, fuel, mps, x))
        }
        // Grille puis cinq tours à ~14 de consommation.
        repeat(5) { events += t.update(packet(0, fuel, 0f, x)) }
        for (lap in 1..5) drive(lap, 700, 60f, 14f / 700)
        // Tour 6 : 18,1 restant, entrée au stand, plein jusqu'à 84,8 AVANT la ligne.
        drive(6, 600, 60f, (fuel - 18.1f) / 600)
        repeat(240) { events += t.update(packet(6, fuel, 0f, x)) } // immobile
        repeat(400) {
            fuel += 66.7f / 400
            events += t.update(packet(6, fuel, 0f, x))
        }
        drive(6, 120, 16.7f, 0f) // limiteur, niveau stable : la hausse est finie
        // La ligne est franchie dans la voie des stands ; le niveau ne bouge pas.
        drive(7, 120, 16.7f, 0f)
        drive(7, 600, 60f, 14.4f / 600)
        drive(8, 10, 60f, 0.001f)
        drive(8, 200, 60f, 0f) // le temps que le chrono du tour arrive

        assertEquals(1, events.count { it is SessionEvent.RefuelDetected }, "un seul ravitaillement physique")
        val laps = t.snapshot.laps.associateBy { it.lapNumber }
        assertTrue(laps.getValue(6).refuelled)
        assertNull(laps.getValue(6).fuelUsed)
        assertFalse(laps.getValue(7).refuelled, "le tour 7 n'est pas un second ravitaillement")
        assertTrue(laps.getValue(7).pitLane, "le tour 7 commence dans la voie des stands")
        assertEquals(14.4f, laps.getValue(7).fuelUsed!!, 0.05f)
        assertTrue(laps.values.all { it.fuelAfter != null })
        // La moyenne de consommation ignore le tour de sortie des stands.
        assertEquals(5, t.snapshot.fuelSamples.size)
    }

    // ------------------------------------------------------------------ P0 : plan stable au tour 7

    private fun planAtLap7(remaining: Float) = RacePlanner.plan(
        RacePlanner.Input(
            rules = rules, gameTotalLaps = 12, currentLap = 7, currentLapRemaining = remaining,
            fuelNow = 81.1f, tank = 100f, perLap = 13.952f, fuelSource = DataSource.MEASURED,
            avgLapMs = 98_000, raceElapsedMs = null, stopsDone = 1, previousStops = emptyList(),
        ),
    )!!

    @Test
    fun replayOfLap7ExplainsTheContradictoryPlan() {
        // Valeurs du journal : tour 7, 81,1 à bord, tour commencé à 84,8, 13,95 par tour (mesuré).
        val remaining = 1f - (84.8f - 81.1f) / 13.952f
        // Bug reproduit : avec « tour en cours entier » (drapeau ravitaillé faux-positif), un arrêt au tour 9 apparaît.
        assertTrue(planAtLap7(1f).stops.isNotEmpty())
        // Avec la part réelle du tour restant : aucun arrêt, comme au tour 8 (« plus d'arrêt nécessaire »).
        assertTrue(planAtLap7(remaining).stops.isEmpty())
    }

    // ------------------------------------------------------------------ P1 : question sur la prochaine gomme

    @Test
    fun nextTyreQuestionsAreRecognisedFromTheRealTranscripts() {
        for (t in listOf(
            "aux prochain d'arret jamais quoi gommes tu deux pneus",
            "au prochain arret je mets quoi comme type de pneus",
            "tu conseil deux mettre quoi gommes pneus prochain stands",
            "tu me conseille de mettre quoi comme pneus au prochain stand",
        )) {
            assertEquals(DriverCommand.Ask(DriverQuery.NEXT_TYRE), DriverCommandParser.parse(t), t)
        }
        // Les questions sur l'état des pneus restent ce qu'elles sont.
        assertEquals(DriverCommand.Ask(DriverQuery.TYRES), DriverCommandParser.parse("est ce que mes pneus sont bons"))
        assertEquals(DriverCommand.Ask(DriverQuery.TYRES), DriverCommandParser.parse("comment sont les pneus"))
    }

    @Test
    fun nextTyreAnswerTalksAboutTyresNotFuel() = runBlocking {
        val (_, s) = race()
        s.start()
        s.driveLaps(2)
        val conv = RadioConversation(s.controller)
        val out = conv.onUtterance(
            "aux prochain d'arret jamais quoi gommes tu deux pneus",
            "au prochain arret je mets quoi comme type de pneus",
            model = null,
        )
        val answer = out.last().text
        assertFalse("Stratégie" in answer, answer)
        assertTrue("règlement non confirmé" in answer, answer)
        assertTrue("course durs" in answer, answer)
        assertTrue("Tu es en course durs." in answer, answer)
        assertTrue("GT7 ne transmet pas l'usure" in answer, answer)
        assertFalse(out.last().expectsReply && s.controller.ledger.changes.isNotEmpty(), "une question ne modifie rien")
        assertTrue(s.controller.ledger.changes.isEmpty())
    }

    // ------------------------------------------------------------------ P1 : surfaces du format C

    private fun cPacket(surfaces: String, n: Int): TelemetryPacket {
        val plain = TestPackets.plain(lap = 3, totalLaps = 12, packetId = ++id, speedMps = 50f, size = PacketFormat.C.size)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).apply {
            surfaces.forEachIndexed { i, c -> put(0x158 + i, c.code.toByte()) }
            putFloat(0x04, n * 0.8f)
        }
        return TelemetryParser.parseDecrypted(plain)!!
    }

    @Test
    fun surfacesSeparateKerbsFromRealExcursions() {
        val trace = SectorTrace()
        var n = 0
        trace.startLap(cPacket("TTTT", n++))
        repeat(300) { trace.add(cPacket("TTTT", n++)) }
        repeat(60) { trace.add(cPacket("TCTC", n++)) } // vibreur : pas une erreur
        repeat(5) { trace.add(cPacket("CGCG", n++)) } // échantillons isolés : pas une sortie
        repeat(30) { trace.add(cPacket("TTTT", n++)) }
        assertEquals(0, trace.offTracks)
        assertTrue(trace.kerbShare!! > 0.1f)
        repeat(20) { trace.add(cPacket("CGCG", n++)) } // deux roues dans l'herbe 0,33 s : une sortie
        repeat(20) { trace.add(cPacket("TTTT", n++)) }
        assertEquals(1, trace.offTracks)
        // Sans format C, aucune donnée de surface n'est inventée.
        val a = SectorTrace()
        val p = TelemetryParser.parseDecrypted(TestPackets.plain(lap = 3, packetId = ++id, speedMps = 50f))!!
        a.startLap(p)
        assertNull(a.kerbShare)
        assertNull(a.offTracks)
    }

    // ------------------------------------------------------------------ P1 : fin de course

    @Test
    fun aMisheardPlaceAfterTheFinishNeverReplacesTheResult() {
        val v = CommandGate.vet(
            DriverCommand.ReportPosition(12), "douzieme", CommandSource.CONVERSATION,
            GateContext(telemetryLive = true, finalPosition = 1),
        )
        val d = assertIs<Vetting.Discuss>(v)
        assertEquals("Course terminée : tu as fini P1. Je ne change plus le classement.", d.reply)
    }

    @Test
    fun frozenOrMenuPacketsAfterTheFinishCreateNoPhantomSession() {
        val store = FakeStorage(rules = rules.copy(totalLaps = 2))
        val s = RaceSim(RaceController(storage = store), totalLaps = 2, perLap = 5f)
        s.start()
        s.driveLaps(2)
        repeat(120) { s.step(60f) } // passage de la ligne d'arrivée
        assertTrue(s.texts.any { it.startsWith("Course terminée") })
        val before = s.said.size
        // Après l'arrivée : même instantané pendant des minutes (79,6 km/h, position figée), puis menus (tour -1, 178 km/h).
        fun frozen(lap: Int, kmh: Float, x: Float) = repeat(600) {
            val plain = TestPackets.plain(lap = lap, totalLaps = 2, fuel = 2.3f, packetId = ++id + 100_000, speedMps = kmh / 3.6f, posX = x, carCode = 3607)
            s.said += s.controller.onPacket(TelemetryParser.parseDecrypted(plain)!!)
        }
        frozen(3, 79.6f, 103.3f)
        assertFalse(s.controller.telemetryLive())
        frozen(-1, 178.1f, -314.2f)
        frozen(3, 79.6f, 103.3f)
        frozen(-1, 178.1f, -314.2f)
        assertEquals(before, s.said.size, s.said.drop(before).map { it.text }.toString())
        assertEquals(1, store.journals.size, "aucune session fantôme")
        assertEquals(1, store.sessions.size)
        assertTrue(SessionJournal.load(store, store.journals.keys.single()).any { "Télémétrie figée" in it.text })
    }
}
