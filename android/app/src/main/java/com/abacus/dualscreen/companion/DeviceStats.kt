package com.abacus.dualscreen.companion

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import java.io.File

/**
 * What the console will actually tell us about itself.
 *
 * This is the data layer behind the dashboard, and the reason it is written as a *capability probe*
 * rather than as a list of readings is that Android has spent a decade closing off exactly the
 * things a hardware dashboard wants. Half of what the AYN dashboard shows, it shows because it is
 * the vendor's own app with the vendor's own driver access. A sideloaded APK is not that, and a
 * dashboard that renders a plausible number where it cannot get a real one is worse than a dashboard
 * with a gap in it — a wrong temperature is read as a real temperature.
 *
 * So every field here is nullable, every source is probed rather than assumed, and [describe]
 * reports what this particular device gave up. What the dashboard draws is decided by what comes
 * back, not by what would look good.
 *
 * ## What is genuinely available to an app like this
 *
 * | Reading | Available | Why |
 * |---|---|---|
 * | RAM in use and total | yes | [ActivityManager.MemoryInfo], a public API |
 * | Battery level, current, voltage, temperature | yes | [BatteryManager], public |
 * | CPU model and core count | yes | `/proc/cpuinfo` and the runtime |
 * | CPU frequency per core | usually | `cpufreq` sysfs is world-readable on most builds |
 * | **System-wide CPU load** | **no** | `/proc/stat` stopped reporting other processes in Android 8 |
 * | GPU vendor and renderer | yes | needs a GL context; read once and cached |
 * | Thermal headroom | Android 11+ | [PowerManager.getThermalHeadroom], public |
 * | Component temperatures in °C | sometimes | `thermal_zone` sysfs, readable on some builds |
 * | Display refresh rate and modes | yes | [Display.getSupportedModes] |
 * | **Setting a system-wide refresh mode** | **no** | needs `WRITE_SECURE_SETTINGS`, an adb or system grant |
 * | **A running game's frame rate** | **no** | an app can only measure its own frames |
 * | **Fan speed and control** | **no** | vendor driver; root or AYN's own service |
 *
 * The three marked **no** are the ones worth being firm about, because they are the three most
 * visible things on the AYN dashboard and there is no supported way to reach them from here. Claiming
 * them would mean inventing numbers.
 */
object DeviceStats {

    /** One reading of everything this device was willing to answer. Nulls mean "not available". */
    data class Reading(
        val ramUsedBytes: Long?,
        val ramTotalBytes: Long?,
        val ramLow: Boolean,

        val batteryPercent: Int?,
        val batteryMicroAmps: Int?,
        val batteryMilliVolts: Int?,
        val batteryCelsius: Float?,
        val charging: Boolean,

        val cpuCores: Int,
        val cpuModel: String?,
        /** Current MHz per core, or null where cpufreq would not be read. */
        val cpuMhz: List<Int?>,

        /** Current GPU clock in MHz, where the driver exposes one. */
        val gpuMhz: Int?,

        /** Peak GPU clock, so a gauge has a scale to draw against. */
        val gpuMaxMhz: Int?,

        /**
         * Instantaneous power at the battery, in watts. Negative while discharging.
         *
         * Computed rather than read: volts times amps, from two figures the battery service already
         * reports. Which is exactly what the number on the AYN dashboard is.
         */
        val watts: Float?,

        /** 0..1, where 1 means thermal throttling is imminent. Android 11+. */
        val thermalHeadroom: Float?,
        /** Named zones in °C, where sysfs allowed it. Often empty. */
        val temperatures: Map<String, Float>,

        val refreshHz: Float?,
        val supportedHz: List<Float>,
    )

    fun read(context: Context, display: Display?): Reading {
        val memory = ActivityManager.MemoryInfo()
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(memory)
        }

        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        return Reading(
            ramUsedBytes = (memory.totalMem - memory.availMem).takeIf { memory.totalMem > 0 },
            ramTotalBytes = memory.totalMem.takeIf { it > 0 },
            ramLow = memory.lowMemory,

            batteryPercent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 },
            // Microamps. Negative while discharging on most devices, which is the sign convention
            // and not an error — the dashboard shows the magnitude and the direction separately.
            batteryMicroAmps = manager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeIf { it != Int.MIN_VALUE && it != 0 },
            batteryMilliVolts = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                ?.takeIf { it > 0 },
            // Reported in tenths of a degree.
            batteryCelsius = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                ?.takeIf { it > 0 }?.let { it / 10f },
            charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false,

            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuModel = cpuModel,
            cpuMhz = cpuFrequencies(),
            gpuMhz = gpuClock(GPU_CURRENT),
            gpuMaxMhz = gpuClock(GPU_MAX),
            watts = watts(manager, battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1),

            thermalHeadroom = thermalHeadroom(context),
            temperatures = temperatures(),

            refreshHz = display?.refreshRate,
            supportedHz = supportedRefreshRates(display),
        )
    }

    // ── the CPU ─────────────────────────────────────────────────────────────

    /** Read once. The model does not change, and parsing it per frame would be silly. */
    private val cpuModel: String? by lazy {
        runCatching {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Hardware") || it.startsWith("model name") }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Current clock per core, in MHz.
     *
     * `scaling_cur_freq` is world-readable on most builds and absent or refused on some, so each
     * core is read independently and a refusal produces a null for that core rather than an empty
     * list — a device that reports six of eight cores should show six, not nothing.
     *
     * A core that is offline reads as an error, which is correct: it has no current frequency.
     */
    private fun cpuFrequencies(): List<Int?> =
        (0 until Runtime.getRuntime().availableProcessors()).map { core ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
                    .readText()
                    .trim()
                    .toInt() / 1_000
            }.getOrNull()
        }

    // ── the GPU ─────────────────────────────────────────────────────────────

    /*
     * Adreno exposes its clock through the kgsl driver, and on most builds it is world-readable.
     *
     * There is no public Android API for a GPU clock at all — the AYN dashboard is reading the same
     * files. Several paths are tried because the node moved between kernel versions, and a device
     * that exposes none of them simply has no GPU gauge rather than a gauge showing zero.
     */
    private val GPU_CURRENT = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
        "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpuclk",
    )

    private val GPU_MAX = listOf(
        "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
        "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
    )

    private fun gpuClock(paths: List<String>): Int? {
        for (path in paths) {
            val hz = runCatching { File(path).readText().trim().toLong() }.getOrNull() ?: continue
            if (hz <= 0) continue
            // Hertz on every kernel that publishes these, so down to megahertz.
            return (hz / 1_000_000L).toInt().takeIf { it in 1..5_000 }
        }
        return null
    }

    // ── power ───────────────────────────────────────────────────────────────

    /**
     * Watts at the battery: volts times amps.
     *
     * The sign is kept. Negative means the pack is being drained, positive means it is being filled,
     * which is the convention the AYN dashboard shows and the one that makes "-1.59 W" readable at a
     * glance as "this is costing you something".
     */
    private fun watts(manager: BatteryManager?, milliVolts: Int): Float? {
        if (manager == null || milliVolts <= 0) return null

        val microAmps = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull() ?: return null

        if (microAmps == Int.MIN_VALUE || microAmps == 0) return null

        val amps = microAmps / 1_000_000f
        val volts = milliVolts / 1_000f
        val power = amps * volts

        // Some devices report current in milliamps rather than microamps, which comes out as a
        // hundreds-of-watts reading on a handheld. Scaled back rather than shown as nonsense.
        return if (kotlin.math.abs(power) > 100f) power / 1_000f else power
    }

    // ── heat ────────────────────────────────────────────────────────────────

    /**
     * How close the device is to throttling, 0 to 1.
     *
     * Not a temperature, and the dashboard should not present it as one. It is the only heat figure
     * with a public API behind it, it is what Android itself uses to decide when to slow things
     * down, and on a handheld it is arguably the more useful of the two: nobody cares that the SoC
     * is at 71°C, they care whether the next ten minutes will be slower than the last.
     */
    private fun thermalHeadroom(context: Context): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null

        return runCatching {
            // The argument is a forecast horizon in seconds; 0 means "right now".
            power.getThermalHeadroom(0).takeIf { !it.isNaN() && it >= 0f }
        }.getOrNull()
    }

    /**
     * Named thermal zones in °C, for the builds that still expose them.
     *
     * Zone names are vendor strings — `cpu-0-0-usr`, `battery`, `gpuss-0-usr` — and mapping them to
     * the labels a dashboard wants is a per-device job, which is exactly why they are returned raw
     * here rather than being guessed at. On a build that refuses sysfs this returns nothing at all,
     * and nothing at all is the honest answer.
     */
    private fun temperatures(): Map<String, Float> = runCatching {
        val zones = File("/sys/class/thermal").listFiles { file ->
            file.name.startsWith("thermal_zone")
        } ?: return emptyMap()

        buildMap {
            for (zone in zones.sortedBy { it.name }) {
                val name = runCatching { File(zone, "type").readText().trim() }.getOrNull() ?: continue
                val raw = runCatching { File(zone, "temp").readText().trim().toFloat() }.getOrNull()
                    ?: continue

                // Millidegrees on almost every device, plain degrees on a few.
                val celsius = if (raw > 1_000f) raw / 1_000f else raw
                if (celsius in 1f..150f) put(name, celsius)
            }
        }
    }.getOrDefault(emptyMap())

    // ── the display ─────────────────────────────────────────────────────────

    /**
     * The refresh rates this display can run at.
     *
     * Readable. **Choosing** one system-wide is not: that lives behind `WRITE_SECURE_SETTINGS`,
     * which is granted by adb or by being a system app, and neither applies here. What an app *can*
     * do is ask for a mode for its own window, which is honest but is not a "120Hz mode" for the
     * console and must not be labelled as one.
     */
    private fun supportedRefreshRates(display: Display?): List<Float> {
        if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()

        return runCatching {
            display.supportedModes
                .map { it.refreshRate }
                .distinctBy { Math.round(it) }
                .sorted()
        }.getOrDefault(emptyList())
    }

    // ── what this device gave up ────────────────────────────────────────────

    /**
     * A plain-language report of which sources answered on this device.
     *
     * Exists to be read off the Thor and sent back, because every "sometimes" in the table at the
     * top of this file is a question that can only be settled on the hardware. Building a dashboard
     * against guesses about what a device exposes is how you end up with three cards showing dashes.
     */
    fun describe(context: Context, display: Display?): String {
        val reading = read(context, display)
        val lines = mutableListOf<String>()

        fun line(label: String, value: String?) {
            lines += "$label: ${value ?: "not available"}"
        }

        line("Device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        line("CPU", reading.cpuModel)
        line("Cores", "${reading.cpuCores}")

        val clocks = reading.cpuMhz.count { it != null }
        line("CPU frequency", if (clocks > 0) "$clocks of ${reading.cpuCores} cores readable — ${reading.cpuMhz.filterNotNull().joinToString { "$it MHz" }}" else null)

        line("RAM", reading.ramTotalBytes?.let {
            "${reading.ramUsedBytes?.div(1 shl 20)} MB used of ${it / (1 shl 20)} MB"
        })

        line("Battery", reading.batteryPercent?.let { "$it%${if (reading.charging) ", charging" else ""}" })
        line("Battery current", reading.batteryMicroAmps?.let { "${it / 1_000} mA" })
        line("Battery voltage", reading.batteryMilliVolts?.let { "$it mV" })
        line("Battery temperature", reading.batteryCelsius?.let { "%.1f °C".format(it) })

        line("GPU clock", reading.gpuMhz?.let { "$it MHz${reading.gpuMaxMhz?.let { max -> " of $max MHz" } ?: ""}" })
        line("Power", reading.watts?.let { "%.2f W".format(it) })
        line("Thermal headroom", reading.thermalHeadroom?.let { "%.2f (1.0 = throttling)".format(it) })
        line(
            "Thermal zones",
            reading.temperatures.takeIf { it.isNotEmpty() }
                ?.entries?.joinToString { "${it.key} %.1f°C".format(it.value) }
        )

        line("Refresh rate", reading.refreshHz?.let { "%.1f Hz".format(it) })
        line("Supported modes", reading.supportedHz.takeIf { it.isNotEmpty() }?.joinToString { "%.0f Hz".format(it) })

        lines += ""
        lines += "Not reachable without root or vendor access, on any device:"
        lines += "  · system-wide CPU load (/proc/stat closed in Android 8)"
        lines += "  · a running game's frame rate"
        lines += "  · fan speed and fan control"
        lines += "  · setting a system-wide refresh mode"

        return lines.joinToString("\n")
    }
}
