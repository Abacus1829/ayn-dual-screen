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
    /**
     * What counts at each step, as a set of alternatives.
     *
     * A set rather than a single code because the same physical button is not the same key code on
     * every handheld: a face button may arrive as `BUTTON_A` on one device and as the letter `A`
     * from an attached keyboard on another, and a sequence that insists on one of them is a
     * sequence that silently cannot be entered on half the hardware it ships to.
     */
    private val steps: List<Set<Int>>,
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
            keyCode in steps[at] -> at++

            // A wrong press resets — but if it happens to be the first button, this is the start of
            // a fresh attempt rather than nothing at all.
            keyCode in steps[0] -> at = 1

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
        private val UP = setOf(KeyEvent.KEYCODE_DPAD_UP)
        private val DOWN = setOf(KeyEvent.KEYCODE_DPAD_DOWN)
        private val LEFT = setOf(KeyEvent.KEYCODE_DPAD_LEFT)
        private val RIGHT = setOf(KeyEvent.KEYCODE_DPAD_RIGHT)

        /**
         * The two face buttons, and every code a handheld might call them.
         *
         * `BUTTON_A`/`BUTTON_B` are what a pad reports. The letters are what an attached keyboard
         * sends. Some handhelds route their face buttons through the system as `ENTER` and `BACK`
         * instead — `ENTER` is accepted, `BACK` is not, because taking over the back button on the
         * home screen would break the way out of the app to hide a feature nobody has found yet.
         */
        private val FACE_A = setOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )

        private val FACE_B = setOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_B)

        /**
         * The sequence that reveals game codes.
         *
         * One list now rather than a pad version and a keyboard version: each step carries every
         * code that counts for it, so a pad, a keyboard and a handheld that reports something in
         * between all enter the same sequence. Two watchers matching two lists was how a device that
         * sent `BUTTON_A` for one button and `B` for the other could satisfy neither.
         */
        val UNLOCK: List<Set<Int>> =
            listOf(UP, UP, DOWN, DOWN, LEFT, RIGHT, LEFT, RIGHT, FACE_B, FACE_A)
    }
}

/**
 * The same sequence, entered by hand.
 *
 * Exists because the keyboard path cannot be relied on. On a desktop emulator the host's own
 * key-mapping layer takes the arrow keys before the app sees them, and on a handheld the d-pad may
 * arrive as motion axes rather than keys — neither is something the app can fix from its side.
 * A swipe is a touch event, and nothing sits between a touch and the window it lands in.
 *
 * Eight swipes for the directions, then two taps for the two buttons. Same order, same rhythm, no
 * hardware required.
 */
object TouchCodes {

    /** Stand-in key codes for gestures, chosen from a range no real key uses. */
    const val SWIPE_UP = 1001
    const val SWIPE_DOWN = 1002
    const val SWIPE_LEFT = 1003
    const val SWIPE_RIGHT = 1004
    const val TAP = 1005

    /** Up, up, down, down, left, right, left, right, tap, tap. */
    val UNLOCK: List<Set<Int>> = listOf(
        SWIPE_UP, SWIPE_UP,
        SWIPE_DOWN, SWIPE_DOWN,
        SWIPE_LEFT, SWIPE_RIGHT,
        SWIPE_LEFT, SWIPE_RIGHT,
        TAP, TAP,
    ).map { setOf(it) }

    /** What to show for each step, matching the key display. */
    val GLYPHS = listOf("↑", "↑", "↓", "↓", "←", "→", "←", "→", "●", "●")
}
