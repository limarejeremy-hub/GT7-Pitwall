package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.FailureKind
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.ai.ToolCapable
import com.gt7pitwall.core.ai.chatgpt.ResponsesStream
import com.gt7pitwall.core.ai.tools.PitwallDataTools
import com.gt7pitwall.core.ai.tools.ToolLoop
import com.gt7pitwall.core.ai.tools.ToolSnapshot
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #70, étape 2 : outils de données de Véga en lecture seule, boucle d'appels bornée. Transport SIMULÉ (aucun
 * appel réseau) : rien ici ne prouve que le forfait accepte les outils sur le Fold — le bouton de test de l'APK le dira.
 */
class VegaToolsTest {

    private fun race(): RaceSim {
        val rules = RaceRules(totalLaps = 12, fuelMultiplier = 7, mandatoryStops = 1, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM), startTyre = TyreCompound.RH)
        return RaceSim(RaceController(storage = FakeStorage(rules = rules)), totalLaps = 12, perLap = 14f).also { it.start(); it.driveLaps(3) }
    }

    private fun snapshotOf(sim: RaceSim) = ToolSnapshot.from(sim.controller.aiFacts(), sim.controller.telemetryLive(), 4, 1_000L)

    @Test fun theEngineFactsAreSplitByTopicWithTheirProvenance() {
        val sim = race()
        val facts = sim.controller.aiFacts()
        val snap = ToolSnapshot.from(facts, true, 4, 1_000L)
        val fuel = snap.result(PitwallDataTools.Tool.FUEL)
        assertTrue(fuel.facts.any { it.text.startsWith("Carburant") }, fuel.toJson())
        val laps = snap.result(PitwallDataTools.Tool.LAPS)
        val measured = laps.facts.filter { Regex("^Tour \\d+ :").containsMatchIn(it.text) }
        assertTrue(measured.isNotEmpty() && measured.all { it.provenance == PitwallDataTools.Provenance.MEASURED }, laps.toJson())
        val rules = snap.result(PitwallDataTools.Tool.RULES)
        assertTrue(rules.facts.any { it.text.startsWith("Obligations du règlement") })
        val live = snap.result(PitwallDataTools.Tool.LIVE)
        assertTrue(live.missing.any { "adversaires" in it }, "ce que GT7 ne transmet pas est dit")
        // Aucune phrase perdue ni inventée : chaque fait d'outil est une phrase du moteur.
        val all = PitwallDataTools.Tool.entries.flatMap { snap.result(it).facts.map { f -> f.text } }
        assertEquals(facts.lines.toSet(), all.toSet() - facts.observations.map { it.text }.toSet())
    }

    @Test fun withoutTelemetryTheToolsSaySoInsteadOfServingOldValues() {
        val snap = ToolSnapshot.from(AiFacts(listOf("Aucune télémétrie de course en ce moment : aucune position.", "Obligations du règlement : 1 arrêt.")), false, null, 5L)
        val fuel = snap.result(PitwallDataTools.Tool.FUEL)
        assertTrue(fuel.facts.isEmpty())
        assertTrue(fuel.missing.any { "télémétrie en direct" in it })
        assertEquals("aucune donnée", fuel.confidence)
    }

    @Test fun theToolsAreReadOnlyAndUseTheNamespaceFormat() {
        val ns = PitwallDataTools.namespace()
        assertEquals("namespace", ns.getString("type"))
        val tools = ns.getJSONArray("tools")
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            assertEquals("function", t.getString("type"))
            assertTrue(t.getString("name").startsWith("get_"), "lecture seule : ${t.getString("name")}")
            assertTrue(t.getBoolean("strict"))
            assertFalse(t.getJSONObject("parameters").getBoolean("additionalProperties"))
        }
        assertEquals(PitwallDataTools.Tool.FUEL, PitwallDataTools.Tool.byName("pitwall.get_fuel_strategy"))
        assertNull(PitwallDataTools.Tool.byName("set_race_rules"))
    }

    // ---------------------------------------------------------------- Boucle d'outils (transport simulé)

    private fun call(id: String, name: String) = ResponsesStream.FunctionCall(id, name, "{}", "pitwall")

    @Test fun theModelFetchesAToolAndAnswersFromItsFacts() = runBlocking<Unit> {
        val snap = snapshotOf(race())
        val bodies = mutableListOf<JSONObject>()
        val loop = ToolLoop({ b ->
            bodies += JSONObject(b)
            if (bodies.size == 1) ResponsesStream.Turn("", listOf(call("call_1", "get_fuel_strategy")), null)
            else ResponsesStream.Turn("Le plan carburant tient.", emptyList(), null)
        })
        val out = loop.run("gpt-6.1-sol", "Tu es Véga.", "Quel est mon plan carburant ?", snap)
        assertIs<AiResult.Text>(out.result)
        assertTrue((out.result as AiResult.Text).evidence.contains("Carburant"), "faits réellement rendus, pour le contrôle des chiffres")
        assertEquals(listOf(ToolLoop.Call("get_fuel_strategy", true)), out.calls)
        val second = bodies[1]
        assertFalse(second.has("previous_response_id"), "aperçu : historique renvoyé dans input")
        assertEquals(false, second.getBoolean("store"))
        val input = second.getJSONArray("input")
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        assertEquals("call_1", input.getJSONObject(2).getString("call_id"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
        assertTrue(input.getJSONObject(2).getString("output").contains("provenance"))
        assertEquals("namespace", second.getJSONArray("tools").getJSONObject(0).getString("type"))
    }

    @Test fun anHallucinatedToolGetsAnErrorNeverData() = runBlocking<Unit> {
        var n = 0
        val loop = ToolLoop({ _ ->
            n++
            if (n == 1) ResponsesStream.Turn("", listOf(call("c", "set_race_rules")), null) else ResponsesStream.Turn("D'accord.", emptyList(), null)
        })
        val out = loop.run("m", "i", "q", snapshotOf(race()))
        assertEquals(listOf(ToolLoop.Call("set_race_rules", false)), out.calls)
        assertEquals("", (out.result as AiResult.Text).evidence)
    }

    @Test fun anEndlessToolLoopIsCutAndFallsBackLocally() = runBlocking<Unit> {
        var i = 0
        val loop = ToolLoop({ _ -> ResponsesStream.Turn("", listOf(call("c${i++}", "get_live_race_state"), call("d${i++}", "get_recent_laps")), null) })
        val out = loop.run("m", "i", "q", snapshotOf(race()))
        assertIs<AiResult.Failure>(out.result)
        assertTrue(out.calls.size <= ToolLoop.MAX_CALLS)
    }

    @Test fun aServerRefusalOfToolsIsFlaggedForARetryWithout() = runBlocking<Unit> {
        val loop = ToolLoop({ _ -> ResponsesStream.Turn("", emptyList(), ResponsesStream.httpFailure(400, """{"error":{"code":"subscription_sharing_unsupported_capability"}}""")) })
        val out = loop.run("m", "i", "q", snapshotOf(race()))
        assertTrue(out.toolsRejected)
    }

    @Test fun theStreamReaderCollectsFunctionCalls() {
        val r = ResponsesStream.Reader()
        listOf(
            """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call_1","name":"get_fuel_strategy","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","delta":"{}"}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_fuel_strategy","namespace":"pitwall","arguments":"{}"}}""",
            """{"type":"response.completed"}""",
        ).forEach { r.line("data: $it"); r.line("") }
        r.end()
        val t = r.turn
        assertNull(t.failure)
        assertEquals(listOf(ResponsesStream.FunctionCall("call_1", "get_fuel_strategy", "{}", "pitwall")), t.calls)
        assertFalse(t.calls.single().toInputItem().has("id"), "aucun identifiant serveur renvoyé (store=false)")
    }

    // ---------------------------------------------------------------- Radio : les outils passent par les mêmes garde-fous

    private class FakeToolChat(var enabled: Boolean, val answer: (ToolSnapshot) -> AiResult) : AiProvider, ToolCapable {
        var plain = 0
        var withTools = 0
        override val backend = AiBackend.CHATGPT
        override val modelId = "gpt-6.1-sol"
        override val ready = true
        override val status = "simulé"
        override val toolsEnabled get() = enabled
        override suspend fun complete(systemInstruction: String, prompt: String): AiResult { plain++; return AiResult.Text("Tu as 9,9 secondes d'avance.") }
        override suspend fun completeWithTools(systemInstruction: String, prompt: String, snapshot: ToolSnapshot): AiResult { withTools++; return answer(snapshot) }
    }

    private fun conversation(chat: FakeToolChat): Pair<RaceSim, RadioConversation> {
        val sim = race()
        val conv = RadioConversation(sim.controller, Mutex()).also { it.cloud = AiRouter(mapOf(AiBackend.CHATGPT to chat), AiUsage(), AiMode.CHATGPT_PLAN, totalTimeoutMs = 12_000L) }
        return sim to conv
    }

    @Test fun toolsAreUsedOnlyWhenThePilotEnabledThem() = runBlocking<Unit> {
        val chat = FakeToolChat(enabled = false) { AiResult.Text("non") }
        val (_, conv) = conversation(chat)
        conv.onUtterance("pourquoi tu veux me faire rentrer maintenant", "pourquoi tu veux me faire rentrer maintenant", model = null)
        assertEquals(1, chat.plain)
        assertEquals(0, chat.withTools)
        val on = FakeToolChat(enabled = true) { AiResult.Text("non") }
        val (_, conv2) = conversation(on)
        conv2.onUtterance("pourquoi tu veux me faire rentrer maintenant", "pourquoi tu veux me faire rentrer maintenant", model = null)
        assertEquals(1, on.withTools)
        assertEquals(0, on.plain)
    }

    @Test fun aNumberAbsentFromTheFactsAndToolsIsStillRejected() = runBlocking<Unit> {
        val chat = FakeToolChat(enabled = true) { snap ->
            AiResult.Text("Il te reste 99,9 tours d'essence.", snap.result(PitwallDataTools.Tool.FUEL).evidenceText)
        }
        val (_, conv) = conversation(chat)
        val out = conv.onUtterance("pourquoi tu veux me faire rentrer maintenant", "pourquoi tu veux me faire rentrer maintenant", model = null)
        assertEquals(1, chat.withTools)
        val answer = out.last { it.category == MessageCategory.ANSWER }
        assertFalse("99,9" in answer.text, answer.text)
    }

    @Test fun aToolFailureFallsBackToTheLocalEngine() = runBlocking<Unit> {
        val chat = FakeToolChat(enabled = true) { AiResult.Failure(FailureKind.QUOTA) }
        val (_, conv) = conversation(chat)
        val out = conv.onUtterance("pourquoi tu veux me faire rentrer maintenant", "pourquoi tu veux me faire rentrer maintenant", model = null)
        assertTrue(out.any { it.category == MessageCategory.ANSWER && !it.fromAi }, out.map { it.text }.toString())
    }

    @Test fun theToolsTestNeedsRealToolCallsAndOnlyTheirFacts() = runBlocking<Unit> {
        val p = com.gt7pitwall.core.ai.chatgpt.FuelPlanCheck
        assertFalse("7,1" in p.toolsPrompt, "les faits ne sont pas dans la question")
        var n = 0
        val loop = ToolLoop({ _ ->
            n++
            if (n == 1) ResponsesStream.Turn("", listOf(call("c1", "get_fuel_strategy")), null)
            else ResponsesStream.Turn("Simulation : arrêt à la fin du tour 6, plein de 85 % pour finir avec 2 % de marge.", emptyList(), null)
        })
        val ok = p.checkWithTools(loop.run("m", "i", p.toolsPrompt, p.snapshot(0)).result)
        assertTrue(ok.fromChatGpt, ok.verdict)
        assertFalse(p.checkWithTools(AiResult.Text("Arrêt au tour 6.")).fromChatGpt, "sans appel d'outil : écarté")
        assertFalse(p.checkWithTools(AiResult.Text("Arrêt au tour 9.", "Plan du moteur : arrêt à la fin du tour 6.")).fromChatGpt, "chiffre absent des outils")
    }
}
