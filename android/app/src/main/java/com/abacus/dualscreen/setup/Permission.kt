package com.abacus.dualscreen.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.abacus.dualscreen.R

/**
 * Everything this app can ask for, and what each one is actually for.
 *
 * The permissions were spread across five screens, each asking at the moment it needed something,
 * each with its own wording. That is the right *time* to ask and the wrong place to keep the list:
 * nobody could see what the app wanted in total, and there was no single answer to "what have I
 * granted, and what did it turn on?"
 *
 * Two rules this list follows, and both are worth stating because they are unusual:
 *
 * - **Nothing here is required.** The second screen — the entire point of the app — needs none of
 *   them: it talks to a local address over `INTERNET`, which is granted at install and not listed
 *   here. Everything below turns on an optional tool. The setup screen says so rather than implying
 *   a wall of prompts must be cleared before the app works.
 * - **Nothing is asked for before it is wanted.** The screen explains and offers; it does not
 *   demand, and skipping is a normal outcome rather than an error state.
 *
 * There is no accessibility service and there will not be one for the sake of convenience. It is the
 * most invasive thing an Android app can hold, and nothing here needs to read other apps' screens.
 */
enum class Permission(
    val id: String,
    @StringRes val title: Int,
    /** What granting it turns on, in the user's terms rather than the system's. */
    @StringRes val unlocks: Int,
    /** Why the system considers it sensitive, said plainly rather than glossed over. */
    @StringRes val cost: Int,
) {

    /**
     * Notifications, for the services that must be able to say they are running.
     *
     * A foreground service is *required* to post a notification — that is the deal Android makes,
     * and it is a good one: nothing of this app's runs in the background invisibly. Denying this
     * does not stop the FTP server or the mirror; it removes their notification, and with it the
     * Stop button that is the quickest way to end them.
     */
    NOTIFICATIONS(
        id = "notifications",
        title = R.string.perm_notifications,
        unlocks = R.string.perm_notifications_unlocks,
        cost = R.string.perm_notifications_cost,
    ),

    /**
     * Installing an APK, for the updater.
     *
     * Grants nothing on its own: it lets the app *ask* the system installer to run, and every
     * install still goes through Android's own confirmation screen.
     */
    INSTALL_UPDATES(
        id = "install",
        title = R.string.perm_install,
        unlocks = R.string.perm_install_unlocks,
        cost = R.string.perm_install_cost,
    ),

    /** Drawing over other apps: the macro pad and the mirror, which exist to outlive leaving the app. */
    OVERLAY(
        id = "overlay",
        title = R.string.perm_overlay,
        unlocks = R.string.perm_overlay_unlocks,
        cost = R.string.perm_overlay_cost,
    ),

    /** Whole-device storage, for the FTP server and nothing else in the app. */
    STORAGE(
        id = "storage",
        title = R.string.perm_storage,
        unlocks = R.string.perm_storage_unlocks,
        cost = R.string.perm_storage_cost,
    ),

    /** System brightness, which is device-wide because Android offers no per-display control. */
    BRIGHTNESS(
        id = "brightness",
        title = R.string.perm_brightness,
        unlocks = R.string.perm_brightness_unlocks,
        cost = R.string.perm_brightness_cost,
    ),

    /**
     * Exemption from battery optimisation.
     *
     * Last on purpose, and the one most people should skip. It only matters for a long FTP transfer
     * or a long mirroring session that the system would otherwise doze; everything else in the app
     * runs while you are looking at it.
     */
    BATTERY(
        id = "battery",
        title = R.string.perm_battery,
        unlocks = R.string.perm_battery_unlocks,
        cost = R.string.perm_battery_cost,
    );

    /** Where a permission stands right now. */
    enum class State {
        /** Held. */
        GRANTED,

        /** Not held, and askable. */
        ASKABLE,

        /**
         * Refused twice, so Android will no longer show the prompt.
         *
         * Only runtime permissions can reach this. The app must send the user to its settings page
         * rather than asking again, and saying that plainly is better than a button that appears to
         * do nothing.
         */
        BLOCKED,

        /** Does not exist on this Android version, so there is nothing to ask for. */
        NOT_APPLICABLE,
    }

    /** The runtime permission behind this entry, for the ones that have one. */
    val runtime: String?
        get() = when (this) {
            NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    android.Manifest.permission.POST_NOTIFICATIONS
                else null

            else -> null
        }

    fun state(context: Context): State = when (this) {
        NOTIFICATIONS -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> State.NOT_APPLICABLE
            granted(context, android.Manifest.permission.POST_NOTIFICATIONS) -> State.GRANTED
            // Whether it is merely un-asked or refused for good is only knowable from an Activity;
            // the screen refines this with [blocked].
            else -> State.ASKABLE
        }

        INSTALL_UPDATES ->
            if (runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false))
                State.GRANTED else State.ASKABLE

        OVERLAY ->
            if (Settings.canDrawOverlays(context)) State.GRANTED else State.ASKABLE

        STORAGE -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> State.NOT_APPLICABLE
            Environment.isExternalStorageManager() -> State.GRANTED
            else -> State.ASKABLE
        }

        BRIGHTNESS ->
            if (Settings.System.canWrite(context)) State.GRANTED else State.ASKABLE

        BATTERY -> {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) State.GRANTED
            else State.ASKABLE
        }
    }

    /**
     * The system page that grants this one, or null when it is a runtime prompt instead.
     *
     * Every one of these is a *page*, not a dialog: Android deliberately makes the special access
     * permissions something you turn on yourself, with the app named in front of you. An app cannot
     * grant them and should not pretend it is about to.
     */
    fun settingsPage(context: Context): Intent? = when (this) {
        NOTIFICATIONS -> null

        INSTALL_UPDATES -> Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        )

        OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName),
        )

        STORAGE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.packageName),
                )
            else null

        BRIGHTNESS -> Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:" + context.packageName),
        )

        // The list rather than the direct request: asking to be exempted outright needs a permission
        // of its own, and this app does not need to hold one to show somebody a switch.
        BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    companion object {

        /** In the order the setup screen offers them: most useful first, most skippable last. */
        val OFFERED = listOf(INSTALL_UPDATES, NOTIFICATIONS, OVERLAY, STORAGE, BRIGHTNESS, BATTERY)

        fun granted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /**
         * Has this runtime permission been refused for good?
         *
         * Android has no direct answer. The rule is: not granted, and the system says not to show a
         * rationale — which means either it has never been asked (before the first prompt) or it has
         * been refused twice. The setup screen only asks this *after* a prompt has come back denied,
         * where the second reading is the true one.
         */
        fun blocked(activity: Activity, permission: String): Boolean =
            !granted(activity, permission) &&
                !activity.shouldShowRequestPermissionRationale(permission)

        /** This app's page in system settings, for anything Android will no longer prompt for. */
        fun appSettings(context: Context): Intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
        )
    }
}
