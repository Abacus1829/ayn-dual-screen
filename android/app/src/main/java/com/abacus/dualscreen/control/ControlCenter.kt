package com.abacus.dualscreen.control

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.abacus.dualscreen.Appearance
import com.abacus.dualscreen.R
import com.abacus.dualscreen.Settings
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Motion
import com.abacus.dualscreen.ui.Ui

/**
 * The quick menu: the handful of things you reach for *while doing something else*.
 *
 * This app grew as a set of separate destinations. Thirteen tiles on the home screen, each a screen
 * of its own, each reached by leaving wherever you were. That is a reasonable way to *build* an app
 * and a poor way to use one, because the things you want mid-session — turn it down, wake the screen,
 * fire a macro, check whether you are still connected — are exactly the things you should not have to
 * leave the session to get at.
 *
 * So this is not another screen. It is a panel that comes up over whatever is already there, does one
 * thing, and goes away. Three rules follow from that and they are the whole design:
 *
 * - **It never replaces what is underneath.** It is a window over the current activity, so dismissing
 *   it puts you back exactly where you were with nothing reloaded and nothing lost.
 * - **It is reachable from everywhere.** [attach] wires it to any screen, so there is no part of the
 *   app where the quick menu is the thing you have to navigate to.
 * - **Everything on it is either instant or one tap from instant.** Sliders act as you drag. Buttons
 *   that open a real screen are there because that screen genuinely needs the room, and they say so
 *   by looking different from the switches.
 *
 * What is *not* here matters as much. Anything you would sit down and do — writing a note, building a
 * macro, arranging a layout, moving files — stays a destination, because a panel you are meant to
 * dismiss is a bad place to work. The rule used to decide was "would you want this while a game is
 * running", and only things that passed it are on the panel.
 */
object ControlCenter {

    /**
     * Put the quick menu within reach of [activity].
     *
     * Call once, from `onCreate`. Idempotent, so a screen that is recreated does not end up with two.
     */
    fun attach(activity: Activity, settings: Settings) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.findViewWithTag<View>(TAG) != null) return

        // A handle rather than a floating button: it sits on the edge, out of the way of content,
        // and reads as "there is something behind this" rather than as an action of its own.
        val handle = TextView(activity).apply {
            tag = TAG
            text = "≡"
            setTextColor(Appearance.accentOf(settings))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            val pad = Ui.dp(activity, 10)
            setPadding(pad, Ui.dp(activity, 4), pad, Ui.dp(activity, 4))
            contentDescription = activity.getString(R.string.control_open)
            background = Appearance.panel(
                activity, settings, activity.getColor(R.color.card_hi), Appearance.accentOf(settings)
            )
            /*
             * The right edge, halfway down — not the top corner.
             *
             * Twenty-seven of this app's layouts put a Done button in the top-right, so a handle
             * anchored there would sit directly on top of the way out of the screen. Halfway down the
             * right edge is the one part of a screen this app never puts a control on, and on a
             * handheld it is where a thumb already rests.
             */
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END,
            ).apply {
                marginEnd = Ui.dp(activity, 2)
            }
            setOnClickListener {
                Feedback.tap(it)
                show(activity, settings)
            }
        }

        Motion.pressable(handle)
        root.addView(handle)
    }

    /** Open the panel over whatever is on screen. */
    fun show(activity: Activity, settings: Settings) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = android.app.Dialog(activity, R.style.Theme_DualScreen_Dialog)
        dialog.setContentView(build(activity, settings, dialog))

        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            // Anchored to the bottom, where a thumb already is on a handheld this size.
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.55f)
        }

        dialog.show()
    }

    // ── the panel ───────────────────────────────────────────────────────────

    private fun build(activity: Activity, settings: Settings, dialog: android.app.Dialog): View {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 18))

            addView(Ui.section(activity, R.string.control_title))

            // ── the two sliders, which are most of why this exists ──────────
            if (audio != null) addView(volume(activity, settings, audio))
            addView(brightness(activity, settings))

            // ── switches ────────────────────────────────────────────────────
            addView(
                Ui.toggle(
                    activity, settings, R.string.control_awake, 0,
                    settings.keepAwake,
                ) { on ->
                    settings.keepAwake = on
                    /*
                     * Applied to the screen underneath, immediately.
                     *
                     * A keep-awake switch that only takes effect on the next screen is a switch you
                     * cannot trust, and this one is most often flipped precisely because the screen
                     * is about to go dark.
                     */
                    if (on) activity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            )

            // ── the shortcuts ───────────────────────────────────────────────
            addView(Ui.section(activity, R.string.control_shortcuts))
            addView(shortcuts(activity, settings, dialog))
        }
    }

    private fun volume(activity: Activity, settings: Settings, audio: AudioManager): View {
        val stream = AudioManager.STREAM_MUSIC
        return slider(
            activity, settings,
            label = activity.getString(R.string.control_volume),
            max = runCatching { audio.getStreamMaxVolume(stream) }.getOrDefault(15),
            value = runCatching { audio.getStreamVolume(stream) }.getOrDefault(0),
        ) { level ->
            runCatching { audio.setStreamVolume(stream, level, 0) }
        }
    }

    /**
     * Brightness, with an honest fallback.
     *
     * System brightness needs WRITE_SETTINGS. Without it the slider still works — it dims *this
     * window*, which is what you actually see — rather than being greyed out with an explanation
     * nobody wants to read while trying to turn the screen down.
     */
    private fun brightness(activity: Activity, settings: Settings): View {
        val allowed = runCatching { AndroidSettings.System.canWrite(activity) }.getOrDefault(false)

        val current = if (allowed) {
            runCatching {
                AndroidSettings.System.getInt(
                    activity.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS
                ) * 100 / 255
            }.getOrDefault(50)
        } else {
            val window = activity.window?.attributes?.screenBrightness ?: -1f
            if (window >= 0f) (window * 100).toInt() else 50
        }

        return slider(
            activity, settings,
            label = activity.getString(R.string.control_brightness),
            max = 100,
            value = current.coerceIn(0, 100),
        ) { level ->
            val safe = level.coerceAtLeast(5)

            // Always dim the window: it is instant and it is what the eye is judging.
            activity.window?.attributes = activity.window?.attributes?.apply {
                screenBrightness = safe / 100f
            }

            if (allowed) {
                runCatching {
                    AndroidSettings.System.putInt(
                        activity.contentResolver,
                        AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
                        AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    )
                    AndroidSettings.System.putInt(
                        activity.contentResolver,
                        AndroidSettings.System.SCREEN_BRIGHTNESS,
                        safe * 255 / 100,
                    )
                }
            }
        }
    }

    private fun slider(
        activity: Activity,
        settings: Settings,
        label: String,
        max: Int,
        value: Int,
        onChange: (Int) -> Unit,
    ): View = Ui.card(activity, settings).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(activity, 14), Ui.dp(activity, 10), Ui.dp(activity, 14), Ui.dp(activity, 12))
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { layoutParams = it }).topMargin = Ui.dp(activity, 6)

        val readout = TextView(activity).apply {
            text = label
            setTextColor(activity.getColor(R.color.text))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        addView(readout)

        addView(SeekBar(activity).apply {
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, level: Int, fromUser: Boolean) {
                    if (fromUser) onChange(level)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                // Sound only on release, not per step. A cue on every increment of a drag is a
                // machine-gun, and this is the one control people move a long way in one gesture.
                override fun onStopTrackingTouch(bar: SeekBar) {
                    Feedback.tap(bar)
                }
            })
        })
    }

    /**
     * The things worth reaching mid-session, as one row of buttons.
     *
     * Each opens a real screen, so they are drawn as links rather than as switches: a control that
     * takes you somewhere should not look like one that acts in place.
     */
    private fun shortcuts(activity: Activity, settings: Settings, dialog: android.app.Dialog): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            fun go(labelRes: Int, glyph: String, target: Class<*>) {
                addView(
                    Ui.link(activity, settings, labelRes, 0, glyph) {
                        dialog.dismiss()
                        activity.startActivity(Intent(activity, target))
                    }
                )
            }

            go(R.string.tool_keyboard, "⌨", com.abacus.dualscreen.KeyboardActivity::class.java)
            go(R.string.tool_notes, "✎", com.abacus.dualscreen.NotesActivity::class.java)
            go(R.string.tool_macros, "◉", com.abacus.dualscreen.MacrosActivity::class.java)
            go(R.string.tool_dashboard, "◍", com.abacus.dualscreen.DashboardActivity::class.java)

            // Home last, because it is the way out rather than a place to go.
            addView(
                Ui.link(activity, settings, R.string.control_home, 0, "⌂") {
                    dialog.dismiss()
                    com.abacus.dualscreen.ui.Nav.home(activity)
                }
            )
        }

    private const val TAG = "control-center-handle"
}
