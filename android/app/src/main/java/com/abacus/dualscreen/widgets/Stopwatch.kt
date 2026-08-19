package com.abacus.dualscreen.widgets

import android.content.Context
import android.os.SystemClock

/**
 * A stopwatch and a countdown, kept where the screen showing them is not.
 *
 * State lives in preferences and time is measured against [SystemClock.elapsedRealtime], so the
 * reading is correct after the screen closes, after the app is killed, and after the device sleeps.
 * A counter that ticks a variable every second would have lost all three, which is the difference
 * between a timer and a decoration.
 *
 * elapsedRealtime rather than currentTimeMillis on purpose: the wall clock can be moved, by the user
 * or by the network, and a stopwatch that jumps an hour because a time zone updated is worthless.
 */
class Stopwatch(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Elapsed milliseconds banked before the current run. */
    private var banked: Long
        get() = prefs.getLong(KEY_BANKED, 0L)
        set(value) = prefs.edit().putLong(KEY_BANKED, value).apply()

    /** When the current run started, or 0 when stopped. */
    private var since: Long
        get() = prefs.getLong(KEY_SINCE, 0L)
        set(value) = prefs.edit().putLong(KEY_SINCE, value).apply()

    val running: Boolean get() = since > 0L

    /** Total elapsed time right now. */
    fun elapsed(): Long =
        banked + if (running) (SystemClock.elapsedRealtime() - since).coerceAtLeast(0L) else 0L

    fun start() {
        if (running) return
        since = SystemClock.elapsedRealtime()
    }

    fun stop() {
        if (!running) return
        banked = elapsed()
        since = 0L
    }

    fun toggle() = if (running) stop() else start()

    fun reset() {
        prefs.edit().remove(KEY_BANKED).remove(KEY_SINCE).remove(KEY_TARGET).apply()
    }

    // ── countdown ───────────────────────────────────────────────────────────

    /**
     * When the countdown ends, or 0 for none.
     *
     * Shares the running flag with the stopwatch deliberately: they are the same clock read two ways,
     * and two independent timers on one screen is a needless second thing to explain.
     */
    var target: Long
        get() = prefs.getLong(KEY_TARGET, 0L)
        set(value) = prefs.edit().putLong(KEY_TARGET, value).apply()

    fun startCountdown(millis: Long) {
        target = millis.coerceAtLeast(0L)
        banked = 0L
        since = SystemClock.elapsedRealtime()
    }

    /** Milliseconds left, or null when no countdown is set. Negative once it has run out. */
    fun remaining(): Long? {
        val goal = target
        if (goal <= 0L) return null
        return goal - elapsed()
    }

    val finished: Boolean get() = remaining()?.let { it <= 0L } == true

    companion object {
        private const val PREFS = "stopwatch"
        private const val KEY_BANKED = "banked"
        private const val KEY_SINCE = "since"
        private const val KEY_TARGET = "target"

        /** `h:mm:ss.t` while it matters, `mm:ss.t` while it does not. */
        fun format(millis: Long): String {
            val ms = millis.coerceAtLeast(0L)
            val tenths = (ms / 100) % 10
            val seconds = (ms / 1000) % 60
            val minutes = (ms / 60_000) % 60
            val hours = ms / 3_600_000

            return if (hours > 0) "%d:%02d:%02d.%d".format(hours, minutes, seconds, tenths)
            else "%02d:%02d.%d".format(minutes, seconds, tenths)
        }
    }
}
