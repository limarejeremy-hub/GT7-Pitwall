package com.gt7pitwall.core.knowledge

/**
 * Règles du chargement de la photo officielle d'une voiture (image de la liste officielle GT7, hôte
 * gran-turismo.com), indépendantes d'Android pour être testées. Les images appartiennent à Sony / Polyphony : elles
 * ne sont ni copiées dans le dépôt ni récupérées en masse ; une seule image, celle de la voiture conduite, est
 * demandée puis mise en cache sur le téléphone. Aucune protection n'est contournée (pas d'en-tête falsifié).
 *
 * Course du 27/10 (APK #420) : la silhouette restait affichée sans aucune explication. Chaque tentative produit
 * maintenant un [Outcome] lisible dans l'Atelier.
 */
object CarImagePolicy {
    const val HOST = "www.gran-turismo.com"
    const val PATH = "/common/dist/gt7/carlist/car_images/"
    const val MAX_BYTES = 2_500_000
    const val MAX_REDIRECTS = 3

    /** Nouvel essai après un échec : pas de requête répétée à chaque affichage. */
    const val RETRY_AFTER_FAILURE_MS = 6 * 3_600_000L

    /** Image gardée en cache 30 jours, au plus [MAX_CACHED] voitures. */
    const val CACHE_MAX_AGE_MS = 30L * 24 * 3_600_000L
    const val MAX_CACHED = 60

    /** Adresse de l'image d'après l'identifiant GT7 reçu en UDP (même identifiant que la base gt7info : 3474 = BRZ GT300 '21). */
    fun url(carCode: Int): String? = carCode.takeIf { it in 1..20_000 }?.let { "https://$HOST${PATH}car$it.webp" }

    /** Redirection acceptée seulement en HTTPS vers le site officiel (jamais vers un autre domaine). */
    fun redirectAllowed(location: String?): Boolean {
        val l = location ?: return false
        val m = Regex("^https://([^/:?#]+)(/|$)").find(l) ?: return l.startsWith("/") && !l.startsWith("//")
        val host = m.groupValues[1].lowercase()
        return host == "gran-turismo.com" || host.endsWith(".gran-turismo.com")
    }

    /** Format d'image reconnu à ses premiers octets (le type annoncé par le serveur peut être générique). */
    fun imageFormat(head: ByteArray): String? = when {
        head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() -> "png"
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "jpeg"
        else -> null
    }

    sealed interface Outcome {
        val text: String

        data class Shown(val carCode: Int, val bytes: Int, val fromCache: Boolean) : Outcome {
            override val text get() = "photo officielle affichée (voiture $carCode, ${bytes / 1024} Ko${if (fromCache) ", cache" else ""})"
        }
        data class Http(val carCode: Int, val code: Int) : Outcome {
            override val text get() = "HTTP $code pour la voiture $carCode" + when (code) {
                403 -> " : accès refusé par le site officiel"
                404 -> " : pas d'image publiée pour cet identifiant"
                in 300..399 -> " : redirection non autorisée"
                else -> ""
            } + " — silhouette affichée"
        }
        data class NotAnImage(val carCode: Int, val contentType: String?) : Outcome {
            override val text get() = "réponse qui n'est pas une image (${contentType ?: "type inconnu"}) pour la voiture $carCode — silhouette affichée"
        }
        data class Undecodable(val carCode: Int, val format: String) : Outcome {
            override val text get() = "image $format reçue mais illisible par Android pour la voiture $carCode — silhouette affichée"
        }
        data class TooLarge(val carCode: Int) : Outcome {
            override val text get() = "image trop volumineuse pour la voiture $carCode — silhouette affichée"
        }
        data class Network(val carCode: Int, val error: String) : Outcome {
            override val text get() = "réseau : $error (voiture $carCode) — silhouette affichée"
        }
        data class Waiting(val carCode: Int, val lastFailure: String) : Outcome {
            override val text get() = "$lastFailure ; nouvel essai dans quelques heures"
        }
        data class Disabled(val reason: String) : Outcome {
            override val text get() = reason
        }
        data object NoCar : Outcome {
            override val text get() = "aucune voiture reçue de la PS5 pour l'instant"
        }
    }
}
