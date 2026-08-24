package com.abacus.dualscreen

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.connect.Awake
import com.abacus.dualscreen.connect.DisplayChoice
import com.abacus.dualscreen.connect.Orientation
import com.abacus.dualscreen.databinding.ActivitySettingsBinding
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Nav

/**
 * Everything the app remembers, in one place.
 *
 * The settings were spread across four screens — some on the home page, some in Appearance, some
 * behind a menu on the saved-connections list. Each of those made sense where it was and none of
 * them was findable if you did not already know. This gathers them.
 *
 * It does not own any of them. Every switch here writes the same preference the original screen
 * writes, and the screens that own a whole subject — appearance, saved connections, control profiles
 * — are linked rather than duplicated, because two editors for one thing is how they drift apart.
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
        binding.settingsList.removeAllViews()

        section(R.string.settings_connection)
        toggle(R.string.opt_autodetect, R.string.opt_autodetect_detail, settings.autoDetect) {
            settings.autoDetect = it
        }
        toggle(R.string.opt_auto_switch, R.string.opt_auto_switch_detail, settings.autoSwitchGame) {
            settings.autoSwitchGame = it
        }
        toggle(R.string.opt_reconnect, R.string.opt_reconnect_detail, settings.autoReconnect) {
            settings.autoReconnect = it
        }
        toggle(R.string.opt_remember_display, R.string.opt_remember_display_detail, settings.rememberDisplay) {
            settings.rememberDisplay = it
        }
        link(R.string.settings_saved_connections) {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }

        section(R.string.settings_session)
        choice(
            R.string.profile_opens_on,
            DisplayChoice.entries.map { displayLabel(it) },
            DisplayChoice.byId(settings.displayChoice).ordinal,
        ) { settings.displayChoice = DisplayChoice.entries[it].id }

        choice(
            R.string.profile_orientation,
            Orientation.entries.map { orientationLabel(it) },
            Orientation.byId(settings.orientation).ordinal,
        ) { settings.orientation = Orientation.entries[it].id }

        choice(
            R.string.profile_awake,
            Awake.entries.map { awakeLabel(it) },
            Awake.byId(settings.awakeMode).ordinal,
        ) {
            settings.awakeMode = Awake.entries[it].id
            // Kept in step, because the older connect screen still reads the boolean.
            settings.keepAwake = settings.awakeMode != Awake.NEVER.id
        }

        toggle(R.string.profiles_show_controls, 0, settings.showControls) {
            settings.showControls = it
        }

        /*
         * The one that decides whether a game keeps working while you use the app.
         *
         * Here rather than buried in the session, because somebody whose controller has stopped
         * responding in the game will come looking for it in Settings.
         */
        toggle(
            R.string.opt_keep_game_focus,
            R.string.opt_keep_game_focus_detail,
            settings.keepGameFocus,
        ) { settings.keepGameFocus = it }

        /*
         * Updates, near the top rather than buried.
         *
         * This app is installed by downloading an APK, so the update path is the only one there is
         * — there is no store quietly doing it in the background. Somebody who wants to know
         * whether they are current should not have to hunt for the answer.
         */
        section(R.string.settings_updates)
        link(R.string.settings_check_updates) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }

        /*
         * Developer tools, listed plainly rather than hidden behind a tap-seven-times gesture.
         *
         * This app is sideloaded from a repository by the people who work on it, and the questions
         * it answers — what this device sends, what it calls its displays, how to reach it without a
         * cable — are asked by whoever is holding the handheld when something misbehaves.
         */
        link(R.string.settings_developer) {
            startActivity(Intent(this, DeveloperActivity::class.java))
        }

        /*
         * The same screen the first run shows, minus the first-run wording.
         *
         * One place to see what has been granted and what each one turned on — which is the thing
         * that was missing while every permission lived on the screen that happened to need it.
         */
        link(R.string.settings_permissions) {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        section(R.string.settings_look)
        link(R.string.settings_appearance) {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        link(R.string.settings_themes) {
            startActivity(Intent(this, ThemesActivity::class.java))
        }

        section(R.string.settings_controls)
        link(R.string.settings_macros) {
            startActivity(Intent(this, MacrosActivity::class.java))
        }
        link(R.string.settings_layouts) {
            startActivity(Intent(this, LayoutEditorActivity::class.java))
        }
        link(R.string.settings_keyboard) {
            startActivity(Intent(this, KeyboardActivity::class.java))
        }

        section(R.string.settings_tools)
        link(R.string.settings_ftp) {
            startActivity(Intent(this, FtpActivity::class.java))
        }
        link(R.string.settings_dashboard) {
            startActivity(Intent(this, WidgetsActivity::class.java))
        }

        /*
         * Only listed once the feature has been found.
         *
         * A "Game Codes" row on a device where it has never been unlocked would give away that there
         * is something to unlock, which is the one thing the feature must not do.
         */
        if (com.abacus.dualscreen.codes.CodeSettings(this).visible) {
            link(R.string.tool_game_codes) {
                startActivity(Intent(this, GameCodesActivity::class.java))
            }
        }
    }

    // ── the pieces ──────────────────────────────────────────────────────────

    private fun section(title: Int) {
        binding.settingsList.addView(TextView(this).apply {
            setText(title)
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(2), dp(16), 0, dp(6))
        })
    }

    private fun toggle(title: Int, detail: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val row = card()

        row.addView(CheckBox(this).apply {
            setText(title)
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = initial
            setOnCheckedChangeListener { view, on ->
                Feedback.tap(view)
                onChange(on)
            }
        })

        if (detail != 0) {
            row.addView(TextView(this).apply {
                setText(detail)
                setTextColor(getColor(R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(2), 0, 0, 0)
            })
        }

        binding.settingsList.addView(row)
    }

    /** A labelled dropdown. The listener is attached after the initial selection, not before. */
    private fun choice(title: Int, labels: List<String>, selected: Int, onChange: (Int) -> Unit) {
        val row = card()

        row.addView(TextView(this).apply {
            setText(title)
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_item, labels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selected.coerceIn(0, labels.size - 1))
        }

        // Posted, because a Spinner delivers its initial selection asynchronously and attaching the
        // listener directly would have it fire once for a choice nobody made.
        spinner.post {
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos != selected) onChange(pos)
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
            }
        }

        row.addView(spinner)
        binding.settingsList.addView(row)
    }

    /** A row that opens the screen which actually owns the subject. */
    private fun link(title: Int, onClick: () -> Unit) {
        val row = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                Feedback.tap(it)
                onClick()
            }
        }

        row.addView(TextView(this).apply {
            setText(title)
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        row.addView(TextView(this).apply {
            text = "›"
            setTextColor(Appearance.accentOf(settings))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })

        binding.settingsList.addView(row)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = Appearance.panel(
            this@SettingsActivity, settings, getColor(R.color.card), getColor(R.color.edge)
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
