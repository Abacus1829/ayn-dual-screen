package com.abacus.dualscreen

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityNoteEditorBinding
import com.abacus.dualscreen.notes.NoteStore
import java.io.File

/**
 * One note, open.
 *
 * Saves as you type rather than behind a button: a notes screen you can lose work in is worse than
 * no notes screen, and there is no state here worth a save-or-discard decision.
 *
 * The title is the filename. Renaming therefore renames the file, which is deliberately deferred
 * until you leave the field or the screen — renaming on every keystroke would leave a trail of
 * `B.txt`, `Bo.txt`, `Bos.txt` behind on the card.
 */
class NoteEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteEditorBinding
    private lateinit var settings: Settings
    private lateinit var store: NoteStore

    /** Reassigned by a rename, so every later write goes to the file that now exists. */
    private lateinit var file: File

    /**
     * Set once the note has been deleted, and checked before every write.
     *
     * Without it the autosave in onPause runs on the way out of the screen and writes the file
     * straight back — an empty note reappearing in the list moments after you deleted it.
     */
    private var deleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = NoteStore(this)

        val path = intent.getStringExtra(EXTRA_FILE)
        if (path.isNullOrBlank()) {
            finish()
            return
        }

        file = File(path)

        // A note opened from the list that has since been deleted — over FTP, say — would otherwise
        // be recreated empty by the first autosave, resurrecting something somebody threw away.
        if (!file.isFile) {
            finish()
            return
        }

        binding.titleField.setText(file.nameWithoutExtension)
        binding.notesField.setText(store.read(file))

        com.abacus.dualscreen.ui.Nav.back(this, binding.backButton)
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.shareButton.setOnClickListener { share() }
        binding.pinButton.setOnClickListener { togglePin() }

        binding.titleField.setOnFocusChangeListener { _, focused -> if (!focused) applyRename() }

        binding.notesField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (deleted) return
                store.save(file, s?.toString().orEmpty())
                showCount()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        showPin()
        showCount()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
        com.abacus.dualscreen.control.ControlCenter.attach(this, settings)
    }

    override fun onPause() {
        super.onPause()
        if (!::file.isInitialized || deleted) return

        store.save(file, binding.notesField.text?.toString().orEmpty())
        applyRename()
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private fun applyRename() {
        if (!::file.isInitialized || deleted) return

        val wanted = binding.titleField.text?.toString().orEmpty()
        val renamed = store.rename(file, wanted)

        if (renamed != file) {
            file = renamed
            // The store makes names unique and filesystem-safe, so what you typed is not always what
            // you got. Showing the real name is better than letting the field disagree with the file.
            binding.titleField.setText(file.nameWithoutExtension)
            showCount()
        }
    }

    private fun togglePin() {
        store.pin(file, !store.isPinned(file))
        showPin()
    }

    private fun showPin() {
        binding.pinButton.text =
            getString(if (store.isPinned(file)) R.string.notes_unpin else R.string.notes_pin)
    }

    /**
     * Hand the text to another app.
     *
     * Text rather than the file itself: sharing a file means a FileProvider and a content URI, and
     * for a note the receiving app almost always wants the words anyway. The file is already
     * reachable over FTP for the case where somebody genuinely wants the file.
     */
    private fun share() {
        val body = binding.notesField.text?.toString().orEmpty()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.notes_share)))
        }.onFailure {
            android.widget.Toast.makeText(
                this, R.string.notes_share_failed, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notes_delete)
            .setMessage(getString(R.string.notes_delete_confirm, file.nameWithoutExtension))
            .setPositiveButton(R.string.notes_delete) { _, _ ->
                deleted = true
                store.delete(file)
                finish()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showCount() {
        val text = binding.notesField.text?.toString().orEmpty()
        val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }

        binding.notesStatus.text = getString(
            R.string.notes_status,
            resources.getQuantityString(R.plurals.notes_lines, lines, lines),
            resources.getQuantityString(R.plurals.notes_words, words, words),
            resources.getQuantityString(R.plurals.notes_chars, text.length, text.length),
            file.name,
        )
    }

    companion object {
        const val EXTRA_FILE = "file"
    }
}
