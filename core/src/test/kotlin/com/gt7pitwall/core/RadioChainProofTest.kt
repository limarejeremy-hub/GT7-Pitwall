package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.ai.ToolCapable
import com.gt7pitwall.core.ai.chatgpt.ResponsesStream
import com.gt7pitwall.core.ai.tools.ToolLoop
import com.gt7pitwall.core.ai.tools.ToolSnapshot
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Consigne du 09/11, point 3 : preuve HORS PS5 de la chaîne question d'analyse → GPT → outils → réponse, par replay
 * d'une course simulée et un FOURNISSEUR SIMULÉ (transport Responses scripté, vraie boucle d'outils [ToolLoop], vrais
 * outils en lecture seule sur le cliché du moteur). Ce n'est PAS une validation réseau, GPT réel, PS5 ou Discord : GPT
 * n'est pas « validé en course » tant qu'un journal de course réel ne montre pas un appel avec outils.
 */
class RadioChainProofTest {

    /** GPT simulé : demande [tools] au 1er aller-retour, puis répond [answer] ; chaque sortie d'outil est gardée. */
    private class ScriptedGpt(private val tools: List<String>, private val answer: String) : AiProvider, ToolCapable {
        val outputs = mutableListOf<JSONObject>()
        var calls = 0
        override val backend = AiBackend.CHATGPT
        override val modelId = "gpt-simulé"
        override val ready = true
        override val status = "simulé"
        override val toolsEnabled = true
        override suspend fun complete(systemInstruction: String, prompt: String): AiResult = error("le chemin avec outils doit être pris")
        override suspend fun completeWithTools(systemInstruction: String, prompt: String, snapshot: ToolSnapshot): AiResult {
            calls++
            var round = 0
            val loop = ToolLoop({ body ->
                round++
                if (round > 1) {
                    val input = JSONObject(body).getJSONArray("input")
                    for (i in 0 until input.length()) input.getJSONObject(i).takeIf { it.optString("type") == "function_call_output" }?.let { outputs += JSONObject(it.getString("output")) }
                }
                if (round == 1) ResponsesStream.Turn("", tools.mapIndexed { i, t -> ResponsesStream.FunctionCall("call_$i", t, "{}", "pitwall") }, null)
                else ResponsesStream.Turn(answer, emptyList(), null)
            })
            return loop.run(modelId, systemInstruction, prompt, snapshot).result
        }
    }

    private val rules = RaceRules(totalLaps = 11, fuelMultiplier = 3, mandatoryStops = 0, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM), startTyre = TyreCompound.RM)

    private fun race(gpt: ScriptedGpt): Pair<RaceSim, RadioConversation> {
        val sim = RaceSim(RaceController(storage = FakeStorage(rules = rules)), totalLaps = 11, perLap = 9f).also { it.start(); it.driveLaps(4) }
        val conv = RadioConversation(sim.controller, Mutex())
        conv.cloud = AiRouter(mapOf(AiBackend.CHATGPT to gpt), AiUsage(), AiMode.CHATGPT_PLAN, totalTimeoutMs = 12_000L)
        return sim to conv
    }

    private fun say(conv: RadioConversation, text: String) = runBlocking { conv.onUtterance(text, text, model = null) }

    /** État de course qui ne doit pas bouger sur une question. */
    private fun state(sim: RaceSim) = listOf(
        sim.controller.rules, sim.controller.ledger.changes, sim.controller.ledger.stopList, sim.controller.ledger.pending,
        sim.controller.plan?.stops,
    )

    private fun assertChain(sim: RaceSim, conv: RadioConversation, gpt: ScriptedGpt, tools: List<String>, before: List<Any?>) {
        assertEquals(1, gpt.calls, "la question a atteint GPT (simulé)")
        assertEquals(tools.size, gpt.outputs.size, "chaque outil demandé a rendu sa sortie")
        for (o in gpt.outputs) {
            assertTrue(o.has("provenance") || o.has("facts"), "faits avec provenance : $o")
            assertTrue(o.has("missing"), "données manquantes explicites : $o")
        }
        val r = conv.lastRoute
        assertNotNull(r)
        assertEquals("IA", r.route, r.text)
        assertEquals(tools, r.tools, r.text)
        assertNotNull(r.latencyMs, "délai mesuré")
        val line = sim.controller.journal.recent().last { it.text.startsWith("Route radio : ") }
        assertEquals(tools.joinToString(","), line.details["outils"], line.text)
        assertNotNull(line.details["delai_ms"])
        assertTrue(line.text.contains("CHATGPT_PLAN") && line.text.contains("gpt-simulé"), "mode et fournisseur effectif : ${line.text}")
        // Aucun secret dans le journal (pas de jeton, d'en-tête d'autorisation ni d'adresse réseau).
        val all = sim.controller.journal.recent(2000).joinToString("\n") { it.text + it.details }
        for (bad in listOf("Bearer", "access_token", "refresh_token", "sk-", "Authorization")) assertTrue(bad !in all, "secret « $bad » dans le journal")
        assertEquals(before, state(sim), "aucune mutation de pneus, d'arrêts, de règles ou de plan sur une question")
        assertTrue(sim.controller.journal.recent().none { it.decision == "appliquée" }, "aucune commande appliquée")
    }

    @Test fun whyAmILosingTimeInThisCornerGoesToGptWithTheTelemetryTools() {
        val tools = listOf("get_corner_comparison", "get_recent_laps")
        val gpt = ScriptedGpt(tools, "D'après tes tours mesurés, je n'ai pas encore assez de passages comparables pour désigner le virage.")
        val (sim, conv) = race(gpt)
        val before = state(sim)
        val out = say(conv, "pourquoi je perds du temps dans ce virage ?")
        assertChain(sim, conv, gpt, tools, before)
        assertTrue(out.any { it.fromAi }, "réponse de GPT dite : ${out.map { it.text }}")
    }

    @Test fun wouldHardsHaveBeenBetterIsAnAnalysisNeverATyreDeclaration() {
        val tools = listOf("get_tyre_strategy", "get_start_tyre_advice")
        val gpt = ScriptedGpt(tools, "Je n'ai pas de relais en durs comparable : je ne peux pas dire que c'était mieux.")
        val (sim, conv) = race(gpt)
        val before = state(sim)
        say(conv, "était-il préférable de partir en durs ?")
        assertChain(sim, conv, gpt, tools, before)
        assertEquals(TyreCompound.RM, sim.controller.rules.startTyre)
    }

    @Test fun aStrategyComparisonUsesTheEngineFuelPlanAndChangesNothing() {
        val tools = listOf("get_fuel_strategy", "get_live_race_state")
        val gpt = ScriptedGpt(tools, "Le plan du moteur reste le plus sûr : rien ne montre qu'un arrêt de plus te ferait gagner du temps.")
        val (sim, conv) = race(gpt)
        val before = state(sim)
        say(conv, "vaut-il mieux faire un arrêt de plus ou garder la stratégie ?")
        assertChain(sim, conv, gpt, tools, before)
    }

    @Test fun aCommandProposedByGptOnAQuestionIsNeitherAppliedNorProposed() {
        val gpt = ScriptedGpt(listOf("get_tyre_strategy"), "COMMANDE: pneus durs")
        val (sim, conv) = race(gpt)
        val before = state(sim)
        val out = say(conv, "était-il préférable de partir en durs ?")
        assertEquals(before, state(sim))
        assertTrue(out.none { it.expectsReply && it.text.startsWith("Tu confirmes") }, "aucune confirmation proposée : ${out.map { it.text }}")
        assertTrue(conv.lastTrace!!.verdict.startsWith("commande de l'IA écartée"), conv.lastTrace!!.verdict)
        assertTrue(sim.controller.journal.recent().none { it.decision == "appliquée" })
    }

    @Test fun stateQuestionsStayLocalAndInstantEvenInGptMode() {
        val gpt = ScriptedGpt(listOf("get_fuel_strategy"), "x")
        val (_, conv) = race(gpt)
        say(conv, "carburant")
        assertEquals(0, gpt.calls, "pas d'attente GPT pour l'état du carburant")
        assertEquals("locale instantanée", conv.lastRoute?.route)
        assertEquals(null, conv.lastRoute?.tools, "chemin sans IA : aucun outil")
    }
}
