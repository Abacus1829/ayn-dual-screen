package com.abacus.dualscreen.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Finds themes — the ones that ship with the app, and the ones people write themselves.
 *
 * User themes live in a folder anybody can reach with a file manager or over this app's own FTP
 * server, which is the point: make a theme on the PC, drag it across, pull down on the picker.
 *
 * ```
 * /sdcard/AynDualScreen/themes/
 *   README.txt          written on first run, explains the format
 *   example.json        a complete theme to copy
 *   my-theme.json
 *   my-theme/           icons named after tool ids — ftp.png, notes.png…
 * ```
 */
class ThemeStore(private val context: Context) {

    /** Where users put their themes. Public storage on purpose — it has to be reachable. */
    val folder: File
        get() = File(Environment.getExternalStorageDirectory(), "AynDualScreen/themes")

    /**
     * Every theme available right now: the built-ins first, then whatever is on disk.
     *
     * Re-read on each call rather than cached. Someone who has just copied a file across expects to
     * see it, and the cost is listing one small directory.
     */
    fun all(): List<ConsoleTheme> = ConsoleTheme.BUILT_IN + userThemes()

    fun byId(id: String?): ConsoleTheme =
        all().firstOrNull { it.id == id } ?: ConsoleTheme.DEFAULT

    private fun userThemes(): List<ConsoleTheme> {
        val files = folder.listFiles { file -> file.extension.equals("json", true) }
            ?: return emptyList()

        return files.sortedBy { it.name.lowercase() }.mapNotNull { file ->
            val id = "user:" + file.nameWithoutExtension
            val icons = File(folder, file.nameWithoutExtension).takeIf { it.isDirectory }?.path ?: ""

            runCatching { ConsoleTheme.parse(file.readText(), id, icons) }
                .onFailure { Log.w(TAG, "Could not read ${file.name}", it) }
                .getOrNull()
        }
    }

    /**
     * A custom icon for one tool, or null to fall back to the built-in glyph.
     *
     * Looked up by the tool's own id — `ftp.png`, `notes.png` — because those ids are already
     * stable and already written down in [com.abacus.dualscreen.Tool]. A theme that provides three
     * icons and no others gets three custom icons and seven glyphs, which is a perfectly reasonable
     * theme and not an error.
     */
    fun icon(theme: ConsoleTheme, toolId: String): Drawable? {
        if (theme.iconFolder.isEmpty()) return null

        val file = File(theme.iconFolder, "$toolId.png").takeIf { it.isFile }
            ?: File(theme.iconFolder, "$toolId.jpg").takeIf { it.isFile }
            ?: return null

        // Decoded at whatever size it is: an icon set is a handful of small PNGs, and scaling them
        // down eagerly would cost sharpness on a panel where these are the largest thing on screen.
        return runCatching {
            BitmapDrawable(context.resources, BitmapFactory.decodeFile(file.path))
        }.getOrNull()
    }

    /**
     * The theme's background image, ready to use, or null.
     *
     * Tiled or stretched as the theme asked. Tiling is what a repeating pattern wants and
     * stretching is what a photograph wants, and getting it wrong makes either look broken — so it
     * is the theme's call, not a guess from the image size.
     */
    fun background(theme: ConsoleTheme): Drawable? {
        if (theme.iconFolder.isEmpty() || theme.backgroundImage.isEmpty()) return null

        val file = File(theme.iconFolder, theme.backgroundImage).takeIf { it.isFile } ?: return null

        return runCatching {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: return null

            if (theme.backgroundTiled) {
                BitmapDrawable(context.resources, bitmap).apply {
                    setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
                }
            } else {
                BitmapDrawable(context.resources, bitmap).apply {
                    gravity = android.view.Gravity.FILL
                }
            }
        }.getOrNull()
    }

    /**
     * The theme's typeface: a file in its folder if there is one, otherwise a platform family name,
     * otherwise null to leave the app's own font alone.
     *
     * A broken font file returns null rather than throwing. A theme with a bad .ttf should look
     * ordinary, not take the home screen down.
     */
    fun typeface(theme: ConsoleTheme): android.graphics.Typeface? {
        if (theme.fontFile.isEmpty()) return null

        if (theme.iconFolder.isNotEmpty()) {
            val file = File(theme.iconFolder, theme.fontFile)
            if (file.isFile) {
                return runCatching { android.graphics.Typeface.createFromFile(file) }.getOrNull()
            }
        }

        // Not a file — treat it as a family name, which is how a theme asks for "monospace"
        // without shipping one.
        return runCatching {
            android.graphics.Typeface.create(theme.fontFile, android.graphics.Typeface.NORMAL)
        }.getOrNull()
    }

    /**
     * Lay down the folder, a README and a working example, once.
     *
     * Called when the theme picker is first opened rather than at startup: an app that creates
     * directories on somebody's storage before being asked is a rude app, and until someone looks
     * at the picker there is nothing for them to find there.
     */
    fun ensureFolder(): Boolean {
        if (!runCatching { folder.mkdirs(); folder.isDirectory }.getOrDefault(false)) return false

        runCatching {
            val readme = File(folder, "README.txt")
            if (!readme.exists()) readme.writeText(README)

            val example = File(folder, "example.json")
            if (!example.exists()) example.writeText(ConsoleTheme.sampleJson())
        }

        return true
    }

    companion object {
        private const val TAG = "AynTheme"

        private val README = """
            Ayn Dual Screen — themes
            ========================

            Drop a .json file in this folder and it appears in the theme picker.

            Copy example.json, rename it, and change the numbers. Every field is optional: whatever
            you leave out keeps the app's default, so a file with just a name and a background is a
            valid theme.

            Colours are written like #RRGGBB or #AARRGGBB.

            ICONS
            -----
            Make a folder next to your .json with the same name:

                my-theme.json
                my-theme/

            and put PNGs in it named after the tools:

                screen.png   notes.png    volume.png   brightness.png
                appearance.png  keyboard.png  mirror.png  macros.png
                ftp.png      stream.png

            Any tool without an icon file keeps its built-in glyph. You do not have to provide all
            of them.

            The easiest way to get files here is this app's own FTP server: start it, connect from
            your PC, and drop them straight in.
        """.trimIndent()
    }
}
