package com.gt7pitwall.app.sport

import android.content.Context
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.sport.GtPlanetFeed
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
        try {
            val conn = (URL(GtPlanetFeed.URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                prefs.getString(KEY_MODIFIED, null)?.let { setRequestProperty("If-Modified-Since", it) }
            }
            try {
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> Unit
                    in 200..299 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        // Un changement de format (aucune fiche lisible) ne remplace pas un cache valide.
                        if (GtPlanetFeed.parse(body, System.currentTimeMillis()).isEmpty()) {
                            error("aucune fiche lisible dans le flux (format modifié ?)")
                        }
                        val tmp = File(dir, "gtplanet.xml.tmp")
                        tmp.writeText(body)
                        tmp.renameTo(cache)
                        conn.getHeaderField("Last-Modified")?.let { prefs.edit().putString(KEY_MODIFIED, it).apply() }
                    }
                    else -> error("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
            prefs.edit().putLong(KEY_FETCH, System.currentTimeMillis()).remove(KEY_ERROR).apply()
        } catch (e: Exception) {
            // Réseau absent ou source indisponible : le cache reste ; l'erreur est affichée, la date de lecture aussi.
            prefs.edit().putLong(KEY_FETCH, System.currentTimeMillis()).putString(KEY_ERROR, e.message ?: e.javaClass.simpleName).apply()
        } finally {
            _busy.value = false
        }
        rebuild()
    }

    private fun rebuild() {
        val posts = runCatching { if (cache.isFile) GtPlanetFeed.parse(cache.readText(), cache.lastModified()) else emptyList() }
            .getOrDefault(emptyList())
        val today = LocalDate.now(GtPlanetFeed.PARIS)
        _state.value = ScheduleBuilder.build(posts, database, today, lastFetchMs, lastError)
    }

    private companion object {
        const val USER_AGENT = "GT7Pitwall/0.8 (Android; lecteur RSS personnel)"
        const val KEY_FETCH = "last_fetch"
        const val KEY_ERROR = "last_error"
        const val KEY_MODIFIED = "last_modified"
    }
}
