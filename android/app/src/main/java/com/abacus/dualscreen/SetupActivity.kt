package com.abacus.dualscreen

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivitySetupBinding
import com.abacus.dualscreen.setup.Permission
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Nav

/**
 * What the app would like, why, and what happens if you say no.
 *
 * Shown once on the first run, between the boot animation and the home screen, and reachable
 * afterwards from Settings. It exists because the alternative — asking at the moment each tool is
 * first opened — meant somebody could use this app for a week without ever learning that the macro
 * pad and the mirror were behind a permission they had refused in passing.
 *
 * The screen is built around one claim, and the claim is true: **none of this is required**. The
 * second screen works with nothing granted. So every row is an offer with a stated benefit and a
 * stated cost, "Skip" is a first-class answer, and finishing with nothing granted is a perfectly
 * good outcome rather than a failure the app nags about later.
 *
 * Rows are rebuilt on every resume, because the answer to most of them is given on a *system* page
 * that returns no result — the only way to know what happened is to look again.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var settings: Settings

    /** True when this is the first run, which changes the wording and what leaving does. */
    private var firstRun = false

    /** Runtime permissions we have actually prompted for, so a refusal can be told from silence. */
    private val asked = mutableSetOf<String>()

    private val prompt = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Whatever the answer, the rows are redrawn: a refusal turns the button into a way to the
        // app's settings page rather than one that would silently do nothing next time.
        build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        firstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)

        binding.doneButton.setOnClickListener {
            Feedback.tap(it)
            settings.setupDone = true
            finish()
        }
        binding.doneButton.setText(if (firstRun) R.string.setup_continue else R.string.action_done)
        binding.backButton.visibility = if (firstRun) View.GONE else View.VISIBLE
        Nav.back(this, binding.backButton)

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    override fun onPause() {
        super.onPause()
        // Leaving by any route counts as having seen it. Otherwise a first run that ends with the
        // home button reopens this screen forever.
        if (firstRun) settings.setupDone = true
    }

    // ── the rows ────────────────────────────────────────────────────────────

    private fun build() {
        binding.permissionList.removeAllViews()

        var granted = 0
        var offered = 0

        for (permission in Permission.OFFERED) {
            val state = stateOf(permission)
            if (state == Permission.State.NOT_APPLICABLE) continue

            offered++
            if (state == Permission.State.GRANTED) granted++
            binding.permissionList.addView(row(permission, state))
        }

        binding.summaryText.text = getString(R.string.setup_summary, granted, offered)
    }

    /**
     * The state, refined by what this screen has actually asked.
     *
     * [Permission.state] cannot tell "never asked" from "refused twice" — that needs an Activity and
     * a memory of having prompted. This has both.
     */
    private fun stateOf(permission: Permission): Permission.State {
        val base = permission.state(this)
        if (base != Permission.State.ASKABLE) return base

        val runtime = permission.runtime ?: return base
        if (runtime !in asked) return base

        return if (Permission.blocked(this, runtime)) Permission.State.BLOCKED else base
    }

    private fun row(permission: Permission, state: Permission.State): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Appearance.panel(
                this@SetupActivity,
                settings,
                getColor(R.color.card),
                if (state == Permission.State.GRANTED) Appearance.accentOf(settings)
                else getColor(R.color.edge),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        heading.addView(TextView(this).apply {
            setText(permission.title)
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        heading.addView(TextView(this).apply {
            setText(
                when (state) {
                    Permission.State.GRANTED -> R.string.setup_state_on
                    Permission.State.BLOCKED -> R.string.setup_state_blocked
                    else -> R.string.setup_state_off
                }
            )
            setTextColor(
                when (state) {
                    Permission.State.GRANTED -> Appearance.accentOf(settings)
                    else -> getColor(R.color.text_faint)
                }
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        card.addView(heading)

        card.addView(TextView(this).apply {
            setText(permission.unlocks)
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        })

        // The cost, always shown and never only on request. A permission screen that lists benefits
        // and hides what it is asking for is an advertisement.
        card.addView(TextView(this).apply {
            setText(permission.cost)
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, 0)
        })

        if (state != Permission.State.GRANTED) {
            card.addView(Button(this).apply {
                setText(
                    if (state == Permission.State.BLOCKED) R.string.setup_open_app_settings
                    else R.string.setup_allow
                )
                isAllCaps = false
                setTextColor(getColor(R.color.text))
                background = Appearance.actionButton(
                    this@SetupActivity, settings, Appearance.accentOf(settings)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
                setOnClickListener {
                    Feedback.tap(it)
                    ask(permission, state)
                }
            })
        }

        return card
    }

    /**
     * Ask, by whichever route this permission has.
     *
     * A refused runtime permission goes to the app's own settings page instead of prompting again:
     * Android will not show the prompt a third time, so a button that tried would do nothing at all
     * and look like a bug in this app rather than a decision the user already made.
     */
    private fun ask(permission: Permission, state: Permission.State) {
        val runtime = permission.runtime

        if (state == Permission.State.BLOCKED) {
            open(Permission.appSettings(this))
            return
        }

        if (runtime != null) {
            asked += runtime
            prompt.launch(runtime)
            return
        }

        val page = permission.settingsPage(this) ?: return
        open(page)
    }

    private fun open(intent: Intent) {
        val opened = runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess

        // Some builds carry no per-app page for a given permission. The general settings screen
        // always exists, and is better than a button that appears to do nothing.
        if (!opened) {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Feedback.failed(this, binding.root, getString(R.string.dev_no_settings_page))
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_FIRST_RUN = "first_run"

        /** The first-run pass: no way back, and the button says "continue" rather than "done". */
        fun firstRun(context: android.content.Context): Intent =
            Intent(context, SetupActivity::class.java).putExtra(EXTRA_FIRST_RUN, true)
    }
}
