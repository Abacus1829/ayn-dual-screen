package com.abacus.dualscreen.update

import android.content.Context

/**
 * What the updater remembers between launches.
 *
 * Its own class rather than more fields on [com.abacus.dualscreen.Settings], because this is the
 * one part of the update system that a future plugin updater would want a second copy of — keyed by
 * source id, so "the app" and "the Stardew plugin" can each be skipped, postponed and throttled
 * independently. It writes into the same preferences file as everything else, under prefixed keys,
 * so there is still one place to clear.
 *
 * Three of these deserve explaining, because together they are the difference between an updater
 * and a nag:
 *
 * - **Skip** is per version. Skipping 0.15.0 says nothing about 0.16.0, so a skipped version is
 *   forgotten the moment a newer one appears rather than silently switching updates off.
 * - **Remind me later** is a timestamp, not a flag. It expires.
 * - **The throttle** stops a check firing on every single launch. A handheld gets opened a dozen
 *   times an evening and GitHub allows sixty anonymous requests an hour.
 */
class UpdatePrefs(context: Context, private val sourceId: String = "app") {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Check on startup at all. The manual button in Settings works regardless. */
    var autoCheck: Boolean
        get() = prefs.getBoolean(key("auto"), true)
        set(value) = prefs.edit().putBoolean(key("auto"), value).apply()

    var channel: Channel
        get() = Channel.byId(prefs.getString(key("channel"), null))
        set(value) = prefs.edit().putString(key("channel"), value.id).apply()

    /** When the last check completed, successful or not. Drives the throttle. */
    var lastCheck: Long
        get() = prefs.getLong(key("last_check"), 0L)
        set(value) = prefs.edit().putLong(key("last_check"), value).apply()

    /** GitHub's ETag from the last release list, so an unchanged list costs no rate limit. */
    var etag: String?
        get() = prefs.getString(key("etag"), null)
        set(value) = prefs.edit().putString(key("etag"), value).apply()

    /** The last update found, so a prompt after a boot animation needs no second request. */
    var cached: Update?
        get() = Update.fromJson(prefs.getString(key("cached"), null))
        set(value) = prefs.edit().putString(key("cached"), value?.toJson()).apply()

    /** The version the user asked not to be told about again. */
    var skipped: String
        get() = prefs.getString(key("skipped"), "").orEmpty()
        set(value) = prefs.edit().putString(key("skipped"), value).apply()

    /** Nothing is offered before this moment. */
    var remindAfter: Long
        get() = prefs.getLong(key("remind_after"), 0L)
        set(value) = prefs.edit().putLong(key("remind_after"), value).apply()

    // ── decisions ───────────────────────────────────────────────────────────

    /** Is a startup check due? Manual checks do not ask. */
    /**
     * The mod versions this device has already been shown.
     *
     * Stored as "name=version" pairs so a mod appearing or disappearing from a release does not
     * disturb the others. It answers the only honest question available: nothing reports which mod
     * version is installed on the PC, but the app does know what it last showed you, and "this is
     * newer than the last one you saw" is both true and the thing worth flagging.
     */
    var seenMods: Map<String, String>
        get() = prefs.getStringSet(key("seen_mods"), emptySet()).orEmpty()
            .mapNotNull { entry ->
                val at = entry.indexOf('=')
                if (at <= 0) null else entry.take(at) to entry.substring(at + 1)
            }
            .toMap()
        set(value) = prefs.edit()
            .putStringSet(key("seen_mods"), value.map { "${it.key}=${it.value}" }.toSet())
            .apply()

    fun dueForCheck(now: Long = System.currentTimeMillis()): Boolean =
        autoCheck && now - lastCheck >= CHECK_INTERVAL_MS

    /** Should this update interrupt somebody who has just opened the app? */
    fun shouldPrompt(update: Update, now: Long = System.currentTimeMillis()): Boolean {
        if (isSkipped(update.version)) return false
        return now >= remindAfter
    }

    fun isSkipped(version: Version): Boolean =
        Version.parse(skipped)?.let { version <= it } == true

    fun skip(version: Version) {
        skipped = version.text
    }

    fun remindLater(now: Long = System.currentTimeMillis()) {
        remindAfter = now + REMIND_LATER_MS
    }

    /** After an install, or when the user asks to be told about everything again. */
    fun clearDeferrals() {
        prefs.edit().remove(key("skipped")).remove(key("remind_after")).apply()
    }

    private fun key(name: String): String = PREFIX + sourceId + "_" + name

    private companion object {
        /** The app's own preferences file, so one clear-data wipes this too. */
        const val PREFS = "dual_screen"
        const val PREFIX = "update_"

        /** Six hours. Often enough to hear about a release the day it lands, rarely enough to be free. */
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** A day. Long enough to be a real postponement, short enough not to be a mute button. */
        const val REMIND_LATER_MS = 24L * 60 * 60 * 1000
    }
}
