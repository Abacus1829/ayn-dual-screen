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

    /**
     * Play the abacus animation when the app opens.
     *
     * On rather than off, because it is the app introducing itself and it costs two seconds. Off is
     * for people who open this fifteen times an evening and have seen it. Note that a device with
     * system animations switched off never sees it either, whatever this says — that setting is
     * honoured in the view itself.
     *
     * Lives here rather than with the update preferences: the animation is the app's, and the
     * update check merely happens to run underneath it.
     */
    var bootAnimation: Boolean
        get() = prefs.getBoolean(KEY_BOOT_ANIMATION, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_ANIMATION, value).apply()

    /**
     * Let whatever is playing keep the controller while a second screen is open.
     *
     * On by default, because it is what a second screen is for. Android focuses one window at a
     * time across every display, so a session window on the lower panel otherwise takes the pad
     * away from the game on the upper one until you touch the game again.
     *
     * Turning it off gives the session window the focus, which is what a page with a text field in
     * it needs — nothing else in the app wants it.
     */
    var keepGameFocus: Boolean
        get() = prefs.getBoolean(KEY_KEEP_GAME_FOCUS, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_GAME_FOCUS, value).apply()

    /** Probe the saved address on launch and switch the picker to whatever is actually running. */
    var autoDetect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DETECT, value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_RECONNECT, value).apply()

    /**
     * The old two-state screen-awake switch.
     *
     * Superseded by [awakeMode], and kept because it is what the toggle on the home screen writes
     * and what an older build's preferences contain. It seeds [awakeMode] the first time that is
     * read, so nobody's existing choice is quietly reversed by the update.
     */
    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    /**
     * When to hold the screen on: `always`, `connected` or `never`.
     *
     * Defaults from [keepAwake], so the behaviour somebody already had is the behaviour they keep.
     * See [com.abacus.dualscreen.connect.Awake].
     */
    var awakeMode: String
        get() = prefs.getString(KEY_AWAKE_MODE, null) ?: if (keepAwake) "always" else "never"
        set(value) = prefs.edit().putString(KEY_AWAKE_MODE, value).apply()

    /**
     * Where new profiles open by default: `main`, `second`, `external`, `auto` or `ask`.
     *
     * See [com.abacus.dualscreen.connect.DisplayChoice]. Automatic means the second screen when
     * there is one and this one when there is not, which is what almost everybody wants.
     */
    var displayChoice: String
        get() = prefs.getString(KEY_DISPLAY_CHOICE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_DISPLAY_CHOICE, value).apply()

    /** Default orientation for new profiles: `auto`, `landscape` or `portrait`. */
    var orientation: String
        get() = prefs.getString(KEY_ORIENTATION, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_ORIENTATION, value).apply()

    /** Show the session's floating controls. Off leaves only the small menu button. */
    var showControls: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CONTROLS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CONTROLS, value).apply()

    var rememberDisplay: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_DISPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_DISPLAY, value).apply()

    /*********
     * Console skin
     *********/
    /**
     * Which console the home screen is dressed as, or "default" for the app's own look.
     *
     * Stored as an id rather than an index so a user theme keeps working when another is added
     * beside it — the list is sorted by filename, and a position would silently point at somebody
     * else's theme the moment a new file lands.
     */
    var consoleTheme: String
        get() {
            /*
             * One-time reset back to the app's own look.
             *
             * Console skins are behind a beta marker now, and anyone already carrying one from an
             * earlier build should land on the stock look rather than on a half-finished costume
             * they never chose. Done once and remembered, so picking a skin afterwards sticks.
             */
            if (!prefs.getBoolean(KEY_THEME_BETA_RESET, false)) {
                prefs.edit()
                    .putBoolean(KEY_THEME_BETA_RESET, true)
                    .putString(KEY_CONSOLE_THEME, "default")
                    .commit()
                return "default"
            }

            return prefs.getString(KEY_CONSOLE_THEME, "default") ?: "default"
        }
        // commit() rather than apply(), unusually. apply() writes on a background thread, and a
        // theme is very often the last thing someone changes before backgrounding or killing the
        // app to go and look at the result -- which is exactly the window where the write is lost.
        // One synchronous write on a deliberate user action is cheap.
        set(value) { prefs.edit().putString(KEY_CONSOLE_THEME, value).commit() }

    /*********
     * FTP
     *********/
    /** Ports below 1024 need root, so the default is the conventional unprivileged one. */
    var ftpPort: Int
        get() = prefs.getInt(KEY_FTP_PORT, FtpServer.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_FTP_PORT, value).apply()

    /** Empty means anonymous, which is how homebrew FTP servers behave and what most people want on a LAN. */
    var ftpUser: String
        get() = prefs.getString(KEY_FTP_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FTP_USER, value).apply()

    /**
     * Stored in plain SharedPreferences, and that is not an oversight worth hiding: the protocol
     * sends it in clear text over the network anyway, so encrypting it at rest would protect it
     * from nobody. It is a doorlock for your own LAN, not a credential.
     */
    var ftpPassword: String
        get() = prefs.getString(KEY_FTP_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FTP_PASS, value).apply()

    /** Serve from "/" rather than shared storage. Needs All files access; see [Storage]. */
    var ftpWholeDevice: Boolean
        get() = prefs.getBoolean(KEY_FTP_WHOLE, true)
        set(value) = prefs.edit().putBoolean(KEY_FTP_WHOLE, value).apply()

    /** Set by the service, read by the UI, so the button says Stop when a server is already up. */
    var ftpRunning: Boolean
        get() = prefs.getBoolean(KEY_FTP_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_FTP_RUNNING, value).apply()

    /**
     * Start the FTP server as soon as the app opens.
     *
     * Off by default, and it should be: a server that starts itself is one somebody can leave
     * running on a café network without ever having pressed a button. Worth having for the people
     * who use this constantly on their own network, which is most of the point of the feature.
     */
    var ftpAutoStart: Boolean
        get() = prefs.getBoolean(KEY_FTP_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_FTP_AUTOSTART, value).apply()

    /** Console text size in sp, so a handheld panel and a monitor can each be readable. */
    var consoleTextSize: Int
        get() = prefs.getInt(KEY_CONSOLE_TEXT, 12).coerceIn(8, 22)
        set(value) = prefs.edit().putInt(KEY_CONSOLE_TEXT, value.coerceIn(8, 22)).apply()

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

    /**
     * The old single-sheet scratchpad.
     *
     * Notes are files now. This is read once, by [com.abacus.dualscreen.notes.NoteStore.migrateLegacy],
     * to rescue whatever an older build left in here — and then kept rather than cleared, because it
     * costs nothing and it is the only copy if that write ever fails.
     */
    var notes: String
        get() = prefs.getString(KEY_NOTES, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_NOTES, value).apply()

    /**
     * The page each game was last on, so returning to it lands where you left.
     *
     * Keyed by game id rather than kept as one value: somebody who reads the map in one game and
     * the inventory in another should get each back as they had it.
     */
    fun lastPageFor(gameId: String): String =
        prefs.getString("${KEY_LAST_PAGE}_$gameId", "").orEmpty()

    fun setLastPageFor(gameId: String, pageId: String) =
        prefs.edit().putString("${KEY_LAST_PAGE}_$gameId", pageId).apply()

    /**
     * Switch the app to whatever companion is actually answering.
     *
     * On by default because it is what people expect, and a switch because it is not what everybody
     * wants: somebody deliberately looking at one game's screen while another is running should not
     * be dragged away from it.
     */
    var autoSwitchGame: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SWITCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SWITCH, value).apply()

    /** How the notes list is arranged. Remembered, because it is a preference and not a mode. */
    var noteSort: String
        get() = prefs.getString(KEY_NOTE_SORT, "recent").orEmpty()
        set(value) = prefs.edit().putString(KEY_NOTE_SORT, value).apply()

    /** What other devices call you in the doodle rooms. Blank means "use the device model". */
    var scribbleName: String
        get() = prefs.getString(KEY_SCRIBBLE_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SCRIBBLE_NAME, value).apply()

    var scribbleRoom: String
        get() = prefs.getString(KEY_SCRIBBLE_ROOM, "A").orEmpty().ifBlank { "A" }
        set(value) = prefs.edit().putString(KEY_SCRIBBLE_ROOM, value).apply()

    /** Last ink colour, so the pen is where you left it rather than white every single time. */
    var scribbleInk: Int
        get() = prefs.getInt(KEY_SCRIBBLE_INK, android.graphics.Color.WHITE)
        set(value) = prefs.edit().putInt(KEY_SCRIBBLE_INK, value).apply()

    /** Which tools are pinned to the home screen, as an ordered list of ids. */
    var pinnedTools: List<String>
        get() = prefs.getString(KEY_TOOLS, null)?.split(',')?.filter { it.isNotBlank() }
            ?: Tool.entries.map { it.id }
        set(value) = prefs.edit().putString(KEY_TOOLS, value.joinToString(",")).apply()

    private companion object {
        const val PREFS = "dual_screen"

        const val KEY_LAST_GAME = "last_game"
        const val KEY_ADVANCED = "advanced"
        const val KEY_BOOT_ANIMATION = "boot_animation"
        const val KEY_KEEP_GAME_FOCUS = "keep_game_focus"
        const val KEY_AUTO_DETECT = "auto_detect"
        const val KEY_RECONNECT = "auto_reconnect"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_REMEMBER_DISPLAY = "remember_display"

        const val KEY_CONSOLE_THEME = "console_theme"
        const val KEY_THEME_BETA_RESET = "console_theme_beta_reset"

        const val KEY_FTP_PORT = "ftp_port"
        const val KEY_FTP_USER = "ftp_user"
        const val KEY_FTP_PASS = "ftp_pass"
        const val KEY_FTP_WHOLE = "ftp_whole_device"
        const val KEY_FTP_RUNNING = "ftp_running"
        const val KEY_FTP_AUTOSTART = "ftp_autostart"
        const val KEY_CONSOLE_TEXT = "console_text_size"
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

        const val KEY_AWAKE_MODE = "awake_mode"
        const val KEY_DISPLAY_CHOICE = "display_choice"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_SHOW_CONTROLS = "show_controls"

        const val KEY_NOTES = "notes"
        const val KEY_NOTE_SORT = "note_sort"
        const val KEY_LAST_PAGE = "last_page"
        const val KEY_AUTO_SWITCH = "auto_switch_game"
        const val KEY_TOOLS = "tools"

        const val KEY_SCRIBBLE_NAME = "scribble_name"
        const val KEY_SCRIBBLE_ROOM = "scribble_room"
        const val KEY_SCRIBBLE_INK = "scribble_ink"
    }
}
