package com.abacus.dualscreen.codes

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What came back from a companion when a code was sent. */
sealed class CodeResult {
    object Ok : CodeResult()
    data class Refused(val reason: String) : CodeResult()
    data class Failed(val reason: String) : CodeResult()
}

/**
 * Talks to a companion about codes, and about nothing else.
 *
 * Deliberately its own endpoints — `GET /codes` and `POST /code` — rather than more cases on the
 * existing `/action`. That separation is the whole point of the feature being optional: a mod can
 * refuse to serve these two paths and remain a completely normal dashboard companion, and a user who
 * turns codes off is not trusting a mod to filter its own action queue. Nothing here touches the
 * telemetry or action paths the dashboard uses.
 *
 * Every method checks the settings before it opens a socket. With the feature off, no request is
 * made at all — not one that is made and ignored.
 */
class CodeClient(context: Context, private val baseUrl: String, private val gameId: String) {

    private val settings = CodeSettings(context)

    /**
     * What this companion says it can do, or an empty list.
     *
     * Empty covers every case that is not an error: the feature off here, off for this game, off in
     * the mod, or a mod old enough not to know the endpoint. The screen shows the same honest
     * "nothing available" for all of them rather than inventing a reason.
     */
    fun catalogue(): List<GameCode> {
        if (!settings.visible || !settings.enabledFor(gameId)) return emptyList()

        val json = runCatching {
            val connection = open("$baseUrl/codes", "GET")
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) null
                else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return emptyList()

        return GameCode.listFrom(json)
    }

    /**
     * Run one.
     *
     * The reply is taken at its word: a companion that answers `{"ok":false,"reason":…}` has refused,
     * and that is reported as a refusal rather than as success. Anything that does not answer at all
     * is a failure. Neither is dressed up — a code that did not run must not look like one that did.
     */
    fun send(code: GameCode, value: String? = null): CodeResult {
        if (!settings.visible) return CodeResult.Refused("Game codes are switched off")
        if (!settings.enabledFor(gameId)) return CodeResult.Refused("Game codes are off for this game")

        val body = JSONObject()
            .put("code", code.id)
            .put("command", code.command)
            .apply { value?.let { put("value", it) } }
            .toString()

        return runCatching {
            val connection = open("$baseUrl/code", "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            try {
                connection.outputStream.use { it.write(body.toByteArray()) }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@runCatching CodeResult.Refused("The game refused it (HTTP ${connection.responseCode})")
                }

                val reply = runCatching {
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                }.getOrNull()

                when {
                    reply == null -> CodeResult.Ok
                    reply.optBoolean("ok", true) -> CodeResult.Ok
                    else -> CodeResult.Refused(reply.optString("reason").ifBlank { "The game refused it" })
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { CodeResult.Failed(it.message ?: "Could not reach the game") }
    }

    private fun open(url: String, method: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

    private companion object {
        const val TIMEOUT_MS = 3000
    }
}
