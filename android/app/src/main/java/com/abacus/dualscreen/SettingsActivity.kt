package com.abacus.dualscreen

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.connect.Awake
import com.abacus.dualscreen.connect.DisplayChoice
import com.abacus.dualscreen.connect.Orientation
import com.abacus.dualscreen.databinding.ActivitySettingsBinding
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Motion
import com.abacus.dualscreen.ui.Nav
import com.abacus.dualscreen.ui.Ui

/**
 * Everything the app remembers, in one place — and only the things that are settings.
 *
 * ## What changed, and why it was worth changing
 *
 * This screen used to list **eight things that are also tiles on the home screen**: the FTP server,
 * the dashboard, macros, layouts, themes, the keyboard, game codes. Opening Settings and finding a
 * second copy of the app's navigation is exactly what made this feel like separate tools sharing an
 * icon rather than one application. A settings screen that is also a launcher is a settings screen
 * nobody trusts to hold the settings.
 *
 * So the rule now is: **Settings holds preferences. The home screen holds tools.** A tool appears
 * here only when the screen genuinely *is* its own settings — the keyboard, which has nothing but
 * settings — or when it is not on the home grid at all and would otherwise be unreachable.
 *
 * Two subjects were merged rather than listed twice:
 *
 * - **Themes** was a separate entry beside Appearance. They are one subject: how the app looks. It
 *   is reached from inside Appearance now.
 * - **Layouts** was a separate entry beside Macros. A layout is an arrangement of macros; it is
 *   reached from the macro screen that owns them.
 *
 * The order is deliberate and follows how often somebody needs each: what the app connects to, what
 * happens when it does, how it looks and sounds, then the things you set once.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        Nav.back(this, binding.backButton)

        build()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on return, because a linked screen may have changed something shown here.
        build()
    }

    private fun build() {
        val list = binding.settingsList
        list.removeAllViews()

        // ── what it connects to ─────────────────────────────────────────────
        list.add(Ui.section(this, R.string.settings_connection))
        list.add(
            Ui.toggle(this, settings, R.string.opt_autodetect, R.string.opt_autodetect_detail, settings.autoDetect) {
                settings.autoDetect = it
            }
        )
        list.add(
            Ui.toggle(this, settings, R.string.opt_auto_switch, R.string.opt_auto_switch_detail, settings.autoSwitchGame) {
                settings.autoSwitchGame = it
            }
        )
        list.add(
            Ui.toggle(this, settings, R.string.opt_reconnect, R.string.opt_reconnect_detail, settings.autoReconnect) {
                settings.autoReconnect = it
            }
        )
        list.add(
            Ui.link(this, settings, R.string.settings_saved_connections, glyph = "⇢") {
                startActivity(Intent(this, ProfilesActivity::class.java))
            }
        )

        // ── what happens on the second screen ───────────────────────────────
        list.add(Ui.section(this, R.string.settings_session))
        list.add(
            choice(
                R.string.profile_opens_on,
                DisplayChoice.entries.map { displayLabel(it) },
                DisplayChoice.byId(settings.displayChoice).ordinal,
            ) { settings.displayChoice = DisplayChoice.entries[it].id }
        )
        list.add(
            choice(
                R.string.profile_orientation,
                Orientation.entries.map { orientationLabel(it) },
                Orientation.byId(settings.orientation).ordinal,
            ) { settings.orientation = Orientation.entries[it].id }
        )
        list.add(
            choice(
                R.string.profile_awake,
                Awake.entries.map { awakeLabel(it) },
                Awake.byId(settings.awakeMode).ordinal,
            ) {
                settings.awakeMode = Awake.entries[it].id
                // Kept in step, because the older connect screen still reads the boolean.
                settings.keepAwake = settings.awakeMode != Awake.NEVER.id
            }
        )
        list.add(
            Ui.toggle(this, settings, R.string.profiles_show_controls, 0, settings.showControls) {
                settings.showControls = it
            }
        )
        /*
         * The one that decides whether a game keeps working while you use the app.
         *
         * Here rather than buried in the session, because somebody whose controller has stopped
         * responding in the game will come looking for it in Settings.
         */
        list.add(
            Ui.toggle(
                this, settings,
                R.string.opt_keep_game_focus, R.string.opt_keep_game_focus_detail,
                settings.keepGameFocus,
            ) { settings.keepGameFocus = it }
        )
        list.add(
            Ui.toggle(this, settings, R.string.opt_remember_display, R.string.opt_remember_display_detail, settings.rememberDisplay) {
                settings.rememberDisplay = it
            }
        )

        // ── how it looks, sounds and feels ──────────────────────────────────
        list.add(Ui.section(this, R.string.settings_look))
        list.add(
            Ui.link(this, settings, R.string.settings_appearance, R.string.settings_appearance_detail, "◈") {
                startActivity(Intent(this, AppearanceActivity::class.java))
            }
        )
        list.add(
            Ui.toggle(this, settings, R.string.settings_sounds, R.string.settings_sounds_detail, settings.sounds) {
                settings.sounds = it
                com.abacus.dualscreen.ui.Sounds.setEnabled(it)
                // Played immediately so the switch demonstrates itself rather than describing itself.
                if (it) Feedback.success(binding.root)
            }
        )
        list.add(
            Ui.toggle(this, settings, R.string.settings_haptics, R.string.settings_haptics_detail, settings.haptics) {
                settings.haptics = it
                Feedback.setHapticsEnabled(it)
                if (it) Feedback.success(binding.root)
            }
        )
        list.add(
            Ui.toggle(this, settings, R.string.settings_intro, R.string.settings_intro_detail, settings.bootAnimation) {
                settings.bootAnimation = it
            }
        )

        // ── controls ────────────────────────────────────────────────────────
        list.add(Ui.section(this, R.string.settings_controls))
        list.add(
            Ui.link(this, settings, R.string.settings_macros, R.string.settings_macros_detail, "⚙") {
                startActivity(Intent(this, MacrosActivity::class.java))
            }
        )
        list.add(
            Ui.link(this, settings, R.string.settings_keyboard, R.string.settings_keyboard_detail, "⌨") {
                startActivity(Intent(this, KeyboardActivity::class.java))
            }
        )

        // ── the things you set once ─────────────────────────────────────────
        list.add(Ui.section(this, R.string.settings_system))
        list.add(
            Ui.link(this, settings, R.string.settings_check_updates, R.string.settings_updates_detail, "⇩") {
                startActivity(Intent(this, UpdateActivity::class.java))
            }
        )
        list.add(
            Ui.link(this, settings, R.string.settings_permissions, R.string.settings_permissions_detail, "◈") {
                startActivity(Intent(this, SetupActivity::class.java))
            }
        )
        list.add(
            Ui.link(this, settings, R.string.settings_developer, R.string.settings_developer_detail, "⌥") {
                startActivity(Intent(this, DeveloperActivity::class.java))
            }
        )

        /*
         * Game codes, listed only once the feature has been found.
         *
         * The exception to "tools are not listed here": it is deliberately absent from the home grid
         * until it is unlocked, so for somebody who has unlocked it this is a reasonable place to
         * find it again. Listing it before then would give away that there is something to find.
         */
        if (com.abacus.dualscreen.codes.CodeSettings(this).visible) {
            list.add(
                Ui.link(this, settings, R.string.tool_game_codes, glyph = "◈") {
                    startActivity(Intent(this, GameCodesActivity::class.java))
                }
            )
        }

        // Says where the tools went, once, at the bottom. Somebody who came here looking for the FTP
        // server should be told rather than left to conclude it was removed.
        list.add(TextView(this).apply {
            setText(R.string.settings_tools_moved)
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(Ui.dp(this@SettingsActivity, 2), Ui.dp(this@SettingsActivity, 18), 0, Ui.dp(this@SettingsActivity, 8))
        })

        Motion.enterChildren(list)
    }

    private fun LinearLayout.add(view: View) = addView(view)

    /**
     * A labelled dropdown.
     *
     * Kept local rather than promoted into [Ui]: it is the only spinner-shaped setting in the app,
     * and a component with one caller is a component that has not earned its abstraction yet.
     */
    private fun choice(title: Int, labels: List<String>, selected: Int, onChange: (Int) -> Unit): View {
        val row = Ui.card(this, settings)

        row.addView(TextView(this).apply {
            setText(title)
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_item, labels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selected.coerceIn(0, labels.size - 1))
            minimumHeight = Ui.dp(this@SettingsActivity, 48)
        }

        // Posted, because a Spinner delivers its initial selection asynchronously and attaching the
        // listener directly would have it fire once for a choice nobody made.
        spinner.post {
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos == selected) return
                    Feedback.select(v)
                    onChange(pos)
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
            }
        }

        row.addView(spinner)
        return row
    }

    private fun displayLabel(choice: DisplayChoice) = getString(
        when (choice) {
            DisplayChoice.MAIN -> R.string.display_opt_main
            DisplayChoice.SECOND -> R.string.display_opt_second
            DisplayChoice.EXTERNAL -> R.string.display_opt_external
            DisplayChoice.AUTO -> R.string.display_opt_auto
            DisplayChoice.ASK -> R.string.display_opt_ask
        }
    )

    private fun orientationLabel(orientation: Orientation) = getString(
        when (orientation) {
            Orientation.AUTOMATIC -> R.string.orientation_auto
            Orientation.LANDSCAPE -> R.string.orientation_landscape
            Orientation.PORTRAIT -> R.string.orientation_portrait
        }
    )

    private fun awakeLabel(awake: Awake) = getString(
        when (awake) {
            Awake.ALWAYS -> R.string.awake_always
            Awake.CONNECTED -> R.string.awake_connected
            Awake.NEVER -> R.string.awake_never
        }
    )
}
