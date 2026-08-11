package com.abacus.dualscreen

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * The keyboard, as far as Android is concerned.
 *
 * A real input method rather than an in-app one, so it types into anything on the Thor — a browser, a
 * launcher, a game's own name field — instead of only into this app's own text boxes.
 */
class KeyboardService : InputMethodService() {

    private lateinit var settings: Settings
    private var keys: KeyboardView? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        live = this
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // rebuilt each time rather than cached: the appearance may have changed since it was last shown,
        // and a keyboard is cheap to make compared with getting a stale one wrong
        val view = KeyboardView(this, settings) { key -> handle(key) }
        keys = view
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keys?.reset()
        keys?.refresh()
    }

    private fun handle(key: KeyboardLayouts.Key) {
        val connection = currentInputConnection ?: return

        when (key.code) {
            KeyboardLayouts.Code.DELETE -> {
                // clear a selection if there is one, otherwise take the character behind the cursor
                val selected = connection.getSelectedText(0)
                if (selected.isNullOrEmpty()) connection.deleteSurroundingText(1, 0)
                else connection.commitText("", 1)
            }

            KeyboardLayouts.Code.ENTER -> sendEnter(connection)

            KeyboardLayouts.Code.SPACE -> connection.commitText(" ", 1)

            KeyboardLayouts.Code.LEFT -> tap(KeyEvent.KEYCODE_DPAD_LEFT)
            KeyboardLayouts.Code.RIGHT -> tap(KeyEvent.KEYCODE_DPAD_RIGHT)

            KeyboardLayouts.Code.HIDE -> requestHideSelf(0)

            else -> key.output?.let { connection.commitText(it, 1) }
        }
    }

    /**
     * Enter means different things in different fields.
     *
     * A search box wants its search action; a multi-line note wants a newline. Honouring the editor's
     * declared action is the difference between the key working everywhere and only working in notes.
     */
    private fun sendEnter(connection: android.view.inputmethod.InputConnection) {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val multiline = (info?.inputType ?: 0) and
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

        if (!multiline && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            connection.performEditorAction(action)
        } else {
            connection.commitText("\n", 1)
        }
    }

    private fun tap(keyCode: Int) {
        val connection = currentInputConnection ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * The way the macro pad gets text into other apps.
     *
     * An overlay button has no input connection of its own — only the active input method does. So the
     * running service hands one out here, and the macro fails honestly when this keyboard isn't the one
     * in use rather than appearing to work and doing nothing.
     */
    companion object {
        @Volatile
        private var live: KeyboardService? = null

        fun type(text: String): Boolean {
            val connection = live?.currentInputConnection ?: return false
            connection.commitText(text, 1)
            return true
        }

        fun press(keyCode: Int): Boolean {
            val service = live ?: return false
            if (service.currentInputConnection == null) return false
            service.tap(keyCode)
            return true
        }
    }
}
