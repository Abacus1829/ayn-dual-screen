package com.abacus.dualscreen

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.theme.ConsoleSkin
import com.abacus.dualscreen.theme.ConsoleTheme
import com.abacus.dualscreen.theme.ThemeStore
import com.abacus.dualscreen.databinding.ActivityThemesBinding

/**
 * Pick a console skin, or find out how to write one.
 *
 * Every entry shows a small live preview built from the theme itself rather than a screenshot —
 * three tiles on the theme's own background, in its own colours. That matters most for user themes,
 * which have no screenshot anybody could have shipped, and it means a preview can never drift out
 * of date with what the skin actually does.
 */
class ThemesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemesBinding
    private lateinit var settings: Settings
    private lateinit var store: ThemeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = ThemeStore(this)

        binding.backButton.setOnClickListener { finish() }
        binding.folderButton.setOnClickListener { showFolder() }

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on every return: someone may have dropped a theme file in over FTP while the
        // screen sat open behind them.
        buildList()
    }

    private fun buildList() {
        binding.themeList.removeAllViews()

        for (theme in store.all()) {
            binding.themeList.addView(row(theme))
        }
    }

    private fun row(theme: ConsoleTheme): LinearLayout {
        val chosen = settings.consoleTheme == theme.id

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ConsoleSkin.tileFace(this@ThemesActivity, theme)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }

            setOnClickListener {
                settings.consoleTheme = theme.id
                Toast.makeText(context, getString(R.string.theme_applied, theme.name), Toast.LENGTH_SHORT).show()
                buildList()
            }

            // Name, and where it came from. A user theme says so, because "why is this one
            // different" is the first question when one misbehaves.
            addView(TextView(context).apply {
                text = if (chosen) "● ${theme.name}" else theme.name
                setTextColor(theme.tileLabel)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })

            val note = listOfNotNull(
                theme.subtitle.ifEmpty { null },
                if (theme.builtIn) null else getString(R.string.theme_from_file),
            ).joinToString(" · ")

            if (note.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = note
                    setTextColor(theme.tileGlyph)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                })
            }

            addView(preview(theme))
        }
    }

    /** Three tiles on the theme's own field — the smallest thing that shows what it looks like. */
    private fun preview(theme: ConsoleTheme): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ConsoleSkin.backdrop(theme)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }

            for (glyph in listOf("▣", "⇅", "▶")) {
                addView(TextView(context).apply {
                    text = glyph
                    gravity = Gravity.CENTER
                    setTextColor(theme.tileGlyph)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    background = ConsoleSkin.tileFace(this@ThemesActivity, theme)
                    layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                        rightMargin = dp(8)
                    }
                })
            }
        }

    /**
     * Create the themes folder and say where it is.
     *
     * Deliberately not done at startup — an app that makes directories on somebody's storage before
     * being asked is a rude app. Opening this screen is the moment it becomes useful.
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
