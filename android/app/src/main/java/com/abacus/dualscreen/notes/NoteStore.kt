package com.abacus.dualscreen.notes

import android.content.Context
import android.os.Environment
import com.abacus.dualscreen.Storage
import java.io.File

/**
 * One note.
 *
 * The file IS the note and the filename IS the title — no header line, no index, no database. Rename
 * the note and the file is renamed with it, so a folder listing over FTP reads exactly like the list
 * in the app.
 */
data class Note(
    val file: File,
    val title: String,
    val preview: String,
    val modified: Long,
    val characters: Int,
    val pinned: Boolean,
)

/** How the list is arranged. Pinned notes come first whichever of these is chosen. */
enum class NoteSort(val id: String) {
    RECENT("recent"),
    NAME("name"),
    LONGEST("longest");

    companion object {
        fun byId(id: String?): NoteSort = entries.firstOrNull { it.id == id } ?: RECENT
    }
}

/**
 * Notes, kept as plain text files somewhere you can actually get at them.
 *
 * ```
 * /sdcard/AynDualScreen/notes/
 *   Boss order.txt
 *   Seed numbers.txt
 * ```
 *
 * Plain `.txt` in a public folder rather than a database, for one reason: this app already runs an
 * FTP server, so notes written on the handheld are on the PC thirty seconds later without an export
 * step, and a note written on the PC appears in the list next time you open it. A private database
 * would have been less code and would have made the notes unreachable, which for a scratchpad you
 * fill with seed numbers and boss orders is the whole value gone.
 *
 * Without All-files access the folder falls back to app-private storage. Everything still works; the
 * notes are simply only reachable from inside the app, and the screen says so rather than leaving
 * somebody hunting for a folder that is not there.
 */
class NoteStore(private val context: Context) {

    /** Where the notes live. Public when we are allowed, private when we are not. */
    val folder: File
        get() {
            val shared = File(Environment.getExternalStorageDirectory(), "AynDualScreen/notes")
            return if (Storage.hasWholeDeviceAccess() || shared.isDirectory) shared
            else File(context.filesDir, "notes")
        }

    /** True when the folder is one a PC can reach over FTP, which changes what the screen offers. */
    fun folderIsShared(): Boolean =
        folder.path.startsWith(Environment.getExternalStorageDirectory().path)

    fun ensureFolder(): File = folder.apply { mkdirs() }

    /**
     * Every note, filtered and ordered.
     *
     * Read from disk on each call rather than cached: a file dragged across over FTP has to show up,
     * and listing a directory of text files is not a cost worth a cache and its staleness bugs.
     */
    fun all(query: String = "", sort: NoteSort = NoteSort.RECENT): List<Note> {
        val files = folder.listFiles { file -> file.isFile && file.extension.equals("txt", true) }
            ?: return emptyList()

        val pins = pinned()

        val notes = files.mapNotNull { file ->
            // The body is read for the preview and for the search, so it is read once here rather
            // than twice. These are notes; if one is big enough for that to hurt, its preview was
            // never going to be useful anyway.
            val body = runCatching { file.readText() }.getOrDefault("")

            Note(
                file = file,
                title = file.nameWithoutExtension,
                preview = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                modified = file.lastModified(),
                characters = body.length,
                pinned = file.name in pins,
            ).takeIf { matches(it, body, query) }
        }

        val ordered = when (sort) {
            NoteSort.RECENT -> notes.sortedByDescending { it.modified }
            NoteSort.NAME -> notes.sortedBy { it.title.lowercase() }
            NoteSort.LONGEST -> notes.sortedByDescending { it.characters }
        }

        // Pinned first, keeping the chosen order inside each group. sortedByDescending is stable, so
        // that is one more pass rather than a special case in each branch above.
        return ordered.sortedByDescending { it.pinned }
    }

    private fun matches(note: Note, body: String, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim()
        return note.title.contains(needle, true) || body.contains(needle, true)
    }

    // ── reading and writing ─────────────────────────────────────────────────

    fun read(file: File): String = runCatching { file.readText() }.getOrDefault("")

    fun save(file: File, text: String) {
        ensureFolder()
        runCatching { file.writeText(text) }
    }

    /**
     * A new, empty note.
     *
     * Given a name rather than left untitled, because the name is the filename: a folder of
     * `Untitled.txt`, `Untitled (2).txt` is markedly worse over FTP than one of dates.
     */
    fun create(title: String): File {
        ensureFolder()
        val file = unique(title.ifBlank { "Note" })
        runCatching { file.writeText("") }
        return file
    }

    /**
     * Rename, which here means renaming the file.
     *
     * Returns the new file, or the old one when the rename could not happen — the caller has to keep
     * writing somewhere, and dropping edits on the floor because a rename failed is the worst
     * outcome available.
     */
    fun rename(file: File, title: String): File {
        val wanted = title.trim().ifBlank { return file }
        if (wanted == file.nameWithoutExtension) return file

        val target = unique(wanted)
        val wasPinned = file.name in pinned()

        return if (runCatching { file.renameTo(target) }.getOrDefault(false)) {
            if (wasPinned) {
                pin(file, false)
                pin(target, true)
            }
            target
        } else {
            file
        }
    }

    fun delete(file: File) {
        pin(file, false)
        runCatching { file.delete() }
    }

    fun duplicate(file: File): File {
        val copy = unique(file.nameWithoutExtension + " copy")
        runCatching { file.copyTo(copy, overwrite = false) }
        return copy
    }

    /** A file that does not exist yet, from a title that may not be usable as one. */
    private fun unique(title: String): File {
        val base = safeName(title)
        var candidate = File(folder, "$base.txt")
        var n = 2

        while (candidate.exists()) {
            candidate = File(folder, "$base ($n).txt")
            n++
        }

        return candidate
    }

    /**
     * A title turned into something every filesystem will accept.
     *
     * The reserved set is FAT's rather than Linux's, because a FAT-formatted SD card is the
     * strictest place these files land. A trailing dot or space goes too — legal here, silently
     * mangled by Windows at the other end of an FTP transfer.
     */
    private fun safeName(title: String): String {
        val cleaned = title.trim().map { c ->
            if (c in FORBIDDEN || c.code < 0x20) '-' else c
        }.joinToString("")

        return cleaned.trim(' ', '.').take(64).ifBlank { "Note" }
    }

    // ── pinning ─────────────────────────────────────────────────────────────

    /**
     * Pins live in preferences, not in the file.
     *
     * A pin is a decision about this device's list rather than a property of the text, and writing
     * it into the note would put it in front of anybody who opened that note on a PC.
     */
    private fun pinned(): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PINNED, emptySet())
            .orEmpty()

    fun pin(file: File, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = pinned().toMutableSet()

        if (on) next += file.name else next -= file.name
        prefs.edit().putStringSet(KEY_PINNED, next).apply()
    }

    fun isPinned(file: File): Boolean = file.name in pinned()

    // ── the old single note ─────────────────────────────────────────────────

    /**
     * Move the one-blob scratchpad into a file, once.
     *
     * Older builds kept notes as a single preference string. Anybody updating has exactly one note
     * in there and would otherwise open this screen to find it empty, which looks like data loss
     * whether or not it is. The preference itself is left alone rather than cleared: it costs
     * nothing to keep and it is the only copy if the write below fails.
     */
    fun migrateLegacy(legacy: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        if (legacy.isBlank()) return

        ensureFolder()
        runCatching { unique("Scratchpad").writeText(legacy) }
    }

    private companion object {
        const val PREFS = "notes"
        const val KEY_PINNED = "pinned"
        const val KEY_MIGRATED = "migrated_from_prefs"

        /** Illegal in a FAT filename, which is the strictest place these end up. */
        val FORBIDDEN = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    }
}
