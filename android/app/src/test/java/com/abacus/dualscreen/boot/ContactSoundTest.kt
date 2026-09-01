package com.abacus.dualscreen.boot

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many sounds the introduction actually makes.
 *
 * This exists because of a report of a loud noise at the end of the animation, twice. The first time
 * it was two cues stacked on the same frame. The second time it was this: the contact solver runs six
 * passes per four-millisecond sub-step — roughly two dozen times per drawn frame — and a pair of
 * beads that is touching gets *resolved* on every one of them. Reporting each as a strike meant one
 * collision produced dozens of sounds, and the seat, where a spring holds every bead pressed against
 * its neighbour, produced a continuous roar.
 *
 * Neither was audible in the code. Both are obvious the moment you count.
 */
class ContactSoundTest {

    private fun Beads.runTo(ms: Float) {
        var at = timeMs
        while (at < ms) {
            at += 16f
            advanceTo(at)
        }
    }

    /** Every contact the simulation reports across a whole unheld run. */
    private fun contactsOverFullRun(): MutableList<Float> {
        val heard = mutableListOf<Float>()
        val beads = Beads()
        beads.onContact = { heard += it }
        beads.runTo(Beads.TOTAL_MS + 400f)
        return heard
    }

    @Test
    fun `the whole introduction is a handful of clinks, not a roar`() {
        val heard = contactsOverFullRun()

        /*
         * Six beads knocking about for a second and a half, then settling. That is a couple of dozen
         * genuine strikes at the very most — and the number that matters is the ceiling, because the
         * failure this guards against produced *hundreds*.
         */
        assertTrue(
            "the introduction fired ${heard.size} sounds, which is a noise rather than beads",
            heard.size <= 40,
        )

        // And it should not be silent either, or the animation has lost its only sound.
        assertTrue("the introduction made no sound at all", heard.isNotEmpty())
    }

    @Test
    fun `the ending does not pile up`() {
        /*
         * The specific complaint: a bang as the mark lands.
         *
         * The seat pulls every bead against its neighbour and holds it there, so this is exactly
         * where a per-pass report becomes a continuous tone. Counting only what happens after free
         * play ends isolates it from the knocking that is supposed to be there.
         */
        val heard = mutableListOf<Float>()
        val beads = Beads()

        beads.runTo(Beads.PLAY_MS)
        beads.onContact = { heard += it }          // listen only from the seat onwards
        beads.runTo(Beads.TOTAL_MS + 400f)

        assertTrue(
            "the seat and eject fired ${heard.size} sounds on top of each other, which is the bang " +
                "at the end of the animation",
            heard.size <= 8,
        )
    }

    @Test
    fun `beads resting together are silent`() {
        /*
         * The bottom rod starts already stacked at its stop, so its pairs are in contact from the
         * first frame and stay that way while the frame is still level. Nothing about that should
         * make a sound: resting beads are quiet, and a held contact is not a strike.
         */
        val heard = mutableListOf<Float>()
        val beads = Beads()
        beads.onContact = { heard += it }

        // The first fifty milliseconds, before the spin has built up any speed.
        beads.runTo(50f)

        assertTrue(
            "${heard.size} sounds in the first fifty milliseconds, while the beads are only resting " +
                "against each other",
            heard.size <= 2,
        )
    }
}
