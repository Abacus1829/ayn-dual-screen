package com.abacus.dualscreen.update

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.abacus.dualscreen.R
import com.abacus.dualscreen.UpdateActivity

/**
 * The one interruption the update system is allowed.
 *
 * It appears after the boot animation has finished, once per launch, and only for a version the user
 * has neither skipped nor postponed. Everything about it is arranged so that saying no is as easy as
 * saying yes:
 *
 * - **Update now** hands over to the update screen and starts downloading, so progress is visible
 *   from the first byte rather than behind a spinner in a dialog.
 * - **Remind me later** is a day, not a launch.
 * - **Skip this version** means this one only. The next release asks again, because "skip" is a
 *   judgement about a build, not a decision to stop updating — that switch lives in Settings where
 *   somebody looking for it can find it.
 *
 * Dismissing it — back, or a tap outside — is none of the three: nothing is recorded, and it asks
 * again next launch.
 */
object UpdatePrompt {

    fun show(activity: Activity, update: Update) {
        val updates = UpdateManager.get(activity)
        updates.promptedThisRun = true

        val installed = updates.installedName().ifBlank { activity.getString(R.string.update_unknown) }

        val body = buildString {
            append(activity.getString(R.string.update_prompt_versions, installed, update.version.text))
            append("\n")
            append(activity.getString(R.string.update_prompt_size, Downloader.bytes(update.size)))

            val notes = ReleaseNotes.readable(update.notes)
            if (notes.isNotBlank()) {
                append("\n\n")
                append(notes)
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.update_prompt_title)
            .setMessage(body)
            .setPositiveButton(R.string.update_now) { _, _ ->
                activity.startActivity(UpdateActivity.downloading(activity))
            }
            .setNeutralButton(R.string.update_later) { _, _ -> updates.remindLater() }
            .setNegativeButton(R.string.update_skip) { _, _ -> updates.skip(update.version) }
            .show()
    }
}
