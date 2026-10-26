package com.gt7pitwall.app.sport

import android.content.Context
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.sport.GtPlanetFeed
import com.gt7pitwall.core.sport.GridStatsDailyFeed
import com.gt7pitwall.core.sport.RefreshPolicy
import com.gt7pitwall.core.sport.ScheduleBuilder
import com.gt7pitwall.core.sport.ScheduleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Programme des courses Sport A/B/C, lu dans le flux RSS public de GTPlanet (une requête au plus toutes les 30 minutes,
 * en pratique quelques-unes par semaine), gardé en cache sur le téléphone : sans réseau, le dernier programme connu
 * reste utilisable tant qu'il couvre la semaine. Aucun compte, aucune clé, aucun serveur.
 */
class SportScheduleRepository(context: Context, private val database: Gt7Database) {
    private val dir = File(context.filesDir, "sport").apply { mkdirs() }
    private val cache = File(dir, "gtplanet.xml")
    private val fallbackCache = File(dir, "gtplanet-general.xml")
    private val gridCache = File(dir, "gridstats.json")
    private val credentials = SportApiCredentialStore(context)
    private val prefs = context.getSharedPreferences("sport", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow<ScheduleState?>(null)
    /** Programme connu (null avant la première lecture du cache). */
    val state: StateFlow<ScheduleState?> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val lastFetchMs: Long? get() = prefs.getLong(KEY_FETCH, 0L).takeIf { it > 0 }
    private val lastError: String? get() = prefs.getString(KEY_ERROR, null)

    /** Cache relu, puis actualisation si nécessaire ; ensuite, vérification toutes les 30 minutes tant que l'appli vit. */
    fun start() {
        scope.launch {
            rebuild()
            while (isActive) {
                rebuild() // passage d'une semaine à l'autre, même sans nouvelle lecture
                refreshIfNeeded()
                delay(RefreshPolicy.MIN_INTERVAL_MS)
            }
        }
    }

    /** Base de voitures et circuits mise à jour : les noms de circuits publiés sont rapprochés de nouveau. */
    fun rebuildNow() {
        scope.launch { rebuild() }
    }

    /** Ouverture de l'application : relecture si la politique le demande (nouvelle semaine, cache ancien). */
    fun onOpen() {
        scope.launch { rebuild(); refreshIfNeeded() }
    }

    /**
     * Retour du réseau : nouvel essai si la dernière lecture a échoué ou si la semaine manque. Android signale chaque
     * changement de réseau : au plus un essai toutes les [NETWORK_RETRY_MS].
     */
    fun onNetworkBack() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkAttemptMs < NETWORK_RETRY_MS) return
        lastNetworkAttemptMs = now
        scope.launch {
            val st = _state.value
            if (lastError != null || st == null || st.stale ||
                RefreshPolicy.needsRefresh(RefreshPolicy.fromMs(now), lastFetchMs, st)
            ) fetch()
        }
    }

    @Volatile private var lastNetworkAttemptMs = 0L

    /** Bouton « Actualiser » : lecture immédiate (toujours limitée à une requête à la fois). */
    fun refreshNow() {
        scope.launch { fetch() }
    }

    private suspend fun refreshIfNeeded() {
        val now = RefreshPolicy.fromMs(System.currentTimeMillis())
        if (RefreshPolicy.needsRefresh(now, lastFetchMs, _state.value)) fetch()
    }

    private suspend fun fetch() = lock.withLock {
        _busy.value = true
        val errors = mutableListOf<String>()
        var succeeded = false
        try {
            val primary = runCatching { fetchGtPlanet(GtPlanetFeed.URL, cache, conditional = true) }
            primary.onSuccess { succeeded = true }
            // Secours sans clé : flux général du même site, si le flux principal échoue ou n'a pas la semaine en cours.
            val week = ScheduleBuilder.weekStart(LocalDate.now(GtPlanetFeed.PARIS))
            val haveWeek = runCatching { cache.isFile && GtPlanetFeed.parse(cache.readText(), 0).any { ScheduleBuilder.weekStart(it.validFrom) == week } }
                .getOrDefault(false)
            if (!haveWeek) {
                runCatching { fetchGtPlanet(GtPlanetFeed.FALLBACK_URL, fallbackCache, conditional = false) }
                    .onSuccess { succeeded = true }
                    .onFailure { e -> errors.add("GTPlanet (flux général) : ${e.message ?: "indisponible"}") }
            }
            primary.onFailure { errors.add(0, "GTPlanet : ${it.message ?: "indisponible"}") }
            // Seule une clé personnelle présente sur le téléphone active cette deuxième source.
            // Aucune requête vers l'API sans clé : pas de création de compte ni de facturation implicite.
            val key = credentials.read()
            if (key != null) {
                runCatching { fetchGridStats(key) }.onSuccess { succeeded = true }
                    .onFailure { errors += "GT GridStats : ${it.message ?: "indisponible"}" }
            }
            // Même si une source marche, l'autre peut être indisponible : on montre le diagnostic.
            prefs.edit().putLong(KEY_FETCH, System.currentTimeMillis())
                .apply {
                    if (errors.isEmpty()) remove(KEY_ERROR)
                    else putString(KEY_ERROR, errors.joinToString(" • ").take(300))
                }.apply()
        } finally {
            _busy.value = false
        }
        rebuild()
    }

    private fun fetchGtPlanet(address: String, target: File, conditional: Boolean) {
        val conn = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            if (conditional && target.isFile) prefs.getString(KEY_MODIFIED, null)?.let { setRequestProperty("If-Modified-Since", it) }
        }
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> Unit
                in 200..299 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.length > MAX_FEED) error("flux trop volumineux")
                    if (GtPlanetFeed.parse(body, System.currentTimeMillis()).isEmpty()) {
                        error("aucune fiche lisible dans le flux")
                    }
                    val tmp = File(dir, "${target.name}.tmp")
                    tmp.writeText(body)
                    if (!tmp.renameTo(target)) error("impossible de remplacer le cache GTPlanet")
                    if (conditional) conn.getHeaderField("Last-Modified")?.let { prefs.edit().putString(KEY_MODIFIED, it).apply() }
                }
                else -> error("HTTP $code")
            }
        } finally { conn.disconnect() }
    }

    private fun fetchGridStats(token: String) {
        val url = URL(GridStatsDailyFeed.URL)
        // La clé personnelle ne part que vers le domaine officiel, en HTTPS.
        if (url.protocol != "https" || url.host != GridStatsDailyFeed.HOST) error("domaine GridStats inattendu")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
            if (!conn.contentType.orEmpty().contains("json", ignoreCase = true)) error("réponse non JSON")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.length > MAX_GRID_JSON) error("réponse trop volumineuse")
            val posts = GridStatsDailyFeed.parse(body, System.currentTimeMillis())
            if (posts.isEmpty()) error("aucune course A/B/C valide")
            val tmp = File(dir, "gridstats.json.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(gridCache)) error("impossible de remplacer le cache GridStats")
        } finally { conn.disconnect() }
    }

    private fun rebuild() {
        fun read(f: File) = runCatching { if (f.isFile) GtPlanetFeed.parse(f.readText(), f.lastModified()) else emptyList() }
            .getOrDefault(emptyList())
        // Le même article peut figurer dans les deux flux : une seule fois.
        val posts = (read(cache) + read(fallbackCache)).distinctBy { it.source.url ?: it.title }
        val today = LocalDate.now(GtPlanetFeed.PARIS)
        val gridPosts = runCatching {
            if (gridCache.isFile) GridStatsDailyFeed.parse(gridCache.readText(), gridCache.lastModified()) else emptyList()
        }.getOrDefault(emptyList())
        _state.value = ScheduleBuilder.build(posts + gridPosts, database, today, lastFetchMs, lastError)
    }

    private companion object {
        const val USER_AGENT = "GT7Pitwall/0.8 (Android; Sport reader)"
        const val MAX_GRID_JSON = 2_000_000
        const val MAX_FEED = 5_000_000
        const val NETWORK_RETRY_MS = 2 * 60_000L
        const val KEY_FETCH = "last_fetch"
        const val KEY_ERROR = "last_error"
        const val KEY_MODIFIED = "last_modified"
    }
}
