package com.abacus.dualscreen.boot

import android.app.Activity
import com.abacus.dualscreen.Appearance
import com.abacus.dualscreen.R
import com.abacus.dualscreen.Settings

/**
 * When the boot animation plays, and what it looks like when it does.
 *
 * Kept apart from the view so the two decisions that are not about drawing — *should this play at
 * all* and *what colour is it* — live somewhere a screen can read without knowing how the animation
 * works. Swapping [AbacusBootView] for a different one later means changing the type here and
 * nothing else.
 */
object Boot {

    /**
     * Once per launch, not once per screen.
     *
     * The home screen is created again whenever somebody comes back to it from a tool, and replaying
     * the animation every time would turn a nice touch into a tax on getting anywhere.
     */
    @Volatile
    var playedThisRun = false
        private set

    /** Whether the animation would play right now — the setting, and whether it already has. */
    fun due(settings: Settings): Boolean = settings.bootAnimation && !playedThisRun

    /**
     * Play it, then call [onDone].
     *
     * [onDone] is called exactly once whatever happens — animation finished, tapped through, or
     * turned off entirely — because it is what lets the screen behind carry on, and a path that
     * skips it is a screen that never appears.
     */
    fun play(activity: Activity, view: AbacusBootView, settings: Settings, onDone: () -> Unit) {
        playedThisRun = true

        view.accent = Appearance.accentOf(settings)
        view.wordmark = activity.getString(R.string.boot_wordmark)
        view.status = activity.getString(R.string.boot_status)
        view.setOnClickListener { view.skip() }

        view.play(onDone)
    }
}
