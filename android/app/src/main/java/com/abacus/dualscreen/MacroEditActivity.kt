package com.abacus.dualscreen

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityMacroEditBinding
import com.abacus.dualscreen.macro.MacroAction
import com.abacus.dualscreen.macro.MacroRunner
import com.abacus.dualscreen.macro.MacroScript
import com.abacus.dualscreen.macro.MacroScriptStore

/**
 * The steps in one macro.
 *
 * Reordering is up/down buttons rather than drag-and-drop. On a handheld, dragging a row inside a
 * scrolling list is the fiddliest interaction there is, and a macro is usually four steps — two taps
 * to move one is faster than getting a drag right once.
 */
class MacroEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMacroEditBinding
    private lateinit var settings: Settings
    private lateinit var store: MacroScriptStore
    private lateinit var script: MacroScript

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMacroEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = MacroScriptStore(this)

        val found = store.byId(intent.getStringExtra(EXTRA_ID))
        if (found == null) {
            finish()
            return
        }
        script = found

        binding.nameField.setText(script.name)
        com.abacus.dualscreen.ui.Nav.back(this, binding.backButton)
        binding.addButton.setOnClickListener { addStep() }
        binding.runButton.setOnClickListener { runScript() }

        build()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
        com.abacus.dualscreen.control.ControlCenter.attach(this, settings)
    }

    override fun onPause() {
        super.onPause()
        if (!::script.isInitialized) return

        script.name = binding.nameField.text.toString().trim().ifBlank { script.name }
        store.save(script)
    }

    // ── the list ────────────────────────────────────────────────────────────

    private fun build() {
        binding.actionList.removeAllViews()

        binding.editorStatus.text =
            resources.getQuantityString(R.plurals.editor_steps, script.actions.size, script.actions.size)

        if (script.actions.isEmpty()) {
            binding.actionList.addView(TextView(this).apply {
                text = getString(R.string.editor_empty)
                setTextColor(getColor(R.color.text_faint))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        script.actions.forEachIndexed { index, action ->
            binding.actionList.addView(row(index, action))
        }
    }

    private fun row(index: Int, action: MacroAction): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = Appearance.panel(
                this@MacroEditActivity, settings,
                getColor(R.color.card_hi), getColor(R.color.edge)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }

            setOnClickListener { editStep(index) }
        }

        // Reachable with the stick, not only with a thumb: a LinearLayout with a click
        // listener is clickable and not focusable, so a D-pad walks straight past it.
        com.abacus.dualscreen.ui.Focus.reachable(row, Appearance.accentOf(settings))

        row.addView(TextView(this).apply {
            text = "${index + 1}"
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            width = dp(26)
        })

        row.addView(TextView(this).apply {
            text = MacroAction.describe(action)
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(6), 0, dp(6), 0)
        })

        row.addView(small("▲", index > 0) { move(index, -1) })
        row.addView(small("▼", index < script.actions.size - 1) { move(index, +1) })
        row.addView(small("✕", true) { remove(index) })

        return row
    }

    private fun small(label: String, enabled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.3f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(getColor(R.color.text_dim))
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = Appearance.panel(
            this@MacroEditActivity, settings, getColor(R.color.card), getColor(R.color.edge)
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(4) }
        setOnClickListener { onClick() }
    }

    // ── editing ─────────────────────────────────────────────────────────────

    private fun move(index: Int, by: Int) {
        val to = index + by
        if (to !in script.actions.indices) return

        val moved = script.actions.removeAt(index)
        script.actions.add(to, moved)
        store.save(script)
        build()
    }

    private fun remove(index: Int) {
        if (index !in script.actions.indices) return
        script.actions.removeAt(index)
        store.save(script)
        build()
    }

    private fun addStep() {
        val action = MacroAction(MacroAction.Kind.KEY_PRESS, value = "ENTER")
        script.actions += action
        store.save(script)
        build()
        editStep(script.actions.size - 1)
    }

    private fun runScript() {
        if (script.actions.isEmpty()) {
            toast(getString(R.string.builder_empty_run))
            return
        }

        MacroRunner.run(this, script)
        toast(getString(R.string.builder_running, script.name))
    }

    /**
     * One dialog for every kind of step.
     *
     * The fields shown change with the kind, because a delay has no key and a key press has no
     * milliseconds — offering all of them at once and hoping the user knows which apply is how
     * editors like this become unusable.
     */
    private fun editStep(index: Int) {
        val action = script.actions.getOrNull(index) ?: return

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val kinds = MacroAction.Kind.entries
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MacroEditActivity, android.R.layout.simple_spinner_item,
                kinds.map { kindLabel(it) },
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(kinds.indexOf(action.kind))
        }

        val keySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MacroEditActivity, android.R.layout.simple_spinner_item,
                MacroAction.KEYS.map { it.first },
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(MacroAction.KEYS.indexOfFirst { it.first == action.value }.coerceAtLeast(0))
        }

        val textField = EditText(this).apply {
            setText(action.value)
            hint = getString(R.string.step_value_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val numberField = EditText(this).apply {
            setText(action.number.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        val number2Field = EditText(this).apply {
            setText(action.number2.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        val keyLabel = label(getString(R.string.step_key))
        val textLabel = label(getString(R.string.step_value))
        val numberLabel = label("")
        val number2Label = label("")

        body.addView(label(getString(R.string.step_kind)))
        body.addView(kindSpinner)
        body.addView(keyLabel); body.addView(keySpinner)
        body.addView(textLabel); body.addView(textField)
        body.addView(numberLabel); body.addView(numberField)
        body.addView(number2Label); body.addView(number2Field)

        fun applyVisibility() {
            val kind = kinds[kindSpinner.selectedItemPosition]

            val needsKey = kind in setOf(
                MacroAction.Kind.KEY_PRESS, MacroAction.Kind.KEY_DOWN,
                MacroAction.Kind.KEY_UP, MacroAction.Kind.KEY_HOLD,
            )
            val needsText = kind in setOf(
                MacroAction.Kind.TEXT, MacroAction.Kind.GAME,
                MacroAction.Kind.APP, MacroAction.Kind.TOOL,
            )
            val needsNumber = kind in setOf(
                MacroAction.Kind.DELAY, MacroAction.Kind.KEY_HOLD,
                MacroAction.Kind.GAME, MacroAction.Kind.TAP,
            )
            val needsNumber2 = kind in setOf(MacroAction.Kind.GAME, MacroAction.Kind.TAP)

            keyLabel.visibility = if (needsKey) View.VISIBLE else View.GONE
            keySpinner.visibility = keyLabel.visibility
            textLabel.visibility = if (needsText) View.VISIBLE else View.GONE
            textField.visibility = textLabel.visibility
            numberLabel.visibility = if (needsNumber) View.VISIBLE else View.GONE
            numberField.visibility = numberLabel.visibility
            number2Label.visibility = if (needsNumber2) View.VISIBLE else View.GONE
            number2Field.visibility = number2Label.visibility

            textLabel.text = when (kind) {
                MacroAction.Kind.GAME -> getString(R.string.step_game_type)
                MacroAction.Kind.APP -> getString(R.string.step_package)
                MacroAction.Kind.TOOL -> getString(R.string.step_tool)
                else -> getString(R.string.step_value)
            }

            numberLabel.text = when (kind) {
                MacroAction.Kind.DELAY, MacroAction.Kind.KEY_HOLD -> getString(R.string.step_ms)
                MacroAction.Kind.GAME -> getString(R.string.step_index)
                else -> getString(R.string.step_x)
            }

            number2Label.text =
                if (kind == MacroAction.Kind.GAME) getString(R.string.step_to)
                else getString(R.string.step_y)
        }

        kindSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) =
                applyVisibility()

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }
        applyVisibility()

        AlertDialog.Builder(this)
            .setTitle(R.string.step_title)
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.notes_save) { _, _ ->
                val kind = kinds[kindSpinner.selectedItemPosition]
                action.kind = kind

                action.value = when (kind) {
                    MacroAction.Kind.KEY_PRESS, MacroAction.Kind.KEY_DOWN,
                    MacroAction.Kind.KEY_UP, MacroAction.Kind.KEY_HOLD ->
                        MacroAction.KEYS[keySpinner.selectedItemPosition].first
                    else -> textField.text.toString().trim()
                }

                action.number = numberField.text.toString().trim().toIntOrNull() ?: 0
                action.number2 = number2Field.text.toString().trim().toIntOrNull() ?: 0

                store.save(script)
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_dim))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun kindLabel(kind: MacroAction.Kind): String = getString(
        when (kind) {
            MacroAction.Kind.KEY_PRESS -> R.string.kind_key_press
            MacroAction.Kind.KEY_DOWN -> R.string.kind_key_down
            MacroAction.Kind.KEY_UP -> R.string.kind_key_up
            MacroAction.Kind.KEY_HOLD -> R.string.kind_key_hold
            MacroAction.Kind.TEXT -> R.string.kind_text
            MacroAction.Kind.DELAY -> R.string.kind_delay
            MacroAction.Kind.GAME -> R.string.kind_game
            MacroAction.Kind.TAP -> R.string.kind_tap
            MacroAction.Kind.APP -> R.string.kind_app
            MacroAction.Kind.TOOL -> R.string.kind_tool
        }
    )

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "id"
    }
}
