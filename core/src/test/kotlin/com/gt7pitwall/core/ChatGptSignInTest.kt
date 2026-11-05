package com.gt7pitwall.core

import com.gt7pitwall.core.ai.chatgpt.LoopbackReceiver
import com.gt7pitwall.core.ai.chatgpt.SignInTrace
import com.gt7pitwall.core.ai.chatgpt.SiwcAuth
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Revue #69 (P0 du 05/11) : transport du retour de connexion avec de VRAIES sockets sur 127.0.0.1 (sur PC). Le cycle de
 * vie Android (Pitwall en arrière-plan, Fold plié) n'est pas reproduit ici : il reste à vérifier sur le téléphone.
 */
class ChatGptSignInTest {
    private val pool = Executors.newCachedThreadPool()
    private val host = SiwcAuth.newHostId()

    private fun get(port: Int, pathAndQuery: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$pathAndQuery").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        val code = c.responseCode
        val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty() }.getOrDefault("")
        c.disconnect()
        return code to body
    }

    @Test fun theBrowserCallbackIsReceivedAfterUnrelatedRequestsAndShowsNoSecret() {
        val r = LoopbackReceiver.open(0)
        val a = SiwcAuth.newAttempt(r.port, host, null)
        val waiting = pool.submit<LoopbackReceiver.Outcome> { r.await(a, 10_000) }
        assertEquals(404, get(r.port, "/favicon.ico").first, "requête annexe : 404, l'attente continue")
        val (code, page) = get(r.port, "/auth/callback?code=SECRETCODE&scope=openid&state=${a.state}&client_id=oaiapp_new")
        assertEquals(200, code)
        assertTrue("Connexion reçue" in page)
        assertFalse("SECRETCODE" in page || a.state in page, "aucun paramètre recopié dans la page")
        val out = waiting.get(5, TimeUnit.SECONDS)
        assertEquals(LoopbackReceiver.Outcome.Received(SiwcAuth.Callback.Code("SECRETCODE", "oaiapp_new")), out)
        assertTrue(r.closed, "écoute fermée après le retour")
    }

    @Test fun cancellingClosesTheListeningSocketAtOnce() {
        val r = LoopbackReceiver.open(0)
        val port = r.port
        val a = SiwcAuth.newAttempt(port, host, null)
        val waiting = pool.submit<LoopbackReceiver.Outcome> { r.await(a, 60_000) }
        Thread.sleep(150)
        val t0 = System.nanoTime()
        r.close()
        assertEquals(LoopbackReceiver.Outcome.Cancelled, waiting.get(2, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000)
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure, "plus rien n'écoute sur le port")
    }

    @Test fun aSilentConnectionDoesNotBlockTheRealCallback() {
        val r = LoopbackReceiver.open(0)
        val a = SiwcAuth.newAttempt(r.port, host, null)
        val waiting = pool.submit<LoopbackReceiver.Outcome> { r.await(a, 10_000, clientReadTimeoutMs = 300) }
        val mute = Socket("127.0.0.1", r.port) // connexion ouverte, rien envoyé (préconnexion du navigateur)
        Thread.sleep(100)
        val (code, _) = get(r.port, "/auth/callback?code=C&state=${a.state}&client_id=oaiapp_new")
        assertEquals(200, code)
        assertIs<LoopbackReceiver.Outcome.Received>(waiting.get(5, TimeUnit.SECONDS))
        mute.close()
    }

    @Test fun theTotalWaitIsBounded() {
        val r = LoopbackReceiver.open(0)
        val a = SiwcAuth.newAttempt(r.port, host, null)
        assertEquals(LoopbackReceiver.Outcome.TimedOut, r.await(a, 200))
        assertTrue(r.closed)
    }

    @Test fun aSecondAttemptGetsAFreePortWhenThePreferredOneIsTaken() {
        val first = LoopbackReceiver.open(0)
        val second = LoopbackReceiver.open(first.port)
        assertTrue(second.port != first.port && second.port > 0)
        first.close(); second.close()
    }

    @Test fun aForgedStateIsRejectedOverTheWire() {
        val r = LoopbackReceiver.open(0)
        val a = SiwcAuth.newAttempt(r.port, host, null)
        val waiting = pool.submit<LoopbackReceiver.Outcome> { r.await(a, 10_000) }
        val (_, page) = get(r.port, "/auth/callback?code=C&state=forged&client_id=oaiapp_new")
        assertTrue("invalide" in page)
        val out = waiting.get(5, TimeUnit.SECONDS) as LoopbackReceiver.Outcome.Received
        assertIs<SiwcAuth.Callback.Invalid>(out.callback)
    }

    // ---------------------------------------------------------------- Trace d'étapes sans secrets

    @Test fun theTraceShowsWhereItStoppedAndNeverASecret() {
        var t = SignInTrace().restart(1_000)
        t = t.ok(SignInTrace.Step.CALLBACK_RECEIVED, 2_000)
        t = t.failed(
            SignInTrace.Step.CODE_EXCHANGED, 3_000,
            "HTTP 400 invalid_grant http://127.0.0.1:1455/auth/callback?code=abc&state=xyz pilote@example.com " +
                "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln urn:uuid:123e4567-e89b-12d3-a456-426614174000 " + "A".repeat(48),
        )
        assertEquals(SignInTrace.Step.CALLBACK_RECEIVED, t.reached)
        assertFalse(t.gptConfirmed)
        val d = t.diagnostic(10_000, "V489")
        assertTrue("✗ Code échangé" in d && "invalid_grant" in d && "HTTP 400" in d, d)
        for (secret in listOf("abc", "xyz", "pilote@example.com", "eyJ", "123e4567", "AAAAAAAAAAAAAAAAAAAA", "127.0.0.1")) {
            assertFalse(secret in d, "$secret dans : $d")
        }
        assertEquals(t, SignInTrace.fromJson(t.toJson()))
        assertEquals(SignInTrace(), SignInTrace.fromJson("pas du json"))
    }

    @Test fun authorisedIsNotTheSameAsAnsweredByGpt() {
        var t = SignInTrace().restart(0)
        listOf(SignInTrace.Step.CALLBACK_RECEIVED, SignInTrace.Step.CODE_EXCHANGED, SignInTrace.Step.ID_TOKEN_VERIFIED,
            SignInTrace.Step.PLAN_GRANTED, SignInTrace.Step.CREDENTIALS_SAVED, SignInTrace.Step.MODEL_FOUND).forEach { t = t.ok(it, 1) }
        assertFalse(t.gptConfirmed, "autorisé, mais GPT pas encore interrogé")
        t = t.ok(SignInTrace.Step.GPT_CONFIRMED, 2)
        assertTrue(t.gptConfirmed)
        assertFalse(t.restart(3).gptConfirmed, "nouvelle connexion : à reconfirmer")
    }
}
