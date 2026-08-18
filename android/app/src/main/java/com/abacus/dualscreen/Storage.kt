package com.abacus.dualscreen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings

/**
 * Whether this app may read the whole device, and how to ask.
 *
 * The FTP server exists to feel like the 3DS's ftpd, where you connect and the whole filesystem is
 * there. Android has spent several versions making that deliberately hard, and the honest summary
 * is:
 *
 * - **Android 10 and earlier**: WRITE_EXTERNAL_STORAGE was enough, and shared storage was a normal
 *   filesystem.
 * - **Android 11 and later**: scoped storage. An app sees its own folders and the media collections,
 *   and nothing else — unless it holds **MANAGE_EXTERNAL_STORAGE**, which is granted by the user in
 *   a system settings screen rather than by a normal permission prompt.
 *
 * That permission is why this app is a sideloaded APK and not a Play Store listing: Google restricts
 * it to file managers and backup tools and would reject this. Distributing from GitHub, as this
 * project already does, that restriction simply does not apply.
 *
 * Even with it, two things stay off limits on a stock device, and no amount of permission changes
 * it: `/data/data` (other apps' private storage) and most of `/system`. A file manager on an
 * unrooted Thor cannot see those either. The server will show the directories and they will look
 * empty, which is the truth rather than a failure.
 */
object Storage {

    /** Can we serve the whole device, or only the parts Android hands out freely? */
    fun hasWholeDeviceAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Below 11 there is no such thing to grant; the ordinary storage permission covers it,
            // and if that is missing the server still works against the app's own folder.
            true
        }

    /**
     * The settings screen that grants it. There is no in-app prompt for this one — the user has to
     * find this app in a system list and turn it on, so the UI has to say that plainly rather than
     * firing an intent and hoping.
     */
    fun requestWholeDeviceAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val intent = Intent(
            AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }.onFailure {
            // Some devices do not carry the per-app screen. The general list always exists.
            runCatching {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** What the FTP server will actually be able to show, in one line, for the setup screen. */
    fun describe(): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> "The whole device."
        hasWholeDeviceAccess() -> "The whole device, except other apps' private data."
        else -> "Shared storage only — grant All files access for the rest."
    }
}
