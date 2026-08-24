package com.abacus.dualscreen.ui

import android.app.Activity
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
}
