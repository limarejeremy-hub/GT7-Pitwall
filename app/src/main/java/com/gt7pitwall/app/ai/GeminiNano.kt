package com.gt7pitwall.app.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.gt7pitwall.core.ai.LanguageModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AiState { OFF, CHECKING, UNAVAILABLE, DOWNLOADING, READY, ERROR }

/**
 * Gemini Nano sur le téléphone, via ML Kit GenAI (Prompt API) et AICore : gratuit, hors ligne, aucune donnée
 * envoyée sur Internet. Disponible seulement sur les appareils compatibles (Galaxy Z Fold récents, Pixel…).
 */
class GeminiNano : LanguageModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var model: GenerativeModel? = null
    private var prepareJob: Job? = null

    private val _state = MutableStateFlow(AiState.OFF)
    val state: StateFlow<AiState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<String?>(null)
    /** Précision affichée (progression du téléchargement, erreur). */
    val detail: StateFlow<String?> = _detail.asStateFlow()

    override val ready: Boolean get() = _state.value == AiState.READY

    override val status: String
        get() = when (_state.value) {
            AiState.OFF -> "désactivé"
            AiState.CHECKING -> "vérification en cours"
            AiState.DOWNLOADING -> "téléchargement du modèle en cours"
            AiState.READY -> "prêt"
            AiState.UNAVAILABLE -> "indisponible sur cet appareil"
            AiState.ERROR -> "erreur ${_detail.value ?: ""}".trim()
        }

    private val _testResult = MutableStateFlow<String?>(null)
    /** Résultat du bouton « Tester Gemini ». */
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    /** Question de test, sans télémétrie : prouve que le modèle tourne et mesure son temps de réponse. */
    fun selfTest() {
        scope.launch {
            if (!ready) {
                _testResult.value = "Pas prêt : $status. Réactive l'interrupteur pour revérifier."
                return@launch
            }
            _testResult.value = "Test en cours…"
            val started = System.currentTimeMillis()
            val text = generate(
                "Tu es un ingénieur de course. Réponds en français, en une phrase courte.",
                "Dis au pilote que la radio fonctionne.",
            )
            val ms = System.currentTimeMillis() - started
            _testResult.value = if (text.isNullOrBlank()) {
                "Aucune réponse après $ms ms. ${_detail.value ?: ""}".trim()
            } else {
                "Réponse en $ms ms : « ${text.trim()} »"
            }
        }
    }

    /** Vérifie la disponibilité et télécharge le modèle si AICore le propose. */
    fun enable() {
        if (prepareJob?.isActive == true || ready) return
        _testResult.value = null
        prepareJob = scope.launch {
            _state.value = AiState.CHECKING
            _detail.value = null
            try {
                val m = model ?: Generation.getClient(generationConfig {}).also { model = it }
                when (val status = m.checkStatus()) {
                    FeatureStatus.AVAILABLE -> _state.value = AiState.READY
                    FeatureStatus.UNAVAILABLE -> {
                        _state.value = AiState.UNAVAILABLE
                        _detail.value = "AICore répond « indisponible » (code $status). Vérifie que les fonctions IA de " +
                            "Samsung / Google (AICore) sont activées et à jour dans le Play Store, puis réessaie."
                    }
                    else -> download(m)
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun disable() {
        prepareJob?.cancel()
        prepareJob = null
        model?.close()
        model = null
        _state.value = AiState.OFF
        _detail.value = null
    }

    private suspend fun download(m: GenerativeModel) {
        _state.value = AiState.DOWNLOADING
        try {
            var total = 0L
            m.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> total = status.bytesToDownload
                    is DownloadStatus.DownloadProgress -> if (total > 0) {
                        _detail.value = "Téléchargement du modèle : ${status.totalBytesDownloaded * 100 / total} %"
                    }
                    is DownloadStatus.DownloadFailed -> throw status.e
                    is DownloadStatus.DownloadCompleted -> _detail.value = null
                }
            }
        } catch (t: Throwable) {
            // Erreur connue des versions bêta à la fin du téléchargement : on revérifie l'état réel.
            if (m.checkStatus() != FeatureStatus.AVAILABLE) throw t
        }
        _state.value = if (m.checkStatus() == FeatureStatus.AVAILABLE) AiState.READY else AiState.UNAVAILABLE
    }

    private fun fail(t: Throwable) {
        _state.value = AiState.ERROR
        _detail.value = t.message ?: t.javaClass.simpleName
    }

    override suspend fun generate(systemInstruction: String, prompt: String): String? {
        val m = model?.takeIf { ready } ?: return null
        // Une requête à la fois : le modèle sur l'appareil ne les traite pas en parallèle.
        return lock.withLock {
            try {
                val request = generateContentRequest(SystemInstruction(systemInstruction), TextPart(prompt)) {
                    temperature = 0.2f
                    topK = 10
                    maxOutputTokens = 120
                }
                m.generateContent(request).candidates.firstOrNull()?.text
            } catch (t: Throwable) {
                _detail.value = t.message ?: t.javaClass.simpleName
                null
            }
        }
    }
}
