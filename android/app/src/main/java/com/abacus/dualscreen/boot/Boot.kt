package com.abacus.dualscreen.boot

import android.app.Activity
import com.abacus.dualscreen.Appearance
import com.abacus.dualscreen.R
import com.abacus.dualscreen.Settings
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Motion
import com.abacus.dualscreen.ui.Sounds

/**
 * When the intro plays, how long a version, and what it sounds like.
 *
 * Kept apart from the view so the decisions that are not about drawing live somewhere a screen can
 * read without knowing how the animation works.
 *
 * ## Full or short
 *
 * The long introduction is worth its two and a half seconds exactly twice: the first time somebody
 * opens the app, and the first time they open it after an update — the two moments when it is
 * telling them something rather than delaying them. Every other launch gets the short version,
 * which is the same animation at speed with the arrival intact and the dwell removed.
 *
 * This is the difference between an intro people describe as nice and one they describe as *that
 * thing I have to sit through*. A gaming handheld gets opened between rounds; a toll booth at the
 * front door of the app would be noticed on about the fifth pass.
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

        /*
         * Switch the engines on here, not only from Appearance.apply.
         *
         * The intro runs from onCreate and that call happens in onResume, so on the launch that
         * matters most — the first one, the only one that gets the full intro — the sound engine
         * would still be holding its default and the introduction would play silently. Setting both
         * here costs two assignments and removes the ordering entirely.
         */
        Sounds.setEnabled(settings.sounds)
        Feedback.setHapticsEnabled(settings.haptics)

        val version = versionOf(activity)
        val full = settings.introShownFor != version
        settings.introShownFor = version

        view.accent = Appearance.accentOf(settings)
        view.wordmark = activity.getString(R.string.boot_wordmark)
        view.status = activity.getString(R.string.boot_status)
        view.full = full

        /*
         * Sound and haptics, driven by the animation rather than scheduled alongside it.
         *
         * The view calls back at the moments that matter — each bead reaching its stop, and the mark
         * landing — so the knock happens on the frame the bead arrives instead of at a timestamp
         * that was right on the machine it was tuned on. On the short version the figure is skipped
         * and only the arrival plays: a two-second musical phrase over a one-second animation is a
         * phrase that gets cut off.
         */
        view.onBeadSeated = { index ->
            Feedback.knock(view, volume = 0.5f + 0.1f * index)
            if (index == 0) Feedback.tap(view)
        }

        view.onLanded = {
            Feedback.success(view)
            if (full) Sounds.play(activity, Sounds.Cue.INTRO, volume = 0.9f)
        }

        view.setOnClickListener {
            // A tap ends it. No sound on the way out: somebody skipping an intro has said what they
            // think of it, and playing them a chord on the way past would be a poor answer.
            view.skip()
        }

        view.play(onDone)
    }

    /**
     * What counts as "this version" for the purpose of showing the full intro.
     *
     * The version name, so a rebuild of the same version does not replay it but an update does.
     * Unreadable version means show it: erring towards the animation once is better than never.
     */
    private fun versionOf(activity: Activity): String = runCatching {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** True when the device wants no animation at all, in which case there is nothing to time. */
    fun silentDevice(activity: Activity): Boolean = !Motion.animated(activity)
}
