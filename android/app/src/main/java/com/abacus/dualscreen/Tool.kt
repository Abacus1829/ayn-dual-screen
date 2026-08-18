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
    // Third attempt at this glyph. ⧉, ▥ and ◨ all drew as tofu on the test device; ◐ renders
    // (Themes uses it), so its neighbour ◑ is the safe pick. Worth checking on a real device
    // rather than trusting a font to have anything outside the common blocks.
    MIRROR("mirror", R.string.tool_mirror, "◎"),

    // The pad itself is on-device. Its text and key macros go out through this app's own keyboard, which
    // is as far as Android lets one app reach into another without a signature-level permission.
    MACROS("macros", R.string.tool_macros, "⚙"),

    /** An FTP server on this device, so a PC can browse it over the network — like ftpd on a 3DS. */
    FTP("ftp", R.string.tool_ftp, "⇅"),

    /** Console skins for the home screen, and the folder where user-made ones live. */
    THEMES("themes", R.string.tool_themes, "◐"),

    /**
     * Pairing with a PC that can stream games.
     *
     * Open rather than greyed, now that there is a screen behind it worth reaching: it finds a host
     * and completes the pairing handshake, which is the hard half. It does NOT play anything, and
     * the screen says so in its first paragraph rather than letting somebody discover it.
     */
    STREAM("stream", R.string.tool_stream, "▶");

    companion object {
        fun byId(id: String?): Tool? = entries.firstOrNull { it.id == id }
    }
}
