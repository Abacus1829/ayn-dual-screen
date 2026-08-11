package com.abacus.dualscreen

import android.content.Context

/**
 * Everything the app remembers, in one place.
 *
 * Addresses are per game, because the two mods default to the same port but may well be on different
 * machines. Appearance and tool state are global — they describe this device, not any one game.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /*********
     * Connection
     *********/
    /** The entry selected last time, so the app opens where it was left. */
    var lastGame: Game
        get() = Game.byId(prefs.getString(KEY_LAST_GAME, null))
        set(value) = prefs.edit().putString(KEY_LAST_GAME, value.id).apply()

    /** Simple mode hides everything except the picker and one button. */
    var advanced: Boolean
        get() = prefs.getBoolean(KEY_ADVANCED, false)
        set(value) = prefs.edit().putBoolean(KEY_ADVANCED, value).apply()

    /** Probe the saved address on launch and switch the picker to whatever is actually running. */
    var autoDetect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DETECT, value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_RECONNECT, value).apply()

    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    var rememberDisplay: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_DISPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_DISPLAY, value).apply()

    fun hostFor(game: Game): String =
        prefs.getString("${KEY_HOST}_${game.id}", "").orEmpty()

    fun setHostFor(game: Game, host: String) =
        prefs.edit().putString("${KEY_HOST}_${game.id}", host).apply()

    fun portFor(game: Game): Int =
        prefs.getInt("${KEY_PORT}_${game.id}", game.defaultPort)

    fun setPortFor(game: Game, port: Int) =
        prefs.edit().putInt("${KEY_PORT}_${game.id}", port).apply()

    /** The display last used for this entry, or -1 if none has been chosen yet. */
    fun displayFor(game: Game): Int =
        prefs.getInt("${KEY_DISPLAY}_${game.id}", -1)

    fun setDisplayFor(game: Game, displayId: Int) =
        prefs.edit().putInt("${KEY_DISPLAY}_${game.id}", displayId).apply()

    /*********
     * Appearance
     *********/
    /** Accent colour as an ARGB int. Drives every highlight in the app. */
    var accent: Int
        get() = prefs.getInt(KEY_ACCENT, 0)
        set(value) = prefs.edit().putInt(KEY_ACCENT, value).apply()

    /** A picked image shown behind the home screen, or empty for none. */
    var backgroundUri: String
        get() = prefs.getString(KEY_BACKGROUND, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BACKGROUND, value).apply()

    /** How much the background is dimmed so text stays readable over it, 0-90 percent. */
    var backgroundDim: Int
        get() = prefs.getInt(KEY_BACKGROUND_DIM, 55)
        set(value) = prefs.edit().putInt(KEY_BACKGROUND_DIM, value).apply()

    /** Text scale, 0.85 to 1.4. */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()

    /** Corner rounding for cards and buttons, in dp. Squared-off or soft is a big part of the look. */
    var corners: Int
        get() = prefs.getInt(KEY_CORNERS, 12)
        set(value) = prefs.edit().putInt(KEY_CORNERS, value).apply()

    /** One of [Appearance.FONTS]. */
    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "sans-serif").orEmpty()
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    /** none, gradient or image. Decides what sits behind every screen. */
    var backgroundMode: String
        get() = prefs.getString(KEY_BG_MODE, "gradient").orEmpty()
        set(value) = prefs.edit().putString(KEY_BG_MODE, value).apply()

    /** How opaque the cards are over the background, 40-100 percent. */
    var surfaceOpacity: Int
        get() = prefs.getInt(KEY_SURFACE_OPACITY, 88)
        set(value) = prefs.edit().putInt(KEY_SURFACE_OPACITY, value).apply()

    /** Tools per row on the home grid. */
    var gridColumns: Int
        get() = prefs.getInt(KEY_GRID_COLUMNS, 4)
        set(value) = prefs.edit().putInt(KEY_GRID_COLUMNS, value).apply()

    /** One of [Appearance.ICON_SETS]. */
    var iconSet: String
        get() = prefs.getString(KEY_ICON_SET, "glyph").orEmpty()
        set(value) = prefs.edit().putString(KEY_ICON_SET, value).apply()

    /** Tools the user has hidden from the home grid. */
    var hiddenTools: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_TOOLS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_TOOLS, value).apply()

    /** Tool order as ids; anything missing falls back to declaration order. */
    var toolOrder: List<String>
        get() = prefs.getString(KEY_TOOL_ORDER, null)?.split(',')?.filter { it.isNotBlank() }
            ?: Tool.entries.map { it.id }
        set(value) = prefs.edit().putString(KEY_TOOL_ORDER, value.joinToString(",")).apply()

    /*********
     * Keyboard
     *********/
    /** Split the rows into two thumb-reachable halves, Steam Deck style. */
    var keyboardSplit: Boolean
        get() = prefs.getBoolean(KEY_KB_SPLIT, true)
        set(value) = prefs.edit().putBoolean(KEY_KB_SPLIT, value).apply()

    /** Key height, 0-100, mapped to 38-64dp. */
    var keyboardSize: Int
        get() = prefs.getInt(KEY_KB_SIZE, 50)
        set(value) = prefs.edit().putInt(KEY_KB_SIZE, value).apply()

    var keyboardHaptics: Boolean
        get() = prefs.getBoolean(KEY_KB_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_KB_HAPTICS, value).apply()

    /*********
     * Tools
     *********/
    var notes: String
        get() = prefs.getString(KEY_NOTES, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_NOTES, value).apply()

    /** Which tools are pinned to the home screen, as an ordered list of ids. */
    var pinnedTools: List<String>
        get() = prefs.getString(KEY_TOOLS, null)?.split(',')?.filter { it.isNotBlank() }
            ?: Tool.entries.map { it.id }
        set(value) = prefs.edit().putString(KEY_TOOLS, value.joinToString(",")).apply()

    private companion object {
        const val PREFS = "dual_screen"

        const val KEY_LAST_GAME = "last_game"
        const val KEY_ADVANCED = "advanced"
        const val KEY_AUTO_DETECT = "auto_detect"
        const val KEY_RECONNECT = "auto_reconnect"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_REMEMBER_DISPLAY = "remember_display"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_DISPLAY = "display"

        const val KEY_ACCENT = "accent"
        const val KEY_BACKGROUND = "background_uri"
        const val KEY_BACKGROUND_DIM = "background_dim"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_CORNERS = "corners"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_BG_MODE = "bg_mode"
        const val KEY_SURFACE_OPACITY = "surface_opacity"
        const val KEY_GRID_COLUMNS = "grid_columns"
        const val KEY_ICON_SET = "icon_set"
        const val KEY_HIDDEN_TOOLS = "hidden_tools"
        const val KEY_TOOL_ORDER = "tool_order"

        const val KEY_KB_SPLIT = "kb_split"
        const val KEY_KB_SIZE = "kb_size"
        const val KEY_KB_HAPTICS = "kb_haptics"

        const val KEY_NOTES = "notes"
        const val KEY_TOOLS = "tools"
    }
}
