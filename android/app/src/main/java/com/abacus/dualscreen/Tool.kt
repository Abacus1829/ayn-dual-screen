package com.abacus.dualscreen

import androidx.annotation.StringRes

/**
 * A tool on the home screen.
 *
 * Listed here rather than in the layout so the home grid, the pin order and any future reordering all
 * read one source. [available] marks the ones that work today; the rest are shown greyed with a reason,
 * because a tool that silently isn't there reads as a bug rather than as "not built yet".
 */
enum class Tool(
    val id: String,
    @StringRes val label: Int,
    val glyph: String,
    val available: Boolean = true,
    @StringRes val blockedReason: Int = 0
) {
    SECOND_SCREEN("screen", R.string.tool_second_screen, "▣"),
    NOTES("notes", R.string.tool_notes, "✎"),
    VOLUME("volume", R.string.tool_volume, "◧"),
    BRIGHTNESS("brightness", R.string.tool_brightness, "☀"),
    APPEARANCE("appearance", R.string.tool_appearance, "◈"),
    KEYBOARD("keyboard", R.string.tool_keyboard, "⌨"),
    MIRROR("mirror", R.string.tool_mirror, "⧉"),

    // The pad itself is on-device. Its text and key macros go out through this app's own keyboard, which
    // is as far as Android lets one app reach into another without a signature-level permission.
    MACROS("macros", R.string.tool_macros, "⚙");

    companion object {
        fun byId(id: String?): Tool? = entries.firstOrNull { it.id == id }
    }
}
