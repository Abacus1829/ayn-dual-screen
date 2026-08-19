package com.abacus.dualscreen.macro

import com.abacus.dualscreen.Macro
import com.abacus.dualscreen.MacroProfile
import org.json.JSONArray
import org.json.JSONObject

/** Why an import was refused, in terms somebody can act on. */
enum class LayoutError {
    NOT_JSON,
    NOT_A_LAYOUT,
    TOO_NEW,
    NO_BUTTONS,
}

/** What came out of a file: a layout and the macros it needs, or a reason it could not be read. */
sealed class LayoutImport {
    data class Ok(val layout: MacroProfile, val scripts: List<MacroScript>) : LayoutImport()
    data class Failed(val reason: LayoutError) : LayoutImport()
}

/**
 * A layout, packaged so it can be handed to somebody else.
 *
 * Self-contained on purpose: a layout whose buttons run macros is useless without those macros, so
 * the package carries them. Only the ones the layout actually references travel — exporting one
 * layout should not hand over every macro on the device.
 *
 * ```json
 * {
 *   "format": 1,
 *   "kind": "ayn-dual-screen-layout",
 *   "layout": { "name": "…", "macros": [ … ] },
 *   "scripts": [ { "id": "…", "name": "…", "actions": [ … ] } ]
 * }
 * ```
 *
 * [FORMAT] is the number to raise when the shape changes in a way an older build could not read.
 * Anything newer is refused rather than half-read, because a layout that imports with its buttons
 * silently missing is worse than one that does not import.
 */
object LayoutPackage {

    const val FORMAT = 1
    const val KIND = "ayn-dual-screen-layout"

    fun export(layout: MacroProfile, allScripts: List<MacroScript>): String {
        val needed = allScripts.filter { script ->
            layout.macros.any { it.kind == Macro.Kind.SCRIPT && it.payload == script.id }
        }

        return JSONObject()
            .put("format", FORMAT)
            .put("kind", KIND)
            .put("app", "AynDualScreen")
            .put("exported", System.currentTimeMillis())
            .put("layout", layout.toJson())
            .put("scripts", JSONArray().also { array -> needed.forEach { array.put(it.toJson()) } })
            .toString(2)
    }

    /**
     * Read a package.
     *
     * Validation is deliberately shallow but real: it must parse, it must say it is a layout, its
     * format must not be from the future, and it must contain at least one button. Individual buttons
     * and steps that will not read are dropped — a layout that loses one broken button is still worth
     * having, and refusing the lot because of one bad field would make hand-edited files unusable.
     */
    fun import(text: String): LayoutImport {
        val json = runCatching { JSONObject(text.trim()) }.getOrNull()
            ?: return LayoutImport.Failed(LayoutError.NOT_JSON)

        // The kind check is what stops a profiles export -- same app, same folder, similar shape --
        // from being imported as a layout and producing an empty pad.
        if (json.optString("kind") != KIND && !json.has("layout")) {
            return LayoutImport.Failed(LayoutError.NOT_A_LAYOUT)
        }

        val format = json.optInt("format", 1)
        if (format > FORMAT) return LayoutImport.Failed(LayoutError.TOO_NEW)

        val layoutJson = json.optJSONObject("layout")
            ?: return LayoutImport.Failed(LayoutError.NOT_A_LAYOUT)

        val layout = runCatching { MacroProfile.fromJson(layoutJson) }.getOrNull()
            ?: return LayoutImport.Failed(LayoutError.NOT_A_LAYOUT)

        // Buttons out of bounds arrive from a device with a different aspect ratio, or from a file
        // somebody edited. Clamping is kinder than dropping them off the edge of the screen.
        layout.macros.forEach {
            it.x = it.x.coerceIn(0f, 0.98f)
            it.y = it.y.coerceIn(0f, 0.98f)
            it.size = it.size.coerceIn(44, 104)
        }

        if (layout.macros.isEmpty()) return LayoutImport.Failed(LayoutError.NO_BUTTONS)

        val scripts = mutableListOf<MacroScript>()
        val array = json.optJSONArray("scripts") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            runCatching { MacroScript.fromJson(item) }.getOrNull()?.let { scripts += it }
        }

        return LayoutImport.Ok(layout, scripts)
    }
}
