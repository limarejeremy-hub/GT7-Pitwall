package com.gt7pitwall.app.ai

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiErrors
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiTrace
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.FailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gemini dans le cloud, via le SDK officiel Firebase AI Logic et la Gemini Developer API (niveau gratuit : projet sans
 * compte de facturation, plan Spark). Inactif tant que le pilote n'a pas saisi la configuration de son projet :
 * l'application compile, s'installe et fonctionne sans Firebase (moteur local, Gemini Nano).
 *
 * Aucune réponse n'est jamais simulée auprès du pilote : sans Firebase, rien n'est envoyé et l'état l'affiche.
 */
class CloudAi(private val context: Context) {

    val store = CloudConfigStore(context)
    val usage = AiUsage().also { it.restore(store.usage) }

    enum class Link { NOT_CONFIGURED, CONFIGURED, CONNECTED, ERROR }

    data class Status(
        val link: Link = Link.NOT_CONFIGURED,
        val detail: String? = null,
        /** Secret de débogage App Check à recopier dans la console (APK de débogage seulement). */
        val debugSecret: String? = null,
        val appCheckMode: String = AppCheckSetup.LABEL,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Dernier échange (modèle, délai, erreur, repli), pour la carte des réglages. */
    private val _trace = MutableStateFlow<AiTrace?>(null)
    val trace: StateFlow<AiTrace?> = _trace.asStateFlow()

    /** Change à chaque requête : la carte relit les compteurs. */
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    @Volatile
    private var app: FirebaseApp? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Un modèle cloud : une requête = une intervention réelle du pilote (ou un test demandé à l'écran). */
    inner class CloudModel(override val backend: AiBackend) : AiProvider {
        override val modelId: String get() = store.modelId(backend)
        override val ready: Boolean get() = app != null && modelId.isNotBlank()
        override val status: String
            get() = when {
                app == null -> "Firebase non configuré"
                usage.blockedReason(backend) != null -> usage.blockedReason(backend)!!
                else -> "prêt ($modelId)"
            }

        override suspend fun complete(systemInstruction: String, prompt: String): AiResult {
            val a = app ?: return AiResult.Failure(FailureKind.NOT_CONFIGURED)
            return try {
                val model = Firebase.ai(app = a, backend = GenerativeBackend.googleAI()).generativeModel(
                    modelName = modelId,
                    // Réponse parlée courte ; la marge couvre la réflexion du modèle, la longueur est bornée par la consigne.
                    generationConfig = generationConfig { maxOutputTokens = MAX_OUTPUT_TOKENS },
                    systemInstruction = content { text(systemInstruction) },
                )
                val text = model.generateContent(prompt).text?.trim()
                if (text.isNullOrEmpty()) AiResult.Failure(FailureKind.EMPTY) else AiResult.Text(text).also { onConnected() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AiErrors.classify(e.javaClass.name, e.message ?: e.cause?.message)
            }
        }
    }

    val lite = CloudModel(AiBackend.FLASH_LITE)
    val flash = CloudModel(AiBackend.FLASH)

    val router = AiRouter(mapOf(AiBackend.FLASH_LITE to lite, AiBackend.FLASH to flash), usage, store.mode)

    init {
        AiBackend.entries.forEach { b -> store.dailyCap(b)?.let { usage.dailyCaps[b] = it } }
        connect()
    }

    /** (Re)crée l'application Firebase du projet saisi. Sans configuration complète : rien n'est initialisé. */
    fun connect() {
        val old = app
        app = null
        runCatching { old?.delete() }
        if (!store.configured) {
            _status.value = Status(Link.NOT_CONFIGURED, "Saisis la configuration de ton projet Firebase pour activer Gemini.", debugSecretOrNull())
            return
        }
        try {
            val options = FirebaseOptions.Builder()
                .setProjectId(store.projectId)
                .setApplicationId(store.appId)
                .setApiKey(store.apiKey() ?: error("clé illisible : ressaisis-la"))
                .build()
            val a = FirebaseApp.initializeApp(context, options, APP_NAME)
            AppCheckSetup.install(context, a, store.debugSecret())
            app = a
            AiBackend.entries.forEach(usage::clearPause)
            _status.value = Status(Link.CONFIGURED, "Configuré. Pas encore vérifié : la première question le dira.", debugSecretOrNull())
        } catch (e: Throwable) {
            _status.value = Status(Link.ERROR, AiErrors.scrub(e.message ?: e.javaClass.simpleName), debugSecretOrNull())
        }
    }

    fun saveConfig(projectId: String, appId: String, apiKey: String?) {
        store.projectId = projectId
        store.appId = appId
        apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let(store::saveApiKey)
        connect()
    }

    fun clearConfig() {
        store.clear()
        connect()
    }

    fun setMode(mode: AiMode) {
        store.mode = mode
        router.mode = mode
        _tick.value++
    }

    fun setDailyCap(b: AiBackend, cap: Int?) {
        store.setDailyCap(b, cap)
        if (cap == null) usage.dailyCaps.remove(b) else usage.dailyCaps[b] = cap
        _tick.value++
    }

    fun renewDebugSecret() {
        store.renewDebugSecret()
        connect()
    }

    /** Appelé après chaque intervention : compteurs sauvegardés, état et trace mis à jour. */
    fun onTrace(t: AiTrace?) {
        t?.let { _trace.value = it }
        t?.attempts?.lastOrNull()?.failure?.let { f ->
            if (f.kind == FailureKind.AUTH || f.kind == FailureKind.MODEL_UNAVAILABLE || f.kind == FailureKind.NOT_CONFIGURED) {
                _status.value = _status.value.copy(link = Link.ERROR, detail = "${f.kind.label}${f.detail?.let { " : $it" } ?: ""}")
            }
        }
        store.usage = usage.save()
        _tick.value++
    }

    /** Test demandé par le pilote : exactement une requête au modèle choisi. */
    fun test(b: AiBackend) {
        val m = if (b == AiBackend.FLASH) flash else lite
        scope.launch {
            if (!m.ready) {
                _testResult.value = "${b.label} : ${m.status}."
                return@launch
            }
            _testResult.value = "${b.label} : test en cours (1 requête)…"
            val t0 = System.currentTimeMillis()
            usage.onSent(b)
            val r = m.complete("Tu es un ingénieur de course. Réponds en français, en une phrase courte.", "Dis au pilote que la radio fonctionne.")
            val ms = System.currentTimeMillis() - t0
            _testResult.value = when (r) {
                is AiResult.Text -> {
                    usage.onSuccess(b, ms)
                    "${b.label} (${m.modelId}) a répondu en $ms ms : « ${r.text.take(120)} »"
                }
                is AiResult.Failure -> {
                    usage.onFailure(b, r, ms)
                    "${b.label} : ${r.kind.label}${r.detail?.let { " ($it)" } ?: ""} après $ms ms."
                }
            }
            if (r is AiResult.Failure && (r.kind == FailureKind.AUTH || r.kind == FailureKind.MODEL_UNAVAILABLE)) {
                _status.value = _status.value.copy(link = Link.ERROR, detail = r.kind.label + (r.detail?.let { " : $it" } ?: ""))
            }
            store.usage = usage.save()
            _tick.value++
        }
    }

    private fun onConnected() {
        if (_status.value.link != Link.CONNECTED) {
            _status.value = _status.value.copy(link = Link.CONNECTED, detail = "Connecté : Gemini a répondu.")
        }
    }

    private fun debugSecretOrNull(): String? {
        if (!AppCheckSetup.USES_DEBUG_SECRET) return null
        val ours = store.debugSecret()
        return app?.let { AppCheckSetup.effectiveSecret(context, it, ours) } ?: ours
    }

    val mode: AiMode get() = router.mode

    private companion object {
        const val APP_NAME = "pitwall-ai"
        const val MAX_OUTPUT_TOKENS = 1024
    }
}
