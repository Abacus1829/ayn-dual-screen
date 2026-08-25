package com.abacus.dualscreen

import androidx.annotation.DrawableRes
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

    /**
     * A drawn icon, used in preference to [glyph] when set.
     *
     * Exists because a font is not a guarantee. Four different characters for Mirror each rendered
     * as tofu on the test device, and finding that out costs a build and an install every time. A
     * vector always draws; the glyph stays as the fallback for the text-icon mode and for tools
     * that do not need one.
     */
    @DrawableRes val icon: Int = 0,

    /**
     * Unfinished, and it says so on the tile.
     *
     * Different from [available]: a beta tool opens and works, it is just not done. Marking it is
     * how somebody knows which rough edges are already known.
     */
    val beta: Boolean = false,

    /** Never shown as a tile on the home grid. Reachable from elsewhere, or not at all. */
    val hidden: Boolean = false,

    val available: Boolean = true,
    @StringRes val blockedReason: Int = 0
) {
    /**
     * Saved connections: the shortest path from opening the app to being on the second screen.
     *
     * First in the list on purpose. It is the thing this app is for.
     */
    PROFILES("profiles", R.string.tool_profiles, "⇢"),

    /**
     * The console's own vital signs.
     *
     * High in the list because it is a destination rather than a utility — the thing you open to
     * find out how hot the console is running, in the way you would open the vendor's dashboard.
     */
    DASHBOARD("dashboard", R.string.tool_dashboard, "◍"),

    SECOND_SCREEN("screen", R.string.tool_second_screen, "▣"),
    NOTES("notes", R.string.tool_notes, "✎"),
    VOLUME("volume", R.string.tool_volume, "◧"),
    // Drawn rather than typed: ☀ has an emoji presentation, so on some devices it rendered as a
    // yellow sun that ignored the accent while every tile around it followed it.
    BRIGHTNESS("brightness", R.string.tool_brightness, "☀", icon = R.drawable.ic_brightness),
    APPEARANCE("appearance", R.string.tool_appearance, "◈"),
    KEYBOARD("keyboard", R.string.tool_keyboard, "⌨"),
    // Drawn rather than typed: see the icon field above.
    MIRROR("mirror", R.string.tool_mirror, "▣", icon = R.drawable.ic_mirror),

    // The pad itself is on-device. Its text and key macros go out through this app's own keyboard, which
    // is as far as Android lets one app reach into another without a signature-level permission.
    MACROS("macros", R.string.tool_macros, "⚙"),

    /**
     * Doodles and short messages, sent to every other device on the Wi-Fi.
     *
     * No pairing and no address to type: presence goes out as a UDP broadcast and anybody running
     * this app answers. Works alone too, in which case it is a drawing scrapbook kept on disk.
     */
    SCRIBBLE("scribble", R.string.tool_scribble, "✍"),

    /**
     * Build sequences of key presses, waits and game actions, and run them.
     *
     * Optional: the app works exactly as before without a single macro, and nothing else depends
     * on one existing.
     */
    MACRO_BUILDER("macrobuilder", R.string.tool_macro_builder, "≡", hidden = true),

    /** Arrange the macro pad, keep several layouts, and hand one to somebody else. */
    LAYOUTS("layouts", R.string.tool_layouts, "▦", hidden = true),

    /**
     * What the handheld knows about itself: clock, battery, network, storage, a stopwatch.
     *
     * The one tool that needs nothing -- no game, no server, no network, no extra permission.
     */
    WIDGETS("widgets", R.string.tool_widgets, "◴"),

    /**
     * Game codes, when a companion offers any.
     *
     * Hidden by default and shown only once the feature has been found and switched on. The grid
     * filters it through [hidden] like any other, so nothing about the home screen had to learn
     * about this feature specially.
     */
    GAME_CODES("gamecodes", R.string.tool_game_codes, "⌘", hidden = true),

    /**
     * Everything the app remembers, gathered.
     *
     * The switches still live where they always did; this is one place to find them.
     */
    SETTINGS("settings", R.string.tool_settings, "⚙"),

    /** An FTP server on this device, so a PC can browse it over the network, like a homebrew file server. */
    FTP("ftp", R.string.tool_ftp, "⇅"),

    /**
     * Console skins for the home screen, and the folder where user-made ones live.
     *
     * Kept OFF the home grid: [hidden] means it never appears as a tile, and the only way in is
     * Appearance. The skins are unfinished and they change how the whole app looks, which is not
     * something to leave one stray tap away on the main screen.
     */
    THEMES("themes", R.string.tool_themes, "◐", beta = true, hidden = true),

    /**
     * Pairing with a PC that can stream games.
     *
     * Open rather than greyed, now that there is a screen behind it worth reaching: it finds a host
     * and completes the pairing handshake, which is the hard half. It does NOT play anything, and
     * the screen says so in its first paragraph rather than letting somebody discover it.
     */
    STREAM("stream", R.string.tool_stream, "▶", beta = true);

    companion object {
        fun byId(id: String?): Tool? = entries.firstOrNull { it.id == id }
    }
}
