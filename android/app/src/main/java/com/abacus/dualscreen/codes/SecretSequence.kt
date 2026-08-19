package com.abacus.dualscreen.codes

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Watches for a button sequence and calls back when it lands.
 *
 * Generic on purpose: a sequence, a timeout and a callback. Another hidden thing later is another
 * instance, not another copy of this logic.
 *
 * The rules are the ones people expect from this kind of thing without being told:
 *
 * - A wrong button resets, but it is **re-tested as a first button** rather than thrown away, so
 *   somebody who fumbles and starts again immediately does not have to pause first.
 * - Too long a gap between presses resets, so half a sequence does not sit there for an hour waiting
 *   to be completed by an unrelated press.
 * - Nothing here consumes the key. Every press is also passed to whatever normally handles it, so
 *   the d-pad keeps navigating the screen while this listens.
 */
class SecretSequence(
    private val steps: List<Int>,
    private val timeoutMs: Long = 5_000L,
    /** How far in, out of how many. Fired on every press so a progress display can follow along. */
    private val onProgress: (Int, Int) -> Unit = { _, _ -> },
    private val onComplete: () -> Unit,
) {

    private var at = 0
    private var lastAt = 0L

    /**
     * Feed a key press in.
     *
     * Always returns false — this never claims the event. A hidden listener that swallowed a d-pad
     * press would break the navigation it is hiding behind, and the first symptom would be a home
     * screen that intermittently ignores the stick.
     */
    fun onKey(keyCode: Int): Boolean {
        val now = SystemClock.elapsedRealtime()

        if (at > 0 && now - lastAt > timeoutMs) at = 0
        lastAt = now

        when {
            keyCode == steps[at] -> at++

            // A wrong press resets — but if it happens to be the first button, this is the start of
            // a fresh attempt rather than nothing at all.
            keyCode == steps[0] -> at = 1

            else -> at = 0
        }

        if (at >= steps.size) {
            at = 0
            onProgress(steps.size, steps.size)
            onComplete()
            return false
        }

        onProgress(at, steps.size)
        return false
    }

    fun reset() {
        at = 0
    }

    companion object {
        /**
         * The sequence that reveals game codes.
         *
         * Directions then two faces, entered on the home screen. Mapped to both the d-pad and the
         * gamepad's face buttons, and to the letters B and A, so it can be entered on a handheld,
         * with a pad, or with a keyboard attached.
         *
         * B before A, in that order — the classic ordering, and the one people try first.
         */
        val UNLOCK: List<Int> = listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_A,
        )

        /**
         * The same sequence for a device with no pad.
         *
         * Letter keys stand in for the two face buttons. Everything else is the d-pad, which a
         * keyboard's arrow keys already produce.
         */
        val UNLOCK_KEYBOARD: List<Int> = UNLOCK.dropLast(2) + listOf(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_A)
    }
}
