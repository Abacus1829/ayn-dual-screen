package com.abacus.dualscreen.ui

import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.abacus.dualscreen.R

/**
 * The one place that decides how the app feels when you touch it.
 *
 * Every button that does something goes through here, so the feedback is the same everywhere and
 * there is a single line to change when it is wrong. Before this, feedback was whatever each screen
 * happened to do — some toasted, some did nothing.
 *
 * Haptics go through [View.performHapticFeedback], which is the accessible answer rather than the
 * obvious one: it honours the system's own touch-feedback setting, needs no VIBRATE permission, and
 * does nothing at all on a device that has haptics switched off. Vibrator would have overridden the
 * user's choice, which is exactly what an accessibility setting exists to prevent.
 */
object Feedback {

    /** An ordinary press: a saved note, a tapped tile. */
    fun tap(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Something completed. Distinct from [tap] so a confirmation feels different from a press. */
    fun success(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /**
     * Something failed, or was refused.
     *
     * LONG_PRESS is the heaviest constant available on every API this app supports; REJECT arrived in
     * API 30 and would be silent on the rest, which is worse than a slightly wrong buzz.
     */
    fun error(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** A press that begins a drag or a hold, so the gesture is acknowledged before it finishes. */
    fun hold(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // ── saying what happened ────────────────────────────────────────────────

    /**
     * How sure the app is about an outcome.
     *
     * The distinction that matters is [SENT] versus [OK]: a request that left the device is not a
     * request that worked, and reporting one as the other is how a UI teaches people not to trust it.
     * Only a protocol that answers earns [OK].
     */
    enum class State { IDLE, BUSY, SENT, OK, BAD }

    /**
     * Paint a status line.
     *
     * [dot] is optional so a screen with no indicator can still use the same wording and colours.
     *
     * The dot's drawable is created here rather than in each layout, because two screens had each
     * built their own oval and they had drifted: one tinted an existing background and the other
     * replaced it, so the same status looked different depending on where you saw it.
     *
     * The colour **crossfades** rather than switching. A status that changes between two frames is
     * easy to miss entirely on a handheld held at arm's length, and this is the one piece of the UI
     * whose whole job is to be noticed changing.
     */
    fun say(text: TextView?, dot: View?, state: State, message: String) {
        text?.text = message

        val context = text?.context ?: dot?.context ?: return
        val colour = androidx.core.content.ContextCompat.getColor(
            context,
            when (state) {
                State.IDLE -> R.color.state_idle
                State.BUSY, State.SENT -> R.color.state_busy
                State.OK -> R.color.state_ok
                State.BAD -> R.color.state_bad
            },
        )

        dot ?: return

        val oval = dot.background as? android.graphics.drawable.GradientDrawable
            ?: android.graphics.drawable.GradientDrawable()
                .apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
                .also { dot.background = it }

        val was = dot.getTag(R.id.tag_dot_colour) as? Int
        dot.setTag(R.id.tag_dot_colour, colour)

        if (was == null || was == colour) {
            oval.setColor(colour)
            return
        }

        android.animation.ValueAnimator.ofArgb(was, colour).apply {
            duration = 220
            addUpdateListener { oval.setColor(it.animatedValue as Int) }
            start()
        }
    }

    fun toast(activity: Activity?, message: String, long: Boolean = false) {
        activity ?: return
        Toast.makeText(activity, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /** Report a failure the same way everywhere: a buzz and a toast that names it. */
    fun failed(activity: Activity?, view: View?, message: String) {
        error(view)
        toast(activity, message, long = true)
    }
}
