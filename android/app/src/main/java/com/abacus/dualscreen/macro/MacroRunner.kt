package com.abacus.dualscreen.macro

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.abacus.dualscreen.KeyboardService
import com.abacus.dualscreen.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs a script.
 *
 * On its own thread, because the whole point of a macro is that it waits between steps and a wait on
 * the main thread is a frozen app. Everything that has to touch the UI or an input connection is
 * posted back.
 *
 * One script at a time. A second Run while one is going cancels the first rather than interleaving
 * two sequences of key presses, which is never what anybody meant.
 */
object MacroRunner {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running: Thread? = null

    /** Keys this run has pressed down and not yet released, so they can be freed if it is cancelled. */
    private val held = mutableSetOf<Int>()

    val isRunning: Boolean get() = running?.isAlive == true

    /**
     * Where game actions go.
     *
     * Set by the session screen while one is open. Null when nothing is connected, which is the
     * honest answer: a game step with no game to send it to should say so rather than silently
     * doing nothing.
     */
    @Volatile
    var target: String? = null

    /**
     * Taps on the second screen.
     *
     * Held by the session screen, which is the only thing that knows where its WebView is. Null when
     * no session is open.
     */
    @Volatile
    var tapper: ((Float, Float) -> Unit)? = null

    fun run(context: Context, script: MacroScript, onDone: (() -> Unit)? = null) {
        cancel()

        val app = context.applicationContext
        val steps = script.actions.map { it.copy() }      // a snapshot; editing mid-run must not alter it

        running = Thread({
            for (step in steps) {
                if (Thread.currentThread().isInterrupted) break
                perform(app, step)
            }

            releaseHeld()
            running = null
            onDone?.let { main.post(it) }
        }, "macro-${script.id}").apply {
            isDaemon = true
            start()
        }
    }

    /** Stop the running script and let go of anything it was holding down. */
    fun cancel() {
        running?.interrupt()
        running = null
        releaseHeld()
    }

    private fun releaseHeld() {
        if (held.isEmpty()) return
        val keys = held.toList()
        held.clear()
        main.post { keys.forEach { KeyboardService.up(it) } }
    }

    // ── the steps ───────────────────────────────────────────────────────────

    private fun perform(context: Context, action: MacroAction) {
        when (action.kind) {
            MacroAction.Kind.DELAY -> sleep(action.number)

            MacroAction.Kind.TEXT -> onMain { needsKeyboard(context, KeyboardService.type(action.value)) }

            MacroAction.Kind.KEY_PRESS -> action.keyCode()?.let { code ->
                onMain { needsKeyboard(context, KeyboardService.press(code)) }
            }

            MacroAction.Kind.KEY_DOWN -> action.keyCode()?.let { code ->
                held += code
                onMain { needsKeyboard(context, KeyboardService.down(code)) }
            }

            MacroAction.Kind.KEY_UP -> action.keyCode()?.let { code ->
                held -= code
                onMain { needsKeyboard(context, KeyboardService.up(code)) }
            }

            MacroAction.Kind.KEY_HOLD -> action.keyCode()?.let { code ->
                held += code
                onMain { KeyboardService.down(code) }
                sleep(action.number)
                held -= code
                onMain { needsKeyboard(context, KeyboardService.up(code)) }
            }

            MacroAction.Kind.GAME -> sendGameAction(context, action)

            MacroAction.Kind.TAP -> {
                val tap = tapper
                if (tap == null) onMain { toast(context, context.getString(R.string.macro_no_session)) }
                else onMain { tap(action.number / 1000f, action.number2 / 1000f) }
            }

            MacroAction.Kind.APP -> onMain {
                val launch = context.packageManager.getLaunchIntentForPackage(action.value)
                if (launch == null) toast(context, context.getString(R.string.macro_no_app, action.value))
                else context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            MacroAction.Kind.TOOL -> onMain { openTool(context, action.value) }
        }
    }

    /**
     * POST to the companion mod, the same way the second screen's own buttons do.
     *
     * `{type, index, to}` is the shape every mod in this project already accepts, so a macro step
     * reaches any of them without the app knowing which game is running. A negative index or `to` is
     * left out entirely rather than sent as -1, because that is what the page does and the mods treat
     * a missing field as "not applicable".
     */
    private fun sendGameAction(context: Context, action: MacroAction) {
        val base = target
        if (base.isNullOrBlank()) {
            onMain { toast(context, context.getString(R.string.macro_no_session)) }
            return
        }

        val body = JSONObject().put("type", action.value).apply {
            if (action.number >= 0) put("index", action.number)
            if (action.number2 >= 0) put("to", action.number2)
        }.toString()

        runCatching {
            val connection = (URL("$base/action").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
            }

            try {
                connection.outputStream.use { it.write(body.toByteArray()) }
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "game action failed", it) }
    }

    private fun openTool(context: Context, id: String) {
        val target = when (com.abacus.dualscreen.Tool.byId(id)) {
            com.abacus.dualscreen.Tool.NOTES -> com.abacus.dualscreen.NotesActivity::class.java
            com.abacus.dualscreen.Tool.VOLUME,
            com.abacus.dualscreen.Tool.BRIGHTNESS -> com.abacus.dualscreen.ControlsActivity::class.java
            com.abacus.dualscreen.Tool.APPEARANCE -> com.abacus.dualscreen.AppearanceActivity::class.java
            com.abacus.dualscreen.Tool.KEYBOARD -> com.abacus.dualscreen.KeyboardActivity::class.java
            com.abacus.dualscreen.Tool.MIRROR -> com.abacus.dualscreen.MirrorActivity::class.java
            com.abacus.dualscreen.Tool.PROFILES -> com.abacus.dualscreen.ProfilesActivity::class.java
            com.abacus.dualscreen.Tool.SCRIBBLE -> com.abacus.dualscreen.ScribbleActivity::class.java
            com.abacus.dualscreen.Tool.FTP -> com.abacus.dualscreen.FtpActivity::class.java
            else -> com.abacus.dualscreen.HomeActivity::class.java
        }

        context.startActivity(Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    /** Interruptible, so cancelling a macro that is mid-wait takes effect immediately. */
    private fun sleep(ms: Int) {
        if (ms <= 0) return
        runCatching { Thread.sleep(ms.coerceAtMost(MAX_DELAY_MS).toLong()) }
            .onFailure { Thread.currentThread().interrupt() }
    }

    private fun onMain(block: () -> Unit) = main.post { runCatching(block) }

    /**
     * Complain once, not once per step.
     *
     * A macro of twenty key presses run without this app's keyboard active would otherwise stack
     * twenty identical toasts, which outlives the macro and buries the screen.
     */
    private var warned = false

    private fun needsKeyboard(context: Context, sent: Boolean) {
        if (sent) {
            warned = false
            return
        }
        if (warned) return

        warned = true
        toast(context, context.getString(R.string.macros_needs_keyboard))
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private const val TAG = "MacroRunner"
    private const val TIMEOUT_MS = 3000

    /** A single wait longer than this is a mistake, not a macro. */
    private const val MAX_DELAY_MS = 60_000
}
