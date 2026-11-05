package com.gt7pitwall.app.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.FailureKind
import com.gt7pitwall.core.ai.chatgpt.FuelPlanCheck
import com.gt7pitwall.core.ai.chatgpt.LoopbackReceiver
import com.gt7pitwall.core.ai.chatgpt.ResponsesStream
import com.gt7pitwall.core.ai.chatgpt.SignInTrace
import com.gt7pitwall.core.ai.chatgpt.SignInTrace.Step
import com.gt7pitwall.core.ai.chatgpt.SiwcAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Issue #67 : ChatGPT avec le forfait du pilote (« Sign in with ChatGPT », aperçu OpenAI pour les applications
 * open source ou hébergées localement). Aucune clé d'API : le pilote se connecte dans son navigateur, Pitwall reçoit
 * le code sur 127.0.0.1 ([LoopbackReceiver]), l'échange contre des jetons et les garde chiffrés (Android Keystore).
 * Les jetons ne sont jamais journalisés, affichés ni envoyés ailleurs qu'à OpenAI.
 *
 * Fournisseur de plus pour [com.gt7pitwall.core.ai.AiRouter] : mêmes contrôles que Gemini, tout échec rend la main au
 * moteur local tout de suite. Jamais de bascule vers une API payante.
 *
 * Revue #69 (05/11) : chaque étape de la connexion est notée ([SignInTrace], sans secrets) pour savoir où elle bloque ;
 * « Autorisé » et « GPT réellement interrogé » sont distingués. Connexion et réponse GPT texte confirmées par le pilote
 * sur le Fold le 05/11 ; la radio (voix, Discord) ne l'est pas.
 */
class ChatGptPlan(private val context: Context, private val usage: AiUsage, private val appVersion: String = "") : AiProvider, com.gt7pitwall.core.ai.ToolCapable {

    enum class State(val label: String) {
        NOT_CONNECTED("Non connecté"),
        NO_PLAN("Connecté mais quota non autorisé"),
        AVAILABLE("Disponible"),
        LIMIT("Limite atteinte"),
    }

    data class Status(
        val state: State,
        val detail: String? = null,
        val account: String? = null,
        val model: String? = null,
        /** Une vraie réponse de GPT a été reçue avec ces jetons (pas seulement l'autorisation). */
        val gptConfirmed: Boolean = false,
        val signingIn: Boolean = false,
        val trace: SignInTrace = SignInTrace(),
    )

    private val store = Store(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    @Volatile
    private var creds: SiwcAuth.Credentials? = null

    @Volatile
    private var trace: SignInTrace = store.trace()

    /** Problème de lecture des identifiants enregistrés (jamais une raison de les effacer). */
    @Volatile
    private var loadProblem: String? = null

    init {
        when (val l = store.credentials()) {
            is Store.Loaded.Ok -> creds = l.credentials
            Store.Loaded.Absent -> Unit
            is Store.Loaded.Unreadable -> loadProblem = "Identifiants enregistrés illisibles (${l.reason}) : gardés, reconnecte-toi si cela persiste."
        }
    }

    private val _link = MutableStateFlow(statusOf(creds, loadProblem))
    /** État affiché dans les réglages (les quatre états du jalon 1, plus le détail et les étapes). */
    val link: StateFlow<Status> = _link.asStateFlow()

    /** « Tu utilises ton forfait ChatGPT » : montré une seule fois, après la première connexion avec le forfait. */
    private val _welcome = MutableStateFlow(false)
    val welcome: StateFlow<Boolean> = _welcome.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    /** Dernière réponse du test carburant (vérifiée, ou réponse locale), pour « Écouter Véga » sans nouvelle requête. */
    private val _lastAnswer = MutableStateFlow<FuelPlanCheck.Outcome?>(null)
    val lastAnswer: StateFlow<FuelPlanCheck.Outcome?> = _lastAnswer.asStateFlow()

    private val signInLock = Any()

    @Volatile
    private var signIn: Job? = null

    @Volatile
    private var receiver: LoopbackReceiver? = null

    /** V492 : latence bout en bout mesurée sur le téléphone (en mémoire seulement), avec et sans outils. */
    val toolLatency = com.gt7pitwall.core.ai.tools.LatencyStats()
    val plainLatency = com.gt7pitwall.core.ai.tools.LatencyStats()

    fun latencySummary(): String = listOfNotNull(
        toolLatency.summary()?.let { "avec outils $it" },
        plainLatency.summary()?.let { "sans outils $it" },
    ).joinToString(" · ").ifEmpty { "aucune mesure encore" }

    /** V492 : faits et analyses réels du moteur (programme Sport en cache…), fournis par l'application. */
    @Volatile
    var liveSnapshot: (suspend () -> com.gt7pitwall.core.ai.tools.ToolSnapshot)? = null

    /** #70 : derniers outils appelés par GPT (noms seulement), pour la carte. */
    private val _lastTools = MutableStateFlow<String?>(null)
    val lastTools: StateFlow<String?> = _lastTools.asStateFlow()

    // ------------------------------------------------------------------ Outils de données (#70, lecture seule, opt-in)

    /** Le pilote autorise Véga à consulter les outils de données (désactivé par défaut tant que non validé sur le Fold). */
    override val toolsEnabled: Boolean get() = store.toolsEnabled

    fun setToolsEnabled(on: Boolean) {
        store.toolsEnabled = on
        publish()
    }

    override suspend fun completeWithTools(systemInstruction: String, prompt: String, snapshot: com.gt7pitwall.core.ai.tools.ToolSnapshot): AiResult =
        completeWithTools(systemInstruction, prompt, snapshot, allowPlainFallback = true)

    /** [allowPlainFallback] faux (test des outils) : un refus des outils est signalé, jamais remplacé par une réponse sans outils. */
    private suspend fun completeWithTools(
        systemInstruction: String,
        prompt: String,
        snapshot: com.gt7pitwall.core.ai.tools.ToolSnapshot,
        allowPlainFallback: Boolean,
    ): AiResult =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val r = try {
                toolLoop(systemInstruction, prompt, snapshot, allowPlainFallback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                AiResult.Failure(FailureKind.TIMEOUT)
            } catch (e: IOException) {
                AiResult.Failure(FailureKind.NETWORK)
            }
            if (r is AiResult.Text) toolLatency.record(System.currentTimeMillis() - t0)
            onResult(r)
            r
        }

    private suspend fun toolLoop(instructions: String, prompt: String, snapshot: com.gt7pitwall.core.ai.tools.ToolSnapshot, allowPlainFallback: Boolean): AiResult {
        val c = fresh() ?: return AiResult.Failure(FailureKind.NOT_CONFIGURED, "ChatGPT non connecté")
        if (!c.planUsage) return AiResult.Failure(FailureKind.NOT_CONFIGURED, "usage du forfait non autorisé")
        val model = c.model ?: readModel(c) ?: return AiResult.Failure(FailureKind.MODEL_UNAVAILABLE, "aucun modèle listé pour ce compte")
        val out = com.gt7pitwall.core.ai.tools.ToolLoop({ body -> streamTurn(c, body) }).run(model, instructions, prompt, snapshot)
        _lastTools.value = if (out.calls.isEmpty()) "aucun outil appelé" else
            "${out.calls.size} outil(s) : " + out.calls.joinToString { it.name + if (it.known) "" else " (inconnu, refusé)" }
        if (out.stats.isNotEmpty()) _lastTools.value = "${_lastTools.value} · ${out.timing}"
        if (out.toolsRejected) {
            val why = SignInTrace.redact((out.result as? AiResult.Failure)?.detail ?: "HTTP 400")
            if (!allowPlainFallback) {
                _lastTools.value = "mode outils non pris en charge pour ce compte ($why)"
                return AiResult.Failure(FailureKind.NOT_CONFIGURED, "mode outils non pris en charge ($why)")
            }
            // Radio : la question contient déjà tous les faits ; une seule reprise, sans outils.
            _lastTools.value = "mode outils non pris en charge ($why) : réponse sans outils"
            return infer(instructions, prompt)
        }
        return out.result
    }

    private suspend fun streamTurn(c: SiwcAuth.Credentials, body: String): ResponsesStream.Turn {
        val req = Request.Builder().url(ResponsesStream.RESPONSES_URL)
            .header("Authorization", "Bearer ${c.accessToken}")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .build()
        val t0 = System.currentTimeMillis()
        return execute(http.newCall(req)) { resp ->
            if (!resp.isSuccessful) return@execute ResponsesStream.Turn("", emptyList(), ResponsesStream.httpFailure(resp.code, resp.body.string().take(4000)))
            val reader = ResponsesStream.Reader()
            val source = resp.body.source()
            var ttfb: Long? = null
            while (true) {
                val l = source.readUtf8Line() ?: break
                if (ttfb == null) ttfb = System.currentTimeMillis() - t0
                reader.line(l)
            }
            reader.end()
            reader.turn.copy(ttfbMs = ttfb)
        }
    }

    /** #70 : même question, faits SEULEMENT via les outils (simulation) : prouve que le forfait accepte les outils. */
    fun testTools() = runToolTest(
        label = "Essai des outils : « ${FuelPlanCheck.QUESTION} » (faits de simulation, via outils seulement)",
        question = FuelPlanCheck.QUESTION,
        prompt = FuelPlanCheck.toolsPrompt,
        snapshot = { FuelPlanCheck.snapshot(now()) },
        local = { FuelPlanCheck.LOCAL_ANSWER },
    )

    /** V492 : « Quelle course Sport cette semaine ? » sur le VRAI programme en cache du téléphone (aucune PS5 requise). */
    fun testSport() {
        val live = liveSnapshot ?: run {
            _testResult.value = "Données Pitwall indisponibles pour l'essai."
            return
        }
        runToolTest(
            label = "Essai sur tes données : « ${com.gt7pitwall.core.ai.tools.VegaCheck.SPORT_QUESTION} » (programme Sport en cache)",
            question = com.gt7pitwall.core.ai.tools.VegaCheck.SPORT_QUESTION,
            prompt = com.gt7pitwall.core.ai.tools.VegaCheck.sportPrompt,
            snapshot = live,
            local = { snap -> com.gt7pitwall.core.ai.tools.VegaCheck.sportLocalAnswer(snap) },
        )
    }

    private fun runToolTest(
        label: String,
        question: String,
        prompt: String,
        snapshot: suspend () -> com.gt7pitwall.core.ai.tools.ToolSnapshot,
        local: (com.gt7pitwall.core.ai.tools.ToolSnapshot) -> String,
    ) {
        scope.launch {
            _lastTools.value = null
            _testResult.value = "$label…"
            val t0 = System.currentTimeMillis()
            var asked = false
            val snap = snapshot()
            val r = if (!ready || !usage.canSend(backend)) {
                AiResult.Failure(FailureKind.NOT_CONFIGURED, status)
            } else {
                asked = true
                usage.onSent(backend)
                (withTimeoutOrNull(TEST_TIMEOUT_MS) { completeWithTools(FuelPlanCheck.instructions, prompt, snap, allowPlainFallback = false) }
                    ?: AiResult.Failure(FailureKind.TIMEOUT)).also { res ->
                    val ms = System.currentTimeMillis() - t0
                    if (res is AiResult.Failure) usage.onFailure(backend, res, ms) else usage.onSuccess(backend, ms)
                }
            }
            val v = com.gt7pitwall.core.ai.tools.VegaCheck.withTools(question, r, local(snap))
            val o = FuelPlanCheck.Outcome(v.answer, v.fromChatGpt, v.verdict)
            _lastAnswer.value = o
            val unsupported = _lastTools.value?.startsWith("mode outils non pris en charge") == true
            val who = when {
                unsupported -> "MODE OUTILS NON PRIS EN CHARGE (pas une validation)"
                o.fromChatGpt -> "Validé : GPT a exécuté des outils et sa réponse ne cite que leurs faits"
                asked -> "GPT interrogé, outils non utilisés ou réponse écartée → secours local"
                else -> "GPT non interrogé → secours local"
            }
            _testResult.value = "$who : « ${o.answer} »\n${o.verdict} · ${_lastTools.value ?: "aucun outil"} · ${System.currentTimeMillis() - t0} ms\nLatence : ${latencySummary()}"
        }
    }

    // ------------------------------------------------------------------ AiProvider

    override val backend = AiBackend.CHATGPT
    override val modelId: String get() = creds?.model ?: "modèle du compte"
    override val ready: Boolean get() = creds?.planUsage == true
    override val status: String
        get() = when (val c = creds) {
            null -> "non connecté"
            else -> if (!c.planUsage) "connecté, usage du forfait non autorisé" else (usage.blockedReason(backend) ?: "prêt (${c.model ?: "modèle à lire"})")
        }

    override suspend fun complete(systemInstruction: String, prompt: String): AiResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val r = try {
            infer(systemInstruction, prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            AiResult.Failure(FailureKind.TIMEOUT)
        } catch (e: IOException) {
            AiResult.Failure(FailureKind.NETWORK)
        }
        if (r is AiResult.Text) plainLatency.record(System.currentTimeMillis() - t0)
        onResult(r)
        r
    }

    private suspend fun infer(instructions: String, prompt: String): AiResult {
        val c = fresh() ?: return AiResult.Failure(FailureKind.NOT_CONFIGURED, "ChatGPT non connecté")
        if (!c.planUsage) return AiResult.Failure(FailureKind.NOT_CONFIGURED, "usage du forfait non autorisé")
        val model = c.model ?: readModel(c) ?: return AiResult.Failure(FailureKind.MODEL_UNAVAILABLE, "aucun modèle listé pour ce compte")
        val req = Request.Builder().url(ResponsesStream.RESPONSES_URL)
            .header("Authorization", "Bearer ${c.accessToken}")
            .header("Accept", "text/event-stream")
            .post(ResponsesStream.requestBody(model, instructions, prompt).toRequestBody(JSON))
            .build()
        return execute(http.newCall(req)) { resp ->
            if (!resp.isSuccessful) return@execute ResponsesStream.httpFailure(resp.code, resp.body.string().take(4000))
            val reader = ResponsesStream.Reader()
            val source = resp.body.source()
            while (true) reader.line(source.readUtf8Line() ?: break)
            reader.end()
            reader.result
        }
    }

    private fun onResult(r: AiResult) {
        val c = creds ?: return
        if (r is AiResult.Text && !trace.gptConfirmed) note { it.ok(Step.GPT_CONFIRMED, now(), c.model) }
        _link.value = when {
            r is AiResult.Text -> statusOf(c)
            r is AiResult.Failure && r.kind == FailureKind.QUOTA -> statusOf(c).copy(
                state = State.LIMIT,
                detail = "Limite d'utilisation atteinte. Vérifie ton forfait ou la limite de Pitwall dans ChatGPT (Gérer l'utilisation). Le moteur local répond en attendant.",
            )
            r is AiResult.Failure -> statusOf(c).copy(detail = "Dernier appel GPT : ${failureText(r)}. Le moteur local a répondu.")
            else -> statusOf(c)
        }
    }

    private fun failureText(f: AiResult.Failure): String = when (f.kind) {
        FailureKind.AUTH -> "accès refusé par OpenAI (401)"
        FailureKind.OVERLOADED -> "service ChatGPT indisponible pour le moment"
        FailureKind.NOT_CONFIGURED -> "requête refusée pour ce compte ou cette application"
        FailureKind.MODEL_UNAVAILABLE -> "aucun modèle éligible pour ce compte"
        else -> f.kind.label
    } + (f.detail?.let { " · ${SignInTrace.redact(it)}" } ?: "")

    // ------------------------------------------------------------------ Connexion

    /** « Continue with ChatGPT » : écoute sur 127.0.0.1 AVANT d'ouvrir le navigateur, une seule tentative à la fois. */
    fun signIn() {
        synchronized(signInLock) {
            if (signIn?.isActive != true) signIn = scope.launch { runSignIn() }
        }
    }

    private suspend fun runSignIn() {
        val r = try {
            LoopbackReceiver.open()
        } catch (e: IOException) {
            note { it.restart(now()).failed(Step.BROWSER_OPENED, now(), "écoute impossible sur 127.0.0.1 (${e.javaClass.simpleName})") }
            publish()
            return
        }
        receiver = r
        try {
            val previous = creds
            val attempt = SiwcAuth.newAttempt(r.port, store.hostId(), store.issuedClientId)
            val url = SiwcAuth.authorizeUrl(attempt, idTokenHint = previous?.idToken, loginHint = previous?.email)
            note { it.restart(now()) }
            _link.value = _link.value.copy(signingIn = true, detail = "Connexion en cours dans le navigateur… (5 min au plus)", trace = trace)
            val opened = withContext(Dispatchers.Main) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            }
            if (!opened) {
                note { it.failed(Step.BROWSER_OPENED, now(), "aucun navigateur n'a pu ouvrir la page de connexion") }
                return
            }
            when (val out = r.await(attempt, SIGN_IN_TIMEOUT_MS)) {
                LoopbackReceiver.Outcome.Cancelled -> _link.value = statusOf(creds).copy(detail = "Connexion annulée.")
                LoopbackReceiver.Outcome.TimedOut -> note { it.failed(Step.CALLBACK_RECEIVED, now(), "pas de retour du navigateur après 5 minutes") }
                is LoopbackReceiver.Outcome.Received -> when (val cb = out.callback) {
                    is SiwcAuth.Callback.Code -> {
                        note { it.ok(Step.CALLBACK_RECEIVED, now()) }
                        // Client attribué dès le retour : un échec de l'échange (invalid_grant…) ne refait pas une inscription.
                        store.issuedClientId = cb.clientId
                        finishSignIn(cb, attempt)
                    }
                    is SiwcAuth.Callback.Denied -> note { it.failed(Step.CALLBACK_RECEIVED, now(), "refus dans ChatGPT (${cb.error})") }
                    is SiwcAuth.Callback.Invalid -> note { it.failed(Step.CALLBACK_RECEIVED, now(), "retour rejeté : ${cb.reason}") }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            note { it.failed(trace.reached?.let(::nextStep) ?: Step.CODE_EXCHANGED, now(), "délai réseau dépassé") }
        } catch (e: IOException) {
            note { it.failed(trace.reached?.let(::nextStep) ?: Step.CODE_EXCHANGED, now(), "réseau : ${e.javaClass.simpleName}") }
        } catch (e: Exception) {
            note { it.failed(trace.reached?.let(::nextStep) ?: Step.CODE_EXCHANGED, now(), "erreur ${e.javaClass.simpleName}") }
        } finally {
            r.close()
            receiver = null
            publish()
        }
    }

    private fun nextStep(s: Step): Step = Step.entries.getOrElse(s.ordinal + 1) { s }

    /** Annulation : la socket d'écoute est fermée tout de suite (plus rien n'écoute sur 127.0.0.1). */
    fun cancelSignIn() {
        receiver?.close()
        signIn?.cancel()
        publish()
    }

    /** Code → jetons ; jeton d'identité vérifié (JWKS, émetteur, audience, expiration, nonce) avant de remplacer quoi que ce soit. */
    private suspend fun finishSignIn(cb: SiwcAuth.Callback.Code, a: SiwcAuth.Attempt) {
        val (code, body) = postForm(SiwcAuth.TOKEN_URL, SiwcAuth.codeExchangeBody(cb.code, cb.clientId, a))
        if (code !in 200..299) {
            note { it.failed(Step.CODE_EXCHANGED, now(), "HTTP $code${errorCode(body)?.let { e -> " $e" } ?: ""}") }
            return
        }
        val t = runCatching { SiwcAuth.TokenResponse.parse(body) }.getOrElse {
            note { it.failed(Step.CODE_EXCHANGED, now(), "réponse de jetons illisible") }
            return
        }
        note { it.ok(Step.CODE_EXCHANGED, now()) }
        val idToken = t.idToken ?: run {
            note { it.failed(Step.ID_TOKEN_VERIFIED, now(), "réponse sans jeton d'identité") }
            return
        }
        val jwks = runCatching { get(SiwcAuth.JWKS_URL) }.getOrElse { e ->
            note { it.failed(Step.ID_TOKEN_VERIFIED, now(), "clés de signature OpenAI inaccessibles (${e.message ?: e.javaClass.simpleName})") }
            return
        }
        val identity = SiwcAuth.verifyIdToken(idToken, jwks, cb.clientId, a.nonce, now() / 1000).getOrElse { e ->
            note { it.failed(Step.ID_TOKEN_VERIFIED, now(), "rejeté : ${e.message}") }
            return
        }
        note { it.ok(Step.ID_TOKEN_VERIFIED, now()) }
        if (t.planUsage) note { it.ok(Step.PLAN_GRANTED, now()) } else note { it.failed(Step.PLAN_GRANTED, now(), "portée du forfait non accordée") }
        var c = SiwcAuth.Credentials(
            clientId = cb.clientId, hostId = a.hostId, subject = identity.subject, email = identity.email,
            accessToken = t.accessToken, refreshToken = t.refreshToken, idToken = idToken, scopes = t.scopes,
            expiresAtMs = now() + t.expiresInS * 1000,
        )
        val firstPlanSignIn = c.planUsage && !store.welcomeShown
        if (!save(c)) {
            note { it.failed(Step.CREDENTIALS_SAVED, now(), "écriture chiffrée impossible (Keystore)") }
            return
        }
        note { it.ok(Step.CREDENTIALS_SAVED, now()) }
        if (c.planUsage) readModel(c)?.let { c = c.copy(model = it) }
        usage.clearPause(backend)
        loadProblem = null
        if (firstPlanSignIn) _welcome.value = true
    }

    fun dismissWelcome() {
        store.welcomeShown = true
        _welcome.value = false
    }

    /** Déconnexion : révocation de la session renouvelable, puis jetons effacés (le client attribué et l'hôte restent). */
    fun signOut() {
        val c = creds ?: return
        scope.launch {
            val confirmed = c.refreshToken?.let { rt ->
                runCatching {
                    val endpoint = JSONObject(get(SiwcAuth.DISCOVERY_URL)).getString("revocation_endpoint")
                    postForm(endpoint, SiwcAuth.revokeBody(c.clientId, rt)).first == 200
                }.getOrDefault(false)
            } ?: false
            save(null)
            note { SignInTrace() }
            _lastAnswer.value = null
            _link.value = statusOf(null).copy(
                detail = if (confirmed) "Déconnecté : session révoquée chez OpenAI." else
                    "Déconnecté sur le téléphone. Révocation non confirmée : tu peux retirer Pitwall dans ChatGPT → Paramètres.",
            )
        }
    }

    /** Page d'utilisation de ChatGPT (« Manage usage ») : limites du forfait et de chaque application. */
    fun openUsage() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(USAGE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Texte à copier : étapes, codes HTTP et codes d'erreur seulement (aucun jeton, code, e-mail ni adresse de retour). */
    fun diagnostic(): String = trace.diagnostic(now(), appVersion) + buildString {
        append("État : ").append(_link.value.state.label)
        append(if (_link.value.gptConfirmed) " · GPT réellement interrogé" else " · GPT pas encore interrogé avec ces jetons").append('\n')
        creds?.let { c ->
            append("Portée forfait : ").append(if (c.planUsage) "oui" else "non").append(" · modèle : ").append(c.model ?: "non lu").append('\n')
            append("Jeton d'accès : ").append(if (c.needsRefresh(now())) "à rafraîchir" else "valide").append('\n')
        }
        loadProblem?.let { append(SignInTrace.redact(it)).append('\n') }
    }

    // ------------------------------------------------------------------ Jalon 1 : test « plan carburant »

    /** Une requête au plus, sur des faits de simulation ; la réponse est vérifiée comme à la radio. */
    fun testFuelPlan() {
        scope.launch {
            _testResult.value = "Question : « ${FuelPlanCheck.QUESTION} » (faits de simulation)…"
            val t0 = System.currentTimeMillis()
            var asked = false
            val r = if (!ready || !usage.canSend(backend)) {
                AiResult.Failure(FailureKind.NOT_CONFIGURED, status)
            } else {
                asked = true
                usage.onSent(backend)
                (withTimeoutOrNull(TEST_TIMEOUT_MS) { complete(FuelPlanCheck.instructions, FuelPlanCheck.prompt) }
                    ?: AiResult.Failure(FailureKind.TIMEOUT)).also { res ->
                    val ms = System.currentTimeMillis() - t0
                    if (res is AiResult.Failure) usage.onFailure(backend, res, ms) else usage.onSuccess(backend, ms)
                }
            }
            val o = FuelPlanCheck.check(r)
            _lastAnswer.value = o
            val who = when {
                o.fromChatGpt -> "GPT réellement interrogé"
                asked -> "GPT interrogé, réponse écartée ou en échec → secours local"
                else -> "GPT non interrogé → secours local"
            }
            _testResult.value = "$who : « ${o.answer} »\n${o.verdict} · ${System.currentTimeMillis() - t0} ms"
        }
    }

    // ------------------------------------------------------------------ Jetons

    /** Jetons valides, rafraîchis près de l'expiration (un seul rafraîchissement à la fois : le jeton tourne). */
    private suspend fun fresh(): SiwcAuth.Credentials? = refreshLock.withLock {
        val c = creds ?: return null
        if (!c.needsRefresh(now())) return c
        val rt = c.refreshToken ?: return c.takeIf { now() < it.expiresAtMs }
        val (code, body) = postForm(SiwcAuth.TOKEN_URL, SiwcAuth.refreshBody(c.clientId, rt))
        if (code in 200..299) {
            val n = runCatching { c.refreshed(SiwcAuth.TokenResponse.parse(body), now()) }.getOrNull() ?: return c.takeIf { now() < it.expiresAtMs }
            save(n)
            return n
        }
        // Seuls les codes officiels « jeton inutilisable » effacent la session ; le reste est passager.
        if (SiwcAuth.refreshTokenUnusable(errorCode(body))) {
            save(null)
            note { SignInTrace() }
            _link.value = statusOf(null).copy(detail = "Session ChatGPT expirée (${errorCode(body)}) : reconnecte-toi.")
            return null
        }
        return c.takeIf { now() < it.expiresAtMs }
    }

    private suspend fun readModel(c: SiwcAuth.Credentials): String? {
        val req = Request.Builder().url(ResponsesStream.MODELS_URL).header("Authorization", "Bearer ${c.accessToken}").get().build()
        val r = runCatching {
            execute(http.newCall(req)) { resp ->
                if (resp.isSuccessful) Result.success(ResponsesStream.listedModels(resp.body.string()).firstOrNull()?.first)
                else Result.failure(IOException("HTTP ${resp.code}"))
            }
        }.getOrElse { Result.failure(it) }
        val slug = r.getOrNull()
        if (slug == null) {
            note { it.failed(Step.MODEL_FOUND, now(), r.exceptionOrNull()?.let { e -> "liste des modèles inaccessible (${e.message ?: e.javaClass.simpleName})" } ?: "aucun modèle éligible listé pour ce compte") }
            return null
        }
        note { it.ok(Step.MODEL_FOUND, now(), slug) }
        if (creds?.subject == c.subject) save((creds ?: c).copy(model = slug))
        return slug
    }

    /** Enregistre (ou efface) ; faux si l'écriture chiffrée a échoué : les jetons en mémoire restent alors les anciens. */
    private fun save(c: SiwcAuth.Credentials?): Boolean {
        val ok = runCatching { store.saveCredentials(c) }.isSuccess
        if (ok) {
            creds = c
            publish()
        }
        return ok
    }

    private fun note(change: (SignInTrace) -> SignInTrace) {
        trace = change(trace)
        store.saveTrace(trace)
        publish()
    }

    private fun publish() {
        val keep = _link.value.takeIf { it.state == State.LIMIT && creds != null }
        _link.value = (keep?.copy(trace = trace, gptConfirmed = trace.gptConfirmed, model = creds?.model) ?: statusOf(creds, loadProblem))
            .copy(signingIn = signIn?.isActive == true && receiver != null)
    }

    private fun statusOf(c: SiwcAuth.Credentials?, problem: String? = null): Status {
        val failed = trace.last?.takeIf { !it.ok }?.let { "Bloqué à « ${it.step.label} »${it.detail?.let { d -> " : $d" } ?: ""}." }
        return when {
            c == null -> Status(State.NOT_CONNECTED, problem ?: failed, trace = trace)
            !c.planUsage -> Status(
                State.NO_PLAN,
                "Tu es connecté, mais l'usage de ton forfait n'a pas été autorisé. Reconnecte-toi et accepte l'utilisation du forfait.",
                c.email, c.model, trace = trace,
            )
            else -> Status(State.AVAILABLE, failed, c.email, c.model, gptConfirmed = trace.gptConfirmed, trace = trace)
        }
    }

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------ HTTP

    private suspend fun <T> execute(call: Call, read: (okhttp3.Response) -> T): T {
        // Annulation du routeur (délai dépassé) : la requête est coupée, rien ne reste en vol.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            return call.execute().use(read)
        } finally {
            handle?.dispose()
        }
    }

    private suspend fun postForm(url: String, form: String): Pair<Int, String> {
        val req = Request.Builder().url(url).header("Accept", "application/json").post(form.toRequestBody(FORM)).build()
        return execute(http.newCall(req)) { it.code to it.body.string() }
    }

    private suspend fun get(url: String): String {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return execute(http.newCall(req)) { if (it.isSuccessful) it.body.string() else throw IOException("HTTP ${it.code}") }
    }

    /** Code d'erreur OAuth seulement (jamais le corps entier, qui n'a rien à faire à l'écran). */
    private fun errorCode(body: String): String? = runCatching {
        val o = JSONObject(body)
        o.optString("error").ifEmpty { null } ?: o.optJSONObject("error")?.optString("code")?.ifEmpty { null }
    }.getOrNull()?.take(60)

    // ------------------------------------------------------------------ Stockage (préférences privées de l'application)

    /**
     * Jetons : chiffrés (AES-256-GCM, clé de l'Android Keystore). En clair dans les préférences privées : identifiant
     * d'hôte (opaque), client attribué (pas un secret), étapes de connexion (sans secrets) et drapeau de bienvenue.
     * L'e-mail n'est gardé que dans les identifiants chiffrés. La sauvegarde Android est désactivée pour l'application.
     */
    private class Store(context: Context) {
        private val prefs = context.getSharedPreferences("chatgpt_plan", Context.MODE_PRIVATE)

        init {
            // V488 gardait l'e-mail en clair pour la reconnexion : retiré (il reste dans les identifiants chiffrés).
            if (prefs.contains("email")) prefs.edit().remove("email").apply()
        }

        sealed interface Loaded {
            data class Ok(val credentials: SiwcAuth.Credentials) : Loaded
            data object Absent : Loaded
            data class Unreadable(val reason: String) : Loaded
        }

        fun hostId(): String = prefs.getString("host_id", null)?.takeIf(SiwcAuth::isValidHostId)
            ?: SiwcAuth.newHostId().also { prefs.edit().putString("host_id", it).apply() }

        var issuedClientId: String?
            get() = prefs.getString("client_id", null)
            set(v) = prefs.edit().putString("client_id", v).apply()

        var welcomeShown: Boolean
            get() = prefs.getBoolean("welcome_shown", false)
            set(v) = prefs.edit().putBoolean("welcome_shown", v).apply()

        var toolsEnabled: Boolean
            get() = prefs.getBoolean("tools_enabled", false)
            set(v) = prefs.edit().putBoolean("tools_enabled", v).apply()

        fun trace(): SignInTrace = SignInTrace.fromJson(prefs.getString("trace", null))
        fun saveTrace(t: SignInTrace) = prefs.edit().putString("trace", t.toJson()).apply()

        /** Une erreur de déchiffrement ou un format inattendu ne supprime rien : on le signale. */
        fun credentials(): Loaded {
            val stored = prefs.getString("credentials_enc", null) ?: return Loaded.Absent
            val plain = try {
                val (iv, enc) = stored.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
                val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
                String(cipher.doFinal(enc), Charsets.UTF_8)
            } catch (e: Exception) {
                return Loaded.Unreadable("déchiffrement Keystore : ${e.javaClass.simpleName}")
            }
            return runCatching { Loaded.Ok(SiwcAuth.Credentials.fromJson(plain)) }.getOrElse { Loaded.Unreadable("profil invalide") }
        }

        /** Écriture synchrone (commit) : un échec est signalé au lieu d'être découvert au redémarrage. */
        fun saveCredentials(c: SiwcAuth.Credentials?) {
            if (c == null) {
                check(prefs.edit().remove("credentials_enc").commit())
                return
            }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val enc = cipher.doFinal(c.toJson().toByteArray(Charsets.UTF_8))
            check(prefs.edit().putString("credentials_enc", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)).commit())
        }

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }

        private companion object {
            const val ALIAS = "gt7pitwall_chatgpt_plan"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val FORM = "application/x-www-form-urlencoded".toMediaType()
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        const val SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000L
        const val TEST_TIMEOUT_MS = 45_000L
    }
}
