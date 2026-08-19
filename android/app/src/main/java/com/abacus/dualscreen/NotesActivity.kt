package com.abacus.dualscreen

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityNotesBinding
import com.abacus.dualscreen.notes.Note
import com.abacus.dualscreen.notes.NoteSort
import com.abacus.dualscreen.notes.NoteStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The list of notes.
 *
 * Notes used to be one preference string — a single sheet you could not name, sort, or get off the
 * device. They are files now, in a folder this app's own FTP server already serves, so the list here
 * and a directory listing on a PC are the same thing seen from two ends.
 *
 * The list is rebuilt from disk in onResume rather than cached, which is what makes a note dropped
 * in over FTP simply appear.
 */
class NotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesBinding
    private lateinit var settings: Settings
    private lateinit var store: NoteStore

    private var sort = NoteSort.RECENT
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = NoteStore(this)
        sort = NoteSort.byId(settings.noteSort)

        // Anybody updating from an older build has exactly one note living in preferences. Move it
        // before the list is drawn, or they open this to an empty screen and quite reasonably
        // conclude their notes are gone.
        store.migrateLegacy(settings.notes)

        com.abacus.dualscreen.ui.Nav.back(this, binding.backButton)
        binding.newButton.setOnClickListener { newNote() }

        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                buildList()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        buildSortSpinner()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        buildList()
    }

    private fun buildSortSpinner() {
        val labels = listOf(
            getString(R.string.notes_sort_recent),
            getString(R.string.notes_sort_name),
            getString(R.string.notes_sort_longest),
        )

        binding.sortSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.sortSpinner.setSelection(NoteSort.entries.indexOf(sort))

        binding.sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sort = NoteSort.entries[position]
                settings.noteSort = sort.id
                buildList()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ── the list ────────────────────────────────────────────────────────────

    private fun buildList() {
        binding.notesList.removeAllViews()
        val notes = store.all(query, sort)

        binding.notesStatus.text = status(notes.size)

        if (notes.isEmpty()) {
            binding.notesList.addView(TextView(this).apply {
                text = getString(
                    if (query.isBlank()) R.string.notes_empty else R.string.notes_no_matches
                )
                setTextColor(getColor(R.color.text_faint))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        for (note in notes) binding.notesList.addView(row(note))
    }

    private fun status(count: Int): String {
        val where = if (store.folderIsShared()) {
            getString(R.string.notes_folder_shared, store.folder.path)
        } else {
            getString(R.string.notes_folder_private)
        }

        return resources.getQuantityString(R.plurals.notes_count, count, count) + " · " + where
    }

    private fun row(note: Note): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = Appearance.panel(
                this@NotesActivity, settings,
                getColor(R.color.card_hi), getColor(R.color.edge)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }

            setOnClickListener { open(note.file) }
            setOnLongClickListener { actions(note); true }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        column.addView(TextView(this).apply {
            // The star goes on the title rather than in its own column: one glance down the left
            // edge tells you which notes are pinned, where a column would be holding space on every
            // row for a mark most of them do not have.
            text = if (note.pinned) "★ ${note.title}" else note.title
            setTextColor(Appearance.accentOf(settings))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        column.addView(TextView(this).apply {
            text = note.preview.ifBlank { getString(R.string.notes_blank) }
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })

        column.addView(TextView(this).apply {
            text = "${WHEN.format(Date(note.modified))} · " +
                resources.getQuantityString(R.plurals.notes_chars, note.characters, note.characters)
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(3), 0, 0)
        })

        row.addView(column)

        row.addView(Button(this).apply {
            text = "⋯"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_dim))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = Appearance.panel(
                this@NotesActivity, settings, getColor(R.color.card), getColor(R.color.edge)
            )
            contentDescription = getString(R.string.notes_actions)
            setOnClickListener { actions(note) }
        })

        return row
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private fun newNote() {
        // Named for the day rather than "Untitled", because the name becomes the filename: a folder
        // of Untitled (2), Untitled (3) is materially worse to work with over FTP than one of dates.
        open(store.create(getString(R.string.notes_new_name, TODAY.format(Date()))))
    }

    private fun open(file: File) {
        startActivity(
            Intent(this, NoteEditorActivity::class.java)
                .putExtra(NoteEditorActivity.EXTRA_FILE, file.path)
        )
    }

    private fun actions(note: Note) {
        val options = arrayOf(
            getString(R.string.notes_open),
            getString(if (note.pinned) R.string.notes_unpin else R.string.notes_pin),
            getString(R.string.notes_rename),
            getString(R.string.notes_duplicate),
            getString(R.string.notes_delete),
        )

        AlertDialog.Builder(this)
            .setTitle(note.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> open(note.file)
                    1 -> {
                        store.pin(note.file, !note.pinned)
                        buildList()
                    }
                    2 -> rename(note)
                    3 -> {
                        store.duplicate(note.file)
                        buildList()
                    }
                    4 -> confirmDelete(note)
                }
            }
            .show()
    }

    private fun rename(note: Note) {
        val field = EditText(this).apply {
            setText(note.title)
            setSelection(note.title.length)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.notes_rename)
            .setView(field)
            .setPositiveButton(R.string.notes_save) { _, _ ->
                store.rename(note.file, field.text.toString())
                buildList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(R.string.notes_delete)
            .setMessage(getString(R.string.notes_delete_confirm, note.title))
            .setPositiveButton(R.string.notes_delete) { _, _ ->
                store.delete(note.file)
                buildList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val WHEN = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        val TODAY = SimpleDateFormat("d MMM", Locale.getDefault())
    }
}
