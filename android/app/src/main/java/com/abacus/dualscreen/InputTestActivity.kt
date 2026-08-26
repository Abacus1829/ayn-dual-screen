package com.abacus.dualscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.codes.SecretSequence
import com.abacus.dualscreen.databinding.ActivityInputBinding
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Nav

/**
 * What this device actually sends.
 *
 * Written because of a bug that could not be reproduced: the hidden button sequence worked on a
 * desktop emulator and did nothing on the handheld. Every plausible cause — the key codes, the
 * source, the axes, the focus, the timing — is invisible from the other end of a bug report, and
 * each round of "try this build" costs a rebuild, a transfer and an install.
 *
 * So this screen shows the events themselves. Press a button and its key code appears; move a stick
 * and the axis and value appear; swipe and the gesture appears. The same [SecretSequence] the home
 * screen uses runs underneath, so its progress display answers the actual question — *does this
 * device's input satisfy the watcher?* — rather than a proxy for it.
 *
 * Everything here is read-only. It watches events on their way past and never consumes one.
 */
class InputTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInputBinding
    private lateinit var settings: Settings

    private val log = ArrayDeque<String>()
    private var events = 0

    /** Where each axis was last time, so a held direction counts once. */
    private var hatX = 0
    private var hatY = 0

    private val sequence = SecretSequence(
        steps = SecretSequence.UNLOCK,
        onProgress = { at, total -> showProgress(at, total) },
    ) {
        Feedback.success(binding.root)
        binding.sequenceState.setText(R.string.input_seq_done)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        Nav.back(this, binding.backButton)

        binding.clearButton.setOnClickListener {
            Feedback.tap(it)
            log.clear()
            events = 0
            sequence.reset()
            render()
        }

        binding.copyButton.setOnClickListener {
            Feedback.tap(it)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("input log", report()))
            Feedback.toast(this, getString(R.string.input_copied))
        }

        binding.devicesText.text = devices()
        render()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
        com.abacus.dualscreen.control.ControlCenter.attach(this, settings)
    }

    // ── watching ────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            note(
                "KEY  " + KeyEvent.keyCodeToString(event.keyCode) +
                    "  (" + event.keyCode + ")  from " + sourceOf(event.source) +
                    "  device " + (InputDevice.getDevice(event.deviceId)?.name ?: event.deviceId)
            )
            sequence.onKey(event.keyCode)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val fromPad = event.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0 ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

        if (fromPad && event.action == MotionEvent.ACTION_MOVE) {
            axis(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X, event, horizontal = true)
            axis(MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y, event, horizontal = false)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun axis(hat: Int, stick: Int, event: MotionEvent, horizontal: Boolean) {
        val fromHat = event.getAxisValue(hat)
        val useHat = kotlin.math.abs(fromHat) > 0.5f
        val value = if (useHat) fromHat else event.getAxisValue(stick)

        val now = when {
            value > 0.5f -> 1
            value < -0.5f -> -1
            else -> 0
        }

        val was = if (horizontal) hatX else hatY
        if (now == was) return
        if (horizontal) hatX = now else hatY = now
        if (now == 0) return

        val key = when {
            horizontal && now > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            horizontal -> KeyEvent.KEYCODE_DPAD_LEFT
            now > 0 -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> KeyEvent.KEYCODE_DPAD_UP
        }

        note(
            "AXIS " + (if (useHat) "HAT" else "STICK") + (if (horizontal) "_X" else "_Y") +
                "  " + String.format("%+.2f", value) +
                "  read as " + KeyEvent.keyCodeToString(key)
        )
        sequence.onKey(key)
    }

    private fun note(line: String) {
        events++
        log.addFirst(String.format("%3d  %s", events, line))
        while (log.size > MAX_LINES) log.removeLast()
        render()
    }

    // ── showing ─────────────────────────────────────────────────────────────

    private fun render() {
        binding.logText.text = if (log.isEmpty()) getString(R.string.input_waiting)
        else log.joinToString("\n")

        binding.summaryText.text = getString(
            R.string.input_summary,
            events,
            if (hasPad()) "gamepad seen" else "no gamepad reported",
        )
    }

    private fun showProgress(at: Int, total: Int) {
        binding.sequenceState.text = getString(R.string.input_seq_progress, at, total)
    }

    /** Everything attached that can send anything, and what each one claims to be. */
    private fun devices(): String {
        // toList first: mapNotNull is not defined on a primitive IntArray.
        val lines = InputDevice.getDeviceIds().toList().mapNotNull { id ->
            val device = InputDevice.getDevice(id) ?: return@mapNotNull null
            val kinds = buildList {
                if (device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) add("gamepad")
                if (device.sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD) add("dpad")
                if (device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) add("joystick")
                if (device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD) add("keyboard")
                if (device.sources and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN) add("touch")
            }
            device.name + "  —  " + (if (kinds.isEmpty()) "other" else kinds.joinToString(", "))
        }

        return if (lines.isEmpty()) "none" else lines.joinToString("\n")
    }

    private fun hasPad(): Boolean = InputDevice.getDeviceIds().any { id ->
        val sources = InputDevice.getDevice(id)?.sources ?: 0
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private fun sourceOf(source: Int): String = buildList {
        if (source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) add("gamepad")
        if (source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD) add("dpad")
        if (source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) add("joystick")
        if (source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD) add("keyboard")
    }.ifEmpty { listOf("0x" + Integer.toHexString(source)) }.joinToString("+")

    /** The whole picture, for pasting into a bug report. */
    private fun report(): String = buildString {
        append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append(" (Android ")
            .append(android.os.Build.VERSION.RELEASE).append(", API ")
            .append(android.os.Build.VERSION.SDK_INT).append(")\n\n")
        append("input devices:\n").append(devices()).append("\n\n")
        append("events (newest first):\n")
        append(if (log.isEmpty()) "none" else log.joinToString("\n"))
    }

    private companion object {
        const val MAX_LINES = 60
    }
}
