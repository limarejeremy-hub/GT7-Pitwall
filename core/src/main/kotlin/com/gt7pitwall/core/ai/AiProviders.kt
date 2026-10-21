package com.gt7pitwall.core.ai

/**
 * Fournisseurs d'IA de la radio. Le moteur Kotlin reste l'autorité : mesures, calculs, alertes et modifications de la
 * course. Une IA ne fait que comprendre, expliquer, comparer et suggérer à partir des faits qu'il lui fournit.
 */
enum class AiBackend(val label: String, val cloud: Boolean) {
    FLASH_LITE("Gemini 3.5 Flash-Lite", cloud = true),
    FLASH("Gemini 3.8 Flash", cloud = true),
    NANO("Gemini Nano (téléphone)", cloud = false),
    ENGINE("Moteur Pitwall (sans IA)", cloud = false),
    ;

    /** Identifiant du modèle par défaut. Sa disponibilité réelle se vérifie au premier appel, pas ici. */
    val defaultModelId: String?
        get() = when (this) {
            FLASH_LITE -> "gemini-3.5-flash-lite"
            FLASH -> "gemini-3.8-flash"
            else -> null
        }
}

/** Choix du pilote dans les réglages. */
enum class AiMode(val label: String) {
    /** Flash-Lite pour la conversation, Flash 3.8 pour les questions stratégiques complexes, repli automatique. */
    AUTO("Automatique : Flash-Lite, et Flash 3.8 pour les questions complexes"),
    FLASH_LITE("Gemini 3.5 Flash-Lite seulement"),
    FLASH("Gemini 3.8 Flash seulement"),
    NANO("Gemini Nano (sur le téléphone)"),
    LOCAL("Moteur local uniquement (aucune IA)"),
    ;

    /** Modèles cloud à essayer, dans l'ordre, pour une question simple ou complexe. */
    fun cloudOrder(complex: Boolean): List<AiBackend> = when (this) {
        AUTO -> if (complex) listOf(AiBackend.FLASH, AiBackend.FLASH_LITE) else listOf(AiBackend.FLASH_LITE)
        FLASH_LITE -> listOf(AiBackend.FLASH_LITE)
        FLASH -> listOf(AiBackend.FLASH)
        NANO, LOCAL -> emptyList()
    }

    val usesNano: Boolean get() = this != LOCAL
}

/** Pourquoi un appel n'a pas abouti. */
enum class FailureKind(val label: String) {
    NOT_CONFIGURED("non configuré"),
    QUOTA("quota atteint (429)"),
    OVERLOADED("modèle surchargé (503)"),
    MODEL_UNAVAILABLE("modèle introuvable pour ce projet"),
    AUTH("accès refusé (App Check ou clé du projet)"),
    NETWORK("pas de connexion Internet"),
    TIMEOUT("délai dépassé"),
    BLOCKED("réponse bloquée par le filtre de sécurité"),
    EMPTY("réponse vide"),
    OTHER("erreur"),
    ;

    /** Échec immédiat : on peut essayer le modèle suivant sans faire attendre le pilote. */
    val fast: Boolean get() = this in setOf(NOT_CONFIGURED, QUOTA, OVERLOADED, MODEL_UNAVAILABLE, AUTH, BLOCKED)
}

sealed interface AiResult {
    data class Text(val text: String) : AiResult

    /** [daily] : le message d'erreur parle d'un plafond journalier (sinon : par minute, ou inconnu). */
    data class Failure(val kind: FailureKind, val detail: String? = null, val daily: Boolean = false) : AiResult
}

/** Un modèle d'IA, cloud ou local. Aucune dépendance Android : le module app fournit Firebase et Gemini Nano. */
interface AiProvider {
    val backend: AiBackend

    /** Modèle réellement interrogé (« gemini-3.5-flash-lite »…). */
    val modelId: String

    /** Configuré et utilisable maintenant (sans préjuger du quota, connu seulement par les réponses du service). */
    val ready: Boolean

    /** État lisible, affiché dans les réglages. */
    val status: String

    /** Une requête réelle au modèle. Le délai maximal est appliqué par l'appelant. */
    suspend fun complete(systemInstruction: String, prompt: String): AiResult
}

/** Lecture des erreurs du SDK : seuls le nom de l'exception et son message sont connus ici. */
object AiErrors {
    fun classify(className: String, message: String?): AiResult.Failure {
        val m = (message ?: "").lowercase()
        val c = className.lowercase()
        val daily = Regex("per ?day|perday|daily|par jour|requests_per_day|rpd").containsMatchIn(m)
        val kind = when {
            "timeout" in c || "timed out" in m -> FailureKind.TIMEOUT
            "quota" in c || "429" in m || "resource_exhausted" in m || "quota" in m || "rate limit" in m -> FailureKind.QUOTA
            "503" in m || "unavailable" in m || "overloaded" in m || "service unavailable" in m -> FailureKind.OVERLOADED
            "unknownhost" in c || "connect" in c || "unable to resolve host" in m || "failed to connect" in m ||
                "network" in m || "socket" in c || "ioexception" in c -> FailureKind.NETWORK
            "appcheck" in c || "app check" in m || "app_check" in m || "permission_denied" in m || "403" in m ||
                "401" in m || "invalidapikey" in c || "api key" in m || "unauthenticated" in m -> FailureKind.AUTH
            "404" in m || "not_found" in m || "not found" in m || "is not supported" in m -> FailureKind.MODEL_UNAVAILABLE
            "promptblocked" in c || "blocked" in m || "safety" in m -> FailureKind.BLOCKED
            "servicedisabled" in c || "not been used" in m || "disabled" in m -> FailureKind.NOT_CONFIGURED
            else -> FailureKind.OTHER
        }
        // Rien de ce que dit le service (projet, clé, jeton) n'est gardé : seulement un extrait court, sans identifiant.
        val detail = message?.let { scrub(it).take(MAX_DETAIL) }
        return AiResult.Failure(kind, detail, daily = kind == FailureKind.QUOTA && daily)
    }

    /** Masque tout ce qui ressemble à une clé, un jeton ou un identifiant de projet. */
    fun scrub(s: String): String = s
        .replace(Regex("AIza[0-9A-Za-z_\\-]{10,}"), "[clé masquée]")
        .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "[jeton masqué]")
        .replace(Regex("projects/[^/\\s]+"), "projects/[projet]")
        .replace(Regex("\\s+"), " ")
        .trim()

    private const val MAX_DETAIL = 160
}
