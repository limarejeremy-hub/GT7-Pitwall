package com.gt7pitwall.core.ai

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Choisit le modèle cloud d'une intervention du pilote et enchaîne les replis : Flash 3.8 pour une question complexe
 * (si le mode le permet), Flash-Lite sinon, puis rien (le moteur local répond). Un appel ne part que sur une vraie
 * phrase du pilote ; jamais sur un paquet UDP ou une mise à jour de l'écran.
 */
class AiRouter(
    private val providers: Map<AiBackend, AiProvider>,
    val usage: AiUsage = AiUsage(),
    @Volatile var mode: AiMode = AiMode.AUTO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Délai total accordé au cloud pour une intervention : au-delà, le moteur local répond. */
    private val totalTimeoutMs: Long = 12_000L,
) {
    /** Un essai : modèle, issue, délai. */
    data class Attempt(val backend: AiBackend, val modelId: String, val failure: AiResult.Failure?, val latencyMs: Long)

    /** Résultat : texte du modèle qui a répondu (ou null), et tous les essais pour le journal et l'écran. */
    data class Outcome(val text: String?, val backend: AiBackend?, val modelId: String?, val attempts: List<Attempt>, val skipped: List<String>) {
        val failure: AiResult.Failure? get() = attempts.lastOrNull()?.failure
    }

    fun provider(b: AiBackend): AiProvider? = providers[b]

    /** Modèles qu'on interrogerait maintenant, dans l'ordre (configurés, pas en pause, sous le plafond saisi). */
    fun candidates(complex: Boolean): List<AiProvider> =
        mode.cloudOrder(complex).mapNotNull { providers[it] }.filter { it.ready && usage.canSend(it.backend) }

    /** Au moins un modèle cloud peut répondre maintenant. */
    fun available(complex: Boolean = false): Boolean = candidates(complex).isNotEmpty()

    suspend fun complete(systemInstruction: String, prompt: String, complex: Boolean): Outcome {
        val attempts = mutableListOf<Attempt>()
        val skipped = mode.cloudOrder(complex).mapNotNull { b ->
            val p = providers[b]
            when {
                p == null || !p.ready -> "${b.label} : ${p?.status ?: "non configuré"}"
                !usage.canSend(b) -> "${b.label} : ${usage.blockedReason(b)}"
                else -> null
            }
        }
        val started = clock()
        for (p in candidates(complex)) {
            val left = totalTimeoutMs - (clock() - started)
            if (left < MIN_ATTEMPT_MS) break
            val t0 = clock()
            usage.onSent(p.backend)
            val result = withTimeoutOrNull(left) {
                try {
                    p.complete(systemInstruction, prompt)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AiErrors.classify(e.javaClass.name, e.message)
                }
            } ?: AiResult.Failure(FailureKind.TIMEOUT)
            val ms = clock() - t0
            val text = (result as? AiResult.Text)?.text?.trim()?.takeIf { it.isNotEmpty() }
            if (text != null) {
                usage.onSuccess(p.backend, ms)
                attempts += Attempt(p.backend, p.modelId, null, ms)
                return Outcome(text, p.backend, p.modelId, attempts, skipped)
            }
            val failure = result as? AiResult.Failure ?: AiResult.Failure(FailureKind.EMPTY)
            usage.onFailure(p.backend, failure, ms)
            attempts += Attempt(p.backend, p.modelId, failure, ms)
            // Échec lent (réseau, délai) : on ne fait pas attendre le pilote une seconde fois.
            if (!failure.kind.fast) break
        }
        return Outcome(null, null, null, attempts, skipped)
    }

    private companion object {
        const val MIN_ATTEMPT_MS = 1_500L
    }
}
