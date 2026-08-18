package com.abacus.dualscreen.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.abacus.dualscreen.Tool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws a [ConsoleTheme] — the tiles, the status bar, the empty slots behind them.
 *
 * Built in code rather than as a layout per console, because that is the whole bet of this feature:
 * one renderer that four shipped skins and any number of user themes drive with different numbers.
 * A layout per console would mean a new XML file for every device, and no user theme could ever
 * introduce one.
 */
object ConsoleSkin {

    /**
     * Build the home grid for a theme.
     *
     * Returns the view to put where the stock tools grid goes. The caller keeps ownership of what
     * happens on a tap — this decides how it looks, not what it does.
     */
    fun buildGrid(
        context: Context,
        theme: ConsoleTheme,
        store: ThemeStore,
        tools: List<Tool>,
        onTap: (Tool) -> Unit,
    ): View {
        val grid = GridLayout(context).apply {
            columnCount = theme.columns
            setBackgroundColor(theme.background)
            val pad = dp(context, 10)
            setPadding(pad, pad, pad, pad)
        }

        // The tile edge, from the screen width and the theme's column count. Measured rather than
        // fixed so a 3DS skin looks the same on the Thor's panel as on a phone.
        val screen = context.resources.displayMetrics.widthPixels
        val cell = (screen - dp(context, 20)) / theme.columns
        val tile = (cell * theme.tileScale).toInt()

        for (tool in tools) {
            grid.addView(buildTile(context, theme, store, tool, cell, tile, onTap))
        }

        return grid
    }

    private fun buildTile(
        context: Context,
        theme: ConsoleTheme,
        store: ThemeStore,
        tool: Tool,
        cell: Int,
        tile: Int,
        onTap: (Tool) -> Unit,
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = GridLayout.LayoutParams().apply {
                width = cell
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(0, dp(context, 6), 0, dp(context, 6))
            }
            setOnClickListener { onTap(tool) }
        }

        // The face: a rounded rectangle with a hairline border, which is what makes a 3DS tile read
        // as a 3DS tile rather than as a flat square.
        val face = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(theme.tileFace)
            cornerRadius = dp(context, theme.tileCorner).toFloat()
            if (theme.tileBorder != 0) setStroke(dp(context, 1), theme.tileBorder)
        }

        val art = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(tile, tile)
            background = face
        }

        // A user icon if the theme supplies one for this tool, the built-in glyph otherwise. A
        // theme with three icons gets three icons and the rest as glyphs, which is a fine theme.
        val custom = store.icon(theme, tool.id)
        if (custom != null) {
            art.addView(ImageView(context).apply {
                setImageDrawable(custom)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val inset = (tile * 0.12f).toInt()
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply { setMargins(inset, inset, inset, inset) }
            })
        } else {
            art.addView(TextView(context).apply {
                text = tool.glyph
                setTextColor(if (tool.available) theme.tileGlyph else faded(theme.tileGlyph))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, tile * 0.44f)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        }

        column.addView(art)

        if (theme.showLabels) {
            column.addView(TextView(context).apply {
                text = context.getString(tool.label)
                setTextColor(if (tool.available) theme.tileLabel else faded(theme.tileLabel))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(context, 4) }
            })
        }

        return column
    }

    /**
     * The status bar across the top: signal, connection pill, date, battery.
     *
     * Each piece is optional because each console shows a different subset — a DS has no status bar
     * at all, and a theme that turns all three off gets a bare strip rather than a broken one.
     */
    fun buildStatusBar(context: Context, theme: ConsoleTheme, connection: String, battery: Int): View? {
        if (theme.statusBackground == 0) return null

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.statusBackground)
            val pad = dp(context, 6)
            setPadding(pad, dp(context, 3), pad, dp(context, 3))
        }

        fun label(text: String, weight: Float = 0f) = TextView(context).apply {
            this.text = text
            setTextColor(theme.statusText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                if (weight > 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                weight,
            )
        }

        if (theme.showSignal) bar.addView(label("▂▄▆"))

        if (theme.statusPill != 0 && connection.isNotEmpty()) {
            bar.addView(TextView(context).apply {
                text = "  $connection  "
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = GradientDrawable().apply {
                    setColor(theme.statusPill)
                    cornerRadius = dp(context, 8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(context, 6) }
            })
        }

        bar.addView(label("", weight = 1f))      // pushes the rest to the right

        if (theme.showDate) bar.addView(label(DATE.format(Date())))
        if (theme.showBattery) bar.addView(label("  $battery% ▮"))

        return bar
    }

    /**
     * The pale grid of empty slots a 3DS shows behind its apps.
     *
     * Drawn as a repeating layer rather than a bitmap so it costs nothing and takes the theme's own
     * colour. Returns null when the theme does not want one.
     */
    fun slotBackdrop(context: Context, theme: ConsoleTheme): GradientDrawable? {
        if (theme.slotColor == 0) return null

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(theme.slotColor)
            cornerRadius = dp(context, theme.tileCorner).toFloat()
        }
    }

    /**
     * One tile's face: rounded rectangle, hairline border, theme colours.
     *
     * A transparent face is a real answer, not a missing one — the PSP draws its icons straight on
     * the background with nothing behind them, and a very large radius turns the square into the
     * Vita's round bubble.
     */
    fun tileFace(context: Context, theme: ConsoleTheme): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(theme.tileFace)
            cornerRadius = dp(context, theme.tileCorner).toFloat()
            if (theme.tileBorder != 0) setStroke(dp(context, 1), theme.tileBorder)
        }

    /**
     * The surface behind everything: a flat colour, or a vertical gradient when the theme sets
     * [ConsoleTheme.backgroundEnd].
     */
    fun backdrop(theme: ConsoleTheme): GradientDrawable =
        if (theme.backgroundEnd == 0) {
            GradientDrawable().apply { setColor(theme.background) }
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.background, theme.backgroundEnd),
            )
        }

    /** Half-strength, for a tool that is present but not finished. */
    private fun faded(color: Int): Int =
        Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private val DATE = SimpleDateFormat("d/M (EEE)  HH mm", Locale.US)
}
