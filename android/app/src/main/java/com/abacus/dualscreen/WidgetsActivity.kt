package com.abacus.dualscreen

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityWidgetsBinding
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Nav
import com.abacus.dualscreen.widgets.Stopwatch
import com.abacus.dualscreen.widgets.Widget

/**
 * What the handheld knows about itself.
 *
 * The app was only useful while a game was running on a PC. This is the answer to the other case:
 * the clock, the battery, what network it is on and what address it has there, how much room is
 * left, and a stopwatch — none of which need a connection, a server, or a permission the app did not
 * already hold.
 *
 * The readings are built from [Widget.ALL] rather than from this layout, so another one is an object
 * and a list entry.
 */
class WidgetsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetsBinding
    private lateinit var settings: Settings
    private lateinit var stopwatch: Stopwatch

    /**
     * Ten times a second while this screen is in front.
     *
     * Fast because the stopwatch shows tenths and anything slower stutters visibly; cheap because
     * every reading is a field lookup. Stopped in onPause, so it cannot outlive the screen.
     */
    private val tick = object : Runnable {
        override fun run() {
            drawStopwatch()
            binding.root.postDelayed(this, 100)
        }
    }

    /** Readings change slowly; re-reading them ten times a second would be silly. */
    private val slowTick = object : Runnable {
        override fun run() {
            drawReadings()
            binding.root.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWidgetsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        stopwatch = Stopwatch(this)

        Nav.back(this, binding.backButton)

        binding.notesButton.setOnClickListener {
            Feedback.tap(it)
            startActivity(Intent(this, NotesActivity::class.java))
        }

        binding.startButton.setOnClickListener {
            Feedback.tap(it)
            stopwatch.toggle()
            drawStopwatch()
        }

        binding.resetButton.setOnClickListener {
            Feedback.tap(it)
            stopwatch.reset()
            drawStopwatch()
        }

        binding.timerButton.setOnClickListener {
            Feedback.tap(it)
            askCountdown()
        }

        buildReadings()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
        com.abacus.dualscreen.control.ControlCenter.attach(this, settings)
    }

    /**
     * Live data from whatever companion is answering, if any.
     *
     * Started only when there is a saved connection to ask. With none, the dashboard is exactly what
     * it was — no card, no polling, no error. "No game running" is the ordinary state of this screen
     * and must not look like a failure.
     */
    private var telemetry: com.abacus.dualscreen.companion.TelemetrySource? = null

    override fun onResume() {
        super.onResume()
        binding.root.post(tick)
        binding.root.post(slowTick)
        startTelemetry()
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(tick)
        binding.root.removeCallbacks(slowTick)
        telemetry?.stop()
        telemetry = null
    }

    private fun startTelemetry() {
        val profile = com.abacus.dualscreen.connect.ProfileStore(this).ordered()
            .firstOrNull { it.usable } ?: return

        telemetry = com.abacus.dualscreen.companion.TelemetrySource(profile.url) { reading ->
            showGame(reading)
        }.also { it.start() }
    }

    /**
     * Show what the companion said, or nothing at all.
     *
     * The card only appears once something has actually answered, and disappears again when it stops
     * — a companion that is not running should leave the dashboard in its normal state rather than
     * leaving a stale reading on screen pretending to be live.
     */
    private fun showGame(reading: com.abacus.dualscreen.companion.Telemetry) {
        if (!reading.reachable) {
            binding.gameCard.visibility = android.view.View.GONE
            return
        }

        val profile = com.abacus.dualscreen.companion.Companions.byId(reading.gameId)
        val name = profile?.game?.let { getString(it.label) } ?: getString(R.string.widgets_game_unknown)

        binding.gameCard.visibility = android.view.View.VISIBLE
        binding.gameLabel.text = getString(R.string.widgets_game)
        binding.gameValue.text = name

        binding.gameDetail.text = when {
            reading.place != null -> reading.place
            reading.inGame -> getString(R.string.widgets_game_playing)
            else -> getString(R.string.widgets_game_menu)
        }
    }

    // ── the stopwatch ───────────────────────────────────────────────────────

    /** Set once the countdown has been announced, so it buzzes once rather than ten times a second. */
    private var announced = false

    private fun drawStopwatch() {
        val remaining = stopwatch.remaining()

        if (remaining != null) {
            binding.stopwatchValue.text = Stopwatch.format(remaining.coerceAtLeast(0L))
            binding.stopwatchDetail.text = getString(
                if (stopwatch.finished) R.string.widgets_timer_done else R.string.widgets_timer_running
            )

            if (stopwatch.finished && !announced) {
                announced = true
                Feedback.success(binding.root)
                Feedback.toast(this, getString(R.string.widgets_timer_done))
            }
        } else {
            announced = false
            binding.stopwatchValue.text = Stopwatch.format(stopwatch.elapsed())
            binding.stopwatchDetail.text = getString(
                if (stopwatch.running) R.string.widgets_running else R.string.widgets_stopped
            )
        }

        binding.startButton.text =
            getString(if (stopwatch.running) R.string.widgets_stop else R.string.widgets_start)
    }

    private fun askCountdown() {
        val field = EditText(this).apply {
            hint = getString(R.string.widgets_timer_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.widgets_timer)
            .setView(field)
            .setPositiveButton(R.string.widgets_start) { _, _ ->
                val minutes = field.text.toString().trim().toIntOrNull() ?: return@setPositiveButton
                if (minutes <= 0) return@setPositiveButton

                announced = false
                stopwatch.startCountdown(minutes * 60_000L)
                drawStopwatch()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── the readings ────────────────────────────────────────────────────────

    /**
     * A card per widget, two across.
     *
     * Built once and only the text is refreshed afterwards; rebuilding these every second would make
     * the screen flicker and would throw away scroll position.
     */
    private fun buildReadings() {
        binding.widgetGrid.removeAllViews()

        val grid = GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        for (widget in Widget.ALL) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Appearance.panel(
                    this@WidgetsActivity, settings, getColor(R.color.card), getColor(R.color.edge)
                )

                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }

            card.addView(TextView(this).apply {
                text = widget.label
                setTextColor(getColor(R.color.text_faint))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })

            card.addView(TextView(this).apply {
                tag = "value:${widget.id}"
                setTextColor(Appearance.accentOf(settings))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(0, dp(2), 0, 0)
            })

            card.addView(TextView(this).apply {
                tag = "detail:${widget.id}"
                setTextColor(getColor(R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })

            grid.addView(card)
        }

        binding.widgetGrid.addView(grid)
        drawReadings()
    }

    private fun drawReadings() {
        for (widget in Widget.ALL) {
            // Guarded individually: one widget that throws on some device must not take the rest of
            // the dashboard with it.
            val reading = runCatching { widget.read(this) }.getOrNull() ?: continue

            binding.widgetGrid.findViewWithTag<TextView>("value:${widget.id}")?.text = reading.value
            binding.widgetGrid.findViewWithTag<TextView>("detail:${widget.id}")?.text = reading.detail
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
