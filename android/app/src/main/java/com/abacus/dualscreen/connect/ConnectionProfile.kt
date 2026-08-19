package com.abacus.dualscreen.connect

import org.json.JSONObject

/**
 * Which screen a session should open on.
 *
 * Stored as an intent rather than a display id, and that is the whole point: display ids are assigned
 * by the system and change when a dock is unplugged or the device reboots. A profile saying "the
 * second screen" still means the right thing tomorrow; a profile saying "display 2" does not.
 */
enum class DisplayChoice(val id: String) {
    /** Whatever [android.view.Display.DEFAULT_DISPLAY] is right now. */
    MAIN("main"),

    /** The first display that is not the main one — on the Thor, the lower panel. */
    SECOND("second"),

    /** A display the system marks as presentable, which is what an HDMI dock produces. */
    EXTERNAL("external"),

    /** Second screen when there is one, main when there is not. The sensible default. */
    AUTO("auto"),

    /** Put the choice up every time. For people who genuinely swap between them. */
    ASK("ask");

    companion object {
        fun byId(id: String?): DisplayChoice = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** How the session should sit. Automatic means "leave it to the system", not "rotate constantly". */
enum class Orientation(val id: String) {
    AUTOMATIC("auto"),
    LANDSCAPE("landscape"),
    PORTRAIT("portrait");

    companion object {
        fun byId(id: String?): Orientation = entries.firstOrNull { it.id == id } ?: AUTOMATIC
    }
}

/** When to hold the screen on. */
enum class Awake(val id: String) {
    /** For as long as the session screen is open, connected or not. The old behaviour. */
    ALWAYS("always"),

    /** Only while the connection is up, so a dead session lets the panel sleep. */
    CONNECTED("connected"),

    /** Never — let the device time out normally. */
    NEVER("never");

    companion object {
        fun byId(id: String?): Awake = entries.firstOrNull { it.id == id } ?: ALWAYS
    }
}

/**
 * A saved thing to connect to.
 *
 * Deliberately just a name, a host and a port plus how you like it opened. The app knows about
 * particular companion mods elsewhere — for identifying what answered, and for sensible default
 * ports — but a profile is not tied to any of them, and a profile pointing at something nobody has
 * written a mod for yet works exactly as well.
 *
 * [preset] is a hint, not a constraint: it carries the accent colour and the default port for a
 * known mod and is otherwise ignored. An unrecognised value falls back to the generic entry rather
 * than invalidating the profile.
 */
data class ConnectionProfile(
    val id: String,
    var name: String,
    var host: String,
    var port: Int,
    var preset: String = "custom",
    var autoConnect: Boolean = false,
    var display: DisplayChoice = DisplayChoice.AUTO,
    var orientation: Orientation = Orientation.AUTOMATIC,
    var awake: Awake = Awake.ALWAYS,
    /** When this was last opened, for ordering the list. 0 means never. */
    var lastUsed: Long = 0L,
) {

    /** What the WebView will actually be pointed at. */
    val url: String get() = "http://$host:$port"

    /** Host and port as one line, for the subtitle under the name. */
    val address: String get() = "$host:$port"

    /**
     * Enough to be worth trying.
     *
     * A blank host or a port outside the legal range cannot connect to anything, and a profile in
     * that state is better shown as broken than offered as a button that silently does nothing.
     */
    val usable: Boolean get() = host.isNotBlank() && port in 1..65535

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("preset", preset)
        .put("autoConnect", autoConnect)
        .put("display", display.id)
        .put("orientation", orientation.id)
        .put("awake", awake.id)
        .put("lastUsed", lastUsed)

    companion object {

        fun newId(): String = "p" + System.currentTimeMillis().toString(36) + (counter++).toString(36)

        private var counter = 0

        /**
         * Read a profile from JSON, or null when there is nothing usable in it.
         *
         * Everything except the host is allowed to be missing or wrong — an import from a hand-edited
         * file should lose the bad fields, not the whole entry. A missing host is the one thing that
         * cannot be defaulted into something meaningful, so that is the only rejection.
         */
        fun fromJson(json: JSONObject): ConnectionProfile? {
            val host = json.optString("host").trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')
                .ifBlank { return null }

            val port = json.optInt("port", 0).takeIf { it in 1..65535 } ?: 27301

            return ConnectionProfile(
                id = json.optString("id").ifBlank { newId() },
                name = json.optString("name").trim().ifBlank { host },
                host = host,
                port = port,
                preset = json.optString("preset").ifBlank { "custom" },
                autoConnect = json.optBoolean("autoConnect", false),
                display = DisplayChoice.byId(json.optString("display")),
                orientation = Orientation.byId(json.optString("orientation")),
                awake = Awake.byId(json.optString("awake")),
                lastUsed = json.optLong("lastUsed", 0L),
            )
        }
    }
}
