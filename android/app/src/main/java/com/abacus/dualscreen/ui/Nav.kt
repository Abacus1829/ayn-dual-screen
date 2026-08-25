package com.abacus.dualscreen.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import com.abacus.dualscreen.HomeActivity

/**
 * Getting back out of a tool.
 *
 * Every tool screen has a visible control that returns where it came from, and they all behave the
 * same way because they all come through here. Relying on the system back gesture alone was the gap:
 * on a handheld the gesture is easy to miss, and on the second panel there may be no gesture area at
 * all — a screen with no visible way out is a trap.
 *
 * The rule is plain: [back] finishes the screen, which returns to whatever opened it. From a tool
 * opened off the home grid that is the home menu; from a nested editor it is the list above it. No
 * screen is recreated, nothing is stacked twice, and the system back button still does exactly what
 * it did before.
 */
object Nav {

    /**
     * Wire a view as the way out.
     *
     * Finishing rather than starting the home screen on purpose: starting it would put a second copy
     * of home on top of a nested editor's parent, and the way out of a nested screen is the screen
     * that opened it, not the top of the app.
     */
    fun back(activity: Activity, view: View?) {
        view ?: return
        Motion.pressable(view)
        view.setOnClickListener {
            // The downward step, everywhere something closes. Paired with the upward one in
            // [Feedback.select], it means direction is audible without anybody being told.
            Feedback.back(view)
            activity.finish()
        }
    }

    /**
     * All the way out to the home menu, from wherever this is.
     *
     * For the few places genuinely several levels deep. CLEAR_TOP reuses the home screen already in
     * the task instead of stacking another, which is what stops a long-running session from leaving
     * a pile of identical screens behind it.
     */
    fun home(activity: Activity, view: View? = null) {
        Feedback.back(view)

        activity.startActivity(
            Intent(activity, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        activity.finish()
    }
}
