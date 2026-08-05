package com.abacus.aynsecondscreen

import android.content.Context

/** Remembers the last address used, so the screen can be reopened without retyping it. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    companion object {
        /** Matches the mod's default in config.json. */
        const val DEFAULT_PORT = 27301

        private const val PREFS = "second_screen"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
    }
}
