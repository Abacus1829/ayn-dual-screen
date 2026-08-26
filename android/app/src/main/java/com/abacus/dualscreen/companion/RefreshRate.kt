package com.abacus.dualscreen.companion

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import kotlin.math.roundToInt

/**
 * The console's refresh rate: what it is, what it could be, and whether we may change it.
 *
 * This is the honest version of the "60 FPS MODE" pill on AYN's own dashboard. It is *not* a frame
 * limiter and it is not a game setting — it is the panel's refresh rate, which is the thing that pill
 * is actually reporting.
 *
 * ## Why this one is reachable when the fan is not
 *
 * Android keeps two settings stores. `Settings.Secure` needs a permission only adb or a system app
 * can hand out. `Settings.System` needs **WRITE_SETTINGS**, which is a special permission but a
 * *user-grantable* one — there is a system screen for it, and this app already asks for it, because
 * the brightness slider needs the same thing.
 *
 * The refresh keys live in `Settings.System`. So the same grant that lets the brightness slider work
 * lets this work, and no adb, root or vendor access is involved anywhere.
 *
 * ## Why every write is read back
 *
 * `peak_refresh_rate` and `min_refresh_rate` are real keys that real devices honour, and they are not
 * a documented public API. A device is free to ignore them, clamp them, or accept the write and carry
 * on at whatever rate it likes. Writing and assuming would produce a toggle that flips, reports
 * success, and changes nothing — which is worse than no toggle.
 *
 * So [apply] writes, reads back, and returns what the device actually settled on. The caller shows
 * that, not what was asked for.
 */
object RefreshRate {

    private const val TAG = "AynTheme"

    /** Hidden but long-standing keys. Both are needed: a peak alone leaves the floor free to drop. */
    private const val PEAK = "peak_refresh_rate"
    private const val MIN = "min_refresh_rate"

    /** Whether this device has more than one rate to choose between. */
    fun modes(display: Display?): List<Int> {
        if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()

        return runCatching {
            display.supportedModes
                .map { it.refreshRate.roundToInt() }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }

    /** Whether the app may write system settings, which is the only thing standing in the way. */
    fun permitted(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /**
     * The rate the panel is running at now.
     *
     * Taken from the display rather than from the setting, because the display is the truth and the
     * setting is only a request. A device that ignored the write reports its real rate here, which is
     * exactly the disagreement worth surfacing.
     */
    fun current(display: Display?): Int? =
        display?.refreshRate?.roundToInt()?.takeIf { it > 0 }

    /** What was last asked for, which may differ from what is happening. */
    fun requested(context: Context): Int? = runCatching {
        Settings.System.getFloat(context.contentResolver, PEAK).roundToInt().takeIf { it > 0 }
    }.getOrNull()

    /**
     * Ask the panel to run at [hz], and report what it actually does.
     *
     * Returns null when the write itself was refused — no permission, or a device with the keys
     * missing entirely. Otherwise returns the rate in force afterwards, which the caller should
     * believe over the one it asked for.
     */
    fun apply(context: Context, display: Display?, hz: Int): Int? {
        if (!permitted(context)) {
            Log.d(TAG, "refresh: no WRITE_SETTINGS")
            return null
        }

        val wrote = runCatching {
            // Both ends. Setting only the peak leaves the device free to drop to its minimum
            // whenever it feels like it, which on a variable-rate panel is most of the time — and
            // then a "120" button produces 60 and looks broken.
            Settings.System.putFloat(context.contentResolver, PEAK, hz.toFloat()) &&
                Settings.System.putFloat(context.contentResolver, MIN, hz.toFloat())
        }.getOrDefault(false)

        if (!wrote) {
            Log.w(TAG, "refresh: the device refused the write")
            return null
        }

        // The panel does not necessarily change on the same frame the setting does.
        return current(display)
    }

    /**
     * A plain-language account, for the developer report.
     *
     * Refresh rate is the one dashboard control that can fail in a way that looks like success, so
     * it is worth being able to read the whole picture off the device rather than inferring it.
     */
    fun describe(context: Context, display: Display?): String {
        val modes = modes(display)
        val lines = mutableListOf<String>()

        lines += "modes: " + if (modes.isEmpty()) "not reported" else modes.joinToString { "$it Hz" }
        lines += "running at: " + (current(display)?.let { "$it Hz" } ?: "not reported")
        lines += "last requested: " + (requested(context)?.let { "$it Hz" } ?: "never set, or key absent")
        lines += "may write settings: " + if (permitted(context)) "yes" else "NO — grant it in Settings"

        if (modes.size <= 1) lines += "note: one mode only, so there is nothing to switch between"

        return lines.joinToString("\n")
    }
}
