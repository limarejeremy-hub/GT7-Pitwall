package com.gt7pitwall.core.ai.chatgpt

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Issue #67 / revue #69 : écoute du retour de connexion sur 127.0.0.1 (documentation « Sign in » : écouter AVANT d'ouvrir
 * le navigateur, seul le port peut varier). Code Java pur, testé sur PC avec de vraies sockets.
 *
 * - [close] (annulation, nouvelle tentative) débloque immédiatement [await] : la socket d'écoute est fermée, jamais
 *   laissée ouverte derrière une coroutine annulée.
 * - Chaque connexion du navigateur a son propre délai de lecture : une connexion muette ne bloque pas l'attente.
 * - Délai total borné ; les requêtes annexes (icône…) reçoivent un 404 sans interrompre l'attente.
 */
class LoopbackReceiver private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort

    @Volatile
    var closed = false
        private set

    /** Résultat de l'attente : retour reçu, annulation, ou délai dépassé. */
    sealed interface Outcome {
        data class Received(val callback: SiwcAuth.Callback) : Outcome
        data object Cancelled : Outcome
        data object TimedOut : Outcome
    }

    fun await(a: SiwcAuth.Attempt, totalTimeoutMs: Long, clientReadTimeoutMs: Int = CLIENT_READ_TIMEOUT_MS, nowMs: () -> Long = System::currentTimeMillis): Outcome {
        val deadline = nowMs() + totalTimeoutMs
        try {
            while (!closed) {
                val left = deadline - nowMs()
                if (left <= 0) return Outcome.TimedOut
                server.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                val sock = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    return Outcome.TimedOut
                }
                sock.use { s ->
                    s.soTimeout = clientReadTimeoutMs
                    val line = try {
                        s.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
                    } catch (e: SocketTimeoutException) {
                        "" // Connexion muette : ignorée, on continue d'attendre le vrai retour.
                    }
                    val target = line.split(' ').getOrNull(1).orEmpty()
                    val out = s.getOutputStream()
                    if (!line.startsWith("GET ") || target.substringBefore('?') != SiwcAuth.CALLBACK_PATH) {
                        runCatching { out.write(NOT_FOUND.toByteArray()); out.flush() }
                        return@use
                    }
                    val cb = SiwcAuth.parseCallback(target, a)
                    runCatching {
                        val bytes = page(cb).toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(bytes)
                        out.flush()
                    }
                    return Outcome.Received(cb)
                }
            }
            return Outcome.Cancelled
        } catch (e: SocketException) {
            if (closed) return Outcome.Cancelled
            throw e
        } finally {
            close()
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }

    companion object {
        const val CLIENT_READ_TIMEOUT_MS = 10_000
        private const val NOT_FOUND = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

        /** Port de la documentation si libre, sinon un port libre ; toujours sur 127.0.0.1 (jamais « localhost »). */
        fun open(preferredPort: Int = SiwcAuth.PREFERRED_PORT): LoopbackReceiver {
            val lo = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val s = try {
                ServerSocket(preferredPort, 4, lo)
            } catch (e: IOException) {
                ServerSocket(0, 4, lo)
            }
            return LoopbackReceiver(s)
        }

        /** Page affichée dans le navigateur : aucun code, jeton ni paramètre recopié. */
        fun page(cb: SiwcAuth.Callback): String {
            val msg = when (cb) {
                is SiwcAuth.Callback.Code -> "Connexion reçue. Tu peux revenir dans GT7 Pitwall."
                is SiwcAuth.Callback.Denied -> "Connexion refusée. Tu peux revenir dans GT7 Pitwall."
                is SiwcAuth.Callback.Invalid -> "Retour de connexion invalide. Recommence depuis GT7 Pitwall."
            }
            return "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>GT7 Pitwall</title><p style='font:18px sans-serif;margin:24px'>$msg</p>"
        }
    }
}
