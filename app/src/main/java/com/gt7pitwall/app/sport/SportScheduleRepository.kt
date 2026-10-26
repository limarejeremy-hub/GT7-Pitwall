package com.gt7pitwall.app.sport

import android.content.Context
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.sport.DgEdgeFeed
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
import org.json.JSONArray
import org.json.JSONObject

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
    private val dgCache = File(dir, "dg-edge.json")
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
        val sourceErrors = mutableListOf<String>()
        try {
            val week = ScheduleBuilder.weekStart(LocalDate.now(GtPlanetFeed.PARIS))
            // HTTP 200 can still contain last week's schedule.
            runCatching { fetchGtPlanet(GtPlanetFeed.URL, cache, conditional = true) }
                .onFailure { sourceErrors += "GTPlanet : ${it.message ?: "indisponible"}" }
            if (!hasWeek(cache, week)) {
                runCatching { fetchGtPlanet(GtPlanetFeed.FALLBACK_URL, fallbackCache, conditional = false) }
                    .onFailure { sourceErrors += "GTPlanet général : ${it.message ?: "indisponible"}" }
            }
            // Independent public website; no API key and no login, personal local use only.
            // Daily verification when available; retry if no current week is cached.
            val hasDg = readDg().any { it.validFrom == week }
            if (!hasDg || System.currentTimeMillis() - dgCache.lastModified() >= RefreshPolicy.DAILY_MS) {
                runCatching { fetchDgEdge(week) }
                    .onFailure { sourceErrors += "DG EDGE : ${it.message ?: "indisponible"}" }
            }
            credentials.read()?.let { token ->
                runCatching { fetchGridStats(token) }
                    .onFailure { sourceErrors += "GT GridStats : ${it.message ?: "indisponible"}" }
            }
            val currentSources = listOfNotNull(cache, fallbackCache).flatMap { file ->
                runCatching { if (file.isFile) GtPlanetFeed.parse(file.readText(), file.lastModified()) else emptyList() }
                    .getOrDefault(emptyList())
            } + readDg() + runCatching {
                if (gridCache.isFile) GridStatsDailyFeed.parse(gridCache.readText(), gridCache.lastModified()) else emptyList()
            }.getOrDefault(emptyList())
            val complete = currentSources.filter { ScheduleBuilder.weekStart(it.validFrom) == week }
                .flatMap { it.races.keys }.toSet()
            val current = complete.containsAll(listOf(
                com.gt7pitwall.core.race.Series.DAILY_A,
                com.gt7pitwall.core.race.Series.DAILY_B,
                com.gt7pitwall.core.race.Series.DAILY_C,
            ))
            val diagnostic = if (current) null else
                "Aucune fiche A/B/C exploitable pour la semaine du $week" +
                    sourceErrors.takeIf { it.isNotEmpty() }?.joinToString(" • ", prefix = " : ").orEmpty()
            prefs.edit().putLong(KEY_FETCH, System.currentTimeMillis()).apply {
                if (diagnostic == null) remove(KEY_ERROR) else putString(KEY_ERROR, diagnostic.take(450))
            }.apply()
        } finally {
            _busy.value = false
            rebuild()
        }
    }

    private fun hasWeek(file: File, week: LocalDate): Boolean = runCatching {
        if (!file.isFile) false else {
            val races = GtPlanetFeed.parse(file.readText(), file.lastModified())
                .filter { ScheduleBuilder.weekStart(it.validFrom) == week }
                .flatMap { it.races.keys }.toSet()
            races.containsAll(listOf(
                com.gt7pitwall.core.race.Series.DAILY_A,
                com.gt7pitwall.core.race.Series.DAILY_B,
                com.gt7pitwall.core.race.Series.DAILY_C,
            ))
        }
    }.getOrDefault(false)

    /** Local cache, usable offline only during its actual week. */
    private fun readDg(): List<com.gt7pitwall.core.sport.WeeklyPost> = runCatching {
        if (!dgCache.isFile) return emptyList()
        val root = JSONObject(dgCache.readText())
        val week = LocalDate.parse(root.getString("week"))
        val pages = root.getJSONArray("pages")
        val entries = (0 until pages.length()).associate { i ->
            val item = pages.getJSONObject(i)
            item.getString("url") to item.getString("html")
        }
        listOfNotNull(DgEdgeFeed.parseWeek(entries, week, dgCache.lastModified()))
    }.getOrDefault(emptyList())

    /** Maximum four small public HTML requests per successful weekly/daily refresh. */
    private fun fetchDgEdge(week: LocalDate) {
        val links = DgEdgeFeed.links(fetchPublicHtml(DgEdgeFeed.INDEX), week)
        if (links.size != 3) error("seulement ${links.size}/3 courses pour la semaine du $week")
        val pages = links.values.associateWith(::fetchPublicHtml)
        if (DgEdgeFeed.parseWeek(pages, week, System.currentTimeMillis()) == null)
            error("fiche publique illisible ou incomplète")
        val array = JSONArray()
        pages.forEach { (url, html) -> array.put(JSONObject().put("url", url).put("html", html)) }
        val tmp = File(dir, "dg-edge.json.tmp")
        tmp.writeText(JSONObject().put("week", week.toString()).put("pages", array).toString())
        if (!tmp.renameTo(dgCache)) error("impossible de sauvegarder la fiche DG EDGE")
    }

    private fun fetchPublicHtml(address: String): String {
        // Allowlisted HTTPS origin and public paths only; no third-party redirects.
        val url = URL(address)
        if (url.protocol != "https" || url.host != "www.dg-edge.com" ||
            !(url.path == "/events/dailies" || url.path.startsWith("/events/dailies/week-")))
            error("adresse de source non autorisée")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "text/html")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
            if (!conn.contentType.orEmpty().contains("html", ignoreCase = true)) error("réponse non HTML")
            if (conn.contentLengthLong > MAX_FEED) error("page trop volumineuse")
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            if (html.length > MAX_FEED) error("page trop volumineuse")
            return html
        } finally { conn.disconnect() }
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
        val dgPosts = readDg()
        val gridPosts = runCatching {
            if (gridCache.isFile) GridStatsDailyFeed.parse(gridCache.readText(), gridCache.lastModified()) else emptyList()
        }.getOrDefault(emptyList())
        _state.value = ScheduleBuilder.build(posts + dgPosts + gridPosts, database, today, lastFetchMs, lastError)
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
