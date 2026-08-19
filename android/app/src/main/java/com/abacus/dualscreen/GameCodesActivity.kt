package com.abacus.dualscreen

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.codes.CodeClient
import com.abacus.dualscreen.codes.CodeResult
import com.abacus.dualscreen.codes.CodeSettings
import com.abacus.dualscreen.codes.Category
import com.abacus.dualscreen.codes.GameCode
import com.abacus.dualscreen.codes.InputType as CodeInput
import com.abacus.dualscreen.databinding.ActivityGameCodesBinding
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Nav

/**
 * What the connected companion says it can do, grouped and run.
 *
 * Nothing here is invented. The list is whatever the companion advertised at `/codes`, so a mod with
 * the feature switched off produces an empty screen that says so rather than a page of buttons that
 * would silently fail — which is the difference between an honest UI and a decorative one.
 */
class GameCodesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameCodesBinding
    private lateinit var settings: Settings
    private lateinit var codes: CodeSettings

    private var client: CodeClient? = null
    private var gameId: String = ""
    private var loaded: List<GameCode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameCodesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        codes = CodeSettings(this)

        Nav.back(this, binding.backButton)
        binding.settingsButton.setOnClickListener { Feedback.tap(it); settingsDialog() }
        binding.typeButton.setOnClickListener { Feedback.tap(it); typeCode() }

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // ── loading ─────────────────────────────────────────────────────────────

    /**
     * Ask the connected companion what it offers.
     *
     * Off the main thread, because it is a network call. With no saved connection there is nothing
     * to ask and the screen says so — an empty list here is a state, not a failure.
     */
    private fun load() {
        binding.codeList.removeAllViews()
        say(getString(R.string.codes_loading))

        val profile = com.abacus.dualscreen.connect.ProfileStore(this).ordered()
            .firstOrNull { it.usable }

        if (profile == null) {
            say(getString(R.string.codes_no_connection))
            return
        }

        gameId = profile.preset
        val target = CodeClient(this, profile.url, gameId)
        client = target

        Thread {
            val found = target.catalogue()
            runOnUiThread {
                loaded = found
                build()
            }
        }.start()
    }

    private fun build() {
        binding.codeList.removeAllViews()

        if (!codes.enabled) {
            say(getString(R.string.codes_off_global))
            return
        }

        if (!codes.enabledFor(gameId)) {
            say(getString(R.string.codes_off_game))
            return
        }

        if (loaded.isEmpty()) {
            say(getString(R.string.codes_none))
            return
        }

        say(resources.getQuantityString(R.plurals.codes_count, loaded.size, loaded.size))

        // Grouped, in the enum's order, so the same game always reads the same way round.
        for (category in Category.entries) {
            val group = loaded.filter { it.category == category }
            if (group.isEmpty()) continue

            binding.codeList.addView(header(category.label))
            group.forEach { binding.codeList.addView(row(it)) }
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(getColor(R.color.text_faint))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(2), dp(14), 0, dp(6))
    }

    private fun row(code: GameCode): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(10), dp(14))
            background = Appearance.panel(
                this@GameCodesActivity, settings,
                getColor(R.color.card_hi),
                if (code.available) getColor(R.color.edge) else getColor(R.color.card),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            alpha = if (code.available) 1f else 0.5f
            isEnabled = code.available
            if (code.available) setOnClickListener { run(code) }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        column.addView(TextView(this).apply {
            text = if (code.icon.isBlank()) code.name else "${code.icon}  ${code.name}"
            setTextColor(Appearance.accentOf(settings))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })

        val detail = when {
            !code.available -> code.blocked
            code.description.isNotBlank() -> code.description
            else -> ""
        }

        if (detail.isNotBlank()) {
            column.addView(TextView(this).apply {
                text = detail
                setTextColor(getColor(R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(3), 0, 0)
            })
        }

        row.addView(column)

        // A toggle shows its state; everything else shows what pressing it will do.
        row.addView(TextView(this).apply {
            text = when (code.input) {
                CodeInput.TOGGLE -> getString(if (code.on) R.string.codes_on else R.string.codes_off)
                CodeInput.NONE -> getString(R.string.codes_run)
                else -> getString(R.string.codes_set)
            }
            setTextColor(
                if (code.input == CodeInput.TOGGLE && code.on) getColor(R.color.state_ok)
                else getColor(R.color.text_dim)
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), 0, dp(6), 0)
        })

        return row
    }

    // ── running one ─────────────────────────────────────────────────────────

    private fun run(code: GameCode) {
        if (code.confirm) {
            AlertDialog.Builder(this)
                .setTitle(code.name)
                .setMessage(getString(R.string.codes_confirm, code.name))
                .setPositiveButton(R.string.codes_run) { _, _ -> collect(code) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }

        collect(code)
    }

    /** Ask for whatever the code needs, then send it. A toggle needs nothing; a number does. */
    private fun collect(code: GameCode) {
        when (code.input) {
            CodeInput.NONE, CodeInput.TOGGLE -> send(code, null)

            CodeInput.PRESET, CodeInput.ITEM, CodeInput.ENTITY -> {
                if (code.choices.isEmpty()) {
                    // No list to choose from means free text is the only honest option.
                    askText(code, InputType.TYPE_CLASS_TEXT)
                    return
                }

                AlertDialog.Builder(this)
                    .setTitle(code.name)
                    .setItems(code.choices.toTypedArray()) { _, which -> send(code, code.choices[which]) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }

            CodeInput.NUMBER -> askText(code, InputType.TYPE_CLASS_NUMBER)
            CodeInput.TEXT -> askText(code, InputType.TYPE_CLASS_TEXT)
        }
    }

    private fun askText(code: GameCode, type: Int) {
        val field = EditText(this).apply {
            inputType = type
            setPadding(dp(16), dp(16), dp(16), dp(16))
            if (code.max > 0) hint = getString(R.string.codes_range, code.min, code.max)
        }

        AlertDialog.Builder(this)
            .setTitle(code.name)
            .setView(field)
            .setPositiveButton(R.string.codes_run) { _, _ ->
                val typed = field.text.toString().trim()
                if (typed.isEmpty()) return@setPositiveButton

                // Clamped here as a courtesy; the companion is still the authority and may refuse.
                val value = if (code.input == CodeInput.NUMBER && code.max > 0) {
                    typed.toIntOrNull()?.coerceIn(code.min, code.max)?.toString() ?: return@setPositiveButton
                } else {
                    typed
                }

                send(code, value)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Send it, and report what actually happened.
     *
     * A refusal is shown as a refusal. The companion is the authority on whether a code ran, and
     * dressing "the game said no" up as success would make the whole screen untrustworthy.
     */
    private fun send(code: GameCode, value: String?) {
        val target = client ?: return
        say(getString(R.string.codes_sending, code.name))

        Thread {
            val result = target.send(code, value)
            runOnUiThread {
                when (result) {
                    is CodeResult.Ok -> {
                        Feedback.success(binding.root)
                        say(getString(R.string.codes_done, code.name))
                        // A toggle's new state comes from the companion, not from a guess here.
                        load()
                    }

                    is CodeResult.Refused -> {
                        Feedback.failed(this, binding.root, result.reason)
                        say(result.reason)
                    }

                    is CodeResult.Failed -> {
                        Feedback.failed(this, binding.root, result.reason)
                        say(getString(R.string.codes_failed))
                    }
                }
            }
        }.start()
    }

    /** Typed codes, for anything a companion exposes that way. Matched against what it advertised. */
    private fun typeCode() {
        val field = EditText(this).apply {
            hint = getString(R.string.codes_type_hint)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.codes_type)
            .setView(field)
            .setPositiveButton(R.string.codes_run) { _, _ ->
                val typed = field.text.toString().trim()
                val match = loaded.firstOrNull {
                    it.secret.isNotBlank() && it.secret.equals(typed, ignoreCase = true)
                }

                if (match == null) Feedback.failed(this, binding.root, getString(R.string.codes_unknown))
                else run(match)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── settings ────────────────────────────────────────────────────────────

    /**
     * The two switches that matter, in the one place somebody would look for them.
     *
     * Turning the feature off entirely also relocks it, so the tile disappears and the hidden
     * sequence has to be found again — off should mean off, not hidden.
     */
    private fun settingsDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val global = android.widget.CheckBox(this).apply {
            text = getString(R.string.codes_enable_global)
            setTextColor(getColor(R.color.text))
            isChecked = codes.enabled
        }

        val perGame = android.widget.CheckBox(this).apply {
            text = getString(R.string.codes_enable_game)
            setTextColor(getColor(R.color.text))
            isChecked = codes.enabledFor(gameId)
            isEnabled = gameId.isNotBlank()
        }

        /**
         * Put the tab away without switching the feature off.
         *
         * The distinction matters: turning game codes off stops the app asking any companion about
         * them at all, and stops the hidden sequence working. This only takes the tile away —
         * everything is still here, and entering the sequence again brings it straight back with
         * your per-game switches as you left them.
         */
        val hide = android.widget.CheckBox(this).apply {
            text = getString(R.string.codes_hide_tab)
            setTextColor(getColor(R.color.text))
            isChecked = false
        }

        body.addView(global)
        body.addView(perGame)
        body.addView(hide)
        body.addView(TextView(this).apply {
            text = getString(R.string.codes_hide_detail)
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, 0)
        })
        body.addView(TextView(this).apply {
            text = getString(R.string.codes_settings_detail)
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(10), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.codes_settings)
            .setView(body)
            .setPositiveButton(R.string.notes_save) { _, _ ->
                codes.enabled = global.isChecked
                if (gameId.isNotBlank()) codes.setEnabledFor(gameId, perGame.isChecked)

                when {
                    // Off entirely: the feature stops existing, so the tile goes with it.
                    !global.isChecked -> {
                        codes.relock()
                        finish()
                    }

                    // Just put away. The feature stays on, so the sequence finds it again.
                    hide.isChecked -> {
                        codes.relock()
                        Feedback.success(binding.root)
                        Feedback.toast(this, getString(R.string.codes_hidden))
                        finish()
                    }

                    else -> load()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun say(message: String) {
        binding.codesStatus.text = message
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
