package com.abacus.dualscreen.macro

import android.view.KeyEvent
import org.json.JSONObject

/**
 * One step in a macro.
 *
 * Deliberately a single data class with a [kind] and three loosely-typed parameters rather than a
 * sealed hierarchy. Every step has to survive a round trip through JSON and through a list editor
 * that does not know what it is holding, and a sealed class buys type safety at the cost of a
 * serialiser per subclass and a UI that has to switch on the type twice.
 *
 * What can actually be done here is bounded by Android, not by imagination:
 *
 * - **Keys** go through this app's own keyboard ([com.abacus.dualscreen.KeyboardService]). It has to
 *   be the active input method and something has to have focus, which is the same requirement the
 *   macro pad has always had.
 * - **Game actions** are an HTTP POST to the companion mod, which is how the second screen's own
 *   buttons already work.
 * - **Taps** land on the second screen this app is showing. There is no step that moves a pointer in
 *   another app: injecting pointer events elsewhere needs root or an accessibility service, and
 *   pretending otherwise would produce a button that silently does nothing.
 */
data class MacroAction(
    var kind: Kind,
    /** Key name, text to type, action type, or app package — whatever [kind] means by it. */
    var value: String = "",
    /** Milliseconds for [Kind.DELAY] and [Kind.KEY_HOLD]; index for [Kind.GAME]; x for [Kind.TAP]. */
    var number: Int = 0,
    /** Second number: `to` for [Kind.GAME], y for [Kind.TAP]. Unused elsewhere. */
    var number2: Int = 0,
) {

    enum class Kind(val id: String) {
        /** Press and release, which is what most things want. */
        KEY_PRESS("key"),

        /** Hold a key down and leave it down. Pair it with [KEY_UP] or nothing will release it. */
        KEY_DOWN("keydown"),

        KEY_UP("keyup"),

        /** Down, wait [number] ms, up. The common case, without needing three steps. */
        KEY_HOLD("keyhold"),

        /** Type a string. */
        TEXT("text"),

        /** Wait [number] ms before the next step. */
        DELAY("delay"),

        /** POST to the companion mod's /action endpoint. */
        GAME("game"),

        /** Tap a point on the second screen, as a fraction of its width and height in tenths of a percent. */
        TAP("tap"),

        /** Launch an app by package name. */
        APP("app"),

        /** Open one of this app's own tools. */
        TOOL("tool");

        companion object {
            fun byId(id: String?): Kind = entries.firstOrNull { it.id == id } ?: KEY_PRESS
        }
    }

    /** The Android key code for this step, or null when the step is not about a key. */
    fun keyCode(): Int? = KEYS.firstOrNull { it.first == value }?.second

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.id)
        .put("value", value)
        .put("number", number)
        .put("number2", number2)

    companion object {
        fun fromJson(json: JSONObject): MacroAction = MacroAction(
            kind = Kind.byId(json.optString("kind")),
            value = json.optString("value"),
            number = json.optInt("number", 0),
            number2 = json.optInt("number2", 0),
        )

        /**
         * The keys a step can press.
         *
         * A superset of the macro pad's original list — that one covered what a button needed, and a
         * macro wants letters and numbers as well. Names are what appears in the editor, so they are
         * short and unambiguous rather than the constant names.
         */
        val KEYS: List<Pair<String, Int>> = buildList {
            add("ENTER" to KeyEvent.KEYCODE_ENTER)
            add("ESC" to KeyEvent.KEYCODE_ESCAPE)
            add("TAB" to KeyEvent.KEYCODE_TAB)
            add("BACK" to KeyEvent.KEYCODE_BACK)
            add("SPACE" to KeyEvent.KEYCODE_SPACE)
            add("DEL" to KeyEvent.KEYCODE_DEL)
            add("UP" to KeyEvent.KEYCODE_DPAD_UP)
            add("DOWN" to KeyEvent.KEYCODE_DPAD_DOWN)
            add("LEFT" to KeyEvent.KEYCODE_DPAD_LEFT)
            add("RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT)
            add("CENTER" to KeyEvent.KEYCODE_DPAD_CENTER)
            add("SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT)
            add("CTRL" to KeyEvent.KEYCODE_CTRL_LEFT)
            add("ALT" to KeyEvent.KEYCODE_ALT_LEFT)

            for (c in 'A'..'Z') add(c.toString() to (KeyEvent.KEYCODE_A + (c - 'A')))
            for (n in 0..9) add(n.toString() to (KeyEvent.KEYCODE_0 + n))
            for (f in 1..12) add("F$f" to (KeyEvent.KEYCODE_F1 + f - 1))
        }

        /** A readable one-liner for the action list. */
        fun describe(action: MacroAction): String = when (action.kind) {
            Kind.KEY_PRESS -> "Press ${action.value}"
            Kind.KEY_DOWN -> "Hold down ${action.value}"
            Kind.KEY_UP -> "Release ${action.value}"
            Kind.KEY_HOLD -> "Hold ${action.value} for ${action.number} ms"
            Kind.TEXT -> "Type \"${action.value}\""
            Kind.DELAY -> "Wait ${action.number} ms"
            Kind.GAME -> "Game action \"${action.value}\"" +
                (if (action.number >= 0) " index ${action.number}" else "") +
                (if (action.number2 >= 0) " to ${action.number2}" else "")
            Kind.TAP -> "Tap second screen at ${action.number / 10f}%, ${action.number2 / 10f}%"
            Kind.APP -> "Open app ${action.value}"
            Kind.TOOL -> "Open ${action.value}"
        }
    }
}
