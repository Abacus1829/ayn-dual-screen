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

    /*
     * The timeline, in milliseconds.
     *
     * The physics owns the first part; these frame it. Everything after the mark resolves is timed
     * from the *release* rather than from launch, because the release can now be anywhere — the
     * intro holds in free play until startup work is done, and that might be forty milliseconds or
     * four seconds depending on the network.
     *
     * These numbers are deliberately slower than they were. The old sequence put the wordmark up
     * 350ms before the mark had finished arriving and faded 280ms later, which reads as hurried:
     * three things competing for the same half-second. Letting each one finish before the next
     * begins costs about a second in total and is most of the difference between an animation that
     * feels considered and one that feels like it is getting out of the way.
     */
    private val arriveMs = 1_050f

    /** Each branding line, timed from the moment the mark is released to resolve. */
    private val line1AtMs = 360f
    private val line2AtMs = 620f
    private val line3AtMs = 880f
    private val lineFadeMs = 540f

    /** How long the finished lockup is held before it goes. */
    private val dwellMs = 640f

    private val brandEndMs = line3AtMs + lineFadeMs + dwellMs
    private val fadeMs = 420L

    /** The simulation. Rebuilt on each play, so a second run is identical to the first. */
    private var beads = Beads()

    /**
     * The long version, for a first launch and the launch after an update.
     *
     * The short version is the same physics run faster with the dwell at the end removed — not a
     * different animation, so the mark still assembles and still lands. Speeding it up rather than
     * cutting it is what keeps a repeat launch feeling like the same app in a hurry rather than like
     * a cheaper version of it.
     */
    var full: Boolean = true

    /**
     * Two beads struck each other, and how hard, 0 to 1.
     *
     * Forwarded straight from the simulation rather than timed against the clock: the whole point is
     * that the sound happens on the frame the beads actually meet, so it belongs to the physics and
     * not to a schedule somebody wrote while watching it once.
     */
    var onBeadContact: ((Float) -> Unit)? = null

    /** Called as each bead reaches its stop, with its index, so a knock can land on that frame. */
    var onBeadSeated: ((Int) -> Unit)? = null

    /** Called once, when the mark has arrived and the red has settled. */
    var onLanded: (() -> Unit)? = null

    /** Beads that have already reported arriving, so each knocks once. */
    private val seated = mutableSetOf<Int>()
    private var landed = false

    /** The short version runs the same clock faster. */
    private val rate: Float get() = if (full) 1f else 1.9f

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

    /**
     * The lockup, in three parts.
     *
     * One word was a logo with a name under it. Three lines is an identity: what it is called, what
     * it is, and who made it — in that order, and each arriving after the one above has settled
     * rather than all at once. The last two are optional; leaving either blank simply drops that
     * line and closes the gap, so this is still one animation rather than one with variants.
     */
    var wordmark: String = ""

    /** What the thing actually is. */
    var subtitle: String = ""

    /** The credit. Quietest of the three, and the last to arrive. */
    var byline: String = ""

    /** Shown under the wordmark once the animation has outstayed its own length. */
    var status: String? = null
        set(value) {
            field = value
            if (running) postInvalidateOnAnimation()
        }

    /**
     * While true the animation stays in free play instead of resolving into the mark.
     *
     * This is the loading state, and it is the same run of the same simulation — the frame has spun
     * in, it is rocking, the beads are still knocking about, and none of the branding has appeared
     * yet. Set it false and the beads seat, the lockup arrives and the sound plays.
     *
     * Setting it true after the mark has already resolved does nothing: the run is past that point
     * and rewinding it would be a cut.
     */
    var waiting: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            beads.holding = value
            if (running) postInvalidateOnAnimation()
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
        // Carried onto the fresh simulation, so an intro that starts already waiting holds from its
        // first frame rather than resolving once and then being told to wait.
        beads.holding = waiting
        beads.onContact = { strength -> onBeadContact?.invoke(strength) }
        seated.clear()
        landed = false
        startedAt = System.currentTimeMillis()
        fadeStartedAt = 0L
        postInvalidateOnAnimation()
    }

    /** End it now — a tap, or something that needs the screen. */
    fun skip() {
        if (!running) return
        // Releasing first matters: a tap during the loading state has to let the physics out of the
        // hold, or the fade would take away a mark that never finished assembling.
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
        beads.advanceTo((System.currentTimeMillis() - startedAt) * rate)
        val elapsed = beads.timeMs.toLong()
        announce(elapsed)

        // The lockup has been up long enough, and nothing is holding it: start the fade.
        if (fadeStartedAt == 0L && !waiting && beads.resolveMs >= brandEndMs * rate)
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

    /**
     * Tell whoever is listening about the moments worth hearing.
     *
     * A bead counts as arrived when it has stopped moving *and* is close to where it will end up —
     * both, because a bead at the far wall mid-bounce is momentarily still and a bead drifting past
     * its target is momentarily in the right place. Reported once each, in the order they settle, so
     * six knocks arrive in the rhythm the physics produced rather than on a schedule somebody wrote.
     */
    private fun announce(elapsed: Long) {
        if (elapsed < 200) return

        for (rod in 0 until Beads.RODS) {
            for (index in 0 until Beads.PER_ROD) {
                val key = rod * Beads.PER_ROD + index
                if (key in seated) continue
                if (!beads.settled(rod, index)) continue

                seated += key
                onBeadSeated?.invoke(seated.size - 1)
            }
        }

        if (!landed && elapsed >= Beads.TOTAL_MS - 40) {
            landed = true
            onLanded?.invoke()
        }
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

    /**
     * The lockup, or the loading line — never both.
     *
     * While the intro is waiting there is no branding at all: the mark rocks, a status line and
     * three dots sit under it, and that is the whole loading state. The name only appears once the
     * app is actually ready, which is what makes its arrival mean something rather than being a
     * caption that happened to be on screen while a download finished.
     */
    private fun drawWord(canvas: Canvas, elapsed: Long) {
        val cx = width / 2f
        val baseline = height / 2f + min(width, height) * 0.29f + height * 0.10f

        if (waiting) {
            drawWaiting(canvas, cx, baseline)
            return
        }

        val since = beads.resolveMs / rate
        val unit = min(width, height)

        // ABACUS — the name, and the only line at full weight.
        drawLine(
            canvas, wordmark, cx, baseline,
            at = line1AtMs, since = since,
            size = unit * 0.066f, spacing = 0.16f, colour = 0xFFEDF0F8.toInt(), rise = 16f,
        )

        // DUAL SCREEN INTERFACE — what it is. Wider tracking and much smaller, so it reads as a
        // descriptor under the name rather than as a second name.
        drawLine(
            canvas, subtitle, cx, baseline + unit * 0.052f,
            at = line2AtMs, since = since,
            size = unit * 0.030f, spacing = 0.30f, colour = 0xFFA8B2CC.toInt(), rise = 10f,
        )

        // Made by Abacus — the credit, quietest, last, and set apart from the pair above it.
        drawLine(
            canvas, byline, cx, baseline + unit * 0.108f,
            at = line3AtMs, since = since,
            size = unit * 0.026f, spacing = 0.04f, colour = 0xFF6F7A96.toInt(), rise = 8f,
        )
    }

    /**
     * One line of the lockup, fading and rising into place.
     *
     * The rise is small and the fade is slow, which is the opposite of how these are usually built.
     * A line that travels a long way draws attention to the motion; a line that barely moves draws
     * attention to itself, and the motion is only there to stop it appearing from nowhere.
     */
    private fun drawLine(
        canvas: Canvas,
        text: String,
        cx: Float,
        y: Float,
        at: Float,
        since: Float,
        size: Float,
        spacing: Float,
        colour: Int,
        rise: Float,
    ) {
        if (text.isBlank()) return

        val appear = ((since - at) / lineFadeMs).coerceIn(0f, 1f)
        if (appear <= 0f) return

        val eased = soften(appear)
        textPaint.color = withAlpha(colour, (255 * eased).toInt())
        textPaint.textSize = size
        textPaint.letterSpacing = spacing
        canvas.drawText(text, cx, y + (1f - eased) * rise, textPaint)
    }

    /** The loading state: what is happening, and three dots saying it still is. */
    private fun drawWaiting(canvas: Canvas, cx: Float, baseline: Float) {
        val note = status
        if (!note.isNullOrBlank()) {
            textPaint.textSize = min(width, height) * 0.032f
            textPaint.letterSpacing = 0.06f
            textPaint.color = withAlpha(0xFF8E97B4.toInt(), 210)
            canvas.drawText(note, cx, baseline, textPaint)
        }

        drawDots(canvas, cx, baseline + min(width, height) * 0.055f)
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

    /**
     * Smoothstep: eased at both ends.
     *
     * [ease] decelerates only, which is right for something arriving under its own momentum and
     * wrong for text, where the abrupt start of the fade is visible as a flicker at low alpha.
     */
    private fun soften(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
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
