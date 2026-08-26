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
import com.abacus.dualscreen.codes.GameDetector
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

    /** A connection the user picked by hand, which outranks anything detection concludes. */
    private var manual: com.abacus.dualscreen.connect.ConnectionProfile? = null

    /** What is typed in the search box, lower-cased once rather than per row. */
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameCodesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        codes = CodeSettings(this)

        Nav.back(this, binding.backButton)
        binding.settingsButton.setOnClickListener { Feedback.tap(it); settingsDialog() }
        binding.typeButton.setOnClickListener { Feedback.tap(it); typeCode() }
        binding.rescanButton.setOnClickListener {
            Feedback.tap(it)
            // An explicit rescan drops any manual choice: the button means "look again", and
            // looking again while still pinned to a hand-picked connection would be a lie.
            manual = null
            load()
        }

        binding.searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(text: android.text.Editable?) {
                query = text?.toString()?.trim().orEmpty()
                build()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)

        com.abacus.dualscreen.control.ControlCenter.attach(this, settings)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // ── loading ─────────────────────────────────────────────────────────────

    /**
     * Work out what is running, then ask it what it can do.
     *
     * The old version took the first saved connection whose host and port were filled in and asked
     * that one — which is not the same as the game you are playing, and with three connections saved
     * it was usually the wrong one. [GameDetector] probes them all and answers with whichever is
     * actually running, preferring one with a save loaded.
     *
     * A manual choice, once made, wins until the screen is left: somebody who overrides the guess
     * has told us something the network cannot.
     */
    private fun load() {
        binding.codeList.removeAllViews()

        if (!codes.enabled) {
            detected(Feedback.State.IDLE, getString(R.string.codes_off_global))
            say(getString(R.string.codes_off_global))
            return
        }

        val chosen = manual
        if (chosen != null) {
            useProfile(chosen, getString(R.string.codes_detected_manual, chosen.name))
            return
        }

        detected(Feedback.State.BUSY, getString(R.string.codes_detecting))
        say(getString(R.string.codes_loading))

        GameDetector.detectAsync(this) { result ->
            when (result) {
                is GameDetector.Result.NothingSaved -> {
                    detected(Feedback.State.BAD, getString(R.string.codes_no_connection))
                    say(getString(R.string.codes_no_connection))
                }

                is GameDetector.Result.NothingRunning -> {
                    detected(Feedback.State.BAD, getString(R.string.codes_none_running))
                    say(getString(R.string.codes_none_running))
                    buildPicker()
                }

                is GameDetector.Result.Found -> {
                    val label = getString(result.game.label)
                    useProfile(
                        result.profile,
                        when {
                            result.place != null ->
                                getString(R.string.codes_detected_in, label, result.place)
                            result.inGame -> getString(R.string.codes_detected, label)
                            else -> getString(R.string.codes_detected_menu, label)
                        },
                        detectedGame = result.inGame,
                    )
                }
            }
        }
    }

    /** Point the screen at one connection and fetch its catalogue. */
    private fun useProfile(
        profile: com.abacus.dualscreen.connect.ConnectionProfile,
        status: String,
        detectedGame: Boolean = true,
    ) {
        detected(if (detectedGame) Feedback.State.OK else Feedback.State.BUSY, status)
        buildPicker()

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

    /**
     * The manual fallback, offered only when there is a choice to make.
     *
     * One saved connection and no picker: a dropdown with a single entry is furniture, not a
     * feature.
     */
    private fun buildPicker() {
        val profiles = com.abacus.dualscreen.connect.ProfileStore(this).ordered().filter { it.usable }
        if (profiles.size < 2) {
            binding.gamePicker.visibility = View.GONE
            return
        }

        val labels = listOf(getString(R.string.codes_auto)) + profiles.map { it.name }
        binding.gamePicker.visibility = View.VISIBLE
        binding.gamePicker.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val selected = manual?.let { profiles.indexOf(it) + 1 } ?: 0
        binding.gamePicker.setSelection(selected)

        // Posted, because a Spinner delivers its first selection asynchronously and would otherwise
        // fire for a choice nobody made.
        binding.gamePicker.post {
            binding.gamePicker.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        val wanted = if (position == 0) null else profiles.getOrNull(position - 1)
                        if (wanted == manual) return
                        manual = wanted
                        load()
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
        }
    }

    private fun detected(state: Feedback.State, message: String) =
        Feedback.say(binding.detectedText, binding.detectedDot, state, message)

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
            binding.searchField.visibility = View.GONE
            say(getString(R.string.codes_none))

            /*
             * An empty list is a screen somebody is looking at while confused, which makes this the
             * most important text on it rather than the least. It says which of the three reasons
             * applies — the mod has the feature off, the mod is older than the feature, or nothing
             * is running — because from the outside they are identical and only one is fixable here.
             */
            binding.codeList.addView(
                com.abacus.dualscreen.ui.Ui.empty(
                    this, settings,
                    glyph = "◌",
                    title = R.string.codes_empty_title,
                    detail = R.string.codes_empty_detail,
                    action = R.string.codes_rescan,
                ) {
                    manual = null
                    load()
                }
            )
            com.abacus.dualscreen.ui.Motion.enterChildren(binding.codeList)
            return
        }

        /*
         * Search appears once the list is long enough to scroll past what you wanted.
         *
         * A search box over six rows is clutter; over forty it is the only way anybody finds
         * anything. The threshold is a judgement, not a measurement, and it is here rather than
         * scattered through the drawing code so it can be changed in one place.
         */
        binding.searchField.visibility = if (loaded.size >= SEARCH_FROM) View.VISIBLE else View.GONE

        val matching = loaded.filter { matches(it) }
        if (matching.isEmpty()) {
            say(getString(R.string.codes_no_match, query))
            return
        }

        say(resources.getQuantityString(R.plurals.codes_count, matching.size, matching.size))

        /*
         * Favourites first, out of their categories.
         *
         * The point of a favourite is not having to know which category somebody filed it under, so
         * pinning it while also leaving it in place below would defeat the exercise. Each one
         * appears once, at the top.
         */
        val favourites = matching.filter { codes.isFavourite(gameId, it.id) }
        if (favourites.isNotEmpty()) {
            binding.codeList.addView(header(getString(R.string.codes_favourites)))
            favourites.forEach { binding.codeList.addView(row(it)) }
        }

        // Grouped, in the enum's order, so the same game always reads the same way round.
        for (category in Category.entries) {
            val group = matching.filter { it.category == category && it !in favourites }
            if (group.isEmpty()) continue

            binding.codeList.addView(header(category.label))
            group.forEach { binding.codeList.addView(row(it)) }
        }

        // Not while searching: re-animating the list on every keystroke would make typing feel like
        // wading, which is the opposite of what a search box is for.
        if (query.isBlank()) com.abacus.dualscreen.ui.Motion.enterChildren(binding.codeList)
    }

    /** Name, description and the typed code all count — people remember any of the three. */
    private fun matches(code: GameCode): Boolean {
        if (query.isBlank()) return true
        return code.name.contains(query, true) ||
            code.description.contains(query, true) ||
            code.secret.contains(query, true)
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
            if (code.available) {
                setOnClickListener { run(code) }
                com.abacus.dualscreen.ui.Motion.pressable(this, scale = 0.985f)
            }
        }

        /*
         * The star, first in the row and its own touch target.
         *
         * Left of the name rather than right, because the row itself runs the code when tapped and
         * a favourite control at the far edge of a full-width button is a mis-tap waiting to happen.
         * 44dp of its own, and it stops the tap from reaching the row underneath.
         */
        val favourite = codes.isFavourite(gameId, code.id)
        row.addView(TextView(this).apply {
            text = if (favourite) "★" else "☆"
            setTextColor(
                if (favourite) Appearance.accentOf(settings) else getColor(R.color.text_faint)
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
            setOnClickListener {
                Feedback.tap(it)
                codes.toggleFavourite(gameId, code.id)
                build()
            }
        })

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

        /*
         * A way in that does not depend on the hardware.
         *
         * The sequence needs this handheld to report its d-pad in a way the app recognises, and when
         * it does not, the feature is not hidden — it is unreachable, and from the outside those
         * look identical. The sequence is still the intended door; this is the one for somebody
         * whose device will not open it.
         */
        val show = android.widget.CheckBox(this).apply {
            text = getString(R.string.codes_show_tile)
            setTextColor(getColor(R.color.text))
            isChecked = codes.shown
        }

        body.addView(global)
        body.addView(perGame)
        body.addView(show)
        body.addView(TextView(this).apply {
            text = getString(R.string.codes_show_tile_detail)
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, dp(6))
        })
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
                codes.shown = show.isChecked
                if (gameId.isNotBlank()) codes.setEnabledFor(gameId, perGame.isChecked)

                when {
                    // Off entirely: the feature stops existing, so the tile goes with it.
                    !global.isChecked -> {
                        codes.relock()
                        codes.shown = false
                        finish()
                    }

                    // Just put away. The feature stays on, so the sequence finds it again.
                    // "Shown" has to go too, or the tile would be hidden and visible at once and
                    // the one that wins would be an accident of which line ran last.
                    hide.isChecked -> {
                        codes.relock()
                        codes.shown = false
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

    private companion object {
        /** Codes in the list before a search box is worth its space. */
        const val SEARCH_FROM = 8
    }
}
