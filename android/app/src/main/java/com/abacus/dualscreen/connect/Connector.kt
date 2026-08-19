package com.abacus.dualscreen.connect

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.abacus.dualscreen.R
import com.abacus.dualscreen.ScreenActivity

/**
 * Opening a session, from wherever it was asked for.
 *
 * The profile list, the network scan and the old address fields all end up here, so a session opens
 * the same way whichever door it came through — same display resolution, same history entry, same
 * fallback when the chosen screen has gone.
 */
object Connector {

    /**
     * Open a profile.
     *
     * [DisplayChoice.ASK] puts a chooser up first; everything else resolves silently. When the
     * chosen display no longer exists the session opens on this one rather than failing, which is
     * the behaviour that matters on a handheld whose second panel can be switched off.
     */
    fun open(activity: Activity, profile: ConnectionProfile, store: ProfileStore) {
        if (!profile.usable) {
            android.widget.Toast.makeText(
                activity, R.string.profile_unusable, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (profile.display == DisplayChoice.ASK && Displays.hasSecond(activity)) {
            ask(activity) { displayId -> launch(activity, profile, store, displayId) }
            return
        }

        launch(activity, profile, store, Displays.resolve(activity, profile.display))
    }

    private fun launch(
        activity: Activity,
        profile: ConnectionProfile,
        store: ProfileStore,
        displayId: Int?,
    ) {
        // Asked again rather than trusted: between resolving and launching, a dock can be pulled.
        val target = displayId?.takeIf { Displays.exists(activity, it) }

        store.touch(profile)

        val intent = Intent(activity, ScreenActivity::class.java)
            .putExtra(ScreenActivity.EXTRA_URL, profile.url)
            .putExtra(ScreenActivity.EXTRA_GAME, profile.preset)
            .putExtra(ScreenActivity.EXTRA_NAME, profile.name)
            .putExtra(ScreenActivity.EXTRA_ORIENTATION, profile.orientation.id)
            .putExtra(ScreenActivity.EXTRA_AWAKE, profile.awake.id)
            .putExtra(ScreenActivity.EXTRA_PROFILE, profile.id)

        runCatching {
            activity.startActivity(intent, Displays.optionsFor(target))
        }.onFailure {
            // A display that vanished between the check and the call throws here. Opening on this
            // screen is always available and is better than nothing happening at all.
            runCatching { activity.startActivity(intent) }
        }
    }

    /** The chooser for [DisplayChoice.ASK], built from the displays that exist at this moment. */
    private fun ask(activity: Activity, onChosen: (Int?) -> Unit) {
        val panels = Displays.all(activity)
        if (panels.size < 2) {
            onChosen(null)
            return
        }

        val labels = panels.map { panel ->
            if (panel.isMain) activity.getString(R.string.display_this_one, panel.label) else panel.label
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle(R.string.display_which)
            .setItems(labels) { _, which ->
                val panel = panels[which]
                onChosen(if (panel.isMain) null else panel.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
