package com.abacus.dualscreen.connect

/**
 * How long to wait before looking for the host again.
 *
 * Its own object rather than a method on the session screen so the policy can be tested without an
 * Activity, and so there is one place to look when somebody asks how hard this app hammers a PC that
 * is switched off.
 *
 * The shape is deliberate: quick at first, because the overwhelmingly common cause of a lost session
 * is a game being restarted and it comes back in seconds; then lengthening, so an evening with the
 * PC off costs a request every half minute rather than one a second. It stops entirely at
 * [MAX_ATTEMPTS] — a handheld should not spend its battery on a host that is not coming back.
 */
object Backoff {

    /** How many times a lost session is chased before it is handed back to the picker. */
    const val MAX_ATTEMPTS = 8

    fun delayFor(attempt: Int): Long = when {
        attempt <= 2 -> 1_500L
        attempt <= 4 -> 4_000L
        attempt <= 6 -> 10_000L
        else -> 30_000L
    }

    fun exhausted(attempt: Int): Boolean = attempt >= MAX_ATTEMPTS

    /**
     * The whole retry budget, for the record: about two and a half minutes of trying before giving
     * up, spread across eight attempts.
     */
    fun totalWait(): Long = (1..MAX_ATTEMPTS).sumOf { delayFor(it) }
}
