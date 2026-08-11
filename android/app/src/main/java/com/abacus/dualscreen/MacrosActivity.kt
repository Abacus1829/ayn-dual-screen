package com.abacus.dualscreen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityMacrosBinding

/**
 * Build a pad of floating buttons, and keep as many sets of them as you like.
 *
 * The buttons are positioned by dragging them where they'll actually be used rather than by typing
 * coordinates here — a macro pad is a physical thing, and the only useful test of a layout is whether
 * your thumb reaches it.
 */
class MacrosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMacrosBinding
    private lateinit var settings: Settings
    private lateinit var store: MacroStore

    private var suppressSpinner = false
    private var moving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMacrosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = MacroStore(this)

        binding.backButton.setOnClickListener { finish() }
        binding.addMacro.setOnClickListener { edit(null) }
        binding.newProfile.setOnClickListener { newProfile() }
        binding.deleteProfile.setOnClickListener { deleteProfile() }
        binding.padButton.setOnClickListener { togglePad() }
        binding.moveButton.setOnClickListener { toggleMove() }
        binding.overlayButton.setOnClickListener {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        buildProfiles()
        buildList()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        showState()
    }

    /*********
     * Profiles
     *********/
    private fun buildProfiles() {
        val names = store.profiles.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        suppressSpinner = true
        binding.profileSpinner.adapter = adapter
        binding.profileSpinner.setSelection(store.activeIndex.coerceIn(0, (names.size - 1).coerceAtLeast(0)))
        suppressSpinner = false

        binding.profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinner) return
                store.activeIndex = position
                buildList()
                refreshPad()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun newProfile() {
        val field = EditText(this).apply {
            hint = getString(R.string.macros_profile_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.macros_new_profile)
            .setView(field)
            .setPositiveButton(R.string.macros_save) { _, _ ->
                val name = field.text.toString().trim().ifBlank { getString(R.string.macros_untitled) }
                val all = store.profiles
                all += MacroProfile(name, mutableListOf())
                store.profiles = all
                store.activeIndex = all.size - 1
                buildProfiles()
                buildList()
                refreshPad()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteProfile() {
        val all = store.profiles
        if (all.size <= 1) {
            // keeping one is simpler than handling an empty pad everywhere downstream
            AlertDialog.Builder(this)
                .setMessage(R.string.macros_last_profile)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val index = store.activeIndex
        AlertDialog.Builder(this)
            .setTitle(R.string.macros_delete_profile)
            .setMessage(getString(R.string.macros_delete_confirm, all[index].name))
            .setPositiveButton(R.string.macros_delete) { _, _ ->
                all.removeAt(index)
                store.profiles = all
                store.activeIndex = 0
                buildProfiles()
                buildList()
                refreshPad()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /*********
     * Buttons
     *********/
    private fun buildList() {
        binding.macroList.removeAllViews()
        val profile = store.active

        if (profile.macros.isEmpty()) {
            binding.macroList.addView(TextView(this).apply {
                text = getString(R.string.macros_empty)
                setTextColor(getColor(R.color.text_faint))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }

        for (macro in profile.macros) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = Appearance.panel(
                    this@MacrosActivity, settings,
                    getColor(R.color.card_hi), getColor(R.color.edge)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                setOnClickListener { edit(macro) }
            }

            row.addView(TextView(this).apply {
                text = macro.label
                setTextColor(Appearance.accentOf(settings))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                width = dp(46)
            })

            row.addView(TextView(this).apply {
                text = describe(macro)
                setTextColor(getColor(R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(8), 0, dp(8), 0)
            })

            row.addView(Button(this).apply {
                text = getString(R.string.macros_delete)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(getColor(R.color.text_faint))
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = Appearance.panel(
                    this@MacrosActivity, settings, getColor(R.color.card), getColor(R.color.edge)
                )
                setOnClickListener { remove(macro) }
            })

            binding.macroList.addView(row)
        }
    }

    private fun describe(macro: Macro): String = when (macro.kind) {
        Macro.Kind.TEXT -> getString(R.string.macros_kind_text, macro.payload)
        Macro.Kind.KEY -> getString(R.string.macros_kind_key, macro.payload)
        Macro.Kind.APP -> getString(R.string.macros_kind_app, macro.payload)
        Macro.Kind.TOOL -> getString(
            R.string.macros_kind_tool,
            Tool.byId(macro.payload)?.let { getString(it.label) } ?: macro.payload
        )
    }

    private fun remove(macro: Macro) {
        val profile = store.active
        profile.macros.removeAll { it.id == macro.id }
        store.save(profile)
        buildList()
        refreshPad()
    }

    /**
     * Add or change one button.
     *
     * Built in code rather than as a layout because the payload field has to change meaning with the
     * kind — free text for a snippet, a fixed list for a key or a tool — and one dialog that reshapes
     * itself beats four near-identical layouts.
     */
    private fun edit(existing: Macro?) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }

        val label = EditText(this).apply {
            hint = getString(R.string.macros_label_hint)
            setText(existing?.label.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val kinds = Macro.Kind.entries
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MacrosActivity, android.R.layout.simple_spinner_item,
                kinds.map { getString(kindLabel(it)) }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(kinds.indexOf(existing?.kind ?: Macro.Kind.TEXT))
        }

        val payload = EditText(this).apply {
            hint = getString(R.string.macros_payload_hint)
            setText(existing?.payload.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val choices = Spinner(this)

        val sizeLabel = TextView(this).apply {
            text = getString(R.string.macros_size)
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        }
        val sizeBar = SeekBar(this).apply {
            max = 60
            progress = ((existing?.size ?: 64) - 44).coerceIn(0, 60)
        }

        /** Show the free-text field or the fixed list, depending on what kind is selected. */
        fun applyKind() {
            val kind = kinds[kindSpinner.selectedItemPosition]
            val fixed = when (kind) {
                Macro.Kind.KEY -> Macro.KEYS.map { it.first }
                Macro.Kind.TOOL -> Tool.entries.map { it.id }
                else -> null
            }

            payload.visibility = if (fixed == null) View.VISIBLE else View.GONE
            choices.visibility = if (fixed == null) View.GONE else View.VISIBLE

            if (fixed != null) {
                choices.adapter = ArrayAdapter(
                    this@MacrosActivity, android.R.layout.simple_spinner_item,
                    if (kind == Macro.Kind.TOOL) Tool.entries.map { getString(it.label) } else fixed
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                val at = fixed.indexOf(existing?.payload).takeIf { it >= 0 } ?: 0
                choices.setSelection(at)
            }
        }

        kindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                applyKind()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        body.addView(label)
        body.addView(kindSpinner)
        body.addView(payload)
        body.addView(choices)
        body.addView(sizeLabel)
        body.addView(sizeBar)
        applyKind()

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.macros_add else R.string.macros_edit)
            .setView(body)
            .setPositiveButton(R.string.macros_save) { _, _ ->
                val kind = kinds[kindSpinner.selectedItemPosition]
                val value = when (kind) {
                    Macro.Kind.KEY -> Macro.KEYS.getOrNull(choices.selectedItemPosition)?.first.orEmpty()
                    Macro.Kind.TOOL -> Tool.entries.getOrNull(choices.selectedItemPosition)?.id.orEmpty()
                    else -> payload.text.toString()
                }

                val profile = store.active
                if (existing == null) {
                    profile.macros += Macro(
                        id = MacroStore.id(),
                        label = label.text.toString().ifBlank { "?" },
                        kind = kind,
                        payload = value,
                        // new buttons stack down the left edge, out of the way of most UI
                        x = 0.06f,
                        y = 0.2f + 0.1f * (profile.macros.size % 6),
                        size = sizeBar.progress + 44
                    )
                } else {
                    existing.label = label.text.toString().ifBlank { "?" }
                    existing.kind = kind
                    existing.payload = value
                    existing.size = sizeBar.progress + 44
                }

                store.save(profile)
                buildList()
                refreshPad()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun kindLabel(kind: Macro.Kind) = when (kind) {
        Macro.Kind.TEXT -> R.string.macros_kind_text_name
        Macro.Kind.KEY -> R.string.macros_kind_key_name
        Macro.Kind.APP -> R.string.macros_kind_app_name
        Macro.Kind.TOOL -> R.string.macros_kind_tool_name
    }

    /*********
     * The pad
     *********/
    private fun togglePad() {
        if (MacroOverlayService.running) {
            startService(Intent(this, MacroOverlayService::class.java).setAction(MacroOverlayService.ACTION_STOP))
            moving = false
        } else {
            if (!canOverlay()) {
                showState()
                return
            }
            val intent = Intent(this, MacroOverlayService::class.java)
                .putExtra(MacroOverlayService.EXTRA_EDITING, moving)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
        binding.root.postDelayed({ showState() }, 400)
    }

    private fun toggleMove() {
        moving = !moving
        if (MacroOverlayService.running) {
            startService(
                Intent(this, MacroOverlayService::class.java)
                    .setAction(MacroOverlayService.ACTION_EDIT)
                    .putExtra(MacroOverlayService.EXTRA_EDITING, moving)
            )
        }
        showState()
    }

    /** Push a changed profile out to a pad that's already up. */
    private fun refreshPad() {
        if (!MacroOverlayService.running) return
        startService(
            Intent(this, MacroOverlayService::class.java)
                .setAction(MacroOverlayService.ACTION_EDIT)
                .putExtra(MacroOverlayService.EXTRA_EDITING, moving)
        )
    }

    private fun showState() {
        val overlay = canOverlay()
        binding.overlayButton.visibility = if (overlay) View.GONE else View.VISIBLE

        val up = MacroOverlayService.running
        binding.padButton.text = getString(if (up) R.string.macros_hide else R.string.macros_show)
        binding.moveButton.text = getString(if (moving) R.string.macros_move_done else R.string.macros_move)
        binding.moveButton.isEnabled = up

        binding.padStatus.text = when {
            !overlay -> getString(R.string.macros_need_overlay)
            up && moving -> getString(R.string.macros_state_moving)
            up -> getString(R.string.macros_state_up)
            else -> getString(R.string.macros_state_down)
        }
    }

    private fun canOverlay(): Boolean = AndroidSettings.canDrawOverlays(this)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
