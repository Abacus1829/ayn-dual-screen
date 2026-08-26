package com.abacus.dualscreen.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A ring gauge: label, big number, unit, and a coloured arc that sweeps to the value.
 *
 * This is the AYN dashboard's signature element — the four rings across the middle of the screen
 * reading CPU, GPU, PWR and RAM — and it is worth building properly rather than approximating with a
 * progress bar, because it is most of what makes that screen recognisable.
 *
 * Three details do the work, and all three are visible in the reference:
 *
 * - **The track is a full circle, the arc is a portion of it.** Both are the same stroke width, the
 *   track sits at low alpha, and the arc starts at the top and runs clockwise.
 * - **There is a dot at the leading edge of the arc.** A plain arc terminates in a flat or rounded
 *   cap and reads as a chart; a dot reads as a *position*, as though something is travelling round
 *   the ring. It is a small thing that carries the whole look.
 * - **Each ring has its own hue.** They are not a palette applied to a set — they are how you tell
 *   the four rings apart at a glance without reading the labels.
 *
 * ## The animation
 *
 * Nothing jumps. When a new reading arrives the arc sweeps to it and the number counts, both over
 * the same interval and on the same curve, so the two never disagree about where they are. A
 * dashboard that snaps between values is legible but feels like a readout; one that travels feels
 * like an instrument, and the difference is about three hundred milliseconds of interpolation.
 *
 * The curve is a slow-out ease with no overshoot. A gauge that overshoots is telling you the value
 * went somewhere it did not go.
 */
class RingGauge @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** What the ring is measuring: "CPU", "GPU", "PWR", "RAM". */
    var label: String = ""
        set(value) { field = value; invalidate() }

    /** The unit under the number: "GHz", "MHz", "W", "GB". */
    var unit: String = ""
        set(value) { field = value; invalidate() }

    /** The ring's own colour. */
    var ringColor: Int = 0xFF6EC1FF.toInt()
        set(value) { field = value; invalidate() }

    /**
     * How many decimal places the number carries.
     *
     * Fixed per gauge rather than chosen per value, because a number that changes width as it
     * changes value makes the whole card twitch — 3.19 becoming 3.2 becoming 3.19 again, twice a
     * second, is the sort of thing that is only visible once you have seen it and then never stops
     * being visible.
     */
    var decimals: Int = 2
        set(value) { field = value; invalidate() }

    /**
     * Whether this gauge has ever been given a reading.
     *
     * Lets the dashboard tell "this device does not report a GPU clock" from "the GPU is idle right
     * now". The first is a gauge that should not be on screen; the second is a gauge that should
     * hold its last value rather than blinking out and resizing everything beside it.
     */
    var hasReading = false
        private set

    private var target = 0f
    private var shown = 0f

    private var targetFraction = 0f
    private var shownFraction = 0f

    private var animator: ValueAnimator? = null

    /**
     * Set the reading.
     *
     * [fraction] is where the arc sits, 0 to 1, and is separate from [value] because the two are
     * rarely the same thing: a power reading of -1.59 W has no natural maximum, and its arc is drawn
     * against a sensible ceiling chosen by the caller rather than against the number itself.
     */
    fun set(value: Float, fraction: Float, animate: Boolean = true) {
        hasReading = true
        val cleanFraction = fraction.coerceIn(0f, 1f)

        if (!animate) {
            animator?.cancel()
            shown = value
            target = value
            shownFraction = cleanFraction
            targetFraction = cleanFraction
            invalidate()
            return
        }

        // Already going there; leave the animation alone rather than restarting it on every poll and
        // leaving the arc permanently at the start of a curve it never finishes.
        if (value == target && cleanFraction == targetFraction) return

        val fromValue = shown
        val fromFraction = shownFraction
        target = value
        targetFraction = cleanFraction

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            interpolator = EASE
            addUpdateListener {
                val t = it.animatedValue as Float
                shown = fromValue + (target - fromValue) * t
                shownFraction = fromFraction + (targetFraction - fromFraction) * t
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Nothing animates for a view nobody is looking at, and a running animator holds this view.
        animator?.cancel()
        animator = null
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val stroke = size * STROKE_SHARE
        val radius = (size - stroke) / 2f - size * 0.02f
        val cx = width / 2f
        val cy = height / 2f

        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        trackPaint.strokeWidth = stroke
        trackPaint.color = withAlpha(ringColor, 46)
        canvas.drawCircle(cx, cy, radius, trackPaint)

        // From the top, clockwise. -90 puts zero at twelve o'clock rather than at three.
        val sweep = 360f * shownFraction
        arcPaint.strokeWidth = stroke
        arcPaint.color = ringColor
        canvas.drawArc(bounds, -90f, sweep, false, arcPaint)

        // The travelling dot. Drawn even at zero, so the ring always has a head rather than
        // appearing to grow one once it passes some threshold.
        val angle = Math.toRadians((sweep - 90f).toDouble())
        dotPaint.color = ringColor
        canvas.drawCircle(
            cx + radius * cos(angle).toFloat(),
            cy + radius * sin(angle).toFloat(),
            stroke * 0.62f,
            dotPaint,
        )

        // ── the stack of text ───────────────────────────────────────────────

        textPaint.color = withAlpha(0xFFFFFFFF.toInt(), 200)
        textPaint.textSize = size * 0.135f
        textPaint.letterSpacing = 0.06f
        textPaint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(label, cx, cy - size * 0.16f, textPaint)

        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textSize = size * 0.24f
        textPaint.letterSpacing = 0f
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(format(shown), cx, cy + size * 0.075f, textPaint)

        textPaint.color = withAlpha(0xFFFFFFFF.toInt(), 170)
        textPaint.textSize = size * 0.115f
        textPaint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(unit, cx, cy + size * 0.235f, textPaint)
    }

    private fun format(value: Float): String = when (decimals) {
        0 -> "%.0f".format(value)
        1 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }

    private fun withAlpha(colour: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))

    companion object {
        /** Stroke as a share of the gauge's width, taken off the reference image. */
        private const val STROKE_SHARE = 0.075f

        private const val SWEEP_MS = 520L

        /** Slow-out, no overshoot: a gauge that overshoots reports a value that never happened. */
        private val EASE = PathInterpolator(0.22f, 0.61f, 0.36f, 1f)
    }
}
