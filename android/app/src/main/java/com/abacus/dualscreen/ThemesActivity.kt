package com.abacus.dualscreen

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
 * Every entry carries a small sample drawn from the theme itself rather than a screenshot: its own
 * artwork with two tiles on it. That matters most for user themes, which have no screenshot anybody
 * could have shipped, and it means a sample can never drift out of date with what the skin does.
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

    /**
     * One row: a small sample of the theme on the left, its name on the right.
     *
     * Compact and uniform on purpose. The first version drew each row using the theme's own tile
     * style, which meant the Vita's 64dp corner radius turned its row into a lozenge and the PSP's
     * transparent tile face made its row vanish — nine of those made the list a mess and far taller
     * than the screen. A picker should be legible at a glance; the sample is where the theme gets
     * to look like itself.
     */
    private fun row(theme: ConsoleTheme): LinearLayout {
        val chosen = settings.consoleTheme == theme.id

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            tag = if (chosen) "accentEdge" else "card"      // Appearance paints these
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(5) }

            setOnClickListener {
                settings.consoleTheme = theme.id
                Toast.makeText(context, getString(R.string.theme_applied, theme.name), Toast.LENGTH_SHORT).show()
                buildList()
            }

            addView(sample(theme))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(10) }

                addView(TextView(context).apply {
                    text = if (chosen) "● ${theme.name}" else theme.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })

                val note = listOfNotNull(
                    theme.subtitle.ifEmpty { null },
                    if (theme.builtIn) null else getString(R.string.theme_from_file),
                ).joinToString(" · ")

                if (note.isNotEmpty()) {
                    addView(TextView(context).apply {
                        text = note
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        alpha = 0.7f
                    })
                }
            })
        }
    }

    /**
     * A postage stamp of the theme: its real artwork, with two tiles on top.
     *
     * Fixed size, so every row is the same height whatever the theme asks for — the tiles inside
     * are scaled to the stamp rather than to the theme's own dimensions.
     */
    private fun sample(theme: ConsoleTheme): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = store.background(theme) ?: ConsoleSkin.backdrop(theme)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(34))

            for (glyph in listOf("▣", "⇅")) {
                addView(TextView(context).apply {
                    text = glyph
                    gravity = Gravity.CENTER
                    setTextColor(theme.tileGlyph)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    background = ConsoleSkin.tileFace(this@ThemesActivity, theme)
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                        .apply { rightMargin = dp(3) }
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
