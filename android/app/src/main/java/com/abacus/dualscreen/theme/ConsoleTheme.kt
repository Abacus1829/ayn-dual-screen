package com.abacus.dualscreen.theme

import android.graphics.Color
import org.json.JSONObject

/**
 * A console skin: what the home screen looks like when it is pretending to be a handheld.
 *
 * This is a layer above [com.abacus.dualscreen.Appearance], which decides accent, font and corner
 * radius. A skin decides the *shape* of the thing — a twin-screen handheld menu is not the stock grid with
 * different colours, it is white tiles with coloured glyphs on a pale grid, a status bar with
 * signal and battery, and a folder row above it.
 *
 * ## Why it is data rather than code
 *
 * Because the second, third and fourth skins are the same screen with different numbers. Writing
 * the clamshell one as a layout would mean writing a crossbar one as another layout, and a bubble one as a
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
     * Added because two of the shipped skins genuinely need it: a bubble-style home screen is a blue wash
     * from deep at the top to pale at the bottom, and rendering it flat loses most of what makes it
     * recognisable. Themes that want a plain colour simply leave it out.
     */
    val backgroundEnd: Int = 0,

    /** The faint grid of empty tile slots those skins draw behind their apps. 0 to omit it. */
    val slotColor: Int = 0,

    // ── the status bar ──────────────────────────────────────────────────────

    /** 0 hides the bar entirely — a DS has no such thing. */
    val statusBackground: Int = 0,
    val statusText: Int = Color.BLACK,

    /** The blue pill the clamshell skin puts around the connection name. 0 for none. */
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

    /** Glyph and label colours. The clamshell skin puts dark glyphs on white; the crossbar skin does the opposite. */
    val tileGlyph: Int,
    val tileLabel: Int,

    /** Highlight around the selected tile — the clamshell skin's green corner brackets. */
    val tileSelected: Int,

    /** Tiles across the grid. Four for the clamshell, three big ones for the bubble skin. */
    val columns: Int = 4,

    /** Tile edge as a fraction of the column width, so tiles can be square-ish or roomy. */
    val tileScale: Float = 0.78f,

    /** Draw the label under the tile at all. The bubble skin puts names under its icons; the crossbar skin does not. */
    val showLabels: Boolean = true,

    // ── the bottom bar ──────────────────────────────────────────────────────

    /** The strip of folder icons the console skins keep along the bottom. 0 for none. */
    val trayBackground: Int = 0,
    val trayIcon: Int = Color.DKGRAY,

    /**
     * A background image, by filename, inside the theme's own folder.
     *
     * The thing that makes a console theme feel like a *theme* rather than a palette: these skins'
     * are mostly a wallpaper, and people expect to drop their own image in. Tiled or stretched
     * per [backgroundTiled]. Empty means use the colours.
     */
    val backgroundImage: String = "",

    /** Tile the image at its own size instead of stretching it to the screen. */
    val backgroundTiled: Boolean = false,

    /**
     * A font file in the theme's folder — "font.ttf" — or a platform family name such as
     * "monospace" or "sans-serif-condensed".
     *
     * A file wins if it exists; otherwise the string is handed to the platform, so a theme can ask
     * for a stock family without shipping anything.
     */
    val fontFile: String = "",

    /** Where the user's icons, image and font live. Never written by a theme file. */
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
         * Clamshell — a twin-screen handheld home menu.
         *
         * The details that make it read as that machine rather than "a white theme": the pale grid of
         * empty slots showing through behind the apps, white tiles with a hairline border and a
         * soft corner, dark glyphs, and the green corner-bracket selection. The status bar is its
         * own thing — signal bars on the left, a blue pill with the connection, the date, and a
         * battery on the right.
         */
        val CLAMSHELL = ConsoleTheme(
            id = "clamshell",
            name = "Theme 1",
            subtitle = "Light, four across",
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
         * Crossbar — a horizontal media-bar menu.
         *
         * A cross-media bar is not a grid, and pretending otherwise would be a lie. What
         * carries across is its *look*: a deep blue-black wash, icons with no tile behind them at
         * all, and thin white labels. So the tile face is transparent, the border is off, and the
         * columns drop to five because those icons are small and close together.
         */
        val CROSSBAR = ConsoleTheme(
            id = "crossbar",
            name = "Theme 5",
            subtitle = "Dark blue, no tiles",
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
         * Bubbles — round icons on a blue wash.
         *
         * Round icons on a pale blue field, three across, and no labels: the hardware puts the name
         * inside the bubble art rather than under it, so labels off is the accurate choice and the
         * tiles get bigger to compensate.
         */
        val BUBBLES = ConsoleTheme(
            id = "bubbles",
            name = "Theme 6",
            subtitle = "Blue, round icons",

            // The home screen is a blue wash, deep at the top and pale toward the bottom. Flat
            // blue reads as "a blue theme"; the gradient is what makes it read as that hardware.
            background = 0xFF2E7CBF.toInt(),
            backgroundEnd = 0xFF8FC7E8.toInt(),
            slotColor = 0,

            // A dark translucent strip with the clock on the right — not a light bar.
            statusBackground = 0xFF13293D.toInt(),
            statusText = 0xFFFFFFFF.toInt(),
            statusPill = 0,
            showSignal = true,
            showDate = false,        // that hardware shows the time, not the date
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
         * Silver — the sparse two-screen menu of an early handheld.
         *
         * Silver-white with a soft blue wash, chunky rounded tiles and a thin blue selection. No
         * status bar: the DS menu has none, so [statusBackground] is 0 and the strip is skipped
         * entirely rather than drawn empty.
         */
        val SILVER = ConsoleTheme(
            id = "silver",
            name = "Theme 8",
            subtitle = "Silver, three across",
            background = 0xFFD9DEE4.toInt(),
            backgroundEnd = 0xFFEFF2F5.toInt(),
            slotColor = 0xFFC6CDD6.toInt(),
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
            tileScale = 0.92f,      // big chunky tiles: a DS menu has very few, very large ones
            showLabels = true,
        )

        /**
         * Tablet Pad — a living-room console tablet menu.
         *
         * Close cousin of the clamshell skin and worth keeping distinct: the field is lighter and faintly warm
         * rather than flat grey, the tiles are larger and softer with barely any border, there are
         * five across instead of four, and the empty slots behind them are more visible. The blue
         * selection is blue, not the clamshell skin's green.
         */
        val TABLET_PAD = ConsoleTheme(
            id = "tabletpad",
            name = "Theme 2",
            subtitle = "Warm light, five across",
            background = 0xFFFDFBF6.toInt(),
            backgroundEnd = 0xFFF2EFE8.toInt(),
            slotColor = 0xFFE7E2D7.toInt(),
            statusBackground = 0xFFFAFAFA.toInt(),
            statusText = 0xFF444444.toInt(),
            statusPill = 0,
            showSignal = false,
            showDate = true,
            showBattery = true,
            tileFace = 0xFFFFFFFF.toInt(),
            tileBorder = 0xFFE0E0E0.toInt(),
            tileCorner = 20,
            tileGlyph = 0xFF4A4A4A.toInt(),
            tileLabel = 0xFF3C3C3C.toInt(),
            tileSelected = 0xFF29B6F6.toInt(),
            columns = 5,
            tileScale = 0.86f,
            showLabels = true,
            trayBackground = 0xFFFFFFFF.toInt(),
            trayIcon = 0xFF2196F3.toInt(),
        )

        /**
         * Channels — a grid of channel tiles.
         *
         * The channel grid rather than the dark settings screens, because the grid is the part
         * that is a home screen. Near-white field, channel tiles in the palest grey with a
         * blue-grey hairline, four across, and the empty channels showing faintly behind them.
         *
         * Distinct from the tablet skin despite the family resemblance: these tiles are wider and
         * flatter, the palette is cooler, and its selection blue is lighter.
         */
        val CHANNELS = ConsoleTheme(
            id = "channels",
            name = "Theme 3",
            subtitle = "Cool light, no top bar",
            background = 0xFFE3EEF7.toInt(),
            backgroundEnd = 0xFFF7FBFD.toInt(),
            slotColor = 0xFFD2E1EE.toInt(),
            // No top strip at all: this hardware puts its clock at the BOTTOM, and having no bar
            // where the other two have one is half of what tells the three apart at a glance.
            statusBackground = 0,
            statusText = 0xFF46545F.toInt(),
            statusPill = 0,
            showSignal = false,
            showDate = false,
            showBattery = false,        // mains-powered hardware; a battery readout would be a lie
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

        /**
         * Hybrid — a modern dockable console menu.
         *
         * Near-black with square-ish tiles and a thin white selection ring. Twelve games across a
         * row on the hardware, which would be unreadable here, so five: the point is the palette
         * and the shape, not the exact count.
         */
        val HYBRID = ConsoleTheme(
            id = "hybrid",
            name = "Theme 4",
            subtitle = "Dark, five across",
            background = 0xFF2D2D2D.toInt(),
            backgroundEnd = 0xFF1A1A1A.toInt(),
            slotColor = 0xFF3A3A3A.toInt(),
            statusBackground = 0xFF232323.toInt(),
            statusText = 0xFFEDEDED.toInt(),
            statusPill = 0,
            showSignal = true,
            showDate = true,
            showBattery = true,
            tileFace = 0xFF4A4A4A.toInt(),
            tileBorder = 0xFF5E5E5E.toInt(),
            tileCorner = 6,
            tileGlyph = 0xFFF2F2F2.toInt(),
            tileLabel = 0xFFDDDDDD.toInt(),
            tileSelected = 0xFFFFFFFF.toInt(),
            columns = 5,
            tileScale = 0.80f,
            showLabels = true,
        )

        /**
         * Grey Box — an older, darker memory-card menu.
         *
         * Kept apart from [CROSSBAR] because the two really do look different: this is the deep
         * blue-to-black wave of an older console menu rather than the crossbar skin's flatter field,
         * and the icons sit larger and further apart.
         */
        val GREY_BOX = ConsoleTheme(
            id = "greybox",
            name = "Theme 7",
            subtitle = "Dark slate",
            background = 0xFF10131C.toInt(),
            backgroundEnd = 0xFF272B3A.toInt(),
            slotColor = 0,
            statusBackground = 0xFF0A0C12.toInt(),
            statusText = 0xFFC9CEDC.toInt(),
            statusPill = 0,
            showSignal = false,
            showDate = true,
            showBattery = false,
            tileFace = 0xFF1C2130.toInt(),
            tileBorder = 0xFF39415A.toInt(),
            tileCorner = 4,
            tileGlyph = 0xFFB8C2DC.toInt(),
            tileLabel = 0xFFA8B2CC.toInt(),
            tileSelected = 0xFF7C8CB8.toInt(),
            columns = 4,
            tileScale = 0.78f,
            showLabels = true,
        )

        /** Every skin that ships with the app. */
        // Ordered as they are numbered, so "Theme 3" is the third one in the list.
        val BUILT_IN = listOf(
            DEFAULT, CLAMSHELL, TABLET_PAD, CHANNELS, HYBRID, CROSSBAR, BUBBLES, GREY_BOX, SILVER,
        )

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
                backgroundImage = root.optString("backgroundImage", ""),
                backgroundTiled = root.optBoolean("backgroundTiled", false),
                fontFile = root.optString("font", ""),
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
              "_iconNames": "screen.png notes.png volume.png brightness.png appearance.png keyboard.png mirror.png macros.png ftp.png stream.png themes.png",

              "_assets": "background.png and font.ttf go in that same folder.",
              "backgroundImage": "background.png",
              "backgroundTiled": false,
              "font": "font.ttf",

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
