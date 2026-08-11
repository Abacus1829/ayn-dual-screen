package com.abacus.dualscreen

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityAppearanceBinding

/**
 * Everything about how the app looks.
 *
 * The card at the top is a real header, a real tool row and a real primary button, painted by the same
 * [Appearance.apply] the rest of the app uses — so it can't drift from the thing it's previewing the way
 * a hand-drawn mockup would.
 */
class AppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceBinding
    private lateinit var settings: Settings

    /**
     * OpenDocument rather than GetContent: it's the one that can be given a *persistable* permission,
     * so the chosen image still loads after a reboot instead of silently disappearing.
     */
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        settings.backgroundUri = uri.toString()
        settings.backgroundMode = "image" // picking an image obviously means you want to see it
        rebuild()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        binding.backButton.setOnClickListener { finish() }
        binding.pickImage.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        binding.clearImage.setOnClickListener {
            settings.backgroundUri = ""
            if (settings.backgroundMode == "image") settings.backgroundMode = "gradient"
            rebuild()
        }
        binding.resetButton.setOnClickListener { resetAll() }
        binding.previewButton.setOnClickListener { /* preview only */ }

        buildSliders()
        rebuild()
    }

    /** Redraw every row that shows a selection, then repaint the screen. */
    private fun rebuild() {
        buildPresets()
        buildAccents()
        buildBackgroundModes()
        buildFonts()
        buildColumns()
        buildIconSets()
        buildToolToggles()
        syncMixer()
        refresh()
    }

    /*********
     * Presets
     *********/
    private fun buildPresets() {
        binding.presetRow.removeAllViews()
        for (preset in Appearance.PRESETS) {
            val chosen = settings.accent == preset.accent && settings.fontFamily == preset.font

            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(8) }
                background = Appearance.panel(
                    this@AppearanceActivity, settings,
                    Appearance.blend(getColor(R.color.card), preset.accent, if (chosen) 0.22f else 0.08f),
                    if (chosen) preset.accent else getColor(R.color.edge),
                    if (chosen) 2 else 1
                )
                setOnClickListener {
                    Appearance.applyPreset(settings, preset)
                    rebuild()
                }
            }

            // a dot in the preset's own accent, so the row reads as colours rather than as words
            chip.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(preset.accent)
                }
            })

            chip.addView(TextView(this).apply {
                text = preset.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(getColor(if (chosen) R.color.text else R.color.text_faint))
                typeface = Typeface.create(preset.font, Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })

            binding.presetRow.addView(chip)
        }
    }

    /*********
     * Colour
     *********/
    private fun buildAccents() {
        binding.accentRow.removeAllViews()
        for (colour in Appearance.ACCENTS) {
            val chosen = Appearance.accentOf(settings) == colour
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(5) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colour)
                    setStroke(dp(if (chosen) 3 else 1), if (chosen) getColor(R.color.text) else colour)
                }
                setOnClickListener {
                    settings.accent = colour
                    rebuild()
                }
            }
            binding.accentRow.addView(swatch)
        }
    }

    /**
     * The three channel sliders and the swatch beside them.
     *
     * Full RGB rather than a fixed palette, because "fully customizable" means the colour is the user's
     * choice, not a menu of mine. The listeners are attached once in [buildSliders]; this only moves the
     * thumbs to match whatever the accent currently is.
     */
    private fun syncMixer() {
        val accent = Appearance.accentOf(settings)
        binding.redBar.progress = Color.red(accent)
        binding.greenBar.progress = Color.green(accent)
        binding.blueBar.progress = Color.blue(accent)
        paintMixer(accent)
    }

    private fun paintMixer(accent: Int) {
        binding.customSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
            setStroke(dp(2), getColor(R.color.edge))
        }
        binding.customHex.text = String.format(
            "#%02X%02X%02X", Color.red(accent), Color.green(accent), Color.blue(accent)
        )
        tint(binding.redBar, Color.rgb(Color.red(accent), 60, 60))
        tint(binding.greenBar, Color.rgb(60, Color.green(accent), 60))
        tint(binding.blueBar, Color.rgb(60, 60, Color.blue(accent)))
    }

    /** Take the accent from wherever the three thumbs are now. */
    private fun mixed(): Int = Color.rgb(
        binding.redBar.progress, binding.greenBar.progress, binding.blueBar.progress
    )

    /*********
     * Background
     *********/
    private fun buildBackgroundModes() {
        val modes = listOf(
            "none" to getString(R.string.bg_none),
            "gradient" to getString(R.string.bg_gradient),
            "image" to getString(R.string.bg_image)
        )
        binding.bgModeRow.removeAllViews()
        for ((mode, label) in modes) {
            binding.bgModeRow.addView(segment(label, settings.backgroundMode == mode) {
                settings.backgroundMode = mode
                rebuild()
            })
        }

        // the image controls are only meaningful in image mode, so don't leave dead sliders on screen
        val imageMode = settings.backgroundMode == "image"
        val show = if (imageMode) View.VISIBLE else View.GONE
        binding.imageRow.visibility = show
        binding.dimLabel.visibility = show
        binding.dimBar.visibility = show
    }

    /*********
     * Type, shape, grid
     *********/
    private fun buildFonts() {
        binding.fontRow.removeAllViews()
        for ((family, label) in Appearance.FONTS) {
            val button = segment(label, settings.fontFamily == family) {
                settings.fontFamily = family
                rebuild()
            }
            button.typeface = Typeface.create(family, Typeface.NORMAL)
            binding.fontRow.addView(button)
        }
    }

    private fun buildColumns() {
        binding.columnRow.removeAllViews()
        for (count in 2..5) {
            binding.columnRow.addView(segment(count.toString(), settings.gridColumns == count) {
                settings.gridColumns = count
                rebuild()
            })
        }
    }

    private fun buildIconSets() {
        binding.iconRow.removeAllViews()
        for ((id, label) in Appearance.ICON_SETS) {
            binding.iconRow.addView(segment(label, settings.iconSet == id) {
                settings.iconSet = id
                rebuild()
            })
        }
    }

    /*********
     * Which tools show
     *********/
    private fun buildToolToggles() {
        binding.toolToggles.removeAllViews()
        val hidden = settings.hiddenTools

        for (tool in Tool.entries) {
            val check = CheckBox(this).apply {
                text = getString(tool.label)
                isChecked = tool.id !in hidden
                setTextColor(getColor(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setOnCheckedChangeListener { _, visible ->
                    // a fresh set: SharedPreferences keeps the instance it was given, so mutating the
                    // one we read back would leave the stored value and the live value the same object
                    settings.hiddenTools = settings.hiddenTools.toMutableSet().apply {
                        if (visible) remove(tool.id) else add(tool.id)
                    }
                }
            }
            binding.toolToggles.addView(check)
        }
    }

    /*********
     * Sliders
     *********/
    private fun buildSliders() {
        binding.dimBar.max = 90
        binding.dimBar.progress = settings.backgroundDim
        binding.dimBar.setOnSeekBarChangeListener(simple { settings.backgroundDim = it; refresh() })

        // 40..100: below 40 the cards stop being readable over a busy image
        binding.opacityBar.max = 60
        binding.opacityBar.progress = (settings.surfaceOpacity - 40).coerceIn(0, 60)
        binding.opacityBar.setOnSeekBarChangeListener(simple {
            settings.surfaceOpacity = it + 40
            refresh()
        })

        // 85..140 maps to a 0.85x..1.40x text scale; below that labels start colliding
        binding.sizeBar.max = 55
        binding.sizeBar.progress = ((settings.fontScale * 100).toInt() - 85).coerceIn(0, 55)
        binding.sizeBar.setOnSeekBarChangeListener(simple {
            settings.fontScale = (it + 85) / 100f
            refresh()
        })

        binding.cornerBar.max = 24
        binding.cornerBar.progress = settings.corners.coerceIn(0, 24)
        binding.cornerBar.setOnSeekBarChangeListener(simple { settings.corners = it; rebuild() })

        for (bar in listOf(binding.redBar, binding.greenBar, binding.blueBar)) {
            bar.max = 255
            bar.setOnSeekBarChangeListener(simple {
                settings.accent = mixed()
                buildAccents() // the palette row shows which swatch (if any) matches
                paintMixer(settings.accent)
                refresh()
            })
        }
    }

    private fun resetAll() {
        Appearance.applyPreset(settings, Appearance.PRESETS[0])
        settings.backgroundUri = ""
        settings.backgroundDim = 55
        settings.fontScale = 1f
        settings.surfaceOpacity = 88
        settings.gridColumns = 4
        settings.iconSet = "glyph"
        settings.hiddenTools = emptySet()
        buildSliders()
        rebuild()
    }

    /*********
     * Preview
     *********/
    /** Re-apply to this very screen, so the settings page is its own preview. */
    private fun refresh() {
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)

        val accent = Appearance.accentOf(settings)
        binding.previewBar.setBackgroundColor(accent)
        binding.previewSub.text = getString(R.string.appearance_preview_sub)
        buildPreviewGrid(accent)
    }

    /** A miniature of the home grid, in the chosen icon set and column count. */
    private fun buildPreviewGrid(accent: Int) {
        binding.previewGrid.removeAllViews()
        val shown = Tool.entries.filter { it.id !in settings.hiddenTools }.take(settings.gridColumns)

        for (tool in shown) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                background = Appearance.tile(this@AppearanceActivity, settings, accent, tool.available)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
            }

            cell.addView(TextView(this).apply {
                text = Appearance.iconFor(settings, tool)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(accent)
                gravity = Gravity.CENTER
            })

            binding.previewGrid.addView(cell)
        }
    }

    /*********
     * Bits
     *********/
    /** One button in a row of mutually exclusive choices. */
    private fun segment(label: String, chosen: Boolean, onPick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(9), dp(4), dp(9))
        minWidth = 0
        minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(5) }
        background = Appearance.panel(
            this@AppearanceActivity, settings,
            getColor(if (chosen) R.color.card_hi else R.color.card),
            if (chosen) Appearance.accentOf(settings) else getColor(R.color.edge),
            if (chosen) 2 else 1
        )
        setTextColor(getColor(if (chosen) R.color.text else R.color.text_faint))
        setOnClickListener { onPick() }
    }

    private fun tint(bar: SeekBar, colour: Int) {
        bar.progressTintList = android.content.res.ColorStateList.valueOf(colour)
        bar.thumbTintList = android.content.res.ColorStateList.valueOf(colour)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun simple(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
            if (fromUser) onChange(value)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }
}
