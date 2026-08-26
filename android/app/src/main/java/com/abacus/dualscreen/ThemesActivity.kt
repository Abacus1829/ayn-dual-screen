package com.abacus.dualscreen

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityThemesBinding
import com.abacus.dualscreen.theme.ConsoleSkin
import com.abacus.dualscreen.theme.ConsoleTheme
import com.abacus.dualscreen.theme.ThemeStore

/**
 * Pick a console skin, or find out how to write one.
 *
 * A dropdown and one preview, rather than a scrolling list of cards. Nine skins as full-width rows
 * ran well past the bottom of the screen and turned a single decision into a catalogue.
 *
 * The preview is drawn from the theme itself -- its real background, its own tile shape and glyph
 * colour, its status strip if it has one -- so it can never drift out of date with what the skin
 * actually does, and a user's own theme gets a preview nobody had to ship a screenshot for.
 */
class ThemesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemesBinding
    private lateinit var settings: Settings
    private lateinit var store: ThemeStore

    private var themes: List<ConsoleTheme> = emptyList()

    /** Set while the spinner is being filled, so populating it does not count as a choice. */
    private var settling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = ThemeStore(this)

        com.abacus.dualscreen.ui.Nav.back(this, binding.backButton)
        binding.folderButton.setOnClickListener { showFolder() }

        binding.themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (settling) return

                val chosen = themes.getOrNull(position) ?: return
                settings.consoleTheme = chosen.id
                showPreview(chosen)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)

        com.abacus.dualscreen.control.ControlCenter.attach(this, settings)
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on every return: someone may have dropped a theme file in over FTP while this
        // screen sat open behind them.
        fill()
    }

    private fun fill() {
        themes = store.all()

        val labels = themes.map { theme ->
            val note = if (theme.builtIn) theme.subtitle else getString(R.string.theme_from_file)
            if (note.isEmpty()) theme.name else "${theme.name} - $note"
        }

        settling = true
        binding.themeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val current = themes.indexOfFirst { it.id == settings.consoleTheme }.coerceAtLeast(0)
        binding.themeSpinner.setSelection(current)
        settling = false

        showPreview(themes.getOrNull(current) ?: ConsoleTheme.DEFAULT)
    }

    /** A working miniature of the skin, built the same way the home screen builds itself. */
    private fun showPreview(theme: ConsoleTheme) {
        binding.themeNote.text = when {
            !theme.builtIn -> getString(R.string.theme_from_file)
            theme.subtitle.isNotEmpty() -> theme.subtitle
            else -> ""
        }

        binding.themePreview.removeAllViews()
        binding.themePreview.background = store.background(theme) ?: ConsoleSkin.backdrop(theme)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // The status strip, for skins that have one. It is half of what separates one light skin from
        // Lite, and leaving it out of the preview hid exactly that difference.
        ConsoleSkin.buildStatusBar(this, theme, getString(R.string.app_name), 80)?.let {
            column.addView(
                it,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            for (glyph in listOf("▣", "⇅", "◐", "▶")) {
                addView(TextView(this@ThemesActivity).apply {
                    text = glyph
                    gravity = Gravity.CENTER
                    setTextColor(theme.tileGlyph)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    background = ConsoleSkin.tileFace(this@ThemesActivity, theme)
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                        .apply { rightMargin = dp(6) }
                })
            }
        })

        binding.themePreview.addView(column)
    }

    /**
     * Create the themes folder and say where it is.
     *
     * Deliberately not done at startup -- an app that makes directories on somebody's storage
     * before being asked is a rude app. Opening this screen is the moment it becomes useful.
     */
    private fun showFolder() {
        val made = store.ensureFolder()

        binding.folderPath.text = if (made) {
            getString(R.string.theme_folder_ready, store.folder.path)
        } else {
            getString(R.string.theme_folder_denied)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
