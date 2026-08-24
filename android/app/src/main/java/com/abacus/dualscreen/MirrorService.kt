package com.abacus.dualscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Puts a live copy of the Thor's main screen onto its second display.
 *
 * Has to be a foreground service: from Android 10 the system will not hand out a MediaProjection to a
 * background process, and from 14 the service must already be running with the mediaProjection type
 * before the projection is taken. So the activity asks permission, hands the result here, and this holds
 * the capture for as long as the mirror is up.
 */
class MirrorService : android.app.Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null

    /** Tear everything down if the system revokes the capture from under us. */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // must be in the foreground *before* the projection is taken, or Android 14 refuses it
        goForeground()

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val displayId = intent.getIntExtra(EXTRA_DISPLAY, -1)

        if (data == null || displayId < 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        val target = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(displayId)
        if (target == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data)?.also {
            it.registerCallback(projectionCallback, null)
        }

        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        show(target)
        running = true
        return START_NOT_STICKY
    }

    /**
     * A black full-screen window on the second display, holding one surface.
     *
     * The capture is pointed straight at that surface rather than at a bitmap we redraw ourselves: the
     * compositor does the scaling, so the mirror costs the app almost nothing per frame.
     */
    private fun show(target: Display) {
        val host = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        host.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        val window = Presentation(this, target).apply {
            setContentView(host)
            window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            // A mirror is a picture: it has nothing to type into and nothing to press. Taking the
            // input focus would stop the controller reaching whatever is being mirrored, which is
            // the one thing a mirror must not do.
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        presentation = window

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                virtualDisplay?.release()
                virtualDisplay = capture(holder, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                virtualDisplay?.release()
                virtualDisplay = null
            }
        })

        runCatching { window.show() }
            .onFailure { stopSelf() }
    }

    private fun capture(holder: SurfaceHolder, width: Int, height: Int): VirtualDisplay? {
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)?.getMetrics(it)
        }

        return projection?.createVirtualDisplay(
            "ThorMirror",
            width,
            height,
            metrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            holder.surface,
            null,
            null
        )
    }

    /*********
     * Foreground
     *********/
    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, getString(R.string.mirror_channel), NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.mirror_running))
            .setContentText(getString(R.string.mirror_running_detail))
            .setOngoing(true)
            .addAction(0, getString(R.string.mirror_stop), stop)
            .build()

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else 0
        )
    }

    override fun onDestroy() {
        running = false
        virtualDisplay?.release()
        virtualDisplay = null
        runCatching { presentation?.dismiss() }
        presentation = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DISPLAY = "display"
        const val ACTION_STOP = "com.abacus.dualscreen.STOP_MIRROR"

        private const val CHANNEL = "mirror"
        private const val NOTIFICATION_ID = 41

        /** Whether a mirror is up, so the screen that started it can offer to stop it. */
        @Volatile
        var running = false
            private set
    }
}
