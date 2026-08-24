package com.abacus.dualscreen.boot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The logo, drawn rather than played back — and the animation ends *on* it.
 *
 * The shape here is the app's own abacus mark: a thick black rounded frame on white, two rods, three
 * beads to a rod, and one bead in red at the top right. The last frame of the animation is that
 * image exactly, so the boot sequence resolves into the logo instead of merely gesturing at it. Get
 * the geometry wrong and it is an abacus; get it right and it is *the* abacus.
 *
 * Everything is Canvas primitives — two rounded rectangles, two bars, six circles. No bitmaps, no
 * frames to decode, no library. That matters more than it sounds: this is the first thing that
 * happens when the app opens, and an animation that has to load something stutters exactly when
 * somebody is deciding whether the app feels solid.
 *
 * **The beads are simulated, not animated.** [Beads] holds a small 1-D physics model — six
 * particles, two rods, elastic collisions — and this class only draws where it says they are. The
 * important consequence is that the sliding and the spinning are the same event: gravity along a rod
 * is `g·sin(θ)` for the very θ the canvas is rotated by, so the beads move because the frame you can
 * see turning is turning. Nothing here is keyframed to look like physics.
 *
 * The red behaves like momentum. When two beads collide it passes to whichever one comes out of the
 * collision travelling faster, which for equal masses is the one that was struck — so it works its
 * way along a rod as the beads knock into each other, and where it ends up is decided by the
 * collisions rather than by a schedule. Each hand-off is drawn as a short crossfade, because a
 * colour that teleports reads as a bug and a colour that travels reads as a pass.
 *
 * The last act is choreographed and says so: see [Beads] for why free dynamics cannot be asked to
 * come to rest on a fixed pose, and how the beads are seated into the mark instead.
 *
 * Two rules it follows, both about not being in the way:
 *
 * - **It never blocks anything.** The screen behind it is being built while it plays, and the update
 *   check is already on its way to the network. The animation is two and a half seconds of its own
 *   and asks nobody for permission to finish.
 * - **It can always be skipped.** A tap ends it immediately, and a device with animations turned off
 *   in accessibility or developer settings gets none at all — one frame, then straight on. That
 *   setting exists so an app does not have to invent its own.
 *
 * [waiting] is the exception: it holds past the natural end for something that genuinely has to
 * finish first, showing a status line and three quiet dots. Nothing in the update path uses it — the
 * check may still be running when the home screen appears — but a future first-run setup step that
 * must complete before the home screen is real has somewhere to go.
 */
class AbacusBootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** The timeline, in milliseconds. The physics owns the first part; these frame it. */
    private val arriveMs = 900f
    private val wordStartMs = 1_250L
    private val holdMs = (Beads.TOTAL_MS + 220f).toLong()
    private val fadeMs = 280L
    private val wordMs = 500L

    /** The simulation. Rebuilt on each play, so a second run is identical to the first. */
    private var beads = Beads()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val frame = RectF()
    private val interior = RectF()

    /** The user's accent, used for the halo behind the mark so the screen still matches the app. */
    var accent: Int = 0xFF6EC1FF.toInt()

    /** The mark's own colours. Fixed, because they are the logo rather than a theme. */
    private val plate = 0xFFF7F8FC.toInt()
    private val ink = 0xFF0A0A0C.toInt()
    private val red = 0xFFF0121A.toInt()

    var wordmark: String = ""

    /** Shown under the wordmark once the animation has outstayed its own length. */
    var status: String? = null
        set(value) {
            field = value
            if (running) postInvalidateOnAnimation()
        }

    /** While true the animation holds at its end rather than fading out. */
    var waiting: Boolean = false
        set(value) {
            val was = field
            field = value
            if (was && !value && running) postInvalidateOnAnimation()
        }

    private var startedAt = 0L
    private var fadeStartedAt = 0L
    private var running = false
    private var done: (() -> Unit)? = null

    /**
     * Honour the system's animator duration scale.
     *
     * Somebody who has turned animations off — for motion sensitivity, on a battery saver, or in
     * developer options — has already said what they want, and a boot animation is exactly the kind
     * of thing they turned off.
     */
    private val animationsOn: Boolean
        get() = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)

    /** Play, then call [onDone] on the main thread. Calling twice does nothing the second time. */
    fun play(onDone: () -> Unit) {
        if (running) return
        done = onDone

        if (!animationsOn) {
            visibility = GONE
            onDone()
            return
        }

        alpha = 1f
        visibility = VISIBLE
        running = true
        beads = Beads()
        startedAt = System.currentTimeMillis()
        fadeStartedAt = 0L
        postInvalidateOnAnimation()
    }

    /** End it now — a tap, or something that needs the screen. */
    fun skip() {
        if (!running) return
        waiting = false
        // Jump to the fade rather than vanishing: a hard cut reads as a crash.
        if (fadeStartedAt == 0L) fadeStartedAt = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Nothing animates for a window nobody is looking at.
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return

        // One clock. The simulation is advanced to wall-clock time in fixed sub-steps, and
        // everything — the frame's angle, the beads, the wordmark — is drawn from where it got to.
        // A stalled frame therefore runs the animation a fraction late rather than skipping physics.
        beads.advanceTo((System.currentTimeMillis() - startedAt).toFloat())
        val elapsed = beads.timeMs.toLong()

        // Past its natural end, and nothing is holding it: start the fade.
        if (fadeStartedAt == 0L && elapsed >= holdMs && !waiting)
            fadeStartedAt = System.currentTimeMillis()

        if (fadeStartedAt > 0L) {
            val fade = (System.currentTimeMillis() - fadeStartedAt).toFloat() / fadeMs
            if (fade >= 1f) {
                running = false
                visibility = GONE
                alpha = 1f
                done?.invoke()
                done = null
                return
            }
            alpha = 1f - fade
        }

        drawMark(canvas, elapsed)
        drawWord(canvas, elapsed)
        postInvalidateOnAnimation()
    }

    // ── the mark ────────────────────────────────────────────────────────────

    private fun drawMark(canvas: Canvas, elapsed: Long) {
        val cx = width / 2f
        val cy = height / 2f - height * 0.06f
        val size = min(width, height) * 0.29f

        // The angle comes from the simulation, because it is also the angle its gravity is resolved
        // along: the beads slide because the frame you can see turning is turning.
        val angle = beads.angleRadians() * 180f / Math.PI.toFloat()
        val arrival = ease(elapsed.toFloat() / arriveMs)
        val scale = 0.64f + 0.36f * overshoot(arrival)

        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.scale(scale, scale, cx, cy)

        // Square, like the mark. The frame is thick enough to be the loudest thing on screen.
        frame.set(cx - size, cy - size, cx + size, cy + size)
        val corner = size * 0.17f
        val border = size * 0.135f

        // A wash of the user's accent behind it, so the boot screen still belongs to their theme
        // even though the mark itself does not move off its own colours.
        fillPaint.color = withAlpha(accent, (52 * arrival).toInt())
        canvas.drawRoundRect(
            frame.left - size * 0.10f,
            frame.top - size * 0.10f,
            frame.right + size * 0.10f,
            frame.bottom + size * 0.10f,
            corner * 1.4f,
            corner * 1.4f,
            fillPaint,
        )

        // Black outer, white inner: the frame is the gap between two filled rectangles rather than a
        // stroke, which is how it stays crisp at any size and keeps the logo's square inner corners.
        val appear = min(1f, arrival * 1.7f)
        fillPaint.color = withAlpha(ink, (255 * appear).toInt())
        canvas.drawRoundRect(frame, corner, corner, fillPaint)

        interior.set(
            frame.left + border,
            frame.top + border,
            frame.right - border,
            frame.bottom - border,
        )
        fillPaint.color = withAlpha(plate, (255 * appear).toInt())
        canvas.drawRoundRect(interior, corner - border * 0.55f, corner - border * 0.55f, fillPaint)

        drawRodsAndBeads(canvas, border, appear)
        canvas.restore()
    }

    /**
     * The rods, and the beads wherever the simulation has put them.
     *
     * Nothing is decided here. Positions come from [Beads] in rod fractions and are scaled to
     * pixels; the red comes from the same place, as a number between 0 and 1 so a hand-off draws as
     * a crossfade rather than a swap.
     */
    private fun drawRodsAndBeads(canvas: Canvas, border: Float, appear: Float) {
        val beadRadius = interior.width() * Beads.BEAD_R
        val bandHeight = interior.height() / (Beads.RODS + 1)

        for (rod in 0 until Beads.RODS) {
            val y = interior.top + bandHeight * (rod + 1)

            fillPaint.color = withAlpha(ink, (255 * appear).toInt())
            canvas.drawRect(interior.left, y - border * 0.42f, interior.right, y + border * 0.42f, fillPaint)

            for (bead in 0 until Beads.PER_ROD) {
                val x = interior.left + interior.width() * beads.positionOf(rod, bead)
                fillPaint.color = withAlpha(
                    blend(ink, red, beads.rednessOf(rod, bead)),
                    (255 * appear).toInt(),
                )
                canvas.drawCircle(x, y, beadRadius, fillPaint)
            }
        }
    }

    private fun drawWord(canvas: Canvas, elapsed: Long) {
        val appear = ((elapsed - wordStartMs).toFloat() / wordMs).coerceIn(0f, 1f)
        if (appear <= 0f) return

        val cx = width / 2f
        val baseline = height / 2f + min(width, height) * 0.29f + height * 0.10f

        textPaint.color = withAlpha(0xFFEDF0F8.toInt(), (255 * ease(appear)).toInt())
        textPaint.textSize = min(width, height) * 0.062f
        textPaint.letterSpacing = 0.14f
        // Rises the last few pixels into place as it fades in.
        canvas.drawText(wordmark, cx, baseline + (1f - ease(appear)) * 14f, textPaint)

        val note = status
        if (waiting && !note.isNullOrBlank()) {
            textPaint.textSize = min(width, height) * 0.036f
            textPaint.letterSpacing = 0.02f
            textPaint.color = withAlpha(0xFF8E97B4.toInt(), 220)
            canvas.drawText(note, cx, baseline + textPaint.textSize * 2.2f, textPaint)
        }

        if (waiting) drawDots(canvas, cx, baseline + min(width, height) * 0.11f)
    }

    /** Three dots breathing in sequence: the whole loading indicator, and no extra view for it. */
    private fun drawDots(canvas: Canvas, cx: Float, y: Float) {
        val radius = min(width, height) * 0.009f
        val spacing = radius * 5f
        val phase = (System.currentTimeMillis() % 1_200L) / 1_200f

        for (i in -1..1) {
            val local = ((phase + i * 0.18f) % 1f)
            val lift = (sin(local * Math.PI * 2).toFloat() + 1f) / 2f
            dotPaint.color = withAlpha(accent, (70 + 150 * lift).toInt())
            canvas.drawCircle(cx + i * spacing, y, radius * (0.8f + 0.35f * lift), dotPaint)
        }
    }

    // ── easing and colour ───────────────────────────────────────────────────

    /** Decelerating: fast at the start, gentle into place. */
    private fun ease(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return 1f - (1f - clamped).pow(3)
    }

    /** A small overshoot, so things arrive with weight instead of stopping dead. */
    private fun overshoot(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        val c = 1.35f
        return 1f + (c + 1) * (clamped - 1).pow(3) + c * (clamped - 1).pow(2)
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) * (1 - a) + Color.red(to) * a).toInt(),
            (Color.green(from) * (1 - a) + Color.green(to) * a).toInt(),
            (Color.blue(from) * (1 - a) + Color.blue(to) * a).toInt(),
        )
    }

    private fun withAlpha(colour: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(colour), Color.green(colour), Color.blue(colour))
}
