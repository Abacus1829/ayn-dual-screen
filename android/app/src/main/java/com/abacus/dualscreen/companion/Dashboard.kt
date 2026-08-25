package com.abacus.dualscreen.companion

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.abacus.dualscreen.R
import com.abacus.dualscreen.Settings
import com.abacus.dualscreen.ui.Motion
import com.abacus.dualscreen.ui.Ui

/**
 * The console dashboard, built to the shape of the one AYN ships.
 *
 * The reference is a grid of translucent rounded cards on a dark ground: a headline card at the top
 * left, a pair of small stat cards under it, and a row of four ring gauges beside them. This builds
 * that arrangement out of the readings the device will actually give up.
 *
 * ## Which tiles exist, and why some do not
 *
 * The AYN dashboard is the vendor's own app talking to the vendor's own driver. Four things on it
 * are simply out of reach of a sideloaded APK, and they are left out rather than mocked up:
 *
 * - **Current FPS.** An app can measure its own frames and nothing else. A number labelled FPS that
 *   is really this app's own refresh rate would be a lie told in large type.
 * - **Fan speed and control.** The fan is on a vendor driver.
 * - **The eight quick toggles** — High Performance, Smart Mode, Ambient LED and the rest. Each one is
 *   a vendor call.
 * - **FPS mode.** The refresh rate can be *read*; choosing one system-wide needs a permission held
 *   by system apps.
 *
 * What is left is real: CPU clock, GPU clock, power draw, memory, temperature and refresh rate. Six
 * genuine readings arranged like the reference beats ten where four are invented.
 *
 * ## Colour
 *
 * The ring hues are taken from the reference — magenta, cyan, amber, green — and are deliberately
 * *not* the user's accent. They are how the four rings are told apart at a glance, which is a job
 * the accent cannot do when the accent is one colour.
 */
object Dashboard {

    /** The reference's ring colours, in its order: CPU, GPU, PWR, RAM. */
    private const val CPU_HUE = 0xFFFF3D9A.toInt()
    private const val GPU_HUE = 0xFF35E4E0.toInt()
    private const val PWR_HUE = 0xFFFFA23A.toInt()
    private const val RAM_HUE = 0xFF57E08A.toInt()

    /** Card fill: a light wash at low alpha, which is what gives the reference its glassy look. */
    private const val CARD_FILL = 0x2E8FA8D8
    private const val CARD_RADIUS_DP = 18

    /**
     * Build the dashboard into [host].
     *
     * Views are created once and then updated in place, so the gauges can animate between readings.
     * Rebuilding them every poll would restart every sweep from zero twice a second, which looks
     * broken and is the most obvious way to get this wrong.
     */
    fun build(activity: Activity, settings: Settings, host: LinearLayout, reading: DeviceStats.Reading) {
        val existing = host.getTag(R.id.tag_base_size) as? Views

        val views = existing ?: create(activity, settings, host).also {
            host.setTag(R.id.tag_base_size, it)
        }

        update(activity, views, reading, animate = existing != null)
    }

    /** The pieces that get updated, held so the second poll does not rebuild the first one's views. */
    private class Views(
        val root: LinearLayout,
        val temperature: TextView,
        val refresh: TextView,
        val cpu: RingGauge,
        val gpu: RingGauge,
        val power: RingGauge,
        val memory: RingGauge,
    )

    private fun create(activity: Activity, settings: Settings, host: LinearLayout): Views {
        host.removeAllViews()
        host.orientation = LinearLayout.VERTICAL
        host.visibility = View.VISIBLE

        // ── the stat pair, and the ring row beside it ───────────────────────
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(activity, 8) }
        }

        val stats = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f)
        }

        val temperature = statCard(activity, activity.getString(R.string.dash_temp), "🌡")
        val refresh = statCard(activity, activity.getString(R.string.dash_display), "◱")

        stats.addView(temperature.first)
        stats.addView(refresh.first.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(activity, 8)
        })

        val rings = card(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f)
                .apply { marginStart = Ui.dp(activity, 8) }
            setPadding(Ui.dp(activity, 10), Ui.dp(activity, 12), Ui.dp(activity, 10), Ui.dp(activity, 12))
        }

        val cpu = gauge(activity, rings, "CPU", "GHz", CPU_HUE, decimals = 2)
        val gpu = gauge(activity, rings, "GPU", "MHz", GPU_HUE, decimals = 0)
        val power = gauge(activity, rings, "PWR", "W", PWR_HUE, decimals = 2)
        val memory = gauge(activity, rings, "RAM", "GB", RAM_HUE, decimals = 2)

        row.addView(stats)
        row.addView(rings)
        host.addView(row)

        return Views(host, temperature.second, refresh.second, cpu, gpu, power, memory)
    }

    private fun update(activity: Activity, views: Views, reading: DeviceStats.Reading, animate: Boolean) {
        // ── CPU: the fastest core, against the fastest clock seen so far ────
        val clocks = reading.cpuMhz.filterNotNull()
        val peak = clocks.maxOrNull()
        if (peak != null) {
            /*
             * The ceiling is remembered rather than read.
             *
             * `cpuinfo_max_freq` is refused on a fair number of builds, and a gauge with no ceiling
             * has no arc. Watching the highest clock actually observed gives a scale that is correct
             * within seconds of the device doing anything, and never wrong in a way that matters:
             * the worst case is a ring that reads a little full early on.
             */
            observedCpuPeak = maxOf(observedCpuPeak, peak)
            views.cpu.set(peak / 1_000f, peak.toFloat() / observedCpuPeak, animate)
            views.cpu.visibility = View.VISIBLE
        } else {
            views.cpu.visibility = View.GONE
        }

        // ── GPU ─────────────────────────────────────────────────────────────
        val gpuMhz = reading.gpuMhz
        if (gpuMhz != null) {
            val ceiling = reading.gpuMaxMhz?.takeIf { it > 0 }
                ?: maxOf(observedGpuPeak, gpuMhz).also { observedGpuPeak = it }
            views.gpu.set(gpuMhz.toFloat(), gpuMhz.toFloat() / ceiling, animate)
            views.gpu.visibility = View.VISIBLE
        } else {
            views.gpu.visibility = View.GONE
        }

        // ── power ───────────────────────────────────────────────────────────
        val watts = reading.watts
        if (watts != null) {
            // Fifteen watts is about as hard as a handheld of this class pulls, and the arc is drawn
            // against the magnitude because a negative arc is not a thing anybody can read.
            views.power.set(watts, kotlin.math.abs(watts) / 15f, animate)
            views.power.visibility = View.VISIBLE
        } else {
            views.power.visibility = View.GONE
        }

        // ── memory ──────────────────────────────────────────────────────────
        val used = reading.ramUsedBytes
        val total = reading.ramTotalBytes
        if (used != null && total != null && total > 0) {
            views.memory.set(used / GIGABYTE, used.toFloat() / total, animate)
            views.memory.visibility = View.VISIBLE
        } else {
            views.memory.visibility = View.GONE
        }

        // ── the two stat cards ──────────────────────────────────────────────
        val hottest = reading.temperatures.maxByOrNull { it.value }?.value ?: reading.batteryCelsius
        views.temperature.text = hottest?.let { "%.0f°C".format(it) } ?: "—"
        (views.temperature.parent as? View)?.visibility = if (hottest != null) View.VISIBLE else View.GONE

        val hz = reading.refreshHz
        views.refresh.text = hz?.let { "%.0f Hz".format(it) } ?: "—"
        (views.refresh.parent as? View)?.visibility = if (hz != null) View.VISIBLE else View.GONE
    }

    // ── the pieces ──────────────────────────────────────────────────────────

    private fun gauge(
        activity: Activity,
        host: LinearLayout,
        label: String,
        unit: String,
        hue: Int,
        decimals: Int,
    ): RingGauge = RingGauge(activity).apply {
        this.label = label
        this.unit = unit
        this.ringColor = hue
        this.decimals = decimals
        layoutParams = LinearLayout.LayoutParams(0, Ui.dp(activity, 76), 1f).apply {
            marginStart = Ui.dp(activity, 3)
            marginEnd = Ui.dp(activity, 3)
        }
        host.addView(this)
    }

    /** One of the small headline cards: an icon and label on top, a large number under it. */
    private fun statCard(activity: Activity, label: String, glyph: String): Pair<View, TextView> {
        val value = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val view = card(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(activity, 14), Ui.dp(activity, 12), Ui.dp(activity, 14), Ui.dp(activity, 14))

            addView(TextView(activity).apply {
                text = "$glyph  ${label.uppercase()}"
                setTextColor(0xCCFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0.08f
            })
            addView(value)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        return view to value
    }

    /** The glassy rounded card the reference uses everywhere. */
    private fun card(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        background = GradientDrawable().apply {
            cornerRadius = Ui.dp(activity, CARD_RADIUS_DP).toFloat()
            setColor(CARD_FILL)
        }
        // Styles itself; Appearance sets the typeface and leaves the colours be.
        tag = "plain"
        Motion.pressable(this)
    }

    private const val GIGABYTE = (1L shl 30).toFloat()

    /** Ceilings learned from what the device has actually been seen doing. */
    @Volatile private var observedCpuPeak = 1
    @Volatile private var observedGpuPeak = 1
}
