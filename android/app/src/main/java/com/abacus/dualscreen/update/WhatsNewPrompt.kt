package com.abacus.dualscreen.update

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.abacus.dualscreen.R
import com.abacus.dualscreen.ui.Feedback

/**
 * "Here is what you just got", once, after an update has installed.
 *
 * Built as a dialog rather than a screen for the same reason [UpdatePrompt] is: it is a thing you
 * read and dismiss, and giving it a whole screen with a back button implies you might want to
 * navigate around in it.
 *
 * Shown after the intro and after any first-run setup, never during. The first launch of a new
 * version is already the launch that plays the long introduction and may also be somebody's very
 * first launch; stacking a notes dialog on top of that is three unfamiliar things in a row before
 * anybody has seen the app.
 */
object WhatsNewPrompt {

    /**
     * Show the notes for the version now running, if there are any waiting.
     *
     * Returns whether anything was shown, so a caller that also wants to offer something else can
     * avoid putting two dialogs on screen at once.
     */
    fun showIfDue(activity: Activity, runningVersion: String): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false

        val raw = WhatsNew.take(activity, runningVersion) ?: return false
        val notes = ReleaseNotes.readable(raw).trim()
        if (notes.isBlank()) return false

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.whats_new_title, runningVersion))
            .setMessage(notes)
            .setPositiveButton(R.string.action_done, null)
            .show()

        // The same cue a completed action gets, because from where the user is standing an update
        // finishing is exactly that.
        Feedback.success(activity.window?.decorView)
        return true
    }
}
