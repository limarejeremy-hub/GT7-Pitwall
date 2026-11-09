package com.gt7pitwall.core.knowledge

/**
 * Fiche officielle d'un circuit et logo du site (catalogue « tracklist » de gran-turismo.com), lus au besoin par le
 * téléphone puis mis en cache. Comme pour les photos de voitures ([CarImagePolicy]) : contenus de Sony / Polyphony,
 * jamais copiés dans le dépôt, une seule lecture du catalogue puis un logo par circuit affiché, APK de débogage seulement.
 *
 * Structure vérifiée le 09/11/2026 sur le site :
 * - la page `/fr/gt7/tracklist/` charge `/common/dist/gt7/tracklist/assets/index-<hash>.js` ;
 * - ce script référence le catalogue `./tracks.fr-<hash>.js` (objet `{ <id du tracé>: { baseId, cornerCount,
 *   countryName, elevationGap_v, length_v, nameLong, straight_v, … } }`) et un module par logo de site
 *   (`…/track_logos/light/<baseId>.png": ()=>…import(`./<baseId>-<hash>.js`)`) ;
 * - chaque module de logo exporte l'adresse du PNG (`/common/dist/gt7/tracklist/assets/<baseId>-<hash>.png`).
 * Les identifiants de tracé sont ceux de `track_signatures.json` (`official_id`). Les noms à hachage changent quand le
 * site est mis à jour : ils sont relus, jamais figés dans le code.
 */
object TrackCatalogPolicy {
    const val HOST = "www.gran-turismo.com"
    const val PAGE = "https://$HOST/fr/gt7/tracklist/"
    const val ASSETS = "/common/dist/gt7/tracklist/assets/"
    const val MAX_SCRIPT_BYTES = 3_000_000
    const val MAX_LOGO_BYTES = 500_000
    /** Catalogue relu au plus une fois par semaine ; logos gardés 30 jours. */
    const val CATALOG_MAX_AGE_MS = 7L * 24 * 3_600_000L
    const val LOGO_MAX_AGE_MS = 30L * 24 * 3_600_000L

    data class Entry(
        val id: String,
        val baseId: String,
        val name: String,
        val country: String?,
        val corners: Int?,
        val lengthM: Double?,
        val elevationM: Double?,
        val straightM: Double?,
    )

    private val ID = Regex("^[0-9a-f]{6}$")

    /** Adresse absolue d'un fichier du site, seulement sous le dossier des ressources du catalogue. */
    fun asset(name: String): String? = name.removePrefix("./").takeIf { Regex("^[A-Za-z0-9._-]+\\.(js|png)$").matches(it) }?.let { "https://$HOST$ASSETS$it" }

    /** Script principal cité par la page. */
    fun indexScript(html: String): String? =
        Regex("""src="(/common/dist/gt7/tracklist/assets/index-[A-Za-z0-9_-]+\.js)"""").find(html)?.groupValues?.get(1)?.let { "https://$HOST$it" }

    /** Module du catalogue dans la langue [lang] (« fr »). */
    fun catalogModule(indexJs: String, lang: String = "fr"): String? =
        Regex("""tracks\.$lang\.ts"\s*:\s*\(\)\s*=>\s*\w+\(\(\)\s*=>\s*import\(`(\./tracks\.$lang-[A-Za-z0-9_-]+\.js)`""").find(indexJs)?.groupValues?.get(1)?.let(::asset)

    /** Module du logo de chaque site : baseId → adresse du module. */
    fun logoModules(indexJs: String): Map<String, String> =
        Regex("""track_logos/light/([0-9a-f]{6})\.png"\s*:\s*\(\)\s*=>\s*\w+\(\(\)\s*=>\s*import\(`(\./[0-9a-f]{6}-[A-Za-z0-9_-]+\.js)`""")
            .findAll(indexJs).mapNotNull { m -> asset(m.groupValues[2])?.let { m.groupValues[1] to it } }.toMap()

    /** Adresse du PNG exportée par un module de logo. */
    fun logoPng(moduleJs: String): String? =
        Regex("""`(/common/dist/gt7/tracklist/assets/[0-9a-f]{6}-[A-Za-z0-9_-]+\.png)`""").find(moduleJs)?.groupValues?.get(1)?.let { "https://$HOST$it" }

    /** Fiches du catalogue : id du tracé → fiche. Un champ illisible reste null. */
    fun parseCatalog(js: String): Map<String, Entry> {
        val out = linkedMapOf<String, Entry>()
        Regex("""(?:^|[{,])"?([0-9a-f]{6})"?\s*:\s*\{([^{}]*)\}""").findAll(js).forEach { m ->
            val id = m.groupValues[1]
            val body = m.groupValues[2]
            fun str(k: String) = Regex("""(?:^|,)$k:`([^`]*)`""").find(body)?.groupValues?.get(1)
            fun dbl(k: String) = Regex("""(?:^|,)$k:(-?[0-9.]+(?:e[0-9]+)?)""").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            val base = str("baseId")?.takeIf { ID.matches(it) } ?: return@forEach
            out[id] = Entry(
                id = id, baseId = base, name = str("nameLong") ?: str("nameBase") ?: return@forEach,
                country = str("countryName"), corners = dbl("cornerCount")?.toInt(),
                lengthM = dbl("length_v"), elevationM = dbl("elevationGap_v"), straightM = dbl("straight_v"),
            )
        }
        return out
    }

    private fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    /**
     * Tracé du catalogue (anglais : mêmes noms que la base gt7info) portant exactement ce nom ; null si aucun ou plusieurs.
     * Sert quand Pitwall n'a pas de signature du circuit (Suzuka East…).
     */
    fun idByName(entries: Map<String, Entry>, name: String?): String? {
        val k = key(name ?: return null).takeIf { it.isNotEmpty() } ?: return null
        return entries.values.filter { key(it.name) == k }.map { it.id }.distinct().singleOrNull()
    }

    /** Redirection acceptée seulement en HTTPS vers le site officiel. */
    fun redirectAllowed(location: String?): Boolean = CarImagePolicy.redirectAllowed(location)
}
