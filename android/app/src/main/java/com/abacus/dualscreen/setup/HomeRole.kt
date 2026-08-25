package com.abacus.dualscreen.setup

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Becoming the console's Home destination, and giving it back.
 *
 * ## What is actually supported
 *
 * Android has exactly one sanctioned way for an app to answer the Home button: declare an activity
 * that handles `android.intent.category.HOME`, and have the *user* choose it as the default home
 * app. There is no permission that grants it, no API that takes it, and that is deliberate — an app
 * that could seize the Home button without being asked would be malware, and Android treats the
 * mechanism accordingly.
 *
 * So there are two supported routes to the chooser and this uses whichever the device has:
 *
 * - **[RoleManager] with [RoleManager.ROLE_HOME]** (API 29+). The modern one. Produces a single
 *   system dialog — "Make Abacus your Home app?" — with one tap to accept. This is the good path.
 * - **[Settings.ACTION_HOME_SETTINGS]** (everything else, and the fallback whenever the role is
 *   unavailable or already held by somebody else). Opens the system's Home-app list, where the user
 *   picks from every installed launcher.
 *
 * Both end at a screen the user controls. Neither can be completed on their behalf, and this class
 * does not try: the request returns an [Intent] and the caller starts it.
 *
 * ## Giving it back
 *
 * **There is no API to drop the home role.** An app can be granted it and cannot un-grant itself,
 * which sounds like an oversight and is not: the alternative is an app that can decide what happens
 * when you press Home *and* decide you cannot change it back.
 *
 * The honest restore path is therefore the same system screen, opened with a clear explanation and
 * with the AYN dashboard named, so the choice is one tap away and obvious. [restore] does that, and
 * [currentHomeLabel] reads back which app currently holds it so the screen can say where things
 * stand rather than guessing.
 *
 * ## The caveat worth stating on this device
 *
 * On the AYN Thor the physical Home button is the vendor's, and whether it dispatches a standard
 * `CATEGORY_HOME` intent or calls the AYN dashboard directly is a property of their firmware, not of
 * this app. If it dispatches the standard intent — which is the normal Android behaviour and what
 * almost all handhelds do — this works. If AYN has wired the button straight to their own package,
 * no supported mechanism exists to intercept it, and the workarounds that appear to (an accessibility
 * service watching for the key, a foreground service racing the launch) are exactly the kind of
 * unreliable trick that should not ship. [dispatchesHomeIntent] reports which case this device is,
 * so the screen can say so plainly instead of leaving somebody wondering why a switch did nothing.
 */
object HomeRole {

    /** Whether this app is the one the Home button currently opens. */
    fun isDefault(context: Context): Boolean =
        homePackage(context) == context.packageName

    /**
     * The package that currently answers Home, or null if the system will not say.
     *
     * Resolved by asking the package manager what a Home intent would open. When several launchers
     * are installed and none has been chosen, Android answers with its resolver activity rather than
     * with a launcher — which is a real state, and one worth not mistaking for a real launcher.
     */
    fun homePackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() ?: return null

        val name = resolved.activityInfo?.packageName ?: return null
        return if (name == "android" || name.endsWith(".resolver")) null else name
    }

    /** The human name of whatever currently answers Home, for a settings row to show. */
    fun currentHomeLabel(context: Context): String? {
        val name = homePackage(context) ?: return null

        return runCatching {
            val manager = context.packageManager
            manager.getApplicationLabel(manager.getApplicationInfo(name, 0)).toString()
        }.getOrDefault(name)
    }

    /**
     * Whether this device dispatches Home through the standard intent at all.
     *
     * If nothing on the system handles `CATEGORY_HOME`, the Home button is not going through the
     * framework and nothing an ordinary app can do will change what it opens.
     */
    fun dispatchesHomeIntent(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * Ask to become the Home app.
     *
     * Prefers the role dialog, which is one tap, and falls back to the Home settings list. Returns
     * null only when the device has no Home settings screen either, which would mean there is
     * nothing to offer and the caller should say so rather than start an intent that goes nowhere.
     */
    fun request(activity: Activity): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (roles != null &&
                roles.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roles.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                return runCatching {
                    roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
                }.getOrNull() ?: homeSettings(activity)
            }
        }

        return homeSettings(activity)
    }

    /**
     * Hand it back.
     *
     * The same system screen, because that is the only place the choice can be made. The caller is
     * expected to explain what to pick before starting this — a settings list appearing with no
     * context is how somebody ends up with no launcher at all.
     */
    fun restore(context: Context): Intent? = homeSettings(context)

    /** The system's Home-app chooser, if this build of Android has one. */
    private fun homeSettings(context: Context): Intent? {
        val intent = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handled = runCatching {
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)

        if (handled) return intent

        // Some vendor builds hide the Home screen but keep the general default-apps one.
        val fallback = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            if (context.packageManager.resolveActivity(fallback, 0) != null) fallback else null
        }.getOrNull()
    }
}
