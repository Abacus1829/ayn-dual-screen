package com.abacus.dualscreen.macro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named sequence of steps.
 *
 * Separate from the macro pad's buttons on purpose. A button is one thing in one place on one layout;
 * a script is a thing you *do*, which several buttons on several layouts may all want to run. Keeping
 * them apart is what lets a layout be shared without dragging every button's private copy of the same
 * five keystrokes along with it.
 */
data class MacroScript(
    val id: String,
    var name: String,
    val actions: MutableList<MacroAction> = mutableListOf(),
) {

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("actions", JSONArray().also { array -> actions.forEach { array.put(it.toJson()) } })

    /** A one-line summary for the list: how many steps, and what the first one is. */
    fun summary(): String = when {
        actions.isEmpty() -> "No steps yet"
        actions.size == 1 -> MacroAction.describe(actions[0])
        else -> "${actions.size} steps · ${MacroAction.describe(actions[0])}"
    }

    companion object {
        private var counter = 0

        fun newId(): String = "m" + System.currentTimeMillis().toString(36) + (counter++).toString(36)

        /**
         * Read a script, or null if there is nothing usable in it.
         *
         * A script with no name is given one; a script whose steps are all unreadable is still a
         * valid empty script and is kept, because losing the name as well helps nobody. Only a
         * completely unparseable object is rejected.
         */
        fun fromJson(json: JSONObject): MacroScript {
            val actions = mutableListOf<MacroAction>()
            val array = json.optJSONArray("actions") ?: JSONArray()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                runCatching { MacroAction.fromJson(item) }.getOrNull()?.let { actions += it }
            }

            return MacroScript(
                id = json.optString("id").ifBlank { newId() },
                name = json.optString("name").trim().ifBlank { "Macro" },
                actions = actions,
            )
        }
    }
}

/**
 * Where scripts live.
 *
 * Its own preference file rather than the macro pad's, so a layout can be wiped without taking every
 * macro on the device with it. Same JSON-array-in-a-string shape as everything else here.
 */
class MacroScriptStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var scripts: MutableList<MacroScript>
        get() {
            val raw = prefs.getString(KEY_SCRIPTS, null) ?: return mutableListOf()

            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it)?.let(MacroScript::fromJson) }
                    .toMutableList()
            }.getOrDefault(mutableListOf())
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(it.toJson()) }
            prefs.edit().putString(KEY_SCRIPTS, array.toString()).apply()
        }

    fun byId(id: String?): MacroScript? = id?.let { scripts.firstOrNull { s -> s.id == it } }

    fun save(script: MacroScript) {
        val all = scripts
        val at = all.indexOfFirst { it.id == script.id }
        if (at >= 0) all[at] = script else all += script
        scripts = all
    }

    fun delete(id: String) {
        scripts = scripts.filterNot { it.id == id }.toMutableList()
    }

    fun duplicate(id: String): MacroScript? {
        val original = byId(id) ?: return null

        val copy = MacroScript(
            id = MacroScript.newId(),
            name = uniqueName(original.name + " copy"),
            // Copied step by step: sharing the list would make editing the copy edit the original.
            actions = original.actions.map { it.copy() }.toMutableList(),
        )

        save(copy)
        return copy
    }

    fun uniqueName(wanted: String): String {
        val taken = scripts.map { it.name }.toSet()
        if (wanted !in taken) return wanted

        var n = 2
        while ("$wanted $n" in taken) n++
        return "$wanted $n"
    }

    private companion object {
        const val PREFS = "macros"
        const val KEY_SCRIPTS = "scripts"
    }
}
