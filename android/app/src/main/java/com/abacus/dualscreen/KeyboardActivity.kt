package com.abacus.dualscreen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityKeyboardBinding

/**
 * Set the keyboard up and see what it will look like before committing to it.
 *
 * Android will not let an app enable its own input method — that has to be done by the user in Settings —
 * so the honest thing is to say where it stands and send them straight to the right page.
 */
class KeyboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeyboardBinding
    private lateinit var settings: Settings
    private var preview: KeyboardView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        binding.backButton.setOnClickListener { finish() }
        binding.enableButton.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.switchButton.setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }

        binding.sizeBar.max = 100
        binding.sizeBar.progress = settings.keyboardSize
        binding.sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.keyboardSize = value
                preview?.refresh()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        binding.hapticsCheck.isChecked = settings.keyboardHaptics
        binding.hapticsCheck.setOnCheckedChangeListener { _, on -> settings.keyboardHaptics = on }

        buildShapeRow()
        buildPreview()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        showStatus() // they may have just come back from the system settings page
    }

    private fun buildShapeRow() {
        binding.shapeRow.removeAllViews()
        for (split in listOf(true, false)) {
            val chosen = settings.keyboardSplit == split
            val button = Button(this).apply {
                text = getString(if (split) R.string.keyboard_split else R.string.keyboard_full)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(11), dp(4), dp(11))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
                background = Appearance.panel(
                    this@KeyboardActivity, settings,
                    getColor(if (chosen) R.color.card_hi else R.color.card),
                    if (chosen) Appearance.accentOf(settings) else getColor(R.color.edge),
                    if (chosen) 2 else 1
                )
                setTextColor(getColor(if (chosen) R.color.text else R.color.text_faint))
                setOnClickListener {
                    settings.keyboardSplit = split
                    buildShapeRow()
                    preview?.refresh()
                }
            }
            binding.shapeRow.addView(button)
        }
    }

    /**
     * A working keyboard, not a picture of one.
     *
     * Its keys type into the field above, so the size and split settings can be judged by using them
     * rather than by looking at them — which is the only way to tell whether a key is big enough.
     */
    private fun buildPreview() {
        val view = KeyboardView(this, settings) { key -> typeInto(key) }
        preview = view
        binding.previewHost.removeAllViews()
        binding.previewHost.addView(view)
    }

    private fun typeInto(key: KeyboardLayouts.Key) {
        val field = binding.tryField
        val text = field.text
        val cursor = field.selectionStart.coerceAtLeast(0)

        when (key.code) {
            KeyboardLayouts.Code.DELETE -> if (cursor > 0) text.delete(cursor - 1, cursor)
            KeyboardLayouts.Code.SPACE -> text.insert(cursor, " ")
            KeyboardLayouts.Code.ENTER -> Unit
            KeyboardLayouts.Code.HIDE -> Unit
            KeyboardLayouts.Code.LEFT -> field.setSelection((cursor - 1).coerceAtLeast(0))
            KeyboardLayouts.Code.RIGHT -> field.setSelection((cursor + 1).coerceAtMost(text.length))
            else -> key.output?.let { text.insert(cursor, it) }
        }
    }

    private fun showStatus() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val id = "$packageName/.KeyboardService"
        val enabled = manager.enabledInputMethodList.any { it.id == id }
        val current = AndroidSettings.Secure.getString(
            contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD
        )

        binding.setupStatus.text = when {
            current == id -> getString(R.string.keyboard_state_active)
            enabled -> getString(R.string.keyboard_state_enabled)
            else -> getString(R.string.keyboard_state_off)
        }
        binding.switchButton.isEnabled = enabled
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
