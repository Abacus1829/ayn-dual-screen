package com.abacus.dualscreen

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat

/**
 * The one place that decides what the app looks like.
 *
 * Every screen calls [apply] on its root, so a change to the accent, background or font takes effect
 * everywhere without each screen knowing the details. Colours are plain ARGB ints rather than resource
 * ids, so the palette can be anything the user picks rather than a fixed set.
 */
object Appearance {

    /** A one-tap look. Sets several things at once; each can still be tweaked afterwards. */
    data class Preset(
        val id: String,
        val label: String,
        val accent: Int,
        val base: Int,
        val corners: Int,
        val font: String,
        val background: String
    )

    val PRESETS = listOf(
        Preset("midnight", "Midnight", 0xFF6EC1FF.toInt(), 0xFF07080F.toInt(), 14, "sans-serif", "gradient"),
        Preset("carbon", "Carbon", 0xFFFF6B6B.toInt(), 0xFF0B0B0D.toInt(), 4, "sans-serif-condensed", "gradient"),
        Preset("nord", "Nord", 0xFF88C0D0.toInt(), 0xFF0E1219.toInt(), 12, "sans-serif", "gradient"),
        Preset("amber", "Amber", 0xFFFFC24D.toInt(), 0xFF120E07.toInt(), 10, "sans-serif-medium", "gradient"),
        Preset("terminal", "Terminal", 0xFF4BE08B.toInt(), 0xFF05090A.toInt(), 2, "monospace", "gradient"),
        Preset("violet", "Violet", 0xFFC58CFF.toInt(), 0xFF0C0713.toInt(), 20, "sans-serif", "gradient"),
        Preset("rose", "Rose", 0xFFFF8FAB.toInt(), 0xFF12090D.toInt(), 18, "sans-serif", "gradient"),
        Preset("ice", "Ice", 0xFF37E5D4.toInt(), 0xFF06100F.toInt(), 8, "sans-serif", "gradient")
    )

    /** The accents offered as swatches. The first is the default. */
    val ACCENTS = intArrayOf(
        0xFF6EC1FF.toInt(), 0xFF7C95FF.toInt(), 0xFF4BE08B.toInt(), 0xFFFFC24D.toInt(),
        0xFFFF8FAB.toInt(), 0xFFC58CFF.toInt(), 0xFFFF6B6B.toInt(), 0xFF37E5D4.toInt(),
        0xFFE0AE68.toInt(), 0xFF88C0D0.toInt(), 0xFFB4FF5A.toInt(), 0xFFEDF0F8.toInt()
    )

    val FONTS = listOf(
        "sans-serif" to "Default",
        "sans-serif-condensed" to "Condensed",
        "sans-serif-medium" to "Medium",
        "serif" to "Serif",
        "monospace" to "Mono"
    )

    /** Icon styles for the tool grid. Text glyphs, so no artwork ships and nothing needs licensing. */
    val ICON_SETS = listOf("glyph" to "Glyph", "block" to "Block", "line" to "Line", "text" to "Text")

    /**
     * A glyph per tool per style — and every tool in every style.
     *
     * These covered eight of the eighteen tools, so switching icon style changed half the grid and
     * left the rest on their default glyph: two styles on one screen, which reads as a rendering
     * bug rather than as a setting. Filling them in is most of what "icons update correctly" means.
     *
     * Characters are drawn from Geometric Shapes, Arrows and Math Operators, which the stock font
     * covers. That is not a matter of taste — ⧉ was tried for Mirror and rendered as tofu on the
     * test device, which is why Mirror has a drawn vector instead.
     */
    private val ICONS = mapOf(
        "glyph" to mapOf(
            "profiles" to "⇢", "screen" to "▣", "notes" to "✎", "volume" to "◧",
            "brightness" to "☀", "appearance" to "◈", "keyboard" to "⌨", "mirror" to "⧉",
            "macros" to "◉", "scribble" to "✎", "macrobuilder" to "≡", "layouts" to "▦",
            "widgets" to "◴", "gamecodes" to "◈", "settings" to "⚙", "ftp" to "⇅",
            "themes" to "◐", "stream" to "▶"
        ),
        "block" to mapOf(
            "profiles" to "▸", "screen" to "■", "notes" to "▤", "volume" to "▮",
            "brightness" to "◐", "appearance" to "◆", "keyboard" to "▦", "mirror" to "▥",
            "macros" to "▩", "scribble" to "▰", "macrobuilder" to "▬", "layouts" to "▨",
            "widgets" to "◕", "gamecodes" to "◼", "settings" to "▧", "ftp" to "▲",
            "themes" to "◑", "stream" to "▶"
        ),
        "line" to mapOf(
            "profiles" to "▹", "screen" to "□", "notes" to "≡", "volume" to "◫",
            "brightness" to "○", "appearance" to "◇", "keyboard" to "⊞", "mirror" to "⊡",
            "macros" to "⊕", "scribble" to "⊙", "macrobuilder" to "≣", "layouts" to "⊟",
            "widgets" to "◔", "gamecodes" to "◌", "settings" to "⊚", "ftp" to "⇕",
            "themes" to "◑", "stream" to "▷"
        ),
        "text" to mapOf(
            "profiles" to "SAV", "screen" to "2ND", "notes" to "TXT", "volume" to "VOL",
            "brightness" to "LUM", "appearance" to "UI", "keyboard" to "KEY", "mirror" to "MIR",
            "macros" to "MAC", "scribble" to "DRW", "macrobuilder" to "SEQ", "layouts" to "LAY",
            "widgets" to "INF", "gamecodes" to "COD", "settings" to "SET", "ftp" to "FTP",
            "themes" to "THM", "stream" to "STR"
        )
    )

    /**
     * Characters that the system font may draw as a colour emoji rather than as a glyph.
     *
     * The brightness sun was the one that got reported — a yellow picture sitting in a grid of
     * accent-coloured symbols, ignoring every attempt to tint it, because a colour emoji is a
     * bitmap and not text. These are the others in the same position.
     */
    private val EMOJI_RISK = setOf('☀', '⌨', '⚙', '✍', '▶', '◉', '⏱')

    /** U+FE0E: "draw the preceding character as text, not as emoji". */
    private const val TEXT_PRESENTATION = '︎'

    fun accentOf(settings: Settings): Int = settings.accent.takeIf { it != 0 } ?: ACCENTS[0]

    /**
     * The glyph for a tool, with a text-presentation request where one is needed.
     *
     * The variation selector is ignored by fonts that were never going to draw the character in
     * colour, so it costs nothing to add and fixes the ones that would have.
     */
    fun iconFor(settings: Settings, tool: Tool): String {
        val glyph = ICONS[settings.iconSet]?.get(tool.id) ?: tool.glyph
        return if (glyph.length == 1 && glyph[0] in EMOJI_RISK) glyph + TEXT_PRESENTATION else glyph
    }

    fun applyPreset(settings: Settings, preset: Preset) {
        settings.accent = preset.accent
        settings.corners = preset.corners
        settings.fontFamily = preset.font
        settings.backgroundMode = preset.background
        settings.backgroundUri = ""
    }

    /**
     * Paint a screen: background, font, and accent on everything tagged for it.
     *
     * Views opt in by tag rather than by type, so a layout says which pieces are accent-coloured
     * without this needing to know what each screen contains:
     *   `accent`     - tint the text
     *   `accentFill` - the primary button: accent-tinted fill, accent edge, faint glow
     *   `accentEdge` - a panel outlined in the accent
     *   `card`       - a panel following the corner-radius and opacity settings
     */
    fun apply(activity: Activity, root: View, settings: Settings, background: ImageView? = null) {
        val accent = accentOf(settings)
        val corner = settings.corners * activity.resources.displayMetrics.density

        paintBackground(activity, settings, background, accent)

        val typeface = Typeface.create(settings.fontFamily.ifBlank { "sans-serif" }, Typeface.NORMAL)
        walk(root) { view ->
            if (view is TextView) {
                view.typeface = Typeface.create(typeface, view.typeface?.style ?: Typeface.NORMAL)
                if (view.getTag(R.id.tag_base_size) == null)
                    view.setTag(R.id.tag_base_size, view.textSize)
                val base = view.getTag(R.id.tag_base_size) as Float
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * settings.fontScale)
            }

            when (view.tag as? String) {
                "accent" -> (view as? TextView)?.setTextColor(accent)
                "accentFill" -> {
                    view.background = primaryButton(activity, settings, accent)
                    (view as? TextView)?.setTextColor(activity.getColor(R.color.text))
                }
                "accentEdge" -> view.background = panel(activity, settings, surface(activity, settings), accent)
                "card" -> view.background = panel(
                    activity, settings, surface(activity, settings), activity.getColor(R.color.edge)
                )

                // Nothing to do, and deliberately so: a view that styles itself says so here.
                "plain" -> Unit

                else -> repaint(activity, settings, view, accent)
            }
        }
    }

    /**
     * The widgets that were still the stock blue whatever accent you picked.
     *
     * Tags cover the pieces a layout deliberately marks up. Everything else — checkboxes, sliders,
     * spinners, the ordinary buttons — took its colour from the theme's `colorAccent`, which is a
     * fixed resource compiled into the APK. So changing the accent repainted the headings and the
     * tool grid and left every control on every screen the same blue, which is what "it only
     * changes the text" was describing.
     *
     * Done by type rather than by tag because that is what makes it *central*: a new screen with a
     * checkbox on it is themed because it is a checkbox, not because somebody remembered to tag it.
     * A view that wants none of this tags itself `plain`.
     */
    private fun repaint(activity: Activity, settings: Settings, view: View, accent: Int) {
        val tint = ColorStateList.valueOf(accent)

        when (view) {
            // Checkboxes, radio buttons and switches. Before Button, which they all extend.
            is CompoundButton -> {
                view.buttonTintList = tint
                if (view is SwitchCompat) {
                    view.thumbTintList = tint
                    view.trackTintList = ColorStateList.valueOf(
                        Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent))
                    )
                }
            }

            is SeekBar -> {
                view.progressTintList = tint
                view.thumbTintList = tint
                view.progressBackgroundTintList =
                    ColorStateList.valueOf(activity.getColor(R.color.edge_hi))
            }

            is ProgressBar -> {
                view.progressTintList = tint
                view.indeterminateTintList = tint
            }

            is Spinner -> view.backgroundTintList = tint

            is EditText -> view.background = field(activity, settings, accent)

            /*
             * Only the buttons still wearing their XML background.
             *
             * @style/Action is a <selector>, so it arrives as a StateListDrawable; a button that a
             * screen has styled in code carries a GradientDrawable or a LayerDrawable instead. That
             * distinction is what lets this repaint every ordinary button in the app without
             * flattening the handful that are drawn deliberately.
             */
            is Button -> if (view.background is StateListDrawable)
                view.background = actionButton(activity, settings, accent)
        }
    }

    /**
     * The ordinary button: card fill, accent-lifted edge, and a ripple.
     *
     * The ripple is the one piece of motion added here, and it earns its place on a handheld: a
     * button that does not acknowledge a press feels broken long before anybody works out why. It
     * replaces a state selector that changed colour instantly.
     */
    fun actionButton(activity: Activity, settings: Settings, accent: Int): Drawable {
        val corner = settings.corners * activity.resources.displayMetrics.density

        fun face(fill: Int, stroke: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(fill)
            setStroke(dp(activity, 1), stroke)
        }

        val states = StateListDrawable().apply {
            addState(
                intArrayOf(-android.R.attr.state_enabled),
                face(activity.getColor(R.color.card), activity.getColor(R.color.edge)),
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                face(blend(activity.getColor(R.color.edge_hi), accent, 0.25f), accent),
            )
            addState(
                intArrayOf(),
                face(
                    activity.getColor(R.color.card_hi),
                    blend(activity.getColor(R.color.edge_hi), accent, 0.35f),
                ),
            )
        }

        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(Color.WHITE)
        }

        return RippleDrawable(
            ColorStateList.valueOf(
                Color.argb(80, Color.red(accent), Color.green(accent), Color.blue(accent))
            ),
            states,
            mask,
        )
    }

    /** A text field, in the current corner radius, with an edge that knows what the accent is. */
    fun field(activity: Activity, settings: Settings, accent: Int): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = settings.corners * activity.resources.displayMetrics.density
            setColor(activity.getColor(R.color.bg))
            setStroke(dp(activity, 1), blend(activity.getColor(R.color.edge_hi), accent, 0.30f))
        }

    /**
     * The primary button: a soft accent glow behind an accent-edged fill.
     *
     * Two stacked layers rather than an elevation shadow, because a coloured glow reads much better on
     * a dark UI than the grey drop shadow elevation gives you.
     */
    fun primaryButton(activity: Activity, settings: Settings, accent: Int): Drawable {
        val corner = settings.corners * activity.resources.displayMetrics.density

        val glow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner + dp(activity, 3)
            setColor(Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }

        val face = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                blend(activity.getColor(R.color.card_hi), accent, 0.34f),
                blend(activity.getColor(R.color.card_hi), accent, 0.14f)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setStroke(dp(activity, 2), accent)
        }

        return LayerDrawable(arrayOf(glow, face)).apply {
            setLayerInset(1, dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2))
        }
    }

    /** A card fill honouring the opacity setting, so a background image can show through. */
    fun surface(activity: Activity, settings: Settings): Int {
        val base = activity.getColor(R.color.card)
        val alpha = (settings.surfaceOpacity.coerceIn(40, 100) * 255 / 100)
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun paintBackground(activity: Activity, settings: Settings, view: ImageView?, accent: Int) {
        val window = activity.window ?: return

        when (settings.backgroundMode) {
            "image" -> {
                val uri = settings.backgroundUri
                if (view != null && uri.isNotBlank()) {
                    try {
                        view.setImageURI(Uri.parse(uri))
                        view.visibility = View.VISIBLE
                        val dim = settings.backgroundDim.coerceIn(0, 90) * 255 / 100
                        view.setColorFilter(Color.argb(dim, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
                        return
                    } catch (_: Exception) {
                        // a revoked or deleted image shouldn't leave the screen unusable
                    }
                }
                view?.visibility = View.GONE
                window.setBackgroundDrawable(GradientDrawable().apply { setColor(activity.getColor(R.color.bg)) })
            }

            "gradient" -> {
                view?.visibility = View.GONE
                // a barely-there wash of the accent, so the whole app picks up the chosen colour
                window.setBackgroundDrawable(
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            blend(activity.getColor(R.color.bg), accent, 0.13f),
                            activity.getColor(R.color.bg),
                            blend(activity.getColor(R.color.bg), accent, 0.06f)
                        )
                    )
                )
            }

            else -> {
                view?.visibility = View.GONE
                window.setBackgroundDrawable(GradientDrawable().apply { setColor(activity.getColor(R.color.bg)) })
            }
        }
    }

    /** Mix [tint] into [base] by [amount] (0-1). */
    fun blend(base: Int, tint: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) * (1 - a) + Color.red(tint) * a).toInt(),
            (Color.green(base) * (1 - a) + Color.green(tint) * a).toInt(),
            (Color.blue(base) * (1 - a) + Color.blue(tint) * a).toInt()
        )
    }

    // The drawable helpers below take a Context rather than an Activity: they only need resources and a
    // colour lookup, and the macro overlay draws its buttons from a Service where there is no Activity.

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** A rounded panel in the current corner radius, for views built in code. */
    fun panel(context: Context, settings: Settings, fill: Int, stroke: Int, strokeDp: Int = 1) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = settings.corners * context.resources.displayMetrics.density
            setColor(fill)
            setStroke(dp(context, strokeDp), stroke)
        }

    /** A tool tile: subtle accent wash so the grid doesn't read as flat grey boxes. */
    fun tile(context: Context, settings: Settings, accent: Int, enabled: Boolean) =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                blend(context.getColor(R.color.card_hi), accent, if (enabled) 0.16f else 0.03f),
                context.getColor(R.color.card)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = settings.corners * context.resources.displayMetrics.density
            setStroke(
                dp(context, 1),
                if (enabled) blend(context.getColor(R.color.edge), accent, 0.45f)
                else context.getColor(R.color.edge)
            )
        }

    private fun walk(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount)
                walk(view.getChildAt(i), action)
        }
    }
}
