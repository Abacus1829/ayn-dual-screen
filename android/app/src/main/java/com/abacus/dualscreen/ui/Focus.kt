package com.abacus.dualscreen.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowManager

/**
 * Staying out of the game's way.
 *
 * Android gives **one window the input focus at a time**, across every display. Per-display focus
 * exists from Android 10 but is off unless the manufacturer turns it on, and on the handhelds this
 * app runs on it is off. So the moment a second-screen window appears on the lower panel, it becomes
 * the focused window and the game on the upper one stops receiving controller input until you touch
 * the game again. Nothing is broken; the system is doing exactly what it says it does.
 *
 * The fix is to say the window does not want focus. [FLAG_NOT_FOCUSABLE] leaves it visible and
 * touchable — you can still tap the second screen — while key and controller events carry on to
 * whatever had them before. That is what a companion screen should have been asking for all along.
 *
 * Two consequences worth knowing, both of which follow from "this window receives no key events":
 *
 * - **The hardware back button will not reach it.** Every screen that uses this must have a visible
 *   way out, which this app already insists on elsewhere — see [Nav].
 * - **No keyboard.** Tapping a text field in a passive window will not raise the IME. A page that
 *   genuinely needs typing needs focus, which is why this is a switch rather than a rule.
 */
object Focus {

    /**
     * Passive: visible and touchable, but never takes the controller from whatever is playing.
     *
     * Safe to call at any point in the lifecycle and safe to call repeatedly; the flag takes effect
     * on the next traversal.
     */
    fun passive(activity: Activity, passive: Boolean) {
        val window = activity.window ?: return
        if (passive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    /**
     * Make a view the D-pad can actually get to, and show when it has.
     *
     * This exists because of a gap that only shows up on the hardware this app is for. A `Button` or
     * a `CheckBox` declared in a layout is focusable by default, so the framework's own directional
     * navigation finds it. **A `LinearLayout` given a click listener is not.** It is clickable, it
     * looks like a button, it responds to a tap — and a D-pad walks straight past it as though it
     * were not there.
     *
     * Almost every row and tile in this app is exactly that: a container built in code with a click
     * listener on it. Which meant the tool grid, the settings rows and the profile list could only be
     * operated by touching the screen, on a console with a perfectly good stick and D-pad sitting
     * under your thumbs.
     *
     * The highlight is drawn as a foreground rather than by swapping the background, because these
     * views already have a background that carries their theme, accent and corner radius, and
     * replacing it on focus would make a focused card a different shape from an unfocused one.
     */
    fun reachable(view: View, accent: Int) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false

        val radius = view.resources.displayMetrics.density * 10f
        val ring = GradientDrawable().apply {
            cornerRadius = radius
            setStroke((view.resources.displayMetrics.density * 2f).toInt(), accent)
            setColor(Color.TRANSPARENT)
        }

        view.foreground = null
        view.setOnFocusChangeListener { target, focused ->
            target.foreground = if (focused) ring else null

            // A focused row that is off screen is the same as no focus at all, and directional
            // navigation inside a ScrollView does not always bring its target into view on its own.
            if (focused) target.post { target.parent?.requestChildFocus(target, target) }
        }
    }

    /**
     * Give the first thing on a screen focus, once.
     *
     * Without this the first press of a D-pad on a fresh screen goes to whatever the framework picks,
     * which is often nothing visible — so it reads as the controller not working rather than as
     * focus starting somewhere unhelpful. Only acts when there is no focus already and when the last
     * input was not a touch, so it never steals a selection from somebody using the screen.
     */
    fun start(root: View) {
        root.post {
            if (root.findFocus() != null) return@post
            root.focusSearch(View.FOCUS_DOWN)?.requestFocus()
        }
    }
}
