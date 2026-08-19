package com.abacus.dualscreen.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry policy.
 *
 * These assertions are about behaviour somebody would notice: that a restarted game is picked up
 * quickly, that an absent PC is not hammered, and that the retrying stops. The exact millisecond
 * values are deliberately pinned — the whole point of this object is that the numbers are a decision
 * rather than an accident, and a change to them should have to be made on purpose.
 */
class BackoffTest {

    @Test
    fun `the first retries are quick`() {
        // A game being restarted comes back in seconds; this is the case that must feel instant.
        assertEquals(1_500L, Backoff.delayFor(1))
        assertEquals(1_500L, Backoff.delayFor(2))
    }

    @Test
    fun `the delay grows and never shrinks`() {
        var previous = 0L
        for (attempt in 1..Backoff.MAX_ATTEMPTS) {
            val delay = Backoff.delayFor(attempt)
            assertTrue("attempt $attempt went backwards", delay >= previous)
            previous = delay
        }
    }

    @Test
    fun `a long outage settles at half a minute`() {
        assertEquals(30_000L, Backoff.delayFor(Backoff.MAX_ATTEMPTS))
        assertEquals(30_000L, Backoff.delayFor(Backoff.MAX_ATTEMPTS + 50))
    }

    @Test
    fun `retrying stops`() {
        assertFalse(Backoff.exhausted(Backoff.MAX_ATTEMPTS - 1))
        assertTrue(Backoff.exhausted(Backoff.MAX_ATTEMPTS))
        assertTrue(Backoff.exhausted(Backoff.MAX_ATTEMPTS + 1))
    }

    @Test
    fun `the whole budget is minutes, not hours`() {
        // Guards against somebody raising MAX_ATTEMPTS without noticing what it costs: a handheld
        // must not sit chasing a switched-off PC all evening.
        val total = Backoff.totalWait()
        assertTrue("budget was ${total}ms", total in 60_000L..600_000L)
    }

    @Test
    fun `an attempt count of zero is still a real delay`() {
        // Defensive: the session screen increments before it schedules, but a zero must never
        // produce a zero-length wait and a tight loop.
        assertTrue(Backoff.delayFor(0) >= 1_000L)
    }
}
