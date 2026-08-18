package com.abacus.dualscreen.theme

import android.graphics.Color
import org.json.JSONObject

/**
 * A console skin: what the home screen looks like when it is pretending to be a handheld.
 *
 * This is a layer above [com.abacus.dualscreen.Appearance], which decides accent, font and corner
 * radius. A skin decides the *shape* of the thing — a 3DS home menu is not the stock grid with
 * different colours, it is white tiles with coloured glyphs on a pale grid, a status bar with
 * signal and battery, and a folder row above it.
 *
 * ## Why it is data rather than code
 *
 * Because the second, third and fourth skins are the same screen with different numbers. Writing
 * the 3DS one as a layout would mean writing a PSP one as another layout, and a Vita one as a
 * third. Written as data, each new console is a few dozen values — and the same fact makes user
 * themes possible at all: if a built-in skin is just a [ConsoleTheme], a user's file can be one too.
 *
 * ## The format
 *
 * A theme is a JSON file plus an optional folder of icons beside it:
 *
 * ```
 * /sdcard/AynDualScreen/themes/
 *   my-theme.json
 *   my-theme/            ← icons, named after the tool ids: screen.png, notes.png, ftp.png…
 * ```
 *
 * Every field is optional. Anything missing falls back to the value in [DEFAULT], so a three-line
 * file is a valid theme and nobody has to write out forty colours to change one.
 */
data class ConsoleTheme(
    val id: String,
    val name: String,

    /** What is written under the name in the picker. Blank for user themes unless they say. */
    val subtitle: String = "",

    // ── the surface ─────────────────────────────────────────────────────────

    /** Behind everything. */
    val background: Int,

    /**
     * The far end of a vertical gradient, or 0 for a flat fill.
     *
     * Added because two of the shipped skins genuinely need it: a Vita home screen is a blue wash
     * from deep at the top to pale at the bottom, and rendering it flat loses most of what makes it
     * recognisable. Themes that want a plain colour simply leave it out.
     */
    val backgroundEnd: Int = 0,

    /** The faint grid of empty tile slots a 3DS draws behind its apps. 0 to omit it. */
    val slotColor: Int = 0,

    // ── the status bar ──────────────────────────────────────────────────────

    /** 0 hides the bar entirely — a DS has no such thing. */
    val statusBackground: Int = 0,
    val statusText: Int = Color.BLACK,

    /** The blue pill a 3DS puts around the connection name. 0 for none. */
    val statusPill: Int = 0,

    /** Show signal bars, the date, and a battery. Each console shows a different subset. */
    val showSignal: Boolean = true,
    val showDate: Boolean = true,
    val showBattery: Boolean = true,

    // ── the tiles ───────────────────────────────────────────────────────────

    /** The face of an app tile. */
    val tileFace: Int,
    val tileBorder: Int = 0,
    val tileCorner: Int = 8,

    /** Glyph and label colours. A 3DS puts dark glyphs on white; a PSP does the opposite. */
    val tileGlyph: Int,
    val tileLabel: Int,

    /** Highlight around the selected tile — the 3DS's green corner brackets. */
    val tileSelected: Int,

    /** Tiles across the grid. A 3DS shows four, a Vita shows three big ones. */
    val columns: Int = 4,

    /** Tile edge as a fraction of the column width, so tiles can be square-ish or roomy. */
    val tileScale: Float = 0.78f,

    /** Draw the label under the tile at all. A Vita puts names under bubbles; a PSP does not. */
    val showLabels: Boolean = true,

    // ── the bottom bar ──────────────────────────────────────────────────────

    /** The strip of folder icons a 3DS keeps along the top of its bottom screen. 0 for none. */
    val trayBackground: Int = 0,
    val trayIcon: Int = Color.DKGRAY,

    /** Where the user's icons live, once loaded. Never written by a theme file. */
    val iconFolder: String = "",

    /** True for the four shipped skins; false for anything read off the filesystem. */
    val builtIn: Boolean = true,
) {

    companion object {

        /**
         * The stock look, and the fallback for every unset field in a user's file.
         *
         * Deliberately the app's own dark style rather than a console: someone who has not chosen a
         * skin should see the app, not a costume.
         */
        val DEFAULT = ConsoleTheme(
            id = "default",
            name = "Ayn Dual Screen",
            subtitle = "The app's own look",
            background = 0xFF07080F.toInt(),
            statusBackground = 0,
            tileFace = 0xFF141821.toInt(),
            tileBorder = 0xFF232A38.toInt(),
            tileCorner = 14,
            tileGlyph = 0xFF6EC1FF.toInt(),
            tileLabel = 0xFFE6ECF5.toInt(),
            tileSelected = 0xFF6EC1FF.toInt(),
            columns = 4,
        )

        /**
         * Nintendo 3DS home menu.
         *
         * The details that make it read as a 3DS rather than "a white theme": the pale grid of
         * empty slots showing through behind the apps, white tiles with a hairline border and a
         * soft corner, dark glyphs, and the green corner-bracket selection. The status bar is its
         * own thing — signal bars on the left, a blue pill with the connection, the date, and a
         * battery on the right.
         */
        val THREE_DS = ConsoleTheme(
            id = "3ds",
            name = "Nintendo 3DS",
            subtitle = "Home Menu",
            background = 0xFFF2F2F2.toInt(),
            slotColor = 0xFFE4E4E4.toInt(),
            statusBackground = 0xFFF7F7F7.toInt(),
            statusText = 0xFF3C3C3C.toInt(),
            statusPill = 0xFF3B8ED6.toInt(),
            showSignal = true,
            showDate = true,
            showBattery = true,
            tileFace = 0xFFFFFFFF.toInt(),
            tileBorder = 0xFFD8D8D8.toInt(),
            tileCorner = 10,
            tileGlyph = 0xFF4A4A4A.toInt(),
            tileLabel = 0xFF3C3C3C.toInt(),
            tileSelected = 0xFF6FE3A0.toInt(),
            columns = 4,
            tileScale = 0.74f,
            showLabels = true,
            trayBackground = 0xFFE9E9E9.toInt(),
            trayIcon = 0xFF6A6A6A.toInt(),
        )

        /**
         * Sony PSP — the XMB.
         *
         * The XMB is a cross-media bar, not a grid, and pretending otherwise would be a lie. What
         * carries across is its *look*: a deep blue-black wash, icons with no tile behind them at
         * all, and thin white labels. So the tile face is transparent, the border is off, and the
         * columns drop to five because XMB icons are small and close together.
         */
        val PSP = ConsoleTheme(
            id = "psp",
            name = "Sony PSP",
            subtitle = "XMB",
            background = 0xFF0A1428.toInt(),
            slotColor = 0,
            statusBackground = 0xFF091223.toInt(),
            statusText = 0xFFD8E4F5.toInt(),
            statusPill = 0,
            showSignal = true,
            showDate = true,
            showBattery = true,
            tileFace = Color.TRANSPARENT,
            tileBorder = 0,
            tileCorner = 0,
            tileGlyph = 0xFFEAF2FF.toInt(),
            tileLabel = 0xFFC3D2E8.toInt(),
            tileSelected = 0xFF8FC6FF.toInt(),
            columns = 5,
            tileScale = 0.62f,
            showLabels = true,
        )

        /**
         * PlayStation Vita — LiveArea bubbles.
         *
         * Round icons on a pale blue field, three across, and no labels: the Vita puts the name
         * inside the bubble art rather than under it, so labels off is the accurate choice and the
         * tiles get bigger to compensate.
         */
        val VITA = ConsoleTheme(
            id = "vita",
            name = "PS Vita",
            subtitle = "LiveArea",

            // The home screen is a blue wash, deep at the top and pale toward the bottom. Flat
            // blue reads as "a blue theme"; the gradient is what reads as a Vita.
            background = 0xFF2E7CBF.toInt(),
            backgroundEnd = 0xFF8FC7E8.toInt(),
            slotColor = 0,

            // A dark translucent strip with the clock on the right — not a light bar.
            statusBackground = 0xFF13293D.toInt(),
            statusText = 0xFFFFFFFF.toInt(),
            statusPill = 0,
            showSignal = true,
            showDate = false,        // the Vita bar shows the time, not the date
            showBattery = true,

            // Round bubbles. A very large corner radius on a square is a circle.
            tileFace = 0xFFFFFFFF.toInt(),
            tileBorder = 0,
            tileCorner = 64,
            tileGlyph = 0xFF2E6FA8.toInt(),

            // Labels sit UNDER the bubbles in white — the first pass had these off, which was
            // simply wrong about the hardware.
            tileLabel = 0xFFFFFFFF.toInt(),
            tileSelected = 0xFFFFFFFF.toInt(),
            columns = 4,
            tileScale = 0.68f,
            showLabels = true,
        )

        /**
         * Nintendo DS Lite — the original two-screen menu.
         *
         * Silver-white with a soft blue wash, chunky rounded tiles and a thin blue selection. No
         * status bar: the DS menu has none, so [statusBackground] is 0 and the strip is skipped
         * entirely rather than drawn empty.
         */
        val DS_LITE = ConsoleTheme(
            id = "dslite",
            name = "Nintendo DS Lite",
            subtitle = "DS Menu",
            background = 0xFFEDF1F5.toInt(),
            slotColor = 0xFFDDE5EC.toInt(),
            statusBackground = 0,
            statusText = 0xFF32404E.toInt(),
            statusPill = 0,
            showSignal = false,
            showDate = false,
            showBattery = false,
            tileFace = 0xFFFBFDFF.toInt(),
            tileBorder = 0xFFC2CEDA.toInt(),
            tileCorner = 12,
            tileGlyph = 0xFF41586E.toInt(),
            tileLabel = 0xFF32404E.toInt(),
            tileSelected = 0xFF4F9BD8.toInt(),
            columns = 3,
            tileScale = 0.80f,
            showLabels = true,
        )

        /**
         * Nintendo Wii U — the GamePad home menu.
         *
         * Close cousin of the 3DS and worth keeping distinct: the field is lighter and faintly warm
         * rather than flat grey, the tiles are larger and softer with barely any border, there are
         * five across instead of four, and the empty slots behind them are more visible. The blue
         * selection is the Wii U's, not the 3DS's green.
         */
        val WII_U = ConsoleTheme(
            id = "wiiu",
            name = "Nintendo Wii U",
            subtitle = "GamePad menu",
            background = 0xFFF4F4F4.toInt(),
            backgroundEnd = 0xFFFFFFFF.toInt(),
            slotColor = 0xFFE8E8E8.toInt(),
            statusBackground = 0xFFFAFAFA.toInt(),
            statusText = 0xFF444444.toInt(),
            statusPill = 0,
            showSignal = false,
            showDate = true,
            showBattery = true,
            tileFace = 0xFFFFFFFF.toInt(),
            tileBorder = 0xFFE0E0E0.toInt(),
            tileCorner = 14,
            tileGlyph = 0xFF4A4A4A.toInt(),
            tileLabel = 0xFF3C3C3C.toInt(),
            tileSelected = 0xFF29B6F6.toInt(),
            columns = 5,
            tileScale = 0.80f,
            showLabels = true,
            trayBackground = 0xFFFFFFFF.toInt(),
            trayIcon = 0xFF2196F3.toInt(),
        )

        /**
         * Nintendo Wii — the channel grid.
         *
         * The Wii Menu rather than the black System Settings screens, because the menu is the part
         * that is a home screen. Near-white field, channel tiles in the palest grey with a
         * blue-grey hairline, four across, and the empty channels showing faintly behind them.
         *
         * Distinct from the Wii U despite the family resemblance: the Wii's tiles are wider and
         * flatter, the palette is cooler, and its selection blue is lighter.
         */
        val WII = ConsoleTheme(
            id = "wii",
            name = "Nintendo Wii",
            subtitle = "Channel menu",
            background = 0xFFFFFFFF.toInt(),
            backgroundEnd = 0xFFEFF3F6.toInt(),
            slotColor = 0xFFE9EDEF.toInt(),
            statusBackground = 0xFFF7F9FA.toInt(),
            statusText = 0xFF46545F.toInt(),
            statusPill = 0,
            showSignal = false,
            showDate = true,
            showBattery = false,        // a Wii is mains-powered; a battery readout would be a lie
            tileFace = 0xFFF2F5F7.toInt(),
            tileBorder = 0xFFC9D3D9.toInt(),
            tileCorner = 10,
            tileGlyph = 0xFF5A6B78.toInt(),
            tileLabel = 0xFF3E4C57.toInt(),
            tileSelected = 0xFF4FC3F7.toInt(),
            columns = 4,
            tileScale = 0.84f,
            showLabels = true,
            trayBackground = 0xFFE7EDF1.toInt(),
            trayIcon = 0xFF6A7D8A.toInt(),
        )

        /** Every skin that ships with the app. */
        val BUILT_IN = listOf(DEFAULT, THREE_DS, WII_U, WII, PSP, VITA, DS_LITE)

        fun byId(id: String?): ConsoleTheme = BUILT_IN.firstOrNull { it.id == id } ?: DEFAULT

        // ── reading a user's theme ──────────────────────────────────────────

        /**
         * Parse one theme file. Anything absent falls back to [DEFAULT].
         *
         * Colours are written the way people write them — `"#FFFFFF"` or `"#CCFFFFFF"` — rather
         * than as signed integers, because a theme file is meant to be edited in a text editor by
         * somebody who is not a programmer.
         *
         * Returns null only if the file is not JSON at all. A theme with a nonsense colour keeps
         * the default for that one field rather than being thrown out entirely: losing a whole
         * theme over one typo is a miserable way to learn the format.
         */
        fun parse(json: String, id: String, iconFolder: String): ConsoleTheme? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val base = DEFAULT

            fun color(key: String, fallback: Int): Int {
                val text = root.optString(key, "").trim()
                if (text.isEmpty()) return fallback
                return runCatching { Color.parseColor(if (text.startsWith("#")) text else "#$text") }
                    .getOrDefault(fallback)
            }

            return ConsoleTheme(
                id = id,
                name = root.optString("name", id),
                subtitle = root.optString("subtitle", ""),
                background = color("background", base.background),
                backgroundEnd = color("backgroundEnd", base.backgroundEnd),
                slotColor = color("slotColor", base.slotColor),
                statusBackground = color("statusBackground", base.statusBackground),
                statusText = color("statusText", base.statusText),
                statusPill = color("statusPill", base.statusPill),
                showSignal = root.optBoolean("showSignal", base.showSignal),
                showDate = root.optBoolean("showDate", base.showDate),
                showBattery = root.optBoolean("showBattery", base.showBattery),
                tileFace = color("tileFace", base.tileFace),
                tileBorder = color("tileBorder", base.tileBorder),
                tileCorner = root.optInt("tileCorner", base.tileCorner),
                tileGlyph = color("tileGlyph", base.tileGlyph),
                tileLabel = color("tileLabel", base.tileLabel),
                tileSelected = color("tileSelected", base.tileSelected),
                columns = root.optInt("columns", base.columns).coerceIn(2, 8),
                tileScale = root.optDouble("tileScale", base.tileScale.toDouble()).toFloat()
                    .coerceIn(0.4f, 1f),
                showLabels = root.optBoolean("showLabels", base.showLabels),
                trayBackground = color("trayBackground", base.trayBackground),
                trayIcon = color("trayIcon", base.trayIcon),
                iconFolder = iconFolder,
                builtIn = false,
            )
        }

        /**
         * A worked example, written to the themes folder the first time it is opened.
         *
         * People do not read a format description; they copy the file next to theirs and change
         * numbers until it looks right. So the sample is a complete, working theme with every field
         * present and a comment field explaining the icon folder.
         */
        fun sampleJson(): String = """
            {
              "name": "My Theme",
              "subtitle": "an example to copy",

              "_icons": "Put PNGs in a folder with the same name as this file, minus .json.",
              "_iconNames": "screen.png notes.png volume.png brightness.png appearance.png keyboard.png mirror.png macros.png ftp.png stream.png",

              "background":       "#F2F2F2",
              "slotColor":        "#E4E4E4",

              "statusBackground": "#F7F7F7",
              "statusText":       "#3C3C3C",
              "statusPill":       "#3B8ED6",
              "showSignal":  true,
              "showDate":    true,
              "showBattery": true,

              "tileFace":     "#FFFFFF",
              "tileBorder":   "#D8D8D8",
              "tileCorner":   10,
              "tileGlyph":    "#4A4A4A",
              "tileLabel":    "#3C3C3C",
              "tileSelected": "#6FE3A0",

              "columns":    4,
              "tileScale":  0.74,
              "showLabels": true,

              "trayBackground": "#E9E9E9",
              "trayIcon":       "#6A6A6A"
            }
        """.trimIndent()
    }
}
