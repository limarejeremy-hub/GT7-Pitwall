package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.FailureKind
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.ai.chatgpt.ResponsesStream
import com.gt7pitwall.core.ai.chatgpt.SiwcAuth
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #67 : « Sign in with ChatGPT » (forfait Plus du pilote, sans clé d'API). Messages OAuth et flux Responses
 * vérifiés contre la documentation officielle (developers.openai.com/siwc, lue le 05/11/2026). Aucun appel réseau :
 * jetons, clés et réponses du modèle sont SIMULÉS ; rien ici ne prouve que le flux marche sur le Fold.
 */
class ChatGptPlanTest {
    private val host = SiwcAuth.newHostId()

    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test fun firstRegistrationUsesTheDynamicClientTheHostIdAndAllOfficialParameters() {
        assertTrue(SiwcAuth.isValidHostId(host), host)
        val a = SiwcAuth.newAttempt(1455, host, issuedClientId = null)
        val url = SiwcAuth.authorizeUrl(a)
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"), url)
        val q = query(url)
        assertEquals("dynamic_agent_client", q["client_id"])
        assertEquals("GT7 Pitwall", q["agent_name_hint"])
        assertEquals(host, q["ext_agent_host_id"])
        assertEquals("code", q["response_type"])
        assertEquals("http://127.0.0.1:1455/auth/callback", q["redirect_uri"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", q["scope"])
        assertEquals("https://api.openai.com/v1", q["resource"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(SiwcAuth.codeChallenge(a.verifier), q["code_challenge"])
        assertEquals(a.state, q["state"])
        assertEquals(a.nonce, q["nonce"])
        assertFalse("localhost" in url)
        assertFalse(a.toString().contains(a.verifier), "le vérificateur PKCE n'apparaît jamais dans un journal")
    }

    @Test fun reauthorisationReusesTheIssuedClientWithoutTheNameHint() {
        val a = SiwcAuth.newAttempt(54321, host, issuedClientId = "oaiapp_abc")
        val q = query(SiwcAuth.authorizeUrl(a, idTokenHint = "eyJ.x.y", loginHint = "pilote@example.com"))
        assertEquals("oaiapp_abc", q["client_id"])
        assertNull(q["agent_name_hint"])
        assertEquals("eyJ.x.y", q["id_token_hint"])
        assertEquals("http://127.0.0.1:54321/auth/callback", q["redirect_uri"], "seul le port peut changer")
    }

    @Test fun pkceMatchesTheRfc7636TestVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SiwcAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun theCallbackIsValidatedBeforeAnyExchange() {
        val a = SiwcAuth.newAttempt(1455, host, null)
        val ok = SiwcAuth.parseCallback("/auth/callback?code=C1&scope=openid&state=${a.state}&client_id=oaiapp_new", a)
        assertEquals(SiwcAuth.Callback.Code("C1", "oaiapp_new"), ok)
        assertIs<SiwcAuth.Callback.Invalid>(SiwcAuth.parseCallback("/auth/callback?code=C1&state=forged&client_id=oaiapp_new", a))
        assertIs<SiwcAuth.Callback.Denied>(SiwcAuth.parseCallback("/auth/callback?error=access_denied&state=${a.state}", a))
        assertIs<SiwcAuth.Callback.Invalid>(SiwcAuth.parseCallback("/auth/callback?code=C1&state=${a.state}", a), "inscription sans client attribué : incomplète")
        assertIs<SiwcAuth.Callback.Invalid>(SiwcAuth.parseCallback("/callback?code=C1&state=${a.state}&client_id=x", a), "/callback ne correspond pas à /auth/callback")
        // Réautorisation : client absent accepté (celui de l'inscription), client différent rejeté.
        val re = SiwcAuth.newAttempt(1455, host, "oaiapp_abc")
        assertEquals(SiwcAuth.Callback.Code("C2", "oaiapp_abc"), SiwcAuth.parseCallback("/auth/callback?code=C2&state=${re.state}", re))
        assertIs<SiwcAuth.Callback.Invalid>(SiwcAuth.parseCallback("/auth/callback?code=C2&state=${re.state}&client_id=oaiapp_other", re))
    }

    @Test fun tokenBodiesFollowTheDocumentation() {
        val a = SiwcAuth.newAttempt(1455, host, null)
        val ex = query("?" + SiwcAuth.codeExchangeBody("C1", "oaiapp_new", a))
        assertEquals("authorization_code", ex["grant_type"])
        assertEquals("oaiapp_new", ex["client_id"])
        assertEquals(a.verifier, ex["code_verifier"])
        assertEquals(a.redirectUri, ex["redirect_uri"])
        assertEquals("https://api.openai.com/v1", ex["resource"])
        val rf = query("?" + SiwcAuth.refreshBody("oaiapp_new", "R1"))
        assertEquals("refresh_token", rf["grant_type"])
        assertNull(rf["scope"], "portée omise pour garder l'accord")
        val rv = query("?" + SiwcAuth.revokeBody("oaiapp_new", "R1"))
        assertEquals("refresh_token", rv["token_type_hint"])
        assertTrue(runCatching { SiwcAuth.refreshBody("dynamic_agent_client", "R1") }.isFailure)
    }

    @Test fun withoutThePlanScopeTheSignInIsKeptButNoInference() {
        val withPlan = SiwcAuth.TokenResponse.parse("""{"access_token":"A","refresh_token":"R","id_token":"I","token_type":"Bearer","expires_in":3600,"scope":"chatgpt.tokens.use.direct email offline_access openid profile resource.invoke"}""")
        assertTrue(withPlan.planUsage)
        assertFalse(withPlan.toString().contains("A\""), "jetons masqués")
        val declined = SiwcAuth.TokenResponse.parse("""{"access_token":"A","id_token":"I","expires_in":3600,"scope":"email openid profile"}""")
        assertFalse(declined.planUsage)
    }

    // ---------------------------------------------------------------- Jeton d'identité (clé RSA simulée)

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun b64(b: ByteArray) = SiwcAuth.b64url(b)
    private fun unsigned(i: BigInteger) = i.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    private val jwks = (keys.public as RSAPublicKey).let { pk ->
        """{"keys":[{"kty":"RSA","kid":"k1","alg":"RS256","n":"${b64(unsigned(pk.modulus))}","e":"${b64(unsigned(pk.publicExponent))}"}]}"""
    }

    private fun idToken(payload: JSONObject, kid: String = "k1"): String {
        val h = b64("""{"alg":"RS256","kid":"$kid","typ":"JWT"}""".toByteArray())
        val p = b64(payload.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update("$h.$p".toByteArray()); sign() }
        return "$h.$p.${b64(sig)}"
    }

    private fun claims(now: Long, nonce: String = "N1", aud: String = "oaiapp_new", iss: String = "https://auth.openai.com") = JSONObject()
        .put("iss", iss).put("aud", aud).put("sub", "user-123").put("email", "pilote@example.com")
        .put("exp", now + 3600).put("iat", now).put("nonce", nonce)

    @Test fun theIdTokenIsVerifiedAgainstTheJwksAndEveryClaim() {
        val now = 1_793_000_000L
        val ok = SiwcAuth.verifyIdToken(idToken(claims(now)), jwks, "oaiapp_new", "N1", now)
        assertEquals("user-123", ok.getOrThrow().subject)
        assertTrue(SiwcAuth.verifyIdToken(idToken(claims(now, nonce = "autre")), jwks, "oaiapp_new", "N1", now).isFailure, "nonce")
        assertTrue(SiwcAuth.verifyIdToken(idToken(claims(now, aud = "oaiapp_x")), jwks, "oaiapp_new", "N1", now).isFailure, "audience")
        assertTrue(SiwcAuth.verifyIdToken(idToken(claims(now, iss = "https://evil.example")), jwks, "oaiapp_new", "N1", now).isFailure, "émetteur")
        assertTrue(SiwcAuth.verifyIdToken(idToken(claims(now - 10_000)), jwks, "oaiapp_new", "N1", now).isFailure, "expiré")
        assertTrue(SiwcAuth.verifyIdToken(idToken(claims(now), kid = "k9"), jwks, "oaiapp_new", "N1", now).isFailure, "clé inconnue")
        val forged = idToken(claims(now)).let { t -> t.substringBeforeLast('.') + "." + b64(ByteArray(256)) }
        assertTrue(SiwcAuth.verifyIdToken(forged, jwks, "oaiapp_new", "N1", now).isFailure, "signature")
    }

    @Test fun credentialsSurviveARoundTripAndNeverPrintTheirTokens() {
        val c = SiwcAuth.Credentials("oaiapp_new", host, "user-123", "pilote@example.com", "ACCESS-SECRET", "REFRESH-SECRET", "ID", setOf(SiwcAuth.PLAN_SCOPE), 10_000L, "gpt-6.1-sol")
        assertEquals(c, SiwcAuth.Credentials.fromJson(c.toJson()))
        assertFalse("SECRET" in c.toString(), c.toString())
        assertTrue(c.needsRefresh(10_000L - 60_000))
        assertTrue(SiwcAuth.refreshTokenUnusable("refresh_token_reused"))
        assertFalse(SiwcAuth.refreshTokenUnusable("server_error"))
    }

    // ---------------------------------------------------------------- Responses (flux simulé)

    @Test fun theRequestBodyIsEligible() {
        val o = JSONObject(ResponsesStream.requestBody("gpt-6.1-sol", "Tu es l'ingénieur.", "Quel est mon plan carburant ?"))
        assertEquals(false, o.getBoolean("store"))
        assertEquals(true, o.getBoolean("stream"))
        assertEquals("Tu es l'ingénieur.", o.getString("instructions"), "consigne dans instructions, jamais un message system")
        assertEquals("user", o.getJSONArray("input").getJSONObject(0).getString("role"))
        for (f in listOf("temperature", "max_output_tokens", "previous_response_id", "metadata", "user", "top_p")) assertFalse(o.has(f), f)
    }

    private fun stream(vararg events: String): AiResult = ResponsesStream.Reader().also { r ->
        events.forEach { e -> r.line("event: x"); r.line("data: $e"); r.line("") }
        r.end()
    }.result

    @Test fun onlyACompletedStreamIsAnAnswer() {
        val ok = stream("""{"type":"response.output_text.delta","delta":"Un arrêt "}""", """{"type":"response.output_text.delta","delta":"fin du tour 7."}""", """{"type":"response.completed"}""")
        assertEquals(AiResult.Text("Un arrêt fin du tour 7."), ok)
        val cut = stream("""{"type":"response.output_text.delta","delta":"Un arrêt"}""")
        assertIs<AiResult.Failure>(cut, "flux coupé avant response.completed")
        val limit = stream("""{"type":"response.output_text.delta","delta":"Un"}""", """{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""")
        assertEquals(FailureKind.QUOTA, (limit as AiResult.Failure).kind)
    }

    @Test fun officialErrorCodesMapToALocalFallbackNeverToAPaidRoute() {
        assertEquals(FailureKind.QUOTA, ResponsesStream.httpFailure(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""").kind)
        assertEquals(FailureKind.AUTH, ResponsesStream.httpFailure(401, """{"detail":"signed identity not accepted"}""").kind)
        assertEquals(FailureKind.NOT_CONFIGURED, ResponsesStream.httpFailure(403, """{"error":{"code":"subscription_sharing_user_not_eligible"}}""").kind)
        assertEquals(FailureKind.OVERLOADED, ResponsesStream.httpFailure(503, """{"error":{"code":"subscription_sharing_usage_unavailable"}}""").kind)
        assertEquals(ResponsesStream.Recovery.PAUSE_LIMIT, ResponsesStream.recovery("subscription_sharing_usage_limit_exceeded", 429))
        assertTrue(FailureKind.QUOTA.fast && FailureKind.AUTH.fast, "échec immédiat : le moteur local répond sans attendre")
    }

    @Test fun onlyListedModelsAreOffered() {
        val m = ResponsesStream.listedModels("""{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},{"slug":"hidden","visibility":"hide"}]}""")
        assertEquals(listOf("gpt-6.1-sol" to "GPT-6.1 Sol"), m)
    }

    // ---------------------------------------------------------------- Radio : faits seulement, repli local

    private class FakeChatGpt(val script: suspend (String) -> AiResult) : AiProvider {
        val prompts = mutableListOf<String>()
        override val backend = AiBackend.CHATGPT
        override val modelId = "gpt-6.1-sol"
        override val ready = true
        override val status = "simulé (test)"
        override suspend fun complete(systemInstruction: String, prompt: String): AiResult { prompts += prompt; return script(prompt) }
    }

    private fun conversation(chat: FakeChatGpt, mode: AiMode = AiMode.CHATGPT_PLAN): Pair<RaceSim, RadioConversation> {
        val rules = RaceRules(totalLaps = 12, fuelMultiplier = 7, mandatoryStops = 1, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM, TyreCompound.RS), startTyre = TyreCompound.RH)
        val sim = RaceSim(RaceController(storage = FakeStorage(rules = rules)), totalLaps = 12, perLap = 14f)
        val conv = RadioConversation(sim.controller, Mutex()).also { it.cloud = AiRouter(mapOf(AiBackend.CHATGPT to chat), AiUsage(), mode, totalTimeoutMs = 12_000L) }
        return sim to conv
    }

    @Test fun chatGptAnswersFromTheEngineFactsAndAnInventedNumberIsRejected() = runBlocking<Unit> {
        val chat = FakeChatGpt { AiResult.Text("Tu as 3,2 secondes d'avance sur le deuxième, garde ce rythme.") }
        val (sim, conv) = conversation(chat)
        sim.start()
        sim.driveLaps(2)
        val out = conv.onUtterance("pourquoi tu veux me faire rentrer maintenant", "pourquoi tu veux me faire rentrer maintenant", model = null)
        assertEquals(1, chat.prompts.size, "une question ouverte part vers ChatGPT")
        assertTrue("CONTEXTE" in chat.prompts.single(), "le prompt porte les faits du moteur")
        val answer = out.last { it.category == MessageCategory.ANSWER }
        assertFalse("3,2" in answer.text, "chiffre absent des faits : jamais dit (${answer.text})")
        assertFalse(answer.fromAi, "réponse écartée : le moteur local répond")
    }

    @Test fun aUsageLimitFallsBackToTheLocalEngineAtOnce() = runBlocking<Unit> {
        val chat = FakeChatGpt { ResponsesStream.httpFailure(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""") }
        val (sim, conv) = conversation(chat)
        sim.start()
        sim.driveLaps(2)
        val out = conv.onUtterance("pourquoi tu veux me faire rentrer maintenant", "pourquoi tu veux me faire rentrer maintenant", model = null)
        assertTrue(out.any { it.category == MessageCategory.ANSWER && !it.fromAi }, out.map { it.text }.toString())
    }

    @Test fun theLocalModeNeverCallsChatGpt() = runBlocking<Unit> {
        val chat = FakeChatGpt { AiResult.Text("ne doit pas être appelé") }
        val (sim, conv) = conversation(chat, mode = AiMode.LOCAL)
        sim.start()
        sim.driveLaps(2)
        conv.onUtterance("pourquoi tu veux me faire rentrer maintenant", "pourquoi tu veux me faire rentrer maintenant", model = null)
        assertEquals(0, chat.prompts.size)
    }

    // ---------------------------------------------------------------- Jalon 1 : « Quel est mon plan carburant ? »

    @Test fun theFuelPlanTestKeepsOnlyValidatedNumbers() {
        val p = com.gt7pitwall.core.ai.chatgpt.FuelPlanCheck
        assertTrue("SIMULATION" in p.prompt && "7,1 %" in p.prompt, "faits simulés, affichés comme tels")
        val good = p.check(AiResult.Text("On s'arrête à la fin du tour 6 avec un plein de 85 %, tu finis avec 2 % de marge."))
        assertTrue(good.fromChatGpt, good.verdict)
        val invented = p.check(AiResult.Text("Arrêt au tour 8 avec 60 % d'essence."))
        assertFalse(invented.fromChatGpt)
        assertEquals(p.LOCAL_ANSWER, invented.answer)
        val offline = p.check(AiResult.Failure(FailureKind.NETWORK))
        assertEquals(p.LOCAL_ANSWER, offline.answer, "sans réseau : réponse locale")
        val limit = p.check(ResponsesStream.httpFailure(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}"""))
        assertFalse(limit.fromChatGpt, "limite atteinte : réponse locale")
    }
}
