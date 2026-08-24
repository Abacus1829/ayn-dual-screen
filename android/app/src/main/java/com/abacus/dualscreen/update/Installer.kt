package com.abacus.dualscreen.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing the file to Android and getting out of the way.
 *
 * This is the deliberately small part. The app downloads and checks; **Android installs**. There is
 * no silent install here and there cannot be one: that needs either the INSTALL_PACKAGES permission,
 * which only system apps hold, or a shell running as root. Both are off the table for a sideloaded
 * app, and neither would be worth having — the confirmation screen is the user's last chance to say
 * no to a binary that is about to replace the one they are running.
 *
 * So what happens is:
 *
 * 1. the APK is exposed through a [FileProvider] as a `content://` URI, readable by the installer
 *    for exactly one intent and by nothing else;
 * 2. an ordinary ACTION_VIEW carries it to whatever package installer the device has;
 * 3. the user reads the screen and taps Install, or does not.
 *
 * The one permission involved is REQUEST_INSTALL_PACKAGES, which lets this app *ask*. It is granted
 * per-app from a system settings page — Android 8 replaced the old device-wide "unknown sources"
 * switch with it precisely so one sideloaded app cannot open the door for every other one.
 */
object Installer {

    /** Matches the provider declared in the manifest. */
    fun authority(context: Context): String = context.packageName + ".updates"

    /**
     * May this app ask the installer to run?
     *
     * False means the request would be refused before any UI appeared, so the screen offers the
     * settings page instead of a button that would do nothing.
     */
    fun canInstall(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /**
     * Open the system page where the permission is granted.
     *
     * There is no in-app prompt for this one, by design: the user has to see this app named on a
     * system screen and turn it on themselves. Some builds do not carry the per-app page, so the
     * general list is the fallback rather than a crash.
     */
    fun requestPermission(context: Context) {
        val perApp = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(perApp) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * Start the installer for [file].
     *
     * Returns null when the installer opened. Nothing here can tell whether the install then
     * *succeeded* — the system does not report back — so the screen that called this re-reads the
     * installed version when the user returns, which is the only honest way to know.
     */
    fun install(context: Context, file: File): Failure? {
        if (!file.isFile) return Failure(UpdateError.INSTALL_FAILED, "the file is gone")

        if (!canInstall(context)) return Failure(UpdateError.PERMISSION)

        val uri = runCatching {
            FileProvider.getUriForFile(context, authority(context), file)
        }.getOrElse {
            return Failure(UpdateError.INSTALL_FAILED, it.message)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Names this app as the source, so the installer can say where the file came from.
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        }

        return try {
            context.startActivity(intent)
            null
        } catch (error: ActivityNotFoundException) {
            // A device with no package installer at all. Rare, and worth saying plainly.
            Failure(UpdateError.INSTALL_FAILED, error.message ?: "no installer on this device")
        } catch (error: Exception) {
            Failure(UpdateError.INSTALL_FAILED, error.message)
        }
    }
}
