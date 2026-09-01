package com.abacus.dualscreen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The synthesised interface sounds.
 *
 * These exist because the failure modes of generated audio are invisible in code and inaudible
 * without headphones on a real device. A wrong envelope is silence. A wrong gain is a click. A
 * division that produces a NaN writes zeroes and nobody notices until somebody asks why the app has
 * gone quiet. None of that shows up in a screenshot or a build log.
 *
 * So each cue is rendered on the desktop JVM and checked for the properties a usable sound has:
 * it exists, it is short enough to be interface audio rather than a ringtone, it is loud enough to
 * be heard and quiet enough not to clip, and — the one that catches the most bugs — it **starts
 * near silence**, because a waveform that begins at full amplitude is the click that makes cheap
 * interface audio sound cheap.
 *
 * Nothing here asserts what a cue sounds like. That is a judgement, and a test that pinned it would
 * be a test that has to be rewritten every time somebody tunes a number.
 */
class SoundsTest {

    /** 16-bit full scale. Samples are compared against this rather than against a magic 32767. */
    private val fullScale = Short.MAX_VALUE.toInt()

    @Test
    fun `every cue renders to audible, unclipped audio`() {
        for (cue in Sounds.Cue.entries) {
            val samples = Sounds.render(cue)

            assertTrue("$cue rendered nothing", samples.isNotEmpty())

            val peak = samples.maxOf { kotlin.math.abs(it.toInt()) }

            assertTrue(
                "$cue is silent — peak $peak. An envelope or a gain is wrong.",
                peak > fullScale / 50,
            )
            assertTrue(
                "$cue clips — peak $peak of $fullScale. Stacked partials are exceeding full scale.",
                peak < fullScale,
            )
        }
    }

    @Test
    fun `tonal cues start near silence`() {
        // The first millisecond. A waveform that begins loud clicks, and the click is audible on
        // exactly the cheap speakers a handheld has.
        val window = 44

        /*
         * The knock is exempt, and not as a fudge.
         *
         * BEAD is a wooden knock — filtered noise under a very fast decay — and a knock *is* its
         * transient. Ramping it in would turn it into a soft mallet hit, which is a different
         * instrument and a worse fit for a bead hitting a stop. Every cue that is a note is held to
         * the rule; the one that is a percussion hit is not.
         */
        val percussive = setOf(Sounds.Cue.BEAD)

        for (cue in Sounds.Cue.entries - percussive) {
            val samples = Sounds.render(cue)
            val opening = samples.take(window).maxOf { kotlin.math.abs(it.toInt()) }
            val peak = samples.maxOf { kotlin.math.abs(it.toInt()) }

            assertTrue(
                "$cue opens at $opening against a peak of $peak — that is a click, not an attack.",
                opening < peak / 2,
            )
        }
    }

    @Test
    fun `every cue is short enough to be feedback`() {
        val rate = 44_100

        for (cue in Sounds.Cue.entries) {
            val ms = Sounds.render(cue).size * 1000 / rate

            /*
             * Every cue, with no exception any more.
             *
             * There used to be one: INTRO was a second-long phrase played over the animation. It is
             * gone, and with it the idea that this app has a piece of music in it — the introduction
             * is scored by its own physics now, one clink per contact. So there is nothing left here
             * that is allowed to be long.
             *
             * BEAD is the longest of them because a struck glass bead rings, and a ring cut off at a
             * fifth of a second is a click. It still has to end well inside the gap between two
             * contacts or a busy moment turns into a smear.
             */
            assertTrue(
                "$cue is $ms ms. Anything past a third of a second stops being feedback and " +
                    "starts being a noise you are waiting out.",
                ms in 20..330,
            )
        }
    }

    @Test
    fun `a press is the quietest thing in the set`() {
        // Deliberate, and worth pinning: TAP plays on every single press, and a press sound at the
        // same level as a confirmation is the one people switch the whole feature off over.
        val tap = Sounds.render(Sounds.Cue.TAP).maxOf { kotlin.math.abs(it.toInt()) }
        val confirm = Sounds.render(Sounds.Cue.CONFIRM).maxOf { kotlin.math.abs(it.toInt()) }
        val select = Sounds.render(Sounds.Cue.SELECT).maxOf { kotlin.math.abs(it.toInt()) }

        assertTrue("a tap ($tap) should be quieter than a confirmation ($confirm)", tap < confirm)
        assertTrue("a tap ($tap) should be quieter than a selection ($select)", tap < select)
    }

    @Test
    fun `rendering is deterministic, including the noisy one`() {
        // The knock uses a random source. It is seeded, so it is the same knock every time rather
        // than a slightly different one per launch — which would be audible as inconsistency.
        for (cue in listOf(Sounds.Cue.BEAD)) {
            val first = Sounds.render(cue)
            val second = Sounds.render(cue)
            assertTrue("$cue changed between renders", first.contentEquals(second))
        }
    }

    @Test
    fun `direction is audible - going in rises and coming out falls`() {
        /*
         * The one property the whole design rests on: forward moves up, back moves down. Measured
         * by comparing where the energy sits in the first half against the second, which is a crude
         * proxy for pitch but a completely reliable one for two-note figures.
         */
        assertTrue("SELECT should rise", risesInPitch(Sounds.render(Sounds.Cue.SELECT)))
        assertTrue("BACK should fall", !risesInPitch(Sounds.render(Sounds.Cue.BACK)))
        assertTrue("TOGGLE_ON should rise", risesInPitch(Sounds.render(Sounds.Cue.TOGGLE_ON)))
        assertTrue("TOGGLE_OFF should fall", !risesInPitch(Sounds.render(Sounds.Cue.TOGGLE_OFF)))
    }

    /** Zero crossings per half: more crossings means a higher pitch, which is all this needs. */
    private fun risesInPitch(samples: ShortArray): Boolean {
        val half = samples.size / 2
        return crossings(samples, 0, half) < crossings(samples, half, samples.size)
    }

    private fun crossings(samples: ShortArray, from: Int, to: Int): Int {
        var count = 0
        for (i in from + 1 until to) {
            if ((samples[i - 1] < 0) != (samples[i] < 0)) count++
        }
        return count
    }

    @Test
    fun `the palette has not silently lost a cue`() {
        /*
         * A cue removed from the enum is a call site that stops making a sound with no compile error
         * anywhere, because everything goes through Feedback.
         *
         * It went from nine to eight deliberately: INTRO, the phrase that played over the boot
         * animation, was removed rather than quietly stopping — the introduction is scored by its own
         * physics now, one clink per bead contact, and a tune on top of that is a soundtrack.
         *
         * This test caught that change, which is exactly its job. The number is updated because the
         * removal was intended; if it ever fails again the first question is whether it was.
         */
        assertEquals(8, Sounds.Cue.entries.size)
    }
}
