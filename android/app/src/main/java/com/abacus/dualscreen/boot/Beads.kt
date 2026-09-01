package com.abacus.dualscreen.boot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The beads, simulated.
 *
 * The abacus spins, and the beads slide **because** it spins: gravity along a rod is `g·sin(θ)` for
 * the same θ the frame is drawn at, so what moves them is the thing you can see moving. They collide
 * with each other and with the stops, and the red goes with the momentum — on every contact it
 * passes to whichever bead comes out of it travelling faster, which is what a struck bead does when
 * the masses are equal.
 *
 * It is deliberately 1-D and dimensionless: a rod is 0..1, a bead has a radius of 0.099 of it, and
 * pixels are somebody else's problem. Six particles, 4 ms sub-steps — a few hundred floating-point
 * operations a frame.
 *
 * ## Why it does not simulate all the way to the end
 *
 * The logo is a fixed pose — two beads at the left stop, one alone at the right, three at the left
 * below — and free dynamics do not converge on a fixed pose. Several thousand parameter combinations
 * were tried; the closest landed a fifth of a rod out with the beads still moving. Tuning further
 * would have meant balancing a chaotic system on a point, which works on one device and drifts on
 * the next.
 *
 * So there are three acts, and only the first is free:
 *
 * - **[Phase.PLAY]** — the spin, the sliding, the collisions, the red changing hands. Genuinely
 *   simulated and genuinely chaotic.
 * - **[Phase.SEAT]** — the frame is level and friction has taken over. Each bead is pulled to its
 *   place by a critically damped spring, which is what a bead pushed home and clicking against its
 *   neighbour looks like. The red is handed along the stack one neighbour at a time as they compress.
 * - **[Phase.EJECT]** — the outermost bead, now holding the red, slides on to the far stop. One bead
 *   separating from the group for a reason, which is the shape of the logo.
 *
 * Seating targets are assigned in current left-to-right order, so no bead ever crosses another on
 * its way home, and the last frame is the mark to within a thousandth of a rod.
 */
internal class Beads {

    private class Bead(var x: Float, var v: Float)

    enum class Phase { PLAY, SEAT, EJECT }

    /** How far the simulation has been advanced. Everything on screen is drawn from this clock. */
    var timeMs = 0f
        private set

    private val rods = Array(RODS) { rod ->
        Array(PER_ROD) { i ->
            // The top rod starts stacked at the far stop, so it has a run-up: it arrives hard, and
            // its far bead recoils away carrying the red. The bottom rod starts already home, so it
            // only ever jostles. Same physics, different starting place.
            val x = if (rod == 0) RIGHT - GAP * (PER_ROD - 1 - i) else LEFT + GAP * i
            Bead(x, 0f)
        }
    }

    /** Which bead is red, and which one it just came from, so a pass can be drawn as a pass. */
    private var redRod = 0
    private var redIndex = PER_ROD - 1
    private var redFrom = PER_ROD - 1
    private var redChangedMs = -1_000f

    private var lastTransferMs = -1_000f

    /** Where each bead is heading once seating starts. Null until then. */
    private var targets: Array<FloatArray>? = null

    /** Scheduled hand-offs as the stack compresses: {at, from, to}. */
    private val hops = mutableListOf<FloatArray>()

    /**
     * Called when two beads actually strike each other, with how hard.
     *
     * The strength is the closing speed, normalised — so a glancing touch and a hard knock are told
     * apart by the caller rather than every contact sounding identical. This is a *contact*, not an
     * arrival: a bead reaching its final stop is a different event and is not reported here.
     */
    var onContact: ((strength: Float) -> Unit)? = null

    /**
     * Stay in free play rather than moving on to seat and eject.
     *
     * This is the loading state, and it is the *same* simulation rather than a second animation: the
     * frame has already spun in and is rocking, the beads are still sliding and knocking into each
     * other, and nothing about the mark is different — it simply has not been told to resolve yet.
     *
     * Set it false and the run continues from wherever it had got to, so what the eye sees is the
     * rocking settling into the finished mark. There is no cut and no restart.
     */
    var holding: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Released: the seat and eject phases are timed from this moment, not from launch.
            if (!value) releasedAt = timeMs
        }

    private var releasedAt = -1f

    /**
     * The clock the phases are read from, which is not always the clock the physics runs on.
     *
     * While holding, this stops just short of the end of free play, so no amount of waiting for the
     * network can push the beads into seating. Once released it resumes, offset so that the moment
     * of release *is* the start of the seat.
     */
    private val phaseMs: Float
        get() {
            if (holding) return minOf(timeMs, PLAY_MS - 1f)

            /*
             * Released before free play would have ended anyway.
             *
             * This is the common case and it was wrong: the release simply moved the clock to the end
             * of free play, so an update check that answered in 200ms — which is what a working wifi
             * connection does — skipped the entire 1500ms the beads spend actually sliding and
             * knocking, and the intro cut straight to seating. Short, and with motionless beads.
             *
             * Nothing was held back in that case, so nothing needs compensating for: the run
             * continues on its own clock and plays exactly as it did before there was a hold at all.
             */
            val released = releasedAt
            if (released < 0f || released <= PLAY_MS) return timeMs

            // Genuinely held past the natural end, so the resolve is timed from the release.
            return PLAY_MS + (timeMs - released)
        }

    val phase: Phase
        get() = when {
            phaseMs < PLAY_MS -> Phase.PLAY
            phaseMs < PLAY_MS + SEAT_MS -> Phase.SEAT
            else -> Phase.EJECT
        }

    /** How far through the resolve we are, for whoever is drawing. 0 while holding. */
    val resolveMs: Float get() = (phaseMs - PLAY_MS).coerceAtLeast(0f)

    /** The angle the frame is at, and the angle its gravity is resolved along. One number, both jobs. */
    fun angleRadians(): Float = angleAt(timeMs)

    /** Bead position along its rod, 0..1. */
    fun positionOf(rod: Int, index: Int): Float = rods[rod][index].x

    /**
     * Has this bead arrived and stopped?
     *
     * Both conditions, and both are needed. A bead at the far wall mid-bounce is momentarily still
     * but nowhere near home; a bead drifting past its target is momentarily in the right place but
     * still travelling. Used to sound a knock on the frame a bead actually lands, rather than at a
     * timestamp that was right on whichever device it was tuned on.
     */
    fun settled(rod: Int, index: Int): Boolean {
        val target = targets?.get(rod)?.get(index) ?: return false
        val bead = rods[rod][index]
        return abs(bead.v) < STILL_SPEED && abs(bead.x - target) < STILL_DISTANCE
    }

    /**
     * How red a bead is, 0..1.
     *
     * A hand-off is a short crossfade rather than a swap: the colour visibly leaves one bead and
     * arrives on the next, which is the difference between passing something and it blinking.
     */
    fun rednessOf(rod: Int, index: Int): Float {
        if (rod != redRod) return 0f
        val t = ((timeMs - redChangedMs) / TRANSFER_FADE_MS).coerceIn(0f, 1f)
        return when (index) {
            redIndex -> t
            redFrom -> 1f - t
            else -> 0f
        }
    }

    /**
     * Advance to [toMs] in fixed sub-steps.
     *
     * Fixed, so the run is identical on a 60 Hz panel and a 120 Hz one — a variable step would make
     * the collisions, and therefore where the red ends up, depend on the frame rate. The catch-up is
     * capped: after a stall the animation runs a fraction late rather than teleporting through the
     * physics it missed.
     */
    fun advanceTo(toMs: Float) {
        val target = minOf(toMs, timeMs + MAX_CATCHUP_MS)
        while (timeMs < target) {
            step()
            timeMs += SUB_MS
        }
    }

    // ── the model ───────────────────────────────────────────────────────────

    private fun step() {
        val seating = phaseMs >= PLAY_MS
        if (seating && targets == null) beginSeating()

        while (hops.isNotEmpty() && timeMs >= hops[0][0]) {
            val hop = hops.removeAt(0)
            handRedTo(0, hop[2].toInt())
        }

        val along = if (seating) 0f else G * sin(angleAt(timeMs))
        val dt = SUB_MS / 1000f

        for (rod in 0 until RODS) {
            val beads = rods[rod]

            for (index in beads.indices) {
                val bead = beads[index]
                if (seating) {
                    // Critically damped: arrives without overshoot, which is what "clicks into
                    // place" means. The bead bound for the far stop is held until the stack has
                    // finished compressing, so it leaves rather than drifting out with the others.
                    val target = targets!![rod][index]

                    /*
                     * The phase clock, not the wall clock.
                     *
                     * This asks "are we still seating", and it used to ask it of \`timeMs\` — which was
                     * the same thing right up until the intro learned to hold. After a twelve-second
                     * hold \`timeMs\` is already far past PLAY_MS + SEAT_MS when seating *begins*, so
                     * the test was false from the first frame and the bead bound for the far stop
                     * left immediately instead of waiting for the stack to finish compressing.
                     *
                     * Same question, asked of the clock the phases are actually measured on.
                     */
                    val held = phaseMs < PLAY_MS + SEAT_MS && target == RIGHT
                    val to = if (held) bead.x else target
                    bead.v += (-SEAT_K * (bead.x - to) - 2f * sqrt(SEAT_K) * bead.v) * dt
                } else {
                    bead.v = (bead.v + along * dt) * exp(-DRAG * dt)
                }
                bead.x += bead.v * dt
            }

            for (bead in beads) {
                if (bead.x < LEFT) {
                    bead.x = LEFT
                    bead.v = abs(bead.v) * WALL_E
                }
                if (bead.x > RIGHT) {
                    bead.x = RIGHT
                    bead.v = -abs(bead.v) * WALL_E
                }
            }

            // Several passes, so a three-bead stack resolves within one sub-step instead of
            // shivering across the next few.
            repeat(CONTACT_PASSES) {
                for (i in 0 until beads.size - 1) {
                    val a = beads[i]
                    val b = beads[i + 1]
                    val gap = b.x - a.x
                    if (gap >= 2 * BEAD_R) continue

                    val push = (2 * BEAD_R - gap) / 2f
                    a.x -= push
                    b.x += push
                    if (a.v - b.v <= 0f) continue   // already separating

                    // Equal masses: at restitution 1 the velocities simply swap, which is why an
                    // abacus rattles the way it does.
                    // How hard, before the velocities are changed by the collision itself.
                    val closing = a.v - b.v

                    val av = ((1 - BEAD_E) * a.v + (1 + BEAD_E) * b.v) / 2f
                    val bv = ((1 + BEAD_E) * a.v + (1 - BEAD_E) * b.v) / 2f
                    a.v = av
                    b.v = bv

                    /*
                     * A contact worth hearing.
                     *
                     * Two beads resting against each other under gravity are technically colliding
                     * on every one of the contact passes, thousands of times a second, and sounding
                     * all of those would be a buzz rather than a clink. Only a genuine strike — one
                     * with real closing speed behind it — is reported.
                     */
                    if (closing > CONTACT_THRESHOLD) {
                        onContact?.invoke((closing / CONTACT_LOUD).coerceIn(0.15f, 1f))
                    }

                    if (seating || timeMs - lastTransferMs < TRANSFER_COOLDOWN_MS) continue

                    // The red goes with the momentum. The cooldown is what makes one pulse read as
                    // one pass: a stack rings several times in a few milliseconds, and drawing every
                    // one of those is a flicker rather than a hand-off.
                    if (redRod == rod && redIndex == i && abs(bv) > abs(av) + EPSILON) {
                        handRedTo(rod, i + 1)
                    } else if (redRod == rod && redIndex == i + 1 && abs(av) > abs(bv) + EPSILON) {
                        handRedTo(rod, i)
                    }
                }
            }
        }
    }

    private fun handRedTo(rod: Int, index: Int) {
        if (rod == redRod && index == redIndex) return
        redFrom = redIndex
        redRod = rod
        redIndex = index
        redChangedMs = timeMs
        lastTransferMs = timeMs
    }

    /**
     * Work out where everything is going, once.
     *
     * Targets are handed out in the order the beads currently lie in, so the leftmost bead takes the
     * leftmost place and nothing has to pass through anything. The red is scheduled to travel to the
     * outermost bead one neighbour at a time — the pulse running through the stack as it compresses.
     */
    private fun beginSeating() {
        targets = Array(RODS) { rod ->
            val beads = rods[rod]
            val order = beads.indices.sortedBy { beads[it].x }
            val wanted = if (rod == 0) TOP_TARGETS else BOTTOM_TARGETS
            FloatArray(beads.size).also { out ->
                order.forEachIndexed { place, beadIndex -> out[beadIndex] = wanted[place] }
            }
        }

        val top = rods[0]
        val order = top.indices.sortedBy { top[it].x }
        val outer = order.last()
        val from = order.indexOf(if (redRod == 0) redIndex else order.first())
        val to = order.indexOf(outer)

        hops.clear()
        if (redRod != 0) handRedTo(0, order.first())

        var step = from
        while (step != to) {
            val next = step + sign((to - step).toFloat()).toInt()
            hops += floatArrayOf(timeMs + (hops.size + 1) * HOP_MS, order[step].toFloat(), order[next].toFloat())
            step = next
        }
    }

    private fun angleAt(ms: Float): Float =
        if (ms < SPIN_MS) {
            (1f - ease(ms / SPIN_MS)) * -2f * PI.toFloat() * TURNS
        } else {
            // Landed, and rocking itself still: a decaying wobble, like something set down on a
            // table. This is the part that sweeps the beads into the stops.
            val s = (ms - SPIN_MS) / 1000f

            /*
             * While holding, the wobble decays to a floor instead of to nothing.
             *
             * A rock that dies out completely would leave the loading state as a still picture with
             * six motionless beads, which is the frozen frame this whole approach exists to avoid.
             * Holding a little energy in it keeps the beads drifting and occasionally knocking, so
             * the screen stays alive without anything new being drawn.
             */
            val decay = exp(-ROCK_DECAY * s)
            val energy = if (holding) maxOf(decay, HOLD_ROCK_FLOOR) else decay

            -ROCK_AMPLITUDE * sin(2f * PI.toFloat() * ROCK_HZ * s) * energy
        }

    private fun ease(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return 1f - (1f - clamped).pow(3)
    }

    companion object {
        const val RODS = 2
        const val PER_ROD = 3

        /** Bead radius as a fraction of a rod, matching the mark's proportions. */
        const val BEAD_R = 0.099f

        /** Centre-to-centre spacing of touching beads, with a hair of daylight so they read apart. */
        const val GAP = 2 * BEAD_R * 1.02f

        const val LEFT = BEAD_R
        const val RIGHT = 1f - BEAD_R

        /** The three acts, in milliseconds from the first frame. */
        const val PLAY_MS = 1_500f
        const val SEAT_MS = 260f
        const val EJECT_MS = 420f
        const val TOTAL_MS = PLAY_MS + SEAT_MS + EJECT_MS

        /** How much of the rock survives while the intro is waiting on startup work. */
        private const val HOLD_ROCK_FLOOR = 0.34f

        /**
         * The closing speed below which a contact is beads settling rather than beads striking.
         *
         * Tuned to the units the simulation works in — rod length is 1.0 — so this is "a fifth of a
         * rod per second", which is a nudge. Anything slower is the stack breathing.
         */
        private const val CONTACT_THRESHOLD = 0.2f

        /** The closing speed that counts as a full-strength strike. */
        private const val CONTACT_LOUD = 1.6f

        private const val SPIN_MS = 1_200f
        private const val TURNS = 1.5f

        private const val SUB_MS = 4f
        private const val MAX_CATCHUP_MS = 100f
        private const val CONTACT_PASSES = 6

        private const val G = 16f
        private const val DRAG = 0.7f
        private const val WALL_E = 0.55f
        private const val BEAD_E = 0.99f
        private const val SEAT_K = 220f

        private const val ROCK_AMPLITUDE = 0.42f
        private const val ROCK_HZ = 1.1f
        private const val ROCK_DECAY = 3.4f

        private const val TRANSFER_COOLDOWN_MS = 150f
        private const val TRANSFER_FADE_MS = 95f
        private const val HOP_MS = 95f

        private const val EPSILON = 1e-6f

        /** Slow enough and close enough to count as arrived. See [settled]. */
        private const val STILL_SPEED = 0.05f
        private const val STILL_DISTANCE = 0.012f

        private val TOP_TARGETS = floatArrayOf(LEFT, LEFT + GAP, RIGHT)
        private val BOTTOM_TARGETS = floatArrayOf(LEFT, LEFT + GAP, LEFT + GAP * 2)
    }
}
