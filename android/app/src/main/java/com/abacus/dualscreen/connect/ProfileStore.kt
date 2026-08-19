package com.abacus.dualscreen.connect

import android.content.Context
import android.os.Environment
import com.abacus.dualscreen.Game
import com.abacus.dualscreen.Settings
import com.abacus.dualscreen.Storage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One line of connection history: somewhere this device actually opened. */
data class Recent(val host: String, val port: Int, val name: String, val at: Long) {

    val address: String get() = "$host:$port"

    fun toJson(): JSONObject = JSONObject()
        .put("host", host)
        .put("port", port)
        .put("name", name)
        .put("at", at)

    companion object {
        fun fromJson(json: JSONObject): Recent? {
            val host = json.optString("host").ifBlank { return null }
            val port = json.optInt("port", 0).takeIf { it in 1..65535 } ?: return null
            return Recent(host, port, json.optString("name").ifBlank { host }, json.optLong("at"))
        }
    }
}

/** What an import actually did, so the screen can say so instead of claiming success. */
data class ImportResult(val added: Int, val merged: Int, val skipped: Int) {
    val total: Int get() = added + merged
}

/**
 * Saved connections, their order, and where they came from.
 *
 * A JSON array in one preference, the same shape the macro pad already uses. No database: the whole
 * dataset is a handful of records read once when a screen opens and written when somebody taps Save,
 * and a Room dependency for that would cost startup time and a schema to migrate for no benefit.
 *
 * Profiles are the user's own list. [Game] still exists beside them, as defaults and as the thing
 * [com.abacus.dualscreen.Probe] matches when identifying what answered — a profile is never limited
 * to a game the app has heard of.
 */
class ProfileStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── the list ────────────────────────────────────────────────────────────

    var profiles: MutableList<ConnectionProfile>
        get() {
            val raw = prefs.getString(KEY_PROFILES, null) ?: return mutableListOf()

            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length())
                    .mapNotNull { ConnectionProfile.fromJson(array.optJSONObject(it) ?: return@mapNotNull null) }
                    .toMutableList()
            }.getOrDefault(mutableListOf())
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(it.toJson()) }
            prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
        }

    fun byId(id: String?): ConnectionProfile? = id?.let { profiles.firstOrNull { p -> p.id == it } }

    /** Add or replace, matched on id. */
    fun save(profile: ConnectionProfile) {
        val all = profiles
        val at = all.indexOfFirst { it.id == profile.id }
        if (at >= 0) all[at] = profile else all += profile
        profiles = all
    }

    fun delete(id: String) {
        profiles = profiles.filterNot { it.id == id }.toMutableList()
        if (defaultId == id) defaultId = null
    }

    fun duplicate(id: String): ConnectionProfile? {
        val original = byId(id) ?: return null
        val copy = original.copy(
            id = ConnectionProfile.newId(),
            name = uniqueName(original.name + " copy"),
            lastUsed = 0L,
        )
        save(copy)
        return copy
    }

    /** A name no other profile is using, so a list of five "Living room PC" cannot happen. */
    fun uniqueName(wanted: String): String {
        val taken = profiles.map { it.name }.toSet()
        if (wanted !in taken) return wanted

        var n = 2
        while ("$wanted $n" in taken) n++
        return "$wanted $n"
    }

    /**
     * The profile to reach for when nobody has picked one.
     *
     * A stored id rather than a flag on the profile, so exactly one can hold it and deleting it
     * cannot leave the flag orphaned on a record that no longer exists.
     */
    var defaultId: String?
        get() = prefs.getString(KEY_DEFAULT, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT, value).apply()

    /** Default first, then most recently used, then the rest by name. */
    fun ordered(): List<ConnectionProfile> {
        val default = defaultId
        return profiles.sortedWith(
            compareByDescending<ConnectionProfile> { it.id == default }
                .thenByDescending { it.lastUsed }
                .thenBy { it.name.lowercase() }
        )
    }

    /** The one to open on launch, if the user has asked for that. */
    fun autoConnectProfile(): ConnectionProfile? =
        ordered().firstOrNull { it.autoConnect && it.usable }

    // ── history ─────────────────────────────────────────────────────────────

    /**
     * Where this device has connected lately, newest first.
     *
     * Separate from the profile list on purpose: connecting to something found by a network scan
     * should be repeatable with one tap without silently filling the saved list with entries nobody
     * asked to keep.
     */
    var recents: List<Recent>
        get() {
            val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()

            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length())
                    .mapNotNull { Recent.fromJson(array.optJSONObject(it) ?: return@mapNotNull null) }
                    .sortedByDescending { it.at }
            }.getOrDefault(emptyList())
        }
        private set(value) {
            val array = JSONArray()
            value.forEach { array.put(it.toJson()) }
            prefs.edit().putString(KEY_RECENTS, array.toString()).apply()
        }

    /**
     * Note a connection.
     *
     * Deduplicated on host and port rather than appended, so opening the same PC twenty times leaves
     * one entry with a fresh timestamp instead of twenty identical rows — which is the failure mode
     * every "recent items" list has by default.
     */
    fun remember(host: String, port: Int, name: String) {
        if (host.isBlank() || port !in 1..65535) return

        val kept = recents.filterNot { it.host == host && it.port == port }
        recents = (listOf(Recent(host, port, name, System.currentTimeMillis())) + kept).take(HISTORY_LIMIT)
    }

    fun clearRecents() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    /** Mark a profile as just used, for both the ordering and the history line. */
    fun touch(profile: ConnectionProfile) {
        profile.lastUsed = System.currentTimeMillis()
        save(profile)
        remember(profile.host, profile.port, profile.name)
    }

    // ── import and export ───────────────────────────────────────────────────

    /**
     * Where an export lands.
     *
     * The same public folder the notes and themes use, for the same reason: this app runs an FTP
     * server, so a file written here is on the PC without an export dialog, a content URI or a
     * storage framework in the way. Falls back to app-private storage when All-files access has not
     * been granted, where it is still reachable through the share sheet.
     */
    fun exportFolder(): File {
        val shared = File(Environment.getExternalStorageDirectory(), "AynDualScreen")
        return if (Storage.hasWholeDeviceAccess() || shared.isDirectory) shared
        else File(context.filesDir, "export")
    }

    fun exportFile(): File = File(exportFolder(), EXPORT_NAME)

    /** Only what is actually stored — no invented fields, nothing derived. */
    fun exportJson(): String {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }

        return JSONObject()
            .put("format", FORMAT)
            .put("app", "AynDualScreen")
            .put("exported", System.currentTimeMillis())
            .put("profiles", array)
            .toString(2)
    }

    /** Write the export and return the file, or null if it could not be written. */
    fun export(): File? {
        val file = exportFile()
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(exportJson())
            file
        }.getOrNull()
    }

    /**
     * Read profiles back in.
     *
     * Anything malformed is skipped rather than fatal: a file somebody edited by hand, or one from a
     * later version of the app, should import whatever it can and say how much it dropped. A profile
     * with the same host and port as one already saved updates that one instead of appearing twice,
     * because importing your own export back should be a no-op rather than a doubling.
     */
    fun import(text: String): ImportResult {
        val entries = parse(text) ?: return ImportResult(0, 0, 0)

        val all = profiles
        var added = 0
        var merged = 0
        var skipped = 0

        for (item in entries) {
            val incoming = runCatching { ConnectionProfile.fromJson(item) }.getOrNull()
            if (incoming == null) {
                skipped++
                continue
            }

            val existing = all.indexOfFirst {
                it.id == incoming.id || (it.host == incoming.host && it.port == incoming.port)
            }

            if (existing >= 0) {
                // Keep the id already on this device so the default-profile pointer and any history
                // stay attached to the same record.
                all[existing] = incoming.copy(id = all[existing].id)
                merged++
            } else {
                all += incoming.copy(name = uniqueNameAmong(all, incoming.name))
                added++
            }
        }

        profiles = all
        return ImportResult(added, merged, skipped)
    }

    private fun uniqueNameAmong(all: List<ConnectionProfile>, wanted: String): String {
        val taken = all.map { it.name }.toSet()
        if (wanted !in taken) return wanted

        var n = 2
        while ("$wanted $n" in taken) n++
        return "$wanted $n"
    }

    /**
     * The profile array out of an export file, or out of a bare array.
     *
     * Both shapes are accepted because the second is what somebody writing one by hand produces, and
     * refusing it would be pedantry rather than validation.
     */
    private fun parse(text: String): List<JSONObject>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val array = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("profiles")
        }.getOrNull() ?: return null

        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    // ── coming from an older build ──────────────────────────────────────────

    /**
     * Turn the old per-game addresses into profiles, once.
     *
     * Before profiles existed the app kept one host and port per [Game] entry. Somebody updating has
     * their addresses in there and would otherwise open a profile list that is empty — which reads
     * as "the update lost my settings", whether or not anything was lost. Only entries with a host
     * actually set are worth carrying across.
     */
    fun migrateFromGames(settings: Settings) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()

        if (profiles.isNotEmpty()) return

        val made = mutableListOf<ConnectionProfile>()

        for (game in Game.entries) {
            val host = settings.hostFor(game).trim()
            if (host.isBlank()) continue

            made += ConnectionProfile(
                id = ConnectionProfile.newId(),
                name = context.getString(game.label),
                host = host,
                port = settings.portFor(game),
                preset = game.id,
                display = DisplayChoice.AUTO,
                awake = if (settings.keepAwake) Awake.ALWAYS else Awake.NEVER,
                lastUsed = if (game == settings.lastGame) System.currentTimeMillis() else 0L,
            )
        }

        if (made.isEmpty()) return

        profiles = made
        defaultId = made.firstOrNull { it.lastUsed > 0 }?.id ?: made.first().id
    }

    private companion object {
        const val PREFS = "profiles"
        const val KEY_PROFILES = "list"
        const val KEY_DEFAULT = "default"
        const val KEY_RECENTS = "recents"
        const val KEY_MIGRATED = "migrated_from_games"

        const val HISTORY_LIMIT = 12
        const val EXPORT_NAME = "profiles.json"

        /** Bumped only if the shape changes in a way an older build could not read. */
        const val FORMAT = 1
    }
}
