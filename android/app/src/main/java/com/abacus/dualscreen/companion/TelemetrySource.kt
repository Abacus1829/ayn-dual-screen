package com.abacus.dualscreen.companion

import android.os.Handler
import android.os.Looper
import com.abacus.dualscreen.Probe
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Polls a companion for what is happening, and tells whoever is listening.
 *
 * Polling rather than a socket because that is what the companions serve: an HTTP `/state` endpoint
 * the second screen's own page already reads several times a second. Adding a push protocol would
 * mean changing four mods for something the existing one does adequately.
 *
 * Every screen that wants live data uses this rather than writing its own request, which is what
 * keeps the dashboard, the session screen and the profile list agreeing about whether a companion is
 * there.
 */
class TelemetrySource(
    private val baseUrl: String,
    /** How often to ask. Slow on purpose: a dashboard is read, not played. */
    private val intervalMs: Long = 2_000L,
    private val onUpdate: (Telemetry) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return

            Thread {
                val reading = read()
                main.post { if (running) onUpdate(reading) }
            }.start()

            main.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running || baseUrl.isBlank()) return
        running = true
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    /**
     * One reading.
     *
     * Blocking; called on its own thread. A failure is a reading too — [Telemetry.NOTHING] means the
     * companion is not answering, which is a fact the dashboard wants rather than an error it should
     * be shielded from.
     */
    private fun read(): Telemetry {
        val json = runCatching {
            val connection = (URL("$baseUrl/state").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }

            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) null
                else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return Telemetry.NOTHING

        // The identification rules already live in Probe, which every other screen uses. Repeating
        // them here would be a second answer to "which game is this" that could disagree.
        val named = json.optString("game").takeIf { it.isNotBlank() }

        return Telemetry(
            reachable = true,
            gameId = named ?: Probe.identifyFromState(json)?.id,
            inGame = json.optBoolean("ready", false),
            place = listOf("worldName", "cell", "locationName")
                .firstNotNullOfOrNull { json.optString(it).takeIf { name -> name.isNotBlank() } },
            raw = json,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 2500
    }
}
