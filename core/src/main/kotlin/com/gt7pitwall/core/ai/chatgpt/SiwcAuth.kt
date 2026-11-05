package com.gt7pitwall.core.ai.chatgpt

import org.json.JSONObject
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Issue #67 : « Sign in with ChatGPT » pour une application open source ou hébergée localement, qui consomme le forfait
 * ChatGPT du pilote (Plus) pour des requêtes Responses éligibles, sans clé d'API payante. Tout ce qui suit reprend la
 * documentation officielle d'OpenAI (developers.openai.com/siwc/token-sharing-open-source, lue le 05/11/2026) :
 * - première inscription avec `client_id=dynamic_agent_client`, `agent_name_hint` et un `ext_agent_host_id` stable ;
 *   le rappel renvoie le `client_id` attribué (oaiapp_…), à garder et réutiliser ;
 * - OAuth Authorization Code + PKCE (S256), `state` et `nonce` neufs à chaque tentative ;
 * - redirection HTTP de bouclage `http://127.0.0.1:<port>/auth/callback` (seul le port peut varier, jamais localhost) ;
 * - portées `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`, ressource
 *   `https://api.openai.com/v1` ;
 * - jeton d'identité vérifié (signature JWKS, émetteur, audience = client attribué, expiration, nonce) ; sans la portée
 *   `chatgpt.tokens.use.direct` accordée, aucun usage du forfait.
 *
 * Pur Kotlin (JVM et Android) : aucune requête réseau ici, seulement la construction et la vérification des messages.
 * Aucun jeton n'est jamais écrit dans un journal : [Credentials.toString] les masque.
 */
object SiwcAuth {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    const val DISCOVERY_URL = "https://auth.openai.com/.well-known/openid-configuration"
    const val JWKS_URL = "https://auth.openai.com/.well-known/jwks.json"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val CALLBACK_PATH = "/auth/callback"

    /** Nom de l'application montré au pilote à l'inscription (le même partout). */
    const val AGENT_NAME = "GT7 Pitwall"

    /** Port de bouclage préféré (exemple de la documentation) ; un autre port libre est accepté. */
    const val PREFERRED_PORT = 1455

    /** Tolérance d'horloge pour l'expiration du jeton d'identité. */
    const val CLOCK_SKEW_S = 120L

    private val random = SecureRandom()

    /** Identifiant d'hôte stable « urn:uuid:… », tiré une fois par installation, jamais un identifiant du pilote. */
    fun newHostId(): String = "urn:uuid:" + UUID.randomUUID()

    fun isValidHostId(id: String): Boolean =
        Regex("^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id) ||
            id.startsWith("urn:ietf:params:oauth:jwk-thumbprint:") || id.startsWith("did:key:")

    /** Valeur aléatoire sûre en base64url sans remplissage (state, nonce, vérificateur PKCE). */
    fun randomToken(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    /** Défi PKCE S256 : base64url(SHA-256(vérificateur)), sans remplissage (RFC 7636). */
    fun codeChallenge(verifier: String): String = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun redirectUri(port: Int): String {
        require(port in 1..65535) { "port invalide" }
        return "http://127.0.0.1:$port$CALLBACK_PATH"
    }

    /** Une tentative d'autorisation : tout ce qu'il faut garder jusqu'au rappel, puis jeter. */
    data class Attempt(
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        /** Client attribué d'une inscription déjà faite ; null : première inscription (dynamic_agent_client). */
        val issuedClientId: String?,
        val hostId: String,
    ) {
        override fun toString() = "Attempt(redirect=$redirectUri, réinscription=${issuedClientId != null})"
    }

    fun newAttempt(port: Int, hostId: String, issuedClientId: String?): Attempt {
        require(isValidHostId(hostId)) { "identifiant d'hôte invalide" }
        return Attempt(randomToken(), randomToken(), randomToken(48), redirectUri(port), issuedClientId, hostId)
    }

    /** URL à ouvrir dans le navigateur du système. */
    fun authorizeUrl(a: Attempt, idTokenHint: String? = null, loginHint: String? = null): String {
        val p = linkedMapOf(
            "client_id" to (a.issuedClientId ?: DYNAMIC_CLIENT),
            "response_type" to "code",
            "redirect_uri" to a.redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to a.state,
            "nonce" to a.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to codeChallenge(a.verifier),
            "ext_agent_host_id" to a.hostId,
        )
        // agent_name_hint seulement à la première inscription ; les indices de compte seulement à la réautorisation.
        if (a.issuedClientId == null) p["agent_name_hint"] = AGENT_NAME
        if (a.issuedClientId != null) {
            idTokenHint?.let { p["id_token_hint"] = it }
            loginHint?.let { p["login_hint"] = it }
        }
        return AUTHORIZE_URL + "?" + p.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    sealed interface Callback {
        /** Code reçu ; [clientId] : client à utiliser pour l'échange (attribué à l'inscription ou déjà connu). */
        data class Code(val code: String, val clientId: String) : Callback {
            override fun toString() = "Code(client=$clientId)"
        }

        /** Le pilote a refusé, ou l'autorisation a échoué : rien n'est échangé. */
        data class Denied(val error: String) : Callback

        /** Réponse invalide (state, client, paramètres) : rejetée sans rien échanger. */
        data class Invalid(val reason: String) : Callback
    }

    /** Lit la cible de la requête reçue sur le port de bouclage (« /auth/callback?code=…&state=… »). */
    fun parseCallback(target: String, a: Attempt): Callback {
        val path = target.substringBefore('?')
        if (path != CALLBACK_PATH) return Callback.Invalid("chemin inattendu")
        val q = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        if (q["state"] != a.state) return Callback.Invalid("state différent : réponse rejetée")
        q["error"]?.let { return Callback.Denied(it) }
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: return Callback.Invalid("code absent")
        val returned = q["client_id"]?.takeIf { it.isNotBlank() }
        return when {
            a.issuedClientId == null && (returned == null || returned == DYNAMIC_CLIENT) ->
                Callback.Invalid("inscription incomplète : aucun client attribué")
            a.issuedClientId == null -> Callback.Code(code, returned!!)
            returned != null && returned != a.issuedClientId -> Callback.Invalid("client différent de l'inscription de ce compte")
            else -> Callback.Code(code, a.issuedClientId)
        }
    }

    /** Corps (form-encoded) de l'échange du code. */
    fun codeExchangeBody(code: String, clientId: String, a: Attempt): String = form(
        "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
        "code_verifier" to a.verifier, "redirect_uri" to a.redirectUri, "resource" to RESOURCE,
    )

    /** Corps du rafraîchissement : client attribué (jamais dynamic_agent_client), portée omise pour garder l'accord. */
    fun refreshBody(clientId: String, refreshToken: String): String {
        require(clientId != DYNAMIC_CLIENT) { "client attribué requis" }
        return form("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken, "resource" to RESOURCE)
    }

    /** Corps de révocation de la session renouvelable (déconnexion). */
    fun revokeBody(clientId: String, refreshToken: String): String =
        form("token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to clientId)

    /** Réponse du point de jeton. */
    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val idToken: String?,
        val expiresInS: Long,
        val scopes: Set<String>,
    ) {
        /** Le pilote a autorisé l'usage de son forfait : sinon, connexion gardée mais aucune inférence. */
        val planUsage: Boolean get() = PLAN_SCOPE in scopes

        override fun toString() = "TokenResponse(scopes=$scopes, expiresIn=$expiresInS)"

        companion object {
            fun parse(json: String): TokenResponse {
                val o = JSONObject(json)
                return TokenResponse(
                    accessToken = o.getString("access_token"),
                    refreshToken = o.optString("refresh_token").ifEmpty { null },
                    idToken = o.optString("id_token").ifEmpty { null },
                    expiresInS = o.optLong("expires_in", 3600),
                    scopes = o.optString("scope").split(' ').filter { it.isNotBlank() }.toSet(),
                )
            }
        }
    }

    /** Identité vérifiée tirée du jeton d'identité. */
    data class Identity(val subject: String, val email: String?)

    /**
     * Vérifie le jeton d'identité : signature RS256 avec la clé publiée (JWKS, par `kid`), émetteur, audience égale au
     * client attribué, expiration (tolérance [CLOCK_SKEW_S]), nonce de cette tentative. Renvoie l'identité, ou une raison.
     */
    fun verifyIdToken(idToken: String, jwksJson: String, clientId: String, nonce: String, nowS: Long): Result<Identity> = runCatching {
        val parts = idToken.split('.')
        require(parts.size == 3) { "jeton d'identité mal formé" }
        val header = JSONObject(String(b64urlDecode(parts[0])))
        val payload = JSONObject(String(b64urlDecode(parts[1])))
        require(header.optString("alg") == "RS256") { "algorithme de signature inattendu" }
        val kid = header.optString("kid")
        val keys = JSONObject(jwksJson).getJSONArray("keys")
        val jwk = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull { it.optString("kid") == kid && it.optString("kty") == "RSA" }
            ?: error("clé de signature inconnue (JWKS à recharger)")
        val key = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, b64urlDecode(jwk.getString("n"))), BigInteger(1, b64urlDecode(jwk.getString("e")))),
        )
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(b64urlDecode(parts[2]))
        }
        require(ok) { "signature du jeton d'identité invalide" }
        require(payload.optString("iss") == ISSUER) { "émetteur inattendu" }
        val aud = payload.opt("aud")
        val audiences = when (aud) {
            is String -> listOf(aud)
            is org.json.JSONArray -> (0 until aud.length()).map { aud.getString(it) }
            else -> emptyList()
        }
        require(clientId in audiences) { "audience différente du client attribué" }
        require(payload.optLong("exp", 0) + CLOCK_SKEW_S >= nowS) { "jeton d'identité expiré" }
        require(payload.optString("nonce") == nonce) { "nonce différent : réponse rejetée" }
        val sub = payload.optString("sub").ifEmpty { error("identité absente") }
        Identity(sub, payload.optString("email").ifEmpty { null })
    }

    /** Informations stockées (chiffrées par l'application), une par compte ChatGPT inscrit. */
    data class Credentials(
        val clientId: String,
        val hostId: String,
        val subject: String,
        val email: String?,
        val accessToken: String,
        val refreshToken: String?,
        val idToken: String?,
        val scopes: Set<String>,
        /** Expiration du jeton d'accès (ms, horloge du téléphone). */
        val expiresAtMs: Long,
        /** Modèle choisi dans le catalogue du compte (slug), null tant qu'il n'est pas lu. */
        val model: String? = null,
    ) {
        val planUsage: Boolean get() = PLAN_SCOPE in scopes

        /** À rafraîchir : moins de [REFRESH_MARGIN_MS] avant l'expiration. */
        fun needsRefresh(nowMs: Long): Boolean = nowMs >= expiresAtMs - REFRESH_MARGIN_MS

        override fun toString() = "Credentials(client=$clientId, compte=${email ?: "?"}, forfait=$planUsage, modèle=$model, jetons=[masqués])"

        fun toJson(): String = JSONObject().apply {
            put("client_id", clientId); put("ext_agent_host_id", hostId); put("subject", subject)
            email?.let { put("email", it) }
            put("access_token", accessToken)
            refreshToken?.let { put("refresh_token", it) }
            idToken?.let { put("id_token", it) }
            put("scopes", org.json.JSONArray(scopes.sorted()))
            put("expires_at_ms", expiresAtMs)
            model?.let { put("model", it) }
        }.toString()

        /** Nouveaux jetons après un rafraîchissement : accès, expiration, portées et jeton de rafraîchissement ensemble. */
        fun refreshed(t: TokenResponse, nowMs: Long): Credentials = copy(
            accessToken = t.accessToken,
            refreshToken = t.refreshToken ?: refreshToken,
            idToken = t.idToken ?: idToken,
            scopes = t.scopes.ifEmpty { scopes },
            expiresAtMs = nowMs + t.expiresInS * 1000,
        )

        companion object {
            const val REFRESH_MARGIN_MS = 5 * 60 * 1000L

            fun fromJson(json: String): Credentials {
                val o = JSONObject(json)
                val sc = o.optJSONArray("scopes")
                return Credentials(
                    clientId = o.getString("client_id"), hostId = o.getString("ext_agent_host_id"), subject = o.getString("subject"),
                    email = o.optString("email").ifEmpty { null }, accessToken = o.getString("access_token"),
                    refreshToken = o.optString("refresh_token").ifEmpty { null }, idToken = o.optString("id_token").ifEmpty { null },
                    scopes = (0 until (sc?.length() ?: 0)).map { sc!!.getString(it) }.toSet(),
                    expiresAtMs = o.getLong("expires_at_ms"), model = o.optString("model").ifEmpty { null },
                )
            }
        }
    }

    /** Erreurs de rafraîchissement qui rendent le jeton inutilisable : reconnexion nécessaire. */
    fun refreshTokenUnusable(errorCode: String?): Boolean = errorCode in setOf(
        "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
    )

    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}
