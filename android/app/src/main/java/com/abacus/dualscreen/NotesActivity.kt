package com.abacus.dualscreen

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityNotesBinding

/**
 * A scratchpad on the handheld.
 *
 * Saves as you type rather than behind a button: a notes screen you can lose work in is worse than no
 * notes screen, and there's no state here worth a save/discard decision.
 */
class NotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        binding.notesField.setText(settings.notes)
        showCount()

        binding.backButton.setOnClickListener { finish() }

        binding.notesField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                settings.notes = s?.toString().orEmpty()
                showCount()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    private fun showCount() {
        val text = binding.notesField.text?.toString().orEmpty()
        val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        binding.notesStatus.text = getString(R.string.notes_saved) + " · $lines lines, ${text.length} characters"
    }

    override fun onPause() {
        super.onPause()
        settings.notes = binding.notesField.text?.toString().orEmpty()
    }
}
