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
import com.abacus.dualscreen.ui.Feedback
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
        /*
         * The cache has to be checked against the host, not just read off it.
         *
         * The views were remembered on a tag and reused on every poll. A tag outlives the views it
         * points at, though: anything that empties the host — a theme change rebuilding the tree, the
         * activity being recreated on a display move, a configuration change — leaves the tag holding
         * a set of detached views that are then dutifully updated forever while the screen shows
         * nothing. That is the dashboard "sometimes disappearing", and it is why it never came back
         * without leaving the screen.
         *
         * So the cache is only trusted while its views are still the ones actually in the host.
         */
        val cached = host.getTag(R.id.tag_base_size) as? Views
        val usable = cached?.takeIf { it.root === host && host.childCount > 0 && it.cpu.parent != null }

        val views = usable ?: create(activity, settings, host).also {
            host.setTag(R.id.tag_base_size, it)
        }

        // Animate only when carrying on from a previous reading. A freshly built set sweeps from
        // zero, which is the right first impression and the wrong thing to do twice a second.
        update(activity, views, reading, animate = usable != null)
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
        val details: LinearLayout,
        val rates: LinearLayout,
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

        /*
         * The rest of what the handheld knows about itself, underneath.
         *
         * These are the existing widgets — clock, battery, network, storage, memory, device — and
         * they are here because their screen was *also* called "Dashboard", so the tool grid carried
         * two tiles with the same name showing overlapping things. The gauges answer "how hard is it
         * working"; these answer "what is it", and they belong on the same screen.
         *
         * Reusing Widget.ALL rather than reimplementing it: each one already knows how to read
         * itself, and a second copy of that logic would be a second thing to keep correct.
         */
        /*
         * The refresh control, above the readouts.
         *
         * Above, because it is the one thing on this screen you can *act* on, and burying the only
         * control under six paragraphs of numbers is how people conclude a dashboard is read-only.
         */
        val rates = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(activity, 8) }
        }
        host.addView(rates)

        val details = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(activity, 8) }
        }
        host.addView(details)

        return Views(host, temperature.second, refresh.second, cpu, gpu, power, memory, details, rates)
    }

    /**
     * The refresh-rate buttons, when there is more than one rate and we are allowed to pick.
     *
     * Three states, and each says something different:
     *
     * - **One mode only** — nothing to choose. The row is not drawn at all.
     * - **Several modes, no permission** — drawn, with a button that goes and asks for it. Showing
     *   the choice and explaining why it is unavailable beats hiding a capability the device has.
     * - **Several modes, permission held** — real buttons, and the one that is lit is the rate the
     *   *panel* reports, not the one last asked for. Those can disagree, and the panel is right.
     */
    private fun updateRates(activity: Activity, host: LinearLayout, reading: DeviceStats.Reading) {
        val modes = reading.supportedHz.map { kotlin.math.round(it).toInt() }.distinct().sorted()

        if (modes.size <= 1) {
            host.visibility = View.GONE
            return
        }

        host.visibility = View.VISIBLE

        val permitted = RefreshRate.permitted(activity)
        val running = reading.refreshHz?.let { kotlin.math.round(it).toInt() }
        val signature = "${modes.joinToString()}|$permitted|$running"

        // Rebuilt only when something about it actually changed. These are buttons, and a button
        // replaced underneath a finger that is already on it does not register the tap.
        if (host.getTag(R.id.tag_base_size) == signature) return
        host.setTag(R.id.tag_base_size, signature)
        host.removeAllViews()

        host.addView(TextView(activity).apply {
            text = activity.getString(R.string.dash_refresh).uppercase()
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Ui.dp(activity, 12) }
        })

        if (!permitted) {
            host.addView(pill(activity, activity.getString(R.string.dash_refresh_allow), lit = false) {
                Feedback.tap(it)
                runCatching {
                    activity.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            android.net.Uri.parse("package:${activity.packageName}"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            })
            return
        }

        for (hz in modes) {
            host.addView(pill(activity, "$hz Hz", lit = hz == running) { view ->
                Feedback.select(view)

                val settled = RefreshRate.apply(activity, activity.windowManager.defaultDisplay, hz)
                if (settled == null || settled != hz) {
                    /*
                     * Said out loud rather than swallowed.
                     *
                     * These keys are not a documented API and a device may accept the write and
                     * carry on at whatever rate it likes. A button that reports success and changes
                     * nothing is worse than one that admits it did not work.
                     */
                    Feedback.failed(
                        activity,
                        host,
                        activity.getString(R.string.dash_refresh_refused, hz),
                    )
                }

                // Forces a rebuild on the next poll so the lit button follows the panel.
                host.setTag(R.id.tag_base_size, null)
            })
        }
    }

    /** A rounded pill button, matching the mode badge in the reference. */
    private fun pill(activity: Activity, label: String, lit: Boolean, onClick: (View) -> Unit): View =
        TextView(activity).apply {
            text = label
            setTextColor(if (lit) 0xFF0B1020.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(Ui.dp(activity, 16), Ui.dp(activity, 7), Ui.dp(activity, 16), Ui.dp(activity, 7))
            background = GradientDrawable().apply {
                cornerRadius = Ui.dp(activity, 999).toFloat()
                setColor(if (lit) 0xFF6EC1FF.toInt() else CARD_FILL)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Ui.dp(activity, 8) }
            setOnClickListener { onClick(it) }
        }

    /**
     * The widget rows, rebuilt from their own readings.
     *
     * Cheap — every one of them is a system call or two — and rebuilt rather than diffed because
     * there are six and none of them animates.
     */
    private fun updateDetails(activity: Activity, host: LinearLayout) {
        val readings = com.abacus.dualscreen.widgets.Widget.ALL.mapNotNull { widget ->
            runCatching { widget.read(activity) }.getOrNull()
        }

        if (readings.isEmpty()) {
            host.visibility = View.GONE
            return
        }

        host.visibility = View.VISIBLE

        // Two per row, matching the tile pairing above it.
        if (host.childCount != (readings.size + 1) / 2) host.removeAllViews()

        readings.chunked(2).forEachIndexed { index, pair ->
            val line = host.getChildAt(index) as? LinearLayout ?: LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(activity, 8) }
                host.addView(this)
            }

            pair.forEachIndexed { column, reading ->
                val existing = line.getChildAt(column) as? LinearLayout
                val cell = existing ?: card(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(activity, 14), Ui.dp(activity, 10), Ui.dp(activity, 14), Ui.dp(activity, 12))
                    addView(TextView(activity).apply {
                        setTextColor(0xCCFFFFFF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        letterSpacing = 0.08f
                    })
                    addView(TextView(activity).apply {
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(activity).apply {
                        setTextColor(0x99FFFFFF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (column > 0) marginStart = Ui.dp(activity, 8) }
                    line.addView(this)
                }

                (cell.getChildAt(0) as TextView).text = reading.title.uppercase()
                (cell.getChildAt(1) as TextView).text = reading.value
                (cell.getChildAt(2) as TextView).apply {
                    text = reading.detail
                    visibility = if (reading.detail.isBlank()) View.GONE else View.VISIBLE
                }
            }
        }
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
            hide(views.cpu)
        }

        // ── GPU ─────────────────────────────────────────────────────────────
        val gpuMhz = reading.gpuMhz
        if (gpuMhz != null) {
            val ceiling = reading.gpuMaxMhz?.takeIf { it > 0 }
                ?: maxOf(observedGpuPeak, gpuMhz).also { observedGpuPeak = it }
            views.gpu.set(gpuMhz.toFloat(), gpuMhz.toFloat() / ceiling, animate)
            views.gpu.visibility = View.VISIBLE
        } else {
            hide(views.gpu)
        }

        // ── power ───────────────────────────────────────────────────────────
        val watts = reading.watts
        if (watts != null) {
            // Fifteen watts is about as hard as a handheld of this class pulls, and the arc is drawn
            // against the magnitude because a negative arc is not a thing anybody can read.
            views.power.set(watts, kotlin.math.abs(watts) / 15f, animate)
            views.power.visibility = View.VISIBLE
        } else {
            hide(views.power)
        }

        // ── memory ──────────────────────────────────────────────────────────
        val used = reading.ramUsedBytes
        val total = reading.ramTotalBytes
        if (used != null && total != null && total > 0) {
            views.memory.set(used / GIGABYTE, used.toFloat() / total, animate)
            views.memory.visibility = View.VISIBLE
        } else {
            hide(views.memory)
        }

        // ── the two stat cards ──────────────────────────────────────────────
        // Chosen by DeviceStats rather than maximised here: the hottest of a Thor's 58 zones is a
        // power regulator at 50°C, which is meaningless on a handheld sitting at 36.
        val hottest = reading.socCelsius ?: reading.batteryCelsius
        views.temperature.text = hottest?.let { "%.0f°C".format(it) } ?: "—"
        (views.temperature.parent as? View)?.visibility = if (hottest != null) View.VISIBLE else View.GONE

        updateRates(activity, views.rates, reading)
        updateDetails(activity, views.details)

        val hz = reading.refreshHz
        views.refresh.text = hz?.let { "%.0f Hz".format(it) } ?: "—"
        (views.refresh.parent as? View)?.visibility = if (hz != null) View.VISIBLE else View.GONE
    }

    /**
     * A reading that has gone away, without moving anything.
     *
     * **Never GONE.** These four gauges share a weighted row, so removing one makes the other three
     * grow to fill the space and then shrink again when it returns — and several of these readings
     * legitimately come and go between polls. Battery current reads exactly zero at the moment of
     * a charge-state change, and the GPU clock node reports nothing at all when the GPU is idle. On
     * a two-second timer that is the ring row jumping about every two seconds for no reason the
     * viewer can see.
     *
     * A gauge that has never had a value at all is a different case: it is removed once, when the
     * dashboard is built, because this device does not supply it.
     */
    private fun hide(gauge: RingGauge) {
        if (gauge.hasReading) {
            // Had a value and lost it. Keep the last one on screen rather than blinking out; it is a
            // second or two stale at worst, and a stale number beats a hole in the layout.
            return
        }
        gauge.visibility = View.GONE
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
        /*
         * Deliberately **not** pressable.
         *
         * These are readouts, and the press animation is for things that respond to being pressed.
         * On a view that is not clickable it also misbehaves outright: the scale-down runs on
         * ACTION_DOWN, the card does not consume the event, the ScrollView takes the gesture and
         * sends ACTION_CANCEL, and the spring-back overshoots past its own size on the way home.
         * Hold a finger on the screen with any movement at all and that cycle repeats — which is
         * the dashboard visibly shaking under your thumb.
         */
        tag = "plain"
    }

    private const val GIGABYTE = (1L shl 30).toFloat()

    /** Ceilings learned from what the device has actually been seen doing. */
    @Volatile private var observedCpuPeak = 1
    @Volatile private var observedGpuPeak = 1
}
