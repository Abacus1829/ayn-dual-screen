package com.abacus.dualscreen.widgets

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One reading: a title, the thing itself, and a line of detail under it. */
data class Reading(val title: String, val value: String, val detail: String = "")

/**
 * The things the handheld can tell you about itself.
 *
 * Every one of these answers without a game, without a network, and without a permission the app did
 * not already hold — which is the point. The second screen was only useful while something was
 * running on a PC; this is what it can show the rest of the time.
 *
 * Each widget is an object with an id and a [read] that returns a [Reading]. Adding another is one
 * object and one line in [ALL] — no layout, no screen changes. Anything that needs a permission the
 * app does not have does not belong here; it belongs behind a button that asks first.
 */
sealed class Widget(val id: String, val label: String) {

    abstract fun read(context: Context): Reading

    /** Nothing here is expensive, but a few are worth reading less often than once a second. */
    open val everySecond: Boolean get() = false

    // ── time ────────────────────────────────────────────────────────────────

    object Clock : Widget("clock", "Time") {
        override val everySecond get() = true

        override fun read(context: Context): Reading {
            val now = Date()
            return Reading(
                title = label,
                value = TIME.format(now),
                detail = DATE.format(now),
            )
        }
    }

    // ── power ───────────────────────────────────────────────────────────────

    /**
     * Battery, read from the sticky broadcast rather than BatteryManager.
     *
     * The sticky intent carries the level, the charging state and the plug type in one read and works
     * on every version this app supports; BatteryManager's property API is tidier and tells you less.
     * Registering a null receiver is the documented way to read a sticky broadcast without
     * subscribing to it.
     */
    object Battery : Widget("battery", "Battery") {
        override fun read(context: Context): Reading {
            val status = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull() ?: return Reading(label, "—")

            val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1

            val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val charging = plugged != 0

            val temperature = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val degrees = if (temperature > 0) " · ${temperature / 10}°C" else ""

            val how = when {
                !charging -> "On battery"
                plugged == BatteryManager.BATTERY_PLUGGED_USB -> "Charging over USB"
                plugged == BatteryManager.BATTERY_PLUGGED_AC -> "Charging"
                else -> "Charging"
            }

            return Reading(
                title = label,
                value = if (percent >= 0) "$percent%" else "—",
                detail = how + degrees,
            )
        }
    }

    // ── network ─────────────────────────────────────────────────────────────

    /**
     * What this device is on, and what address it has there.
     *
     * Needs only ACCESS_NETWORK_STATE, which the app already holds for the connection screen. The
     * address comes from the interface list rather than WifiManager, which keeps it free of the
     * location permission that reading Wi-Fi details now requires — and it covers Ethernet docks,
     * which the Wi-Fi API does not.
     */
    object Network : Widget("network", "Network") {
        override fun read(context: Context): Reading {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val addresses = com.abacus.dualscreen.FtpServer.localAddresses()

            val kind = runCatching {
                val network = manager?.activeNetwork ?: return@runCatching "Offline"
                val caps = manager.getNetworkCapabilities(network) ?: return@runCatching "Offline"

                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                    else -> "Connected"
                }
            }.getOrDefault("Unknown")

            return Reading(
                title = label,
                value = kind,
                detail = addresses.firstOrNull() ?: "No address",
            )
        }
    }

    // ── storage and memory ──────────────────────────────────────────────────

    object Storage : Widget("storage", "Storage") {
        override fun read(context: Context): Reading {
            val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }
                .getOrNull() ?: return Reading(label, "—")

            val free = stat.availableBlocksLong * stat.blockSizeLong
            val total = stat.blockCountLong * stat.blockSizeLong
            val used = total - free

            return Reading(
                title = label,
                value = "${gib(free)} free",
                detail = "${gib(used)} of ${gib(total)} used",
            )
        }

        private fun gib(bytes: Long) = "%.1f GiB".format(bytes.toDouble() / (1L shl 30))
    }

    object Memory : Widget("memory", "Memory") {
        override fun read(context: Context): Reading {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return Reading(label, "—")

            val info = ActivityManager.MemoryInfo()
            runCatching { manager.getMemoryInfo(info) }

            val free = info.availMem
            val total = info.totalMem

            return Reading(
                title = label,
                value = "${mib(free)} free",
                detail = "${mib(total)} total" + if (info.lowMemory) " · running low" else "",
            )
        }

        private fun mib(bytes: Long) = "%,d MiB".format(bytes / (1L shl 20))
    }

    // ── the device ──────────────────────────────────────────────────────────

    object Device : Widget("device", "Device") {
        override fun read(context: Context): Reading = Reading(
            title = label,
            value = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Unknown",
            detail = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
        )
    }

    companion object {
        /** Every widget, in the order they read best on a wide panel. */
        val ALL: List<Widget> = listOf(Clock, Battery, Network, Storage, Memory, Device)

        fun byId(id: String?): Widget? = ALL.firstOrNull { it.id == id }

        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private val DATE = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
    }
}
