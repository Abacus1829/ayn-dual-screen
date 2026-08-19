package com.abacus.dualscreen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A button on the macro pad, and the sets they're saved in.
 *
 * Positions are stored as a fraction of the screen rather than in pixels, so a profile built in portrait
 * still lands somewhere sensible in landscape and on the second panel.
 */
data class Macro(
    val id: String,
    var label: String,
    var kind: Kind,
    var payload: String,
    /** 0..1 across the screen. */
    var x: Float,
    var y: Float,
    /** Button width in dp. Height follows. */
    var size: Int,

    /**
     * What other gestures on this button do, as trigger id to macro id.
     *
     * A map rather than a field per gesture, because the set of gestures is going to grow — a swipe,
     * a gamepad button. Each of those becomes one entry in [Trigger] and needs no change to this
     * class, to the stored JSON, or to anything that reads it.
     *
     * A plain tap is deliberately NOT in here: it stays [kind] and [payload], which is what every
     * layout ever saved already contains. Moving it would have meant migrating them all to gain
     * nothing.
     */
    val bindings: MutableMap<String, String> = mutableMapOf(),

    /**
     * Whether a tap alternates rather than firing once.
     *
     * The button holds its key down on the first press and releases it on the second, which is how a
     * sprint or a crouch is actually used. Only meaningful for [Kind.KEY]; ignored elsewhere.
     */
    var toggle: Boolean = false,
) {

    /**
     * A way of touching a button.
     *
     * Ids are stored, so they must not change. New entries are new capabilities and nothing else —
     * an older build reading a layout that uses one finds a binding it does not recognise and
     * ignores it, which is the whole reason this is a map.
     */
    enum class Trigger(val id: String) {
        LONG_PRESS("long"),
        DOUBLE_TAP("double");

        companion object {
            fun byId(id: String?) = entries.firstOrNull { it.id == id }
        }
    }

    enum class Kind(val id: String) {
        /** Type text into whatever has focus. Needs the Thor Keyboard to be the active keyboard. */
        TEXT("text"),

        /** Press a key: enter, escape, tab, the d-pad. Same requirement as [TEXT]. */
        KEY("key"),

        /** Open another app by package name. Works anywhere. */
        APP("app"),

        /** Open one of this app's own tools. */
        TOOL("tool"),

        /**
         * Run a saved macro, by its id.
         *
         * The button holds the id rather than the steps, so one macro edited once changes every
         * button on every layout that runs it -- and a layout can be shared as a small list of
         * references plus the macros it actually needs.
         */
        SCRIPT("script");

        companion object {
            fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: TEXT
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("kind", kind.id)
        .put("payload", payload)
        .put("x", x.toDouble())
        .put("y", y.toDouble())
        .put("size", size)
        // Written only when there is something to write, so an ordinary button's JSON is exactly
        // what it was before this existed.
        .apply {
            if (bindings.isNotEmpty()) {
                put("bindings", JSONObject().also { o -> bindings.forEach { (k, v) -> o.put(k, v) } })
            }
            if (toggle) put("toggle", true)
        }

    companion object {
        fun fromJson(json: JSONObject) = Macro(
            id = json.optString("id", System.nanoTime().toString()),
            label = json.optString("label", "?"),
            kind = Kind.byId(json.optString("kind")),
            payload = json.optString("payload", ""),
            x = json.optDouble("x", 0.5).toFloat(),
            y = json.optDouble("y", 0.5).toFloat(),
            size = json.optInt("size", 64),
            bindings = readBindings(json),
            toggle = json.optBoolean("toggle", false),
        )

        /** Absent, empty or malformed all mean the same thing here: no extra gestures. */
        private fun readBindings(json: JSONObject): MutableMap<String, String> {
            val out = mutableMapOf<String, String>()
            val obj = json.optJSONObject("bindings") ?: return out

            for (key in obj.keys()) {
                val value = obj.optString(key)
                if (value.isNotBlank()) out[key] = value
            }
            return out
        }

        /** The keys a macro can press, and what to call them. */
        val KEYS = listOf(
            "ENTER" to android.view.KeyEvent.KEYCODE_ENTER,
            "ESC" to android.view.KeyEvent.KEYCODE_ESCAPE,
            "TAB" to android.view.KeyEvent.KEYCODE_TAB,
            "BACK" to android.view.KeyEvent.KEYCODE_BACK,
            "UP" to android.view.KeyEvent.KEYCODE_DPAD_UP,
            "DOWN" to android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            "RIGHT" to android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            "SPACE" to android.view.KeyEvent.KEYCODE_SPACE,
            "DEL" to android.view.KeyEvent.KEYCODE_DEL
        )
    }
}

/**
 * A named set of buttons — a remote control profile.
 *
 * [gameId] is what makes one usable per game: blank means it is the general one, and a [Game] id
 * means the pad reaches for it while the app is connected to that game. Blank is the default, so
 * every layout saved before this existed stays exactly what it was.
 */
data class MacroProfile(
    val name: String,
    val macros: MutableList<Macro>,
    var gameId: String = "",
) {

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("gameId", gameId)
        .put("macros", JSONArray().also { array -> macros.forEach { array.put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): MacroProfile {
            val list = mutableListOf<Macro>()
            val array = json.optJSONArray("macros") ?: JSONArray()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { list += Macro.fromJson(it) }
            }
            return MacroProfile(
                name = json.optString("name", "Profile"),
                macros = list,
                gameId = json.optString("gameId", ""),
            )
        }
    }
}

/**
 * Where profiles live.
 *
 * JSON in SharedPreferences rather than a database: this is a handful of buttons, and keeping it to one
 * string means a profile can be copied out and pasted back without any export machinery.
 */
class MacroStore(context: Context) {

    private val prefs = context.getSharedPreferences("dual_screen", Context.MODE_PRIVATE)

    var profiles: MutableList<MacroProfile>
        get() {
            val raw = prefs.getString(KEY_PROFILES, null) ?: return mutableListOf(starter())
            return runCatching {
                val array = JSONArray(raw)
                val list = mutableListOf<MacroProfile>()
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { list += MacroProfile.fromJson(it) }
                }
                list.ifEmpty { mutableListOf(starter()) }
            }.getOrElse { mutableListOf(starter()) }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(it.toJson()) }
            prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
        }

    /**
     * The profile to use for a game, falling back to the one the user chose.
     *
     * A layout assigned to that game wins; otherwise the active one, which is the general profile
     * and is what every existing install has. Nothing switches behind the user's back — the pad only
     * consults this when it is asked to.
     */
    fun profileFor(gameId: String?): MacroProfile {
        val wanted = gameId?.takeIf { it.isNotBlank() } ?: return active
        return profiles.firstOrNull { it.gameId == wanted } ?: active
    }

    /** Index of the profile in use; clamped on read so a deleted profile can't leave it dangling. */
    var activeIndex: Int
        get() = prefs.getInt(KEY_ACTIVE, 0).coerceIn(0, (profiles.size - 1).coerceAtLeast(0))
        set(value) = prefs.edit().putInt(KEY_ACTIVE, value).apply()

    val active: MacroProfile get() = profiles.getOrElse(activeIndex) { starter() }

    fun save(profile: MacroProfile) {
        val all = profiles
        val index = activeIndex
        if (index in all.indices) all[index] = profile else all += profile
        profiles = all
    }

    /** Something on screen the first time, so the pad isn't an empty rectangle. */
    private fun starter() = starterProfile("Default")

    companion object {
        private const val KEY_PROFILES = "macro_profiles"
        private const val KEY_ACTIVE = "macro_active"

        fun id(): String = System.nanoTime().toString(36)

        /**
         * The layout a fresh install gets, and what Reset restores.
         *
         * Public so the layout editor can put it back without knowing what is in it.
         */
        fun starterProfile(name: String) = MacroProfile(
            name,
            mutableListOf(
                Macro(id(), "GG", Macro.Kind.TEXT, "Good game", 0.08f, 0.30f, 64),
                Macro(id(), "⏎", Macro.Kind.KEY, "ENTER", 0.08f, 0.50f, 64),
                Macro(id(), "◈", Macro.Kind.TOOL, Tool.APPEARANCE.id, 0.08f, 0.70f, 64)
            )
        )
    }
}
