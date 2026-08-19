package com.abacus.dualscreen.codes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What kind of control a code needs. Decides which row the UI draws for it. */
enum class InputType { NONE, TOGGLE, NUMBER, TEXT, ITEM, ENTITY, PRESET }

/** Roughly what a code touches. Only used to group the list. */
enum class Category(val label: String) {
    PLAYER("Player"),
    INVENTORY("Inventory"),
    WORLD("World"),
    OTHER("Other");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.name.equals(id, true) } ?: OTHER
    }
}

/**
 * One thing a companion says it can do.
 *
 * Descriptive, not executable: the app holds no game logic. A code names a command the companion
 * has registered and describes the control needed to send it, and the companion decides whether to
 * honour it. That is what keeps the app game-agnostic and keeps authority where it belongs.
 *
 * Codes are **advertised by the companion**, never assumed. A mod with codes switched off reports
 * none, and the app then has nothing to draw — which is the difference between a feature that is
 * off and a screen full of buttons that silently fail.
 */
data class GameCode(
    val id: String,
    val name: String,
    val description: String = "",
    val category: Category = Category.OTHER,
    /** A short glyph for the row. Text, so no drawable is needed per code. */
    val icon: String = "",
    val input: InputType = InputType.NONE,
    /** The command the companion registered. Sent as the action type. */
    val command: String = "",
    val min: Int = 0,
    val max: Int = 0,
    /** Preset choices for [InputType.PRESET], and the item list for ITEM/ENTITY. */
    val choices: List<String> = emptyList(),
    /** Current state for a toggle, as last reported. */
    val on: Boolean = false,
    /** Ask before running. For anything destructive or hard to undo. */
    val confirm: Boolean = false,
    /** A classic typed code that also triggers this, e.g. "HEALME". */
    val secret: String = "",
    /** Why it is unavailable right now, or blank when it is available. */
    val blocked: String = "",
) {
    val available: Boolean get() = blocked.isBlank()

    companion object {
        /**
         * Read one code from a companion's catalogue.
         *
         * Everything except the id has a default, because a companion is entitled to describe a code
         * with two fields and let the app draw something sensible.
         */
        fun fromJson(json: JSONObject): GameCode? {
            val id = json.optString("id").ifBlank { return null }

            return GameCode(
                id = id,
                name = json.optString("name").ifBlank { id },
                description = json.optString("description"),
                category = Category.byId(json.optString("category")),
                icon = json.optString("icon"),
                input = runCatching {
                    InputType.valueOf(json.optString("input", "NONE").uppercase())
                }.getOrDefault(InputType.NONE),
                command = json.optString("command").ifBlank { id },
                min = json.optInt("min", 0),
                max = json.optInt("max", 0),
                choices = json.optJSONArray("choices")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty(),
                on = json.optBoolean("on", false),
                confirm = json.optBoolean("confirm", false),
                secret = json.optString("secret"),
                blocked = json.optString("blocked"),
            )
        }

        /** The whole catalogue a companion served. Malformed entries are skipped, never fatal. */
        fun listFrom(json: JSONObject): List<GameCode> {
            val array = json.optJSONArray("codes") ?: JSONArray()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
        }
    }
}

/**
 * Whether game codes exist at all, and for which games.
 *
 * Three states matter and they are not the same thing:
 *
 * - **Off** — the feature does not exist. No hidden listener, no tile, no requests, nothing asked of
 *   any companion. A build in this state behaves exactly as one without the feature.
 * - **On but locked** — the machinery is there and the hidden sequence can find it. Still no tile.
 * - **On and unlocked** — the tile appears and stays.
 *
 * The off state is deliberately total rather than cosmetic: somebody who turns this off should be
 * able to keep their mods entirely local, and hiding a button while still asking companions about
 * codes would not have given them that.
 */
class CodeSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The master switch. On by default — the feature is invisible until unlocked anyway. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Whether the hidden sequence has been entered.
     *
     * Persisted, so it is found once and stays found. Cleared only by turning the feature off and on
     * again, which is the one place somebody would look to hide it.
     */
    var unlocked: Boolean
        get() = prefs.getBoolean(KEY_UNLOCKED, false)
        set(value) { prefs.edit().putBoolean(KEY_UNLOCKED, value).commit() }

    /** Everything must be true for a tile to appear or a request to go out. */
    val visible: Boolean get() = enabled && unlocked

    /** Per game, so one can be off while the rest are on. On unless turned off. */
    fun enabledFor(gameId: String): Boolean =
        enabled && prefs.getBoolean("$KEY_GAME$gameId", true)

    fun setEnabledFor(gameId: String, on: Boolean) =
        prefs.edit().putBoolean("$KEY_GAME$gameId", on).apply()

    /** Forget the unlock, for somebody who wants it hidden again. */
    fun relock() { prefs.edit().putBoolean(KEY_UNLOCKED, false).commit() }

    private companion object {
        const val PREFS = "gamecodes"
        const val KEY_ENABLED = "enabled"
        const val KEY_UNLOCKED = "unlocked"
        const val KEY_GAME = "game_"
    }
}
