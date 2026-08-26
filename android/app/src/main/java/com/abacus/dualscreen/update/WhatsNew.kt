package com.abacus.dualscreen.update

import android.content.Context

/**
 * What you actually got, shown once, after the update has installed.
 *
 * The release notes were already on screen — in the prompt, *before* you agreed to anything. That is
 * the wrong moment for them and it is the only moment they appeared. At that point the notes are a
 * sales pitch you skim on the way to tapping Update; afterwards they are the answer to "what changed",
 * which is the question people actually have, and by then they were gone.
 *
 * So the notes are kept at the moment the installer is handed the file, tagged with the version they
 * describe. The next launch compares that tag against the version that is actually running:
 *
 * - **They match** — the update went through, and this is the first launch of it. Show them, once.
 * - **They do not** — the install was cancelled, failed, or somebody sideloaded a different build.
 *   Throw them away rather than showing notes for a version that is not here.
 *
 * That second case is the reason this is keyed on the version rather than just being a flag. An
 * install that the user backed out of at Android's own confirmation dialog leaves no trace anywhere
 * this app can see, so "did it work" can only be answered by looking at what is running now.
 */
object WhatsNew {

    private const val PREFS = "ayn_whats_new"
    private const val KEY_VERSION = "version"
    private const val KEY_NOTES = "notes"

    /**
     * Remember what this version says it changed, for after it is running.
     *
     * Called when the file is handed to the installer, not when the download finishes — a downloaded
     * update that never gets installed should not announce itself later.
     */
    fun remember(context: Context, version: String, notes: String) {
        if (version.isBlank()) return

        prefs(context).edit()
            .putString(KEY_VERSION, version)
            .putString(KEY_NOTES, notes.take(MAX_NOTES))
            .apply()
    }

    /**
     * The notes to show right now, or null.
     *
     * Reading them clears them: these are shown once. Somebody who wants them again has the release
     * page, and a card that reappears on every launch is a card people learn to dismiss without
     * reading.
     */
    fun take(context: Context, runningVersion: String): String? {
        val store = prefs(context)
        val forVersion = store.getString(KEY_VERSION, null) ?: return null

        // Cleared either way. If this is not the version those notes describe, the install did not
        // happen and holding them for a later launch would only make them more wrong.
        val notes = store.getString(KEY_NOTES, null)
        store.edit().remove(KEY_VERSION).remove(KEY_NOTES).apply()

        if (forVersion != runningVersion) return null
        return notes?.takeIf { it.isNotBlank() }
    }

    /** Forget without showing, for a user who has asked not to be told about updates at all. */
    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_VERSION).remove(KEY_NOTES).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * A ceiling on what gets stored.
     *
     * Release notes are written by whoever cut the release and can be arbitrarily long. This is a
     * preferences file, not a document store, and a card nobody will read past the first screenful
     * does not need the other nine.
     */
    private const val MAX_NOTES = 4_000
}
