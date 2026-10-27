package com.gt7pitwall.app.sport

import android.content.Context
import com.gt7pitwall.core.knowledge.Gt7Database
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

/**
 * Mise à jour automatique des voitures et des circuits : la base communautaire gt7info (ddm999, licence MIT-0) est
 * relue une fois par jour sur GitHub (fichiers publics, aucun compte, aucune clé). Une version lisible et complète
 * remplace la base en mémoire sans redémarrer, et reste en cache pour les lancements suivants ; sinon la base actuelle
 * (cache ou version livrée dans l'APK) est gardée.
 */
class DatabaseRepository(context: Context, private val database: Gt7Database, private val onUpdated: () -> Unit) {
    private val dir = File(context.filesDir, "gt7db").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("gt7db", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _status = MutableStateFlow(status())
    /** « 591 voitures, 123 circuits, mis à jour il y a 2 h » : affiché dans les réglages. */
    val status: StateFlow<String> = _status.asStateFlow()

    fun start() {
        scope.launch {
            while (isActive) {
                val last = prefs.getLong(KEY_FETCH, 0L)
                if (System.currentTimeMillis() - last >= REFRESH_MS) refresh()
                delay(CHECK_MS)
            }
        }
    }

    /** Dernière mise à jour réussie (null : base livrée avec l'APK ou cache ancien) et dernière erreur. */
    val lastSuccessMs: Long? get() = prefs.getLong(KEY_SUCCESS, 0L).takeIf { it > 0 }
    val lastError: String? get() = prefs.getString(KEY_ERROR, null)

    /** Ouverture de l'application : relecture si la dernière vérification date de plus de [OPEN_REFRESH_MS]. */
    fun onOpen() {
        val last = prefs.getLong(KEY_FETCH, 0L)
        if (System.currentTimeMillis() - last >= OPEN_REFRESH_MS) scope.launch { refresh() }
    }

    /** Retour du réseau : nouvel essai si la dernière mise à jour a échoué (au plus une fois toutes les 2 minutes). */
    fun onNetworkBack() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkAttemptMs < NETWORK_RETRY_MS) return
        lastNetworkAttemptMs = now
        if (lastError != null || now - prefs.getLong(KEY_FETCH, 0L) >= REFRESH_MS) scope.launch { refresh() }
    }

    @Volatile private var lastNetworkAttemptMs = 0L

    /** Voiture inconnue reçue par la télémétrie : relecture anticipée, au plus une fois par heure. */
    fun unknownCar() {
        val last = prefs.getLong(KEY_FETCH, 0L)
        if (System.currentTimeMillis() - last >= UNKNOWN_RETRY_MS) scope.launch { refresh() }
    }

    private suspend fun refresh() = lock.withLock {
        try {
            val texts = FILES.associateWith { download(it) }
            val fresh = Gt7Database.parseChecked(texts.getValue(CARS), texts.getValue(MAKERS), texts.getValue(COURSES), database)
                ?: error("version téléchargée incomplète ou illisible")
            FILES.forEach { name ->
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(texts.getValue(name))
                tmp.renameTo(File(dir, name))
            }
            // Catégories des voitures (Gr.3, N…) : fichier facultatif ; s'il manque ou est illisible, l'ancien reste.
            runCatching { download(GROUPS) }.getOrNull()
                ?.takeIf { Gt7Database.parseGroups(it).size >= MIN_GROUPS }
                ?.let { text ->
                    fresh.carGroups = Gt7Database.parseGroups(text)
                    val tmp = File(dir, "$GROUPS.tmp")
                    tmp.writeText(text)
                    tmp.renameTo(File(dir, GROUPS))
                }
            database.replace(fresh)
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_FETCH, now).putLong(KEY_SUCCESS, now).remove(KEY_ERROR).apply()
            onUpdated()
        } catch (e: Exception) {
            // Réseau absent ou source indisponible : la base actuelle reste ; nouvel essai à la prochaine vérification.
            prefs.edit().putLong(KEY_FETCH, System.currentTimeMillis() - REFRESH_MS + RETRY_MS)
                .putString(KEY_ERROR, e.message ?: e.javaClass.simpleName).apply()
        }
        _status.value = status()
    }

    private fun download(name: String): String {
        val conn = (URL(BASE_URL + name).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "GT7Pitwall (Android; base de données)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code pour $name")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun status(): String {
        val last = prefs.getLong(KEY_FETCH, 0L).takeIf { it > 0 }
        val base = "${database.cars.size} voitures, ${database.courses.count { !it.learned }} circuits" +
            database.learned.size.takeIf { it > 0 }?.let { ", $it appris" }.orEmpty()
        val error = prefs.getString(KEY_ERROR, null)
        return base + when {
            error != null -> " — dernière mise à jour impossible ($error)"
            last != null -> " — vérifié ${com.gt7pitwall.core.sport.RefreshPolicy.age(System.currentTimeMillis(), last)}"
            else -> ""
        }
    }

    companion object {
        const val BASE_URL = "https://raw.githubusercontent.com/ddm999/gt7info/web-new/_data/db/"
        const val CARS = "cars.csv"
        const val MAKERS = "maker.csv"
        const val COURSES = "course.csv"
        val FILES = listOf(CARS, MAKERS, COURSES)
        const val GROUPS = "cargrp.csv"
        /** En dessous, le fichier de catégories téléchargé est jugé incomplet (507 lignes le 27/10/2026). */
        const val MIN_GROUPS = 400
        const val REFRESH_MS = 24 * 3_600_000L
        const val RETRY_MS = 3_600_000L
        const val UNKNOWN_RETRY_MS = 3_600_000L
        const val CHECK_MS = 30 * 60_000L
        private const val KEY_FETCH = "last_fetch"
        private const val KEY_ERROR = "last_error"
        private const val KEY_SUCCESS = "last_success"
        const val OPEN_REFRESH_MS = 6 * 3_600_000L
        const val NETWORK_RETRY_MS = 2 * 60_000L

        /** Base au lancement : dernière version téléchargée si elle est lisible et complète, sinon celle de l'APK. */
        fun load(context: Context): Gt7Database {
            fun asset(name: String) = context.assets.open("gt7db/$name").bufferedReader().use { it.readText() }
            val bundled = runCatching { Gt7Database.parse(asset(CARS), asset(MAKERS), asset(COURSES)) }.getOrDefault(Gt7Database.EMPTY)
            val dir = File(context.filesDir, "gt7db")
            val cached = runCatching {
                fun file(name: String) = File(dir, name).readText()
                Gt7Database.parseChecked(file(CARS), file(MAKERS), file(COURSES), bundled)
            }.getOrNull()
            val base = cached ?: bundled
            base.carGroups = runCatching { Gt7Database.parseGroups(File(dir, GROUPS).readText()) }.getOrNull()
                ?.takeIf { it.size >= MIN_GROUPS }
                ?: runCatching { Gt7Database.parseGroups(asset(GROUPS)) }.getOrDefault(emptyMap())
            return base
        }
    }
}
