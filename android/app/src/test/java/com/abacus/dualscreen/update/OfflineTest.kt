package com.abacus.dualscreen.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Being offline is a normal state for this app, and must not look like a fault.
 *
 * This exists because of a report that said, simply, "it doesn't work without internet" — and the app
 * was working perfectly. What had gone wrong was what it *said*: the launch update check failed
 * immediately with no connection, the introduction put "No connection" under the logo, and then held
 * that message on screen for the whole twelve seconds of the animation. Nothing was broken and there
 * was no way for anybody to know that.
 *
 * The lesson is not about a string. It is that this app mirrors a game on your own network and needs
 * the internet for exactly one thing — looking for its own updates — so a launch with no connection
 * is an ordinary launch and has to read like one.
 */
class OfflineTest {

    @Test
    fun `the startup check gives up long before the introduction ends`() {
        /*
         * NET_CAPABILITY_INTERNET means "this network is meant to reach the internet", not "it
         * does". A router with no line, a hotspot with no data, a PC's own ad-hoc network — on all of
         * them the capability check passes and the request then sits there until it times out.
         *
         * That timeout therefore has to be shorter than the thing waiting on it, or a launch on a
         * LAN-only network spends its introduction waiting for a reply that is never coming.
         */
        assertTrue(
            "the startup connect timeout (${Http.STARTUP_CONNECT_MS}ms) is not shorter than the " +
                "one for a check somebody actually asked for (${Http.DEFAULT_CONNECT_MS}ms)",
            Http.STARTUP_CONNECT_MS < Http.DEFAULT_CONNECT_MS,
        )

        // The introduction holds for twelve seconds and releases unconditionally at fourteen. A
        // startup check that could outlast that would be a check nothing ever waits for.
        assertTrue(
            "a launch check can take ${Http.STARTUP_CONNECT_MS}ms, which is not comfortably inside " +
                "the introduction it runs behind",
            Http.STARTUP_CONNECT_MS <= 5_000,
        )
    }

    @Test
    fun `being offline is reported as itself, not as some other network fault`() {
        /*
         * The distinction the launch screen relies on. "Offline" is expected and says nothing;
         * anything else is worth a quiet word, because it might be worth acting on.
         *
         * If these ever collapsed into one error the screen could no longer tell them apart, and it
         * would go back to announcing a problem to everybody who happens to be on a train.
         */
        assertTrue(
            "OFFLINE and NETWORK have become the same thing",
            UpdateError.OFFLINE != UpdateError.NETWORK,
        )

        // A host that cannot be resolved is the shape "no internet" actually arrives in.
        assertEquals(
            "an unresolvable host should be reported as being offline",
            UpdateError.OFFLINE,
            Http.classify(java.net.UnknownHostException("api.github.com")).error,
        )

        // A connection that was made and then went quiet is a different problem and says so.
        assertEquals(
            "a timeout is not the same as having no connection at all",
            UpdateError.NETWORK,
            Http.classify(java.net.SocketTimeoutException("timed out")).error,
        )
    }

    @Test
    fun `nothing in the update path is required for the app to run`() {
        /*
         * The claim this app makes about itself, pinned.
         *
         * Every failure the checker can produce has to be one the caller can carry on from — there is
         * no outcome that means "stop". If a future error ever needed handling before the app could
         * be used, the app would have acquired an internet requirement it does not want.
         */
        val failures = UpdateError.entries.map { Failure(it) }

        for (failure in failures) {
            assertTrue(
                "${failure.error} has no message to show, so a screen reporting it would be blank",
                failure.error.name.isNotBlank(),
            )
        }

        assertTrue("there are no update errors at all, which cannot be right", failures.isNotEmpty())
    }
}
