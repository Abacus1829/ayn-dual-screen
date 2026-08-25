package com.abacus.dualscreen.ui

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * How this app moves.
 *
 * Before this, animation was whatever each screen happened to write: a 120ms alpha here, a 450ms
 * one there, no shared idea of how fast "fast" is. That reads as several apps rather than one, and
 * the fix is not more animation but *the same* animation everywhere.
 *
 * ## The timings, and why these numbers
 *
 * - **[QUICK] 120ms** — a press, a tint, a checkbox. Below about 100ms a movement is perceived as an
 *   instant change; above about 150ms a press starts to feel like it is lagging your finger.
 * - **[NORMAL] 200ms** — the default. A panel opening, a row appearing, a value crossfading.
 * - **[SLOW] 320ms** — something big and infrequent: a whole list arriving, a theme being applied.
 * - **[STAGGER] 26ms** — the gap between one list item and the next. Small enough that a long list
 *   still finishes quickly, large enough that the eye reads a sequence rather than a flicker.
 *
 * ## Reduced motion is honoured, not approximated
 *
 * Everything here checks the system's **animator duration scale**. Somebody who has turned
 * animations off — for motion sensitivity, on a battery saver, in developer options — gets the end
 * state immediately and no animation at all. That setting exists precisely so an app does not have
 * to invent its own switch, and honouring it is the difference between a preference and a
 * suggestion.
 */
object Motion {

    const val QUICK = 120L
    const val NORMAL = 200L
    const val SLOW = 320L
    const val STAGGER = 26L

    /** Arriving: fast then easing into place. The default for anything appearing. */
    val ENTER: Interpolator = DecelerateInterpolator(1.6f)

    /** Leaving: gentle then quick. Things should go away faster than they arrive. */
    val EXIT: Interpolator = AccelerateInterpolator(1.4f)

    /** A press releasing, with a hair of overshoot so it feels sprung rather than mechanical. */
    val SPRING: Interpolator = OvershootInterpolator(1.6f)

    /** Standard ease, for movement that is neither arriving nor leaving. */
    val EASE: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** Whether the device wants animation at all. */
    fun animated(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }.getOrDefault(true)

    /**
     * Fade and lift a view into place.
     *
     * [delay] is what a staggered list passes in. The view is left at its final values whatever
     * happens, so a cancelled or skipped animation cannot strand something invisible.
     */
    fun enter(view: View, delay: Long = 0L, distanceDp: Float = 10f) {
        val end = { view.alpha = 1f; view.translationY = 0f }

        if (!animated(view.context)) {
            end()
            return
        }

        view.alpha = 0f
        view.translationY = distanceDp * view.resources.displayMetrics.density

        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(NORMAL)
            .setInterpolator(ENTER)
            .withEndAction(end)
            .start()
    }

    /**
     * Bring in every child of a container, one after another.
     *
     * The cap matters: a list of forty rows staggered at 26ms would take a second to finish
     * appearing, by which point it is an obstacle rather than a flourish. After the cap everything
     * arrives together.
     */
    fun enterChildren(container: ViewGroup, maxStaggered: Int = 10) {
        if (!animated(container.context)) return

        for (i in 0 until container.childCount) {
            enter(container.getChildAt(i), delay = STAGGER * minOf(i, maxStaggered))
        }
    }

    /**
     * The press: down small, up with a little spring.
     *
     * Wired as a touch listener rather than a click listener so the scale follows the finger — down
     * on the way down, back on release or on the drag that cancels it. Anything already handling
     * its own touches keeps them; this returns false and never consumes an event.
     */
    fun pressable(view: View, scale: Float = 0.96f) {
        if (!animated(view.context)) return

        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    target.animate().scaleX(scale).scaleY(scale)
                        .setDuration(QUICK).setInterpolator(EASE).start()

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                ->
                    target.animate().scaleX(1f).scaleY(1f)
                        .setDuration(NORMAL).setInterpolator(SPRING).start()
            }
            false
        }
    }

    /** Grow or collapse a view by its measured height, for sections that open in place. */
    fun expand(view: View, open: Boolean, onEnd: (() -> Unit)? = null) {
        if (!animated(view.context)) {
            view.visibility = if (open) View.VISIBLE else View.GONE
            onEnd?.invoke()
            return
        }

        val parentWidth = (view.parent as? View)?.width ?: 0
        view.measure(
            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val full = view.measuredHeight

        val from = if (open) 0 else view.height.takeIf { it > 0 } ?: full
        val to = if (open) full else 0

        view.visibility = View.VISIBLE

        ValueAnimator.ofInt(from, to).apply {
            duration = NORMAL
            interpolator = if (open) ENTER else EXIT
            addUpdateListener {
                view.layoutParams.height = it.animatedValue as Int
                view.requestLayout()
                view.alpha = if (open) it.animatedFraction else 1f - it.animatedFraction
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Back to wrap_content, or a row added later would be clipped by the height
                    // this animation happened to finish on.
                    view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.alpha = 1f
                    view.visibility = if (open) View.VISIBLE else View.GONE
                    view.requestLayout()
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** Swap a view's contents without the change happening between two frames. */
    fun crossfade(view: View, change: () -> Unit) {
        if (!animated(view.context)) {
            change()
            return
        }

        view.animate().alpha(0f).setDuration(QUICK).setInterpolator(EXIT).withEndAction {
            change()
            view.animate().alpha(1f).setDuration(NORMAL).setInterpolator(ENTER).start()
        }.start()
    }

    /**
     * A brief pulse, for something that succeeded in place.
     *
     * Up and back, once. Used where a screen has nothing to navigate to on success and would
     * otherwise have to say so in words.
     */
    fun pulse(view: View, scale: Float = 1.06f) {
        if (!animated(view.context)) return

        view.animate().scaleX(scale).scaleY(scale)
            .setDuration(QUICK).setInterpolator(ENTER)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(NORMAL).setInterpolator(SPRING).start()
            }.start()
    }

    /** A short sideways shake, for a refusal. Two returns, damped, never more. */
    fun shake(view: View) {
        if (!animated(view.context)) return

        val distance = 8f * view.resources.displayMetrics.density
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SLOW
            addUpdateListener {
                val t = it.animatedFraction
                val decay = 1f - t
                view.translationX =
                    (Math.sin(t * Math.PI * 4).toFloat()) * distance * decay
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.translationX = 0f
                }
            })
            start()
        }
    }
}
