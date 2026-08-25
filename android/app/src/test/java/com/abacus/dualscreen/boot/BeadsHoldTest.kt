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
