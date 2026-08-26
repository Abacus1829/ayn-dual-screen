package com.abacus.dualscreen

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityMacroBuilderBinding
import com.abacus.dualscreen.macro.MacroRunner
import com.abacus.dualscreen.macro.MacroScript
import com.abacus.dualscreen.macro.MacroScriptStore

/**
 * The list of macros.
 *
 * Separate from the macro pad, which is a set of buttons in positions. A macro here is a sequence you
 * can run from anywhere, put on a button, or hand to somebody else with a layout.
 */
class MacroBuilderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMacroBuilderBinding
    private lateinit var settings: Settings
    private lateinit var store: MacroScriptStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMacroBuilderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = MacroScriptStore(this)

        com.abacus.dualscreen.ui.Nav.back(this, binding.backButton)
        binding.newButton.setOnClickListener { create() }

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        binding.macroList.removeAllViews()
        val scripts = store.scripts

        binding.builderStatus.text =
            resources.getQuantityString(R.plurals.builder_count, scripts.size, scripts.size)

        if (scripts.isEmpty()) {
            binding.macroList.addView(TextView(this).apply {
                text = getString(R.string.builder_empty)
                setTextColor(getColor(R.color.text_faint))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        for (script in scripts) binding.macroList.addView(row(script))
    }

    private fun row(script: MacroScript): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = Appearance.panel(
                this@MacroBuilderActivity, settings,
                getColor(R.color.card_hi), getColor(R.color.edge)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            setOnClickListener { edit(script.id) }
            setOnLongClickListener { actions(script); true }
        }

        // Reachable with the stick, not only with a thumb: a LinearLayout with a click
        // listener is clickable and not focusable, so a D-pad walks straight past it.
        com.abacus.dualscreen.ui.Focus.reachable(row, Appearance.accentOf(settings))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        column.addView(TextView(this).apply {
            text = script.name
            setTextColor(Appearance.accentOf(settings))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        column.addView(TextView(this).apply {
            text = script.summary()
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        row.addView(column)

        row.addView(small(getString(R.string.editor_run)) { run(script) })
        row.addView(small("⋯") { actions(script) })

        return row
    }

    private fun small(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(getColor(R.color.text_dim))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = Appearance.panel(
            this@MacroBuilderActivity, settings, getColor(R.color.card), getColor(R.color.edge)
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private fun create() {
        val script = MacroScript(
            id = MacroScript.newId(),
            name = store.uniqueName(getString(R.string.builder_new_name)),
        )
        store.save(script)
        edit(script.id)
    }

    private fun edit(id: String) {
        startActivity(Intent(this, MacroEditActivity::class.java).putExtra(MacroEditActivity.EXTRA_ID, id))
    }

    private fun run(script: MacroScript) {
        if (script.actions.isEmpty()) {
            toast(getString(R.string.builder_empty_run))
            return
        }

        MacroRunner.run(this, script)
        toast(getString(R.string.builder_running, script.name))
    }

    private fun actions(script: MacroScript) {
        val options = arrayOf(
            getString(R.string.editor_run),
            getString(R.string.profiles_edit),
            getString(R.string.notes_rename),
            getString(R.string.profiles_duplicate),
            getString(R.string.profiles_delete),
        )

        AlertDialog.Builder(this)
            .setTitle(script.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> run(script)
                    1 -> edit(script.id)
                    2 -> rename(script)
                    3 -> {
                        store.duplicate(script.id)
                        build()
                    }
                    4 -> confirmDelete(script)
                }
            }
            .show()
    }

    private fun rename(script: MacroScript) {
        val field = EditText(this).apply {
            setText(script.name)
            setSelection(script.name.length)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.notes_rename)
            .setView(field)
            .setPositiveButton(R.string.notes_save) { _, _ ->
                script.name = field.text.toString().trim().ifBlank { script.name }
                store.save(script)
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(script: MacroScript) {
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_delete)
            .setMessage(getString(R.string.builder_delete_confirm, script.name))
            .setPositiveButton(R.string.profiles_delete) { _, _ ->
                store.delete(script.id)
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
