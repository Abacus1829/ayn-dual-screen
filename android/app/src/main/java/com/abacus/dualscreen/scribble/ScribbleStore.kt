package com.abacus.dualscreen.scribble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.abacus.dualscreen.Storage
import org.json.JSONObject
import java.io.File

/**
 * One thing somebody said, typed or drawn or both.
 *
 * [image] is a filename beside the log rather than the bytes, so reading the room back does not mean
 * decoding every picture in it.
 */
data class Scribble(
    val id: String,
    val at: Long,
    val who: String,
    val text: String,
    val image: String,
    val mine: Boolean,
)

/**
 * The rooms, on disk.
 *
 * ```
 * /sdcard/AynDualScreen/scribble/
 *   A/  log.jsonl        one message per line
 *       1723993..._.png  the doodle from that message
 *   B/  …
 * ```
 *
 * A line-per-message log rather than one JSON document, because appending a line is one open in
 * append mode and cannot corrupt what is already there — a room being written to while the process
 * dies loses the last line, not the conversation. Doodles sit beside it as ordinary PNGs, which is
 * what makes a room something you can browse over FTP and keep.
 */
class ScribbleStore(private val context: Context) {

    val root: File
        get() {
            val shared = File(Environment.getExternalStorageDirectory(), "AynDualScreen/scribble")
            return if (Storage.hasWholeDeviceAccess() || shared.isDirectory) shared
            else File(context.filesDir, "scribble")
        }

    fun room(room: String): File = File(root, room).apply { mkdirs() }

    private fun log(room: String): File = File(room(room), "log.jsonl")

    /**
     * Everything said in a room, oldest first.
     *
     * A line that will not parse is skipped rather than thrown: a truncated last line from a killed
     * process should cost that one message, not the whole room.
     */
    fun all(room: String): List<Scribble> {
        val file = log(room)
        if (!file.isFile) return emptyList()

        return runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                Scribble(
                    id = json.optString("id"),
                    at = json.optLong("at"),
                    who = json.optString("who"),
                    text = json.optString("text"),
                    image = json.optString("img"),
                    mine = json.optBoolean("mine"),
                )
            }.getOrNull()
        }
    }

    /**
     * Add a message, writing the doodle beside the log.
     *
     * The id is the timestamp plus a counter rather than the timestamp alone: two messages arriving
     * inside the same millisecond is unlikely and not impossible, and the loser would silently
     * overwrite the other's picture.
     */
    fun append(room: String, who: String, text: String, image: Bitmap?, mine: Boolean): Scribble {
        val id = "${System.currentTimeMillis()}-${counter++}"
        val name = if (image == null) "" else "$id.png"

        if (image != null) {
            runCatching {
                File(room(room), name).outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }

        val message = Scribble(id, System.currentTimeMillis(), who, text, name, mine)

        runCatching {
            log(room).appendText(
                JSONObject()
                    .put("id", message.id)
                    .put("at", message.at)
                    .put("who", message.who)
                    .put("text", message.text)
                    .put("img", message.image)
                    .put("mine", message.mine)
                    .toString() + "\n"
            )
        }

        trim(room)
        return message
    }

    /** The doodle for a message, or null when it was text only or the file has gone. */
    fun image(room: String, message: Scribble): Bitmap? {
        if (message.image.isEmpty()) return null
        val file = File(room(room), message.image)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    fun clear(room: String) {
        val folder = room(room)
        runCatching { folder.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Keep a room to a sensible length.
     *
     * Without this a room grows for as long as the app is installed, and the pictures — not the
     * text — are what fills a card. Rewriting the log to drop the oldest lines is cheap at this size
     * and means the orphaned PNGs can be deleted in the same pass, which a append-only scheme
     * would leave behind forever.
     */
    private fun trim(room: String) {
        val file = log(room)
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.size <= LIMIT) return

        val keep = lines.takeLast(LIMIT)
        val kept = keep.mapNotNull { runCatching { JSONObject(it).optString("img") }.getOrNull() }
            .filter { it.isNotEmpty() }
            .toSet()

        runCatching { file.writeText(keep.joinToString("\n", postfix = "\n")) }

        runCatching {
            room(room).listFiles { f -> f.extension.equals("png", true) }
                ?.filter { it.name !in kept }
                ?.forEach { it.delete() }
        }
    }

    companion object {
        /** The rooms that always exist, so there is somewhere to go without making one first. */
        val ROOMS = listOf("A", "B", "C", "D")

        /** Messages kept per room before the oldest start dropping off. */
        private const val LIMIT = 200

        private var counter = 0
    }
}
