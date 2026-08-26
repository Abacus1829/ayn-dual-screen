package com.abacus.dualscreen.boot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intro's loading state.
 *
 * The intro now doubles as the loading screen: it holds in free play while the update check runs and
 * resolves into the mark once the app is ready. That makes the hold a piece of startup logic rather
 * than a piece of decoration, and the ways it can go wrong are ways somebody gets stuck looking at a
 * logo — which is precisely the failure nobody notices on a fast desk connection.
 *
 * The simulation is pure arithmetic with no Android in it, so all of that is checkable here.
 */
class BeadsHoldTest {

    /** Run the simulation to a wall-clock time, the way the view does each frame. */
    private fun Beads.runTo(ms: Float) {
        var at = timeMs
        while (at < ms) {
            at += 16f
            advanceTo(at)
        }
    }

    @Test
    fun `holding keeps the run in free play however long it waits`() {
        val beads = Beads()
        beads.holding = true

        // Four times the length of the whole animation. A held intro has to survive a slow network,
        // not merely a slow frame.
        beads.runTo(Beads.TOTAL_MS * 4)

        assertEquals(
            "a held intro seated itself anyway, so the mark resolves while the app is still working",
            Beads.Phase.PLAY,
            beads.phase,
        )
        assertEquals("resolve started while still holding", 0f, beads.resolveMs, 0.01f)
    }

    @Test
    fun `releasing resolves from the moment of release, not from launch`() {
        val beads = Beads()
        beads.holding = true
        beads.runTo(Beads.TOTAL_MS * 3)

        beads.holding = false

        // Immediately after release the resolve has barely begun, even though the simulation has
        // been running for three times the animation's length. If the phase clock were still tied to
        // launch this would already be past EJECT and the seat would be skipped entirely.
        assertTrue(
            "released at resolveMs=${beads.resolveMs}, which means the seat was skipped",
            beads.resolveMs < 50f,
        )
        assertEquals("the seat did not begin on release", Beads.Phase.SEAT, beads.phase)

        // Still seating a moment later, rather than having skipped straight to the eject.
        beads.runTo(beads.timeMs + Beads.SEAT_MS / 2f)
        assertEquals("the seat was cut short", Beads.Phase.SEAT, beads.phase)

        beads.runTo(beads.timeMs + Beads.SEAT_MS)
        assertEquals("the eject did not follow the seat", Beads.Phase.EJECT, beads.phase)
    }

    @Test
    fun `a fast release still plays the whole of free play`() {
        /*
         * The case that shipped broken in 0.21.0, and the one this file did not cover.
         *
         * Every test here held for longer than the animation. The *common* case is the opposite: an
         * update check on a working connection answers in a couple of hundred milliseconds, and the
         * release lands long before free play would have ended on its own.
         *
         * The clock then jumped to the end of free play, so the 1500ms the beads spend sliding and
         * knocking into each other was skipped outright. On the device that reads as an intro that is
         * far too short with beads that never move — which is exactly what came back from the Thor.
         */
        val beads = Beads()
        beads.holding = true

        // A quick answer from the network.
        beads.runTo(200f)
        beads.holding = false

        assertEquals(
            "the seat began at 200ms, skipping the whole of free play",
            Beads.Phase.PLAY,
            beads.phase,
        )

        // Still in free play most of the way through, as an unheld run would be.
        beads.runTo(Beads.PLAY_MS - 100f)
        assertEquals("free play was cut short after a fast release", Beads.Phase.PLAY, beads.phase)

        // And it resolves on the natural schedule rather than early.
        beads.runTo(Beads.PLAY_MS + 60f)
        assertEquals("the seat did not arrive on time", Beads.Phase.SEAT, beads.phase)
    }

    @Test
    fun `a fast release is indistinguishable from never holding at all`() {
        // Stronger than the test above: the whole point is that a hold nobody waited on leaves no
        // trace in the animation. Same clock, same phase, at every point in the run.
        val held = Beads().apply { holding = true }
        val plain = Beads()

        held.runTo(150f)
        held.holding = false

        var at = 150f
        while (at < Beads.TOTAL_MS + 200f) {
            at += 16f
            held.advanceTo(at)
            plain.advanceTo(at)
            assertEquals(
                "a fast hold changed the animation at ${at}ms",
                plain.phase,
                held.phase,
            )
        }
    }

    @Test
    fun `a run that never holds behaves exactly as it did before`() {
        // The hold is opt-in. Every launch that has no startup work to wait for — which is most of
        // them — must be the animation that shipped, not a new one with a disabled branch in it.
        val beads = Beads()

        beads.runTo(Beads.PLAY_MS - 50f)
        assertEquals(Beads.Phase.PLAY, beads.phase)

        beads.runTo(Beads.PLAY_MS + 50f)
        assertEquals(Beads.Phase.SEAT, beads.phase)

        beads.runTo(Beads.TOTAL_MS + 50f)
        assertEquals(Beads.Phase.EJECT, beads.phase)
    }

    @Test
    fun `a twelve second hold is twelve seconds of beads still moving`() {
        /*
         * The long intro is long by *holding*, not by slowing anything down.
         *
         * That distinction is the whole reason it works: a stretched animation plays at half speed
         * and looks broken, whereas a held one is the same physics continuing to run. So the thing
         * worth pinning is that the extra time is spent in free play with the beads actually going
         * somewhere — not parked on a still frame waiting out a timer.
         */
        val beads = Beads()
        beads.holding = true

        var moved = 0
        var previous = beads.positionOf(0, 0)
        var at = 0f

        while (at < 12_000f) {
            at += 16f
            beads.advanceTo(at)
            val now = beads.positionOf(0, 0)
            if (kotlin.math.abs(now - previous) > 0.0005f) moved++
            previous = now
        }

        assertEquals("twelve seconds of holding left free play", Beads.Phase.PLAY, beads.phase)
        assertTrue(
            "the bead moved on only $moved of 750 frames, so most of the hold is a still picture",
            moved > 375,
        )
    }

    @Test
    fun `the ending is the same however long it was held`() {
        /*
         * However long the wait, the mark settles into the same shape.
         *
         * This is the backstop for the hold as a whole: a change that broke seating after a long wait
         * — targets computed from the wrong clock, a phase skipped, the spring never engaging — shows
         * up here as beads finishing somewhere other than their slots.
         *
         * It does **not** catch everything. The seat is a critically damped spring, so given enough
         * settling time it converges from almost any starting state, which means differences in the
         * *timing* of the ending are invisible to it. One such difference was found and fixed by
         * reading the code rather than by this test: the line deciding whether the outermost bead is
         * still being held through the seat was asking the wall clock instead of the phase clock, and
         * after twelve seconds of holding the wall clock is long past that point before the seat even
         * begins. No test here distinguishes that, and pretending otherwise would be worse than
         * saying so.
         */
        fun endingOf(holdMs: Float): List<Float> {
            val beads = Beads()
            beads.holding = true
            beads.runTo(holdMs)
            beads.holding = false

            // Well past the eject. The seat is a critically damped spring, so how long it takes to
            // arrive depends on how fast the bead was travelling when it started — and after a brief
            // hold the beads are still flying. Comparing before both have converged measures the
            // entry velocity, not the ending.
            beads.runTo(beads.timeMs + Beads.SEAT_MS + Beads.EJECT_MS + 2_000f)

            // Sorted per rod: which *bead* ends in which slot is decided by the collisions and is
            // legitimately different after twelve seconds of them. What must not change is the shape
            // the mark settles into.
            return (0 until Beads.RODS).flatMap { rod ->
                (0 until Beads.PER_ROD).map { beads.positionOf(rod, it) }.sorted()
            }
        }

        val brief = endingOf(200f)
        val long = endingOf(12_000f)

        brief.forEachIndexed { index, position ->
            assertEquals(
                "the mark settles into a different shape after a long hold",
                position.toDouble(),
                long[index].toDouble(),
                0.02,
            )
        }
    }

    @Test
    fun `the frame keeps moving while it waits`() {
        /*
         * The reason the hold is not just a paused frame.
         *
         * The rocking decays to nothing within a couple of seconds, so a hold that used the raw
         * decay would be a still picture of six motionless beads within about the time a slow update
         * check takes — a frozen frame, which is the exact thing a loading animation exists to avoid.
         */
        val beads = Beads()
        beads.holding = true
        beads.runTo(6_000f)

        var lowest = Float.MAX_VALUE
        var highest = -Float.MAX_VALUE
        var at = beads.timeMs
        // Captured before the loop. Reading beads.timeMs in the condition makes the bound advance
        // with the simulation, which is a loop that never ends.
        val until = beads.timeMs + 1_400f
        while (at < until) {
            at += 16f
            beads.advanceTo(at)
            val angle = beads.angleRadians()
            if (angle < lowest) lowest = angle
            if (angle > highest) highest = angle
        }

        assertTrue(
            "after six seconds of waiting the frame swings only ${highest - lowest} radians, " +
                "which on screen is a still image",
            highest - lowest > 0.02f,
        )
    }
}
