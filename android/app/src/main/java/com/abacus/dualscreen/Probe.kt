package com.abacus.dualscreen

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Why a connection didn't happen. Each case has a different fix, so they're kept apart. */
enum class Failure {
    /** The address doesn't resolve — usually a typo, or a hostname the Thor can't look up. */
    UNKNOWN_HOST,

    /**
     * Something is at that address but nothing is listening on the port.
     *
     * Almost always the mod running without LAN access: it binds loopback only, so the game is up and
     * the port is closed to everything except the PC itself.
     */
    REFUSED,

    /** No answer at all. Typically a firewall dropping the packets, or the wrong subnet. */
    TIMEOUT,

    OTHER
}

/**
 * What a connection check found at an address.
 *
 * [game] is null when something answered but didn't look like either mod, which is a different
 * problem from nothing answering at all and worth telling the user apart.
 */
data class ProbeResult(
    val reachable: Boolean,
    val game: Game?,
    /** True once the player is actually in a world/save, rather than sitting at the main menu. */
    val inGame: Boolean,
    /** The world or farm name, when one is loaded. */
    val place: String?,
    val failure: Failure?,
    val detail: String?
)

/**
 * Works out what's listening at an address, without the caller having to say what it expects.
 *
 * Deliberately does no work on the main thread — every method here blocks. Call from a background
 * thread and post the result back.
 */
object Probe {

    private const val TIMEOUT_MS = 3000

    fun run(baseUrl: String): ProbeResult {
        // /state first: if a world is loaded it identifies the game and names the place in one call
        val state = runCatching { fetchJson("$baseUrl/state") }
        val json = state.getOrNull()

        if (json != null) {
            val identified = identify(json)
            if (identified != null) {
                return ProbeResult(
                    reachable = true,
                    game = identified.first,
                    inGame = true,
                    place = identified.second,
                    failure = null,
                    detail = null
                )
            }
        }

        // reachable but nothing loaded yet
        if (json != null) {
            // a mod that names itself is still identifiable at the main menu, and says so precisely
            named(json)?.let {
                return ProbeResult(true, it, inGame = false, place = null, failure = null, detail = null)
            }

            // otherwise fall back to which map endpoint exists. Only the mods, since CUSTOM has no path
            // to probe and appending a null would just request a nonsense URL.
            for (game in Game.detectable) {
                if (runCatching { status("$baseUrl${game.probePath}") }.getOrNull() == HttpURLConnection.HTTP_OK)
                    return ProbeResult(true, game, inGame = false, place = null, failure = null, detail = null)
            }
            return ProbeResult(true, game = null, inGame = false, place = null, failure = null, detail = null)
        }

        // nothing usable at /state — find out whether anything is there at all, and why not
        val root = runCatching { status(baseUrl) }
        root.getOrNull()?.let { code ->
            return ProbeResult(true, game = null, inGame = false, place = null, failure = null, detail = "HTTP $code")
        }

        val error = root.exceptionOrNull() ?: state.exceptionOrNull()
        return ProbeResult(
            reachable = false,
            game = null,
            inGame = false,
            place = null,
            failure = classify(error),
            detail = error?.message
        )
    }

    /**
     * Map the exception onto something actionable.
     *
     * Android reports a refused connection as a plain [ConnectException] whose message mentions
     * ECONNREFUSED, so the message is checked as well as the type.
     */
    private fun classify(error: Throwable?): Failure = when {
        error is UnknownHostException -> Failure.UNKNOWN_HOST
        error is SocketTimeoutException -> Failure.TIMEOUT
        error is ConnectException ->
            if (error.message?.contains("refused", ignoreCase = true) == true) Failure.REFUSED
            else Failure.TIMEOUT
        error is IOException && error.message?.contains("ECONNREFUSED", ignoreCase = true) == true -> Failure.REFUSED
        else -> Failure.OTHER
    }

    /**
     * Tell the two games apart by fields only one of them sends.
     *
     * Terraria's snapshot has a world name and a biome; Stardew's has a season and a location. Keyed
     * off the payload rather than the port, because both mods default to the same port.
     */
    /**
     * The mod behind a payload, whether or not a world is loaded.
     *
     * The Minecraft mod names itself in a "game" field and keeps doing so at the main menu, which is the
     * only way to tell three mods apart reliably. The older two are recognised by fields they happen to
     * have, so they keep working without a new build.
     */
    private fun named(state: JSONObject): Game? {
        val id = state.optString("game").takeIf { it.isNotBlank() } ?: return null

        // matched without case because the mods spell their own name the way their codebase does:
        // Minecraft sends "minecraft", the New Vegas plugin sends "FalloutNV"
        return Game.entries.firstOrNull { it.id.equals(id, ignoreCase = true) && it.isMod }
    }

    private fun identify(state: JSONObject): Pair<Game, String?>? {
        if (!state.optBoolean("ready", false))
            return null

        named(state)?.let { return it to placeName(state) }

        if (state.has("worldName"))
            return Game.TERRARIA to state.optString("worldName").takeIf { it.isNotBlank() }

        if (state.has("season") || state.has("locationName"))
            return Game.STARDEW to state.optString("locationName").takeIf { it.isNotBlank() }

        return null
    }

    /**
     * Whatever this mod calls the place you're standing in.
     *
     * Each one names it differently — a world, a farm, a cell — so they're tried in turn rather than
     * the app insisting on one field name. A blank result is fine: it only ever decorates the
     * connection screen.
     */
    private fun placeName(state: JSONObject): String? =
        listOf("worldName", "cell", "locationName")
            .firstNotNullOfOrNull { state.optString(it).takeIf { name -> name.isNotBlank() } }

    /** Throws rather than swallowing, so the caller can tell a refusal from a timeout. */
    private fun fetchJson(url: String): JSONObject? {
        val connection = open(url)
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun status(url: String): Int {
        val connection = open(url)
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
}
