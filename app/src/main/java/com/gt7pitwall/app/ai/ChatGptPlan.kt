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
import com.gt7pitwall.core.ai.chatgpt.ResponsesStream
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
import java.net.InetAddress
import java.net.ServerSocket
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
 * le code sur 127.0.0.1 (redirection « loopback » imposée par la documentation), l'échange contre des jetons et les
 * garde chiffrés (Android Keystore). Les jetons ne sont jamais journalisés, affichés ni envoyés ailleurs qu'à OpenAI.
 *
 * C'est un fournisseur de plus pour [com.gt7pitwall.core.ai.AiRouter] : les réponses passent par les mêmes contrôles
 * que Gemini (chiffres vérifiés contre les faits, commandes rejouées par le moteur), et tout échec (réseau, limite du
 * forfait, refus) rend la main au moteur local tout de suite. Jamais de bascule vers une API payante.
 *
 * Non vérifié sur le Fold : ouverture du navigateur, retour vers 127.0.0.1 pendant que Pitwall est en arrière-plan.
 */
class ChatGptPlan(private val context: Context, private val usage: AiUsage) : AiProvider {

    enum class State(val label: String) {
        NOT_CONNECTED("Non connecté"),
        NO_PLAN("Connecté mais quota non autorisé"),
        AVAILABLE("Disponible"),
        LIMIT("Limite atteinte"),
    }

    data class Status(val state: State, val detail: String? = null, val account: String? = null, val model: String? = null)

    private val store = Store(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    @Volatile
    private var creds: SiwcAuth.Credentials? = store.credentials()

    private val _link = MutableStateFlow(statusOf(creds))
    /** État affiché dans les réglages (les quatre états du jalon 1, plus un détail). */
    val link: StateFlow<Status> = _link.asStateFlow()

    /** « Tu utilises ton forfait ChatGPT » : montré une seule fois, après la première connexion avec le forfait. */
    private val _welcome = MutableStateFlow(false)
    val welcome: StateFlow<Boolean> = _welcome.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    @Volatile
    private var signIn: Job? = null

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
        val r = try {
            infer(systemInstruction, prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            AiResult.Failure(FailureKind.TIMEOUT)
        } catch (e: IOException) {
            AiResult.Failure(FailureKind.NETWORK)
        }
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
        _link.value = when {
            r is AiResult.Text -> statusOf(c)
            r is AiResult.Failure && r.kind == FailureKind.QUOTA ->
                statusOf(c).copy(state = State.LIMIT, detail = "Limite d'utilisation atteinte. Vérifie ton forfait ou la limite de Pitwall dans ChatGPT (Gérer l'utilisation). Le moteur local répond en attendant.")
            r is AiResult.Failure -> statusOf(c).copy(detail = "Dernier essai : ${r.kind.label}${r.detail?.let { " ($it)" } ?: ""}. Le moteur local a répondu.")
            else -> statusOf(c)
        }
    }

    // ------------------------------------------------------------------ Connexion

    /** « Continue with ChatGPT » : écoute sur 127.0.0.1, ouvre le navigateur, attend le retour (5 min au plus). */
    fun signIn() {
        if (signIn?.isActive == true) return
        signIn = scope.launch {
            val server = try {
                openLoopback()
            } catch (e: IOException) {
                _link.value = _link.value.copy(detail = "Impossible d'écouter sur 127.0.0.1 : connexion annulée.")
                return@launch
            }
            try {
                server.use { s ->
                    s.soTimeout = SIGN_IN_TIMEOUT_MS
                    val previous = creds
                    val attempt = SiwcAuth.newAttempt(s.localPort, store.hostId(), store.issuedClientId)
                    val url = SiwcAuth.authorizeUrl(attempt, idTokenHint = previous?.idToken, loginHint = previous?.email ?: store.lastEmail)
                    _link.value = _link.value.copy(detail = "Connexion en cours dans le navigateur…")
                    withContext(Dispatchers.Main) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    when (val cb = awaitCallback(s, attempt)) {
                        is SiwcAuth.Callback.Code -> finishSignIn(cb, attempt)
                        is SiwcAuth.Callback.Denied -> _link.value = statusOf(creds).copy(detail = "Connexion refusée dans ChatGPT (${cb.error}).")
                        is SiwcAuth.Callback.Invalid -> _link.value = statusOf(creds).copy(detail = "Retour de connexion rejeté : ${cb.reason}.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                _link.value = statusOf(creds).copy(detail = "Pas de retour du navigateur après 5 minutes : connexion annulée.")
            } catch (e: Exception) {
                _link.value = statusOf(creds).copy(detail = "Connexion échouée : ${e.javaClass.simpleName}.")
            }
        }
    }

    fun cancelSignIn() {
        signIn?.cancel()
        _link.value = statusOf(creds)
    }

    private fun openLoopback(): ServerSocket {
        val lo = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        // Port de la documentation d'abord ; sinon un port libre (seul le port peut changer dans l'adresse de retour).
        return try {
            ServerSocket(SiwcAuth.PREFERRED_PORT, 4, lo)
        } catch (e: IOException) {
            ServerSocket(0, 4, lo)
        }
    }

    /** Lit les requêtes du navigateur jusqu'au retour de connexion ; les autres (icône…) reçoivent un 404. */
    private fun awaitCallback(s: ServerSocket, a: SiwcAuth.Attempt): SiwcAuth.Callback {
        repeat(MAX_LOOPBACK_REQUESTS) {
            s.accept().use { sock ->
                val line = sock.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
                val target = line.split(' ').getOrNull(1).orEmpty()
                val out = sock.getOutputStream()
                if (!line.startsWith("GET ") || target.substringBefore('?') != SiwcAuth.CALLBACK_PATH) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@use
                }
                val cb = SiwcAuth.parseCallback(target, a)
                val msg = when (cb) {
                    is SiwcAuth.Callback.Code -> "Connexion reçue. Tu peux revenir dans GT7 Pitwall."
                    is SiwcAuth.Callback.Denied -> "Connexion refusée. Tu peux revenir dans GT7 Pitwall."
                    is SiwcAuth.Callback.Invalid -> "Retour de connexion invalide. Recommence depuis GT7 Pitwall."
                }
                val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>GT7 Pitwall</title><p style='font:18px sans-serif;margin:24px'>$msg</p>"
                val bytes = html.toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(bytes)
                out.flush()
                return cb
            }
        }
        return SiwcAuth.Callback.Invalid("trop de requêtes sans retour de connexion")
    }

    /** Code → jetons ; jeton d'identité vérifié (JWKS, émetteur, audience, expiration, nonce) avant de remplacer quoi que ce soit. */
    private suspend fun finishSignIn(cb: SiwcAuth.Callback.Code, a: SiwcAuth.Attempt) {
        val (code, body) = postForm(SiwcAuth.TOKEN_URL, SiwcAuth.codeExchangeBody(cb.code, cb.clientId, a))
        if (code !in 200..299) {
            _link.value = statusOf(creds).copy(detail = "Échange du code refusé (HTTP $code${errorCode(body)?.let { ", $it" } ?: ""}).")
            return
        }
        val t = SiwcAuth.TokenResponse.parse(body)
        val idToken = t.idToken ?: run {
            _link.value = statusOf(creds).copy(detail = "Réponse sans jeton d'identité : connexion refusée.")
            return
        }
        val jwks = get(SiwcAuth.JWKS_URL)
        val identity = SiwcAuth.verifyIdToken(idToken, jwks, cb.clientId, a.nonce, System.currentTimeMillis() / 1000).getOrElse {
            _link.value = statusOf(creds).copy(detail = "Jeton d'identité rejeté : ${it.message}.")
            return
        }
        var c = SiwcAuth.Credentials(
            clientId = cb.clientId, hostId = a.hostId, subject = identity.subject, email = identity.email,
            accessToken = t.accessToken, refreshToken = t.refreshToken, idToken = idToken, scopes = t.scopes,
            expiresAtMs = System.currentTimeMillis() + t.expiresInS * 1000,
        )
        if (c.planUsage) readModel(c)?.let { c = c.copy(model = it) }
        val firstPlanSignIn = c.planUsage && !store.welcomeShown
        save(c)
        store.issuedClientId = cb.clientId
        store.lastEmail = identity.email
        usage.clearPause(backend)
        _link.value = statusOf(c)
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

    // ------------------------------------------------------------------ Jalon 1 : test « plan carburant »

    /** Une requête au plus, sur des faits de simulation ; la réponse est vérifiée comme à la radio. */
    fun testFuelPlan() {
        scope.launch {
            _testResult.value = "Question : « ${FuelPlanCheck.QUESTION} » (faits de simulation)…"
            val t0 = System.currentTimeMillis()
            val r = if (!ready || !usage.canSend(backend)) {
                AiResult.Failure(FailureKind.NOT_CONFIGURED, status)
            } else {
                usage.onSent(backend)
                (withTimeoutOrNull(TEST_TIMEOUT_MS) { complete(FuelPlanCheck.instructions, FuelPlanCheck.prompt) }
                    ?: AiResult.Failure(FailureKind.TIMEOUT)).also { res ->
                    val ms = System.currentTimeMillis() - t0
                    if (res is AiResult.Failure) usage.onFailure(backend, res, ms) else usage.onSuccess(backend, ms)
                }
            }
            val o = FuelPlanCheck.check(r)
            _testResult.value = "${if (o.fromChatGpt) "ChatGPT" else "Moteur local"} : « ${o.answer} »\n${o.verdict} · ${System.currentTimeMillis() - t0} ms"
        }
    }

    // ------------------------------------------------------------------ Jetons

    /** Jetons valides, rafraîchis près de l'expiration (un seul rafraîchissement à la fois : le jeton tourne). */
    private suspend fun fresh(): SiwcAuth.Credentials? = refreshLock.withLock {
        val c = creds ?: return null
        if (!c.needsRefresh(System.currentTimeMillis())) return c
        val rt = c.refreshToken ?: return c.takeIf { System.currentTimeMillis() < it.expiresAtMs }
        val (code, body) = postForm(SiwcAuth.TOKEN_URL, SiwcAuth.refreshBody(c.clientId, rt))
        if (code in 200..299) {
            val n = c.refreshed(SiwcAuth.TokenResponse.parse(body), System.currentTimeMillis())
            save(n)
            return n
        }
        if (SiwcAuth.refreshTokenUnusable(errorCode(body)) || code == 401) {
            save(null)
            _link.value = statusOf(null).copy(detail = "Session ChatGPT expirée : reconnecte-toi.")
            return null
        }
        // Erreur passagère : le jeton actuel sert encore s'il n'a pas expiré.
        return c.takeIf { System.currentTimeMillis() < it.expiresAtMs }
    }

    private suspend fun readModel(c: SiwcAuth.Credentials): String? {
        val req = Request.Builder().url(ResponsesStream.MODELS_URL).header("Authorization", "Bearer ${c.accessToken}").get().build()
        val slug = runCatching {
            execute(http.newCall(req)) { if (it.isSuccessful) ResponsesStream.listedModels(it.body.string()).firstOrNull()?.first else null }
        }.getOrNull() ?: return null
        if (creds?.subject == c.subject) save(c.copy(model = slug))
        return slug
    }

    private fun save(c: SiwcAuth.Credentials?) {
        creds = c
        store.saveCredentials(c)
    }

    private fun statusOf(c: SiwcAuth.Credentials?): Status = when {
        c == null -> Status(State.NOT_CONNECTED)
        !c.planUsage -> Status(
            State.NO_PLAN,
            "Tu es connecté, mais l'usage de ton forfait n'a pas été autorisé. Reconnecte-toi et accepte l'utilisation du forfait.",
            c.email, c.model,
        )
        else -> Status(State.AVAILABLE, null, c.email, c.model)
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun <T> execute(call: Call, read: (okhttp3.Response) -> T): T {
        // Annulation du routeur (délai dépassé) : la requête est coupée, rien ne reste en vol.
        currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        return call.execute().use(read)
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
    }.getOrNull()

    // ------------------------------------------------------------------ Stockage chiffré (Android Keystore)

    private class Store(context: Context) {
        private val prefs = context.getSharedPreferences("chatgpt_plan", Context.MODE_PRIVATE)

        /** Identifiant d'hôte stable (opaque, ce n'est pas un secret) : réutilisé à chaque connexion. */
        fun hostId(): String = prefs.getString("host_id", null)?.takeIf(SiwcAuth::isValidHostId)
            ?: SiwcAuth.newHostId().also { prefs.edit().putString("host_id", it).apply() }

        /** Client attribué à l'inscription (gardé après déconnexion : une reconnexion ne recrée pas de client). */
        var issuedClientId: String?
            get() = prefs.getString("client_id", null)
            set(v) = prefs.edit().putString("client_id", v).apply()

        var lastEmail: String?
            get() = prefs.getString("email", null)
            set(v) = prefs.edit().putString("email", v).apply()

        var welcomeShown: Boolean
            get() = prefs.getBoolean("welcome_shown", false)
            set(v) = prefs.edit().putBoolean("welcome_shown", v).apply()

        fun credentials(): SiwcAuth.Credentials? = prefs.getString("credentials_enc", null)?.let { stored ->
            runCatching {
                val (iv, enc) = stored.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
                val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
                SiwcAuth.Credentials.fromJson(String(cipher.doFinal(enc), Charsets.UTF_8))
            }.getOrNull()
        }

        fun saveCredentials(c: SiwcAuth.Credentials?) {
            if (c == null) {
                prefs.edit().remove("credentials_enc").apply()
                return
            }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val enc = cipher.doFinal(c.toJson().toByteArray(Charsets.UTF_8))
            prefs.edit().putString("credentials_enc", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
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
        const val SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000
        const val MAX_LOOPBACK_REQUESTS = 12
        const val TEST_TIMEOUT_MS = 45_000L
    }
}
