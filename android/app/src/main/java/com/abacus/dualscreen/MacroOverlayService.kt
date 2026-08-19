package com.abacus.dualscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * The macro pad: floating buttons that stay put while you use other apps.
 *
 * Each button is its own small overlay window rather than one full-screen overlay with holes in it.
 * That matters — a full-screen overlay would sit between you and whatever is underneath, and everything
 * outside a button would have to be forwarded by hand. This way the system only sends us the taps that
 * actually land on a button.
 */
class MacroOverlayService : Service() {

    private lateinit var settings: Settings
    private lateinit var store: MacroStore
    private lateinit var windows: WindowManager

    private val buttons = mutableListOf<Pair<Macro, View>>()
    private var editing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = MacroStore(this)
        windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_EDIT -> {
                editing = intent.getBooleanExtra(EXTRA_EDITING, false)
                rebuild()
                return START_STICKY
            }
        }

        goForeground()
        editing = intent?.getBooleanExtra(EXTRA_EDITING, false) ?: false
        rebuild()
        running = true
        return START_STICKY
    }

    /*********
     * Buttons
     *********/
    private fun rebuild() {
        clear()
        val metrics = resources.displayMetrics

        for (macro in store.active.macros) {
            val size = dp(macro.size)
            val view = TextView(this).apply {
                text = macro.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (macro.label.length > 3) 12f else 17f)
                setTextColor(getColor(R.color.text))
                background = Appearance.panel(
                    this@MacroOverlayService,
                    settings,
                    Appearance.blend(getColor(R.color.card), Appearance.accentOf(settings), 0.25f),
                    Appearance.accentOf(settings),
                    2
                )
                alpha = if (editing) 1f else 0.82f
            }

            val params = WindowManager.LayoutParams(
                size, size,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (macro.x * metrics.widthPixels).toInt().coerceIn(0, metrics.widthPixels - size)
                y = (macro.y * metrics.heightPixels).toInt().coerceIn(0, metrics.heightPixels - size)
            }

            attach(view, params, macro, size)
        }
    }

    /**
     * One touch handler doing two jobs: drag while editing, fire while not.
     *
     * Kept as a movement threshold rather than a long-press, because on a handheld a macro you have to
     * hold to move is a macro you fire by accident every time you try to move it.
     */
    private fun attach(view: View, params: WindowManager.LayoutParams, macro: Macro, size: Int) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!editing) return@setOnTouchListener true
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && kotlin.math.hypot(dx, dy) < dp(6)) return@setOnTouchListener true
                    moved = true

                    val metrics = resources.displayMetrics
                    params.x = (startX + dx).toInt().coerceIn(0, metrics.widthPixels - size)
                    params.y = (startY + dy).toInt().coerceIn(0, metrics.heightPixels - size)
                    runCatching { windows.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    when {
                        editing && moved -> savePosition(macro, params)
                        editing -> Unit

                        /*
                         * A long press is decided on release, not by a timer while the finger is
                         * down. On a pad you can also drag, a timer makes "long press" and "about to
                         * move" the same gesture; measuring afterwards costs nothing and is never
                         * wrong about which one happened.
                         */
                        event.eventTime - event.downTime >= LONG_PRESS_MS &&
                            macro.bindings.containsKey(Macro.Trigger.LONG_PRESS.id) ->
                            runBinding(view, macro, Macro.Trigger.LONG_PRESS)

                        else -> tapped(view, macro)
                    }
                    true
                }

                else -> true
            }
        }

        runCatching { windows.addView(view, params) }
            .onSuccess { buttons += macro to view }
    }

    private fun savePosition(macro: Macro, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        macro.x = params.x.toFloat() / metrics.widthPixels
        macro.y = params.y.toFloat() / metrics.heightPixels

        val profile = store.active
        profile.macros.firstOrNull { it.id == macro.id }?.let {
            it.x = macro.x
            it.y = macro.y
        }
        store.save(profile)
    }

    /*********
     * Gestures
     *********/

    /** When the last tap on each button landed, so a second one soon after is a double tap. */
    private val lastTap = mutableMapOf<String, Long>()

    /** Buttons currently holding a key down, for [Macro.toggle]. */
    private val held = mutableMapOf<String, Int>()

    /**
     * A plain tap — which may turn out to be the second half of a double tap.
     *
     * The double-tap binding is only waited for when the button actually has one. Without that
     * check, every button on the pad would sit on its hands for a quarter of a second before doing
     * anything, to support a gesture almost none of them use.
     */
    private fun tapped(view: View, macro: Macro) {
        val double = macro.bindings[Macro.Trigger.DOUBLE_TAP.id]

        if (double == null) {
            fireTap(view, macro)
            return
        }

        val now = System.currentTimeMillis()
        val previous = lastTap[macro.id] ?: 0L

        if (now - previous <= DOUBLE_TAP_MS) {
            lastTap.remove(macro.id)
            view.removeCallbacks(pending[macro.id] ?: Runnable {})
            runBinding(view, macro, Macro.Trigger.DOUBLE_TAP)
            return
        }

        lastTap[macro.id] = now

        // The single tap is deferred by the double-tap window, so a button with both bindings does
        // not fire the single one first and the double one immediately afterwards.
        val single = Runnable { fireTap(view, macro) }
        pending[macro.id] = single
        view.postDelayed(single, DOUBLE_TAP_MS)
    }

    private val pending = mutableMapOf<String, Runnable>()

    private fun runBinding(view: View, macro: Macro, trigger: Macro.Trigger) {
        val id = macro.bindings[trigger.id] ?: return
        val script = com.abacus.dualscreen.macro.MacroScriptStore(this).byId(id)

        if (script == null) {
            toast(getString(R.string.macro_no_script))
            return
        }

        com.abacus.dualscreen.ui.Feedback.hold(view)
        com.abacus.dualscreen.macro.MacroRunner.run(this, script)
    }

    /**
     * A tap, honouring [Macro.toggle].
     *
     * A toggling button holds its key down and leaves it down until the next press. Anything that is
     * not a key ignores the flag, because "held" means nothing for launching an app.
     */
    private fun fireTap(view: View, macro: Macro) {
        com.abacus.dualscreen.ui.Feedback.tap(view)

        if (!macro.toggle || macro.kind != Macro.Kind.KEY) {
            fire(macro)
            return
        }

        val code = Macro.KEYS.firstOrNull { it.first == macro.payload }?.second
        if (code == null) {
            fire(macro)
            return
        }

        val down = held.remove(macro.id)
        if (down != null) {
            if (!KeyboardService.up(down)) needKeyboard()
            view.alpha = 1f
        } else {
            if (!KeyboardService.down(code)) needKeyboard() else held[macro.id] = code
            // Dimmed while held, so a button that is holding a key down looks different from one
            // that is not. A toggle you cannot see the state of is worse than no toggle.
            view.alpha = 0.55f
        }
    }

    /*********
     * Firing
     *********/
    private fun fire(macro: Macro) {
        when (macro.kind) {
            Macro.Kind.TEXT ->
                if (!KeyboardService.type(macro.payload)) needKeyboard()

            Macro.Kind.KEY -> {
                val code = Macro.KEYS.firstOrNull { it.first == macro.payload }?.second
                if (code == null || !KeyboardService.press(code)) needKeyboard()
            }

            Macro.Kind.APP -> {
                val launch = packageManager.getLaunchIntentForPackage(macro.payload)
                if (launch == null) {
                    toast(getString(R.string.macro_no_app, macro.payload))
                } else {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            Macro.Kind.SCRIPT -> {
                val script = com.abacus.dualscreen.macro.MacroScriptStore(this)
                    .byId(macro.payload)

                if (script == null) toast(getString(R.string.macro_no_script))
                else com.abacus.dualscreen.macro.MacroRunner.run(this, script)
            }

            Macro.Kind.TOOL -> {
                val target = when (Tool.byId(macro.payload)) {
                    Tool.NOTES -> NotesActivity::class.java
                    Tool.VOLUME, Tool.BRIGHTNESS -> ControlsActivity::class.java
                    Tool.APPEARANCE -> AppearanceActivity::class.java
                    Tool.KEYBOARD -> KeyboardActivity::class.java
                    Tool.MIRROR -> MirrorActivity::class.java
                    else -> HomeActivity::class.java
                }
                startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** The honest failure: text and key macros go through our own keyboard, so it has to be the one in use. */
    private fun needKeyboard() = toast(getString(R.string.macros_needs_keyboard))

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /*********
     * Plumbing
     *********/
    /** Let go of every toggled key before the buttons disappear, or one stays down forever. */
    private fun releaseHeld() {
        held.values.forEach { KeyboardService.up(it) }
        held.clear()
    }

    private fun clear() {
        releaseHeld()
        for ((_, view) in buttons) runCatching { windows.removeView(view) }
        buttons.clear()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, getString(R.string.macro_channel), NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, MacroOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.macro_running))
            .setContentText(getString(R.string.macro_running_detail))
            .setOngoing(true)
            .addAction(0, getString(R.string.macro_hide), stop)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        running = false
        clear()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.abacus.dualscreen.STOP_MACROS"
        const val ACTION_EDIT = "com.abacus.dualscreen.EDIT_MACROS"
        const val EXTRA_EDITING = "editing"

        private const val CHANNEL = "macros"
        private const val NOTIFICATION_ID = 42

        /** Held for this long before release counts as a long press. Android's own default. */
        private const val LONG_PRESS_MS = 500L

        /** Two taps inside this window are one double tap. */
        private const val DOUBLE_TAP_MS = 260L

        @Volatile
        var running = false
            private set
    }
}
