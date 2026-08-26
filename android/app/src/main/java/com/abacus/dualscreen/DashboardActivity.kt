package com.abacus.dualscreen

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.companion.Dashboard
import com.abacus.dualscreen.companion.DeviceStats
import com.abacus.dualscreen.databinding.ActivityDashboardBinding
import com.abacus.dualscreen.ui.Nav

/**
 * The console's own dashboard, as a screen of its own.
 *
 * It started life as a strip on the home screen, which was the wrong place for it twice over: the
 * home screen is about *connecting to a game* and already carries a status card, a game picker, an
 * address form and a tool grid, so a live hardware readout was competing for attention with all of
 * that — and it is the kind of thing you go and look at deliberately, the way you would open the
 * console's own dashboard, rather than something you want glancing at you while you pick a profile.
 *
 * ## The polling
 *
 * Two seconds while this screen is in front, stopped in [onPause]. The readings are a handful of
 * small sysfs files, but a refusing path can block far longer than a file that size has any right
 * to, so they are read on a background thread and delivered back to the gauges on the main one.
 *
 * The gauges animate between readings, which is the whole reason the views are built once and
 * updated in place rather than rebuilt each tick — see [Dashboard].
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var settings: Settings

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        Nav.back(this, binding.backButton)
    }

    override fun onResume() {
        super.onResume()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
        com.abacus.dualscreen.control.ControlCenter.attach(this, settings)

        // Straight away rather than after the first interval: a dashboard that opens empty and fills
        // in two seconds later reads as broken, however briefly.
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        // Nothing is polled behind a screen nobody is looking at.
        handler.removeCallbacks(tick)
    }

    private fun refresh() {
        Thread {
            val reading = DeviceStats.read(this, panelDisplay)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Dashboard.build(this, settings, binding.dashboardHost, reading)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private val panelDisplay: android.view.Display?
        get() = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) display
            else @Suppress("DEPRECATION") windowManager.defaultDisplay
        }.getOrNull()

    private companion object {
        /** A dashboard is read, not watched. Two seconds is plenty and costs almost nothing. */
        const val POLL_MS = 2_000L
    }
}
