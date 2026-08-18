package com.abacus.dualscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Keeps [FtpServer] alive while you copy files, and tells you where to point your PC.
 *
 * A foreground service rather than something owned by an activity, for the obvious reason: you start
 * the server, put the Thor down, and go and drag files around on the PC. An activity-scoped server
 * would die the moment the screen turned off, halfway through a transfer.
 *
 * The notification is not decoration — it is the only thing on screen once you have walked away, so
 * it carries the address to type and a Stop button, and it cannot be swiped away while a transfer is
 * running.
 */
class FtpService : android.app.Service() {

    private var server: FtpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        try {
            handleStart(intent)
        } catch (e: Throwable) {
            /*
             * Whatever went wrong, SAY SO.
             *
             * This catch exists because "Start server does nothing" is an unfixable bug report, and
             * that is exactly what a service failing in onStartCommand produces: the system logs a
             * line nobody can see and the UI sits there looking untouched. Android has a growing
             * list of ways to refuse a foreground service — notification permission, background
             * start restrictions, foreground-service-type rules that change every release — and
             * each one throws something different here.
             *
             * Now the reason lands on the console screen instead of in a logcat nobody is attached
             * to.
             */
            lastError = e.message ?: e.javaClass.simpleName
            android.util.Log.e("AynFtp", "FTP service failed to start", e)
            stopEverything()
            START_NOT_STICKY
        }

    private fun handleStart(intent: Intent?): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        lastError = null
        if (server != null) return START_STICKY      // already up; a second tap changes nothing

        val settings = Settings(this)
        val port = intent.getIntExtra(EXTRA_PORT, FtpServer.DEFAULT_PORT)
        val user = intent.getStringExtra(EXTRA_USER).orEmpty()
        val pass = intent.getStringExtra(EXTRA_PASS).orEmpty()
        val wholeDevice = intent.getBooleanExtra(EXTRA_WHOLE_DEVICE, false)

        // Whole-device browsing is what makes this feel like the 3DS's ftpd, and it is also the part
        // Android fights hardest. Without MANAGE_EXTERNAL_STORAGE granted, rooting at "/" produces a
        // tree that looks right and is empty everywhere that matters -- so fall back to shared
        // storage, which always works, rather than serving a convincing lie.
        val root = if (wholeDevice && Storage.hasWholeDeviceAccess()) {
            File("/")
        } else {
            Environment.getExternalStorageDirectory() ?: filesDir
        }

        val ftp = FtpServer(
            port = port,
            root = root,
            username = user,
            password = pass,
            // Rooted at "/" there is nothing above to restrict to, and the check would only cost
            // a canonicalise per path.
            restrictToRoot = root.path != "/",
            onClients = { attached -> updateNotification(port, root, attached.size) },
        )

        goForeground(port, root, 0)

        if (ftp.start() == null) {
            // The port was taken. Say so in the notification AND on the console screen, rather than
            // sitting there looking like a running server that nothing can connect to.
            lastError = "Port $port is already in use. Try another, such as 2122."
            notify(
                title = "FTP could not start",
                text = lastError ?: "",
                ongoing = false,
            )
            stopSelf()
            return START_NOT_STICKY
        }

        server = ftp
        live = ftp
        servingFrom = root.path
        settings.ftpRunning = true
        updateNotification(port, root, 0)
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        server?.stop()
        server = null
        live = null
        servingFrom = ""
        Settings(this).ftpRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── the notification ────────────────────────────────────────────────────

    private fun goForeground(port: Int, root: File, clients: Int) {
        val notification = build(port, root, clients)

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0,
        )
    }

    private fun updateNotification(port: Int, root: File, clients: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, build(port, root, clients))
    }

    private fun notify(title: String, text: String, ongoing: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(
            NOTIFICATION_ID,
            baseNotification().setContentTitle(title).setContentText(text).setOngoing(ongoing).build(),
        )
    }

    private fun build(port: Int, root: File, clients: Int): Notification {
        // Asked of the device, not of the server field — which is still null while the very first
        // notification is being built, and used to make this say "No network" on a device that had
        // one.
        val addresses = FtpServer.localAddresses()

        // The address is the whole point of the notification. Without a network there is nothing
        // useful to say, and saying "ftp://:2121" would be worse than admitting it.
        val where = when {
            addresses.isEmpty() -> "No network — connect to Wi-Fi first."
            else -> addresses.joinToString("  ") { "ftp://$it:$port" }
        }

        val busy = when (clients) {
            0 -> "Nobody connected"
            1 -> "1 client connected"
            else -> "$clients clients connected"
        }

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FtpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return baseNotification()
            .setContentTitle(where)
            .setContentText("$busy · serving ${root.path}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$where\n$busy\nServing ${root.path}"))
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun baseNotification(): NotificationCompat.Builder {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "FTP server", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the Thor is sharing its files over FTP."
                }
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
    }

    companion object {
        private const val CHANNEL = "ftp"
        private const val NOTIFICATION_ID = 0x1717

        /**
         * What the running server is doing, for any screen that wants to show it.
         *
         * A static handle rather than a bound service: the only reader is one activity that wants
         * an address and a list of clients a few times a second, and binding — with its connection
         * callbacks and its lifecycle — would be a great deal of ceremony for a snapshot. Null
         * whenever nothing is running, which is exactly the question the UI asks first.
         */
        @Volatile
        var live: FtpServer? = null
            private set

        /** Where the running server is rooted, for the screen to display. */
        @Volatile
        var servingFrom: String = ""
            private set

        /**
         * Why the last start attempt failed, or null if it did not.
         *
         * Read by the console screen so a failure is visible on the device. A user cannot attach a
         * debugger, and "nothing happened" is the least actionable bug report there is.
         */
        @Volatile
        var lastError: String? = null

        const val ACTION_START = "com.abacus.dualscreen.FTP_START"
        const val ACTION_STOP = "com.abacus.dualscreen.FTP_STOP"

        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_WHOLE_DEVICE = "whole"

        fun start(
            context: Context,
            port: Int = FtpServer.DEFAULT_PORT,
            user: String = "",
            pass: String = "",
            wholeDevice: Boolean = true,
        ) {
            val intent = Intent(context, FtpService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_USER, user)
                .putExtra(EXTRA_PASS, pass)
                .putExtra(EXTRA_WHOLE_DEVICE, wholeDevice)

            /*
             * ContextCompat, NOT Context.startForegroundService.
             *
             * That method arrived in Android 8.0. Calling it directly on anything older throws
             * NoSuchMethodError, which is not an exception a try/catch around the service helps
             * with — it kills the calling activity outright, so the screen simply vanishes the
             * instant Start is pressed. That is what "the FTP closes when I press start server"
             * was.
             *
             * ContextCompat picks startService below 26 and startForegroundService at or above it.
             */
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FtpService::class.java).setAction(ACTION_STOP))
        }
    }
}
