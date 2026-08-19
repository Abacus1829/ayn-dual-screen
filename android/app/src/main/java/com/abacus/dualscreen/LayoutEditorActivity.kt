package com.abacus.dualscreen

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityLayoutEditorBinding
import com.abacus.dualscreen.macro.LayoutError
import com.abacus.dualscreen.macro.LayoutImport
import com.abacus.dualscreen.macro.LayoutPackage
import com.abacus.dualscreen.macro.MacroScriptStore
import java.io.File

/**
 * Arrange the macro pad, and manage the layouts it can be.
 *
 * The pad's buttons were always positioned by dragging them around the overlay, which works but can
 * only be done with the pad on top of whatever you were doing. This edits the same layouts, from
 * inside the app, on a canvas shaped like the screen — and adds the parts the overlay could never
 * offer: several named layouts, resizing, and handing one to somebody else.
 */
class LayoutEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLayoutEditorBinding
    private lateinit var settings: Settings
    private lateinit var store: MacroStore
    private lateinit var scripts: MacroScriptStore

    /** Guards the spinner listener while it is being repopulated. */
    private var building = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLayoutEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = MacroStore(this)
        scripts = MacroScriptStore(this)

        binding.backButton.setOnClickListener { finish() }
        binding.menuButton.setOnClickListener { moreMenu() }
        binding.addButton.setOnClickListener { addButton() }
        binding.editButton.setOnClickListener { editSelected() }
        binding.deleteButton.setOnClickListener { deleteSelected() }

        binding.canvas.accent = Appearance.accentOf(settings)
        binding.canvas.faceColor = getColor(R.color.card_hi)
        binding.canvas.edgeColor = getColor(R.color.edge)

        binding.canvas.onChanged = {
            save()
            // The hint carries the size, which a resize has just changed.
            showSelection()
        }
        binding.canvas.onSelected = { showSelection() }

        buildSpinner()
        loadActive()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    /**
     * The canvas is shaped like the screen it stands for.
     *
     * Done once the view has been measured, because the aspect ratio needs its real width. Without
     * it a button dragged to the bottom-right here lands somewhere else on the pad.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return

        val metrics = resources.displayMetrics
        val frame = binding.canvas.parent as? View ?: return
        val ratio = metrics.heightPixels.toFloat() / metrics.widthPixels

        val width = minOf(frame.width, (frame.height / ratio).toInt())
        if (width <= 0) return

        val params = binding.canvas.layoutParams
        if (params.width == width) return

        params.width = width
        params.height = (width * ratio).toInt()
        binding.canvas.layoutParams = params
    }

    // ── layouts ─────────────────────────────────────────────────────────────

    private fun buildSpinner() {
        building = true

        val names = store.profiles.mapIndexed { index, profile ->
            if (index == store.activeIndex) "★ ${profile.name}" else profile.name
        }

        binding.layoutSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, names
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.layoutSpinner.setSelection(store.activeIndex)

        /*
         * Cleared after the current message loop, not immediately.
         *
         * A Spinner delivers its initial selection asynchronously, so clearing this on the next line
         * would leave the flag down by the time that callback arrived — and the callback rebuilt the
         * spinner, which fired it again, forever. That loop redrew the canvas hundreds of times a
         * second and cleared the selected button every time, which is what "tapping a button does
         * nothing" turned out to be.
         */
        binding.layoutSpinner.post { building = false }

        binding.layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // The second guard is the load-bearing one: re-entry with the position we just
                // stored is a no-op rather than another rebuild.
                if (building || position == store.activeIndex) return

                store.activeIndex = position
                loadActive()
                buildSpinner()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun loadActive() {
        binding.canvas.buttons = store.active.macros
        binding.canvas.select(null)
        showSelection()
    }

    /**
     * Write the canvas's own list back.
     *
     * Not `store.save(store.active)`: `active` parses a fresh profile out of preferences every time
     * it is read, so saving that would write back exactly what was already on disk and throw away
     * the drag that prompted the save. The list the canvas has been editing is the one that matters.
     */
    private fun save() {
        val all = store.profiles
        val index = store.activeIndex
        if (index !in all.indices) return

        all[index] = MacroProfile(all[index].name, binding.canvas.buttons)
        store.profiles = all
    }

    private fun showSelection() {
        val has = binding.canvas.selected != null
        binding.editButton.isEnabled = has
        binding.deleteButton.isEnabled = has
        binding.editButton.alpha = if (has) 1f else 0.4f
        binding.deleteButton.alpha = if (has) 1f else 0.4f

        binding.editorHint.text = binding.canvas.selected?.let {
            getString(R.string.layouts_selected, it.label, it.size)
        } ?: getString(R.string.layouts_hint)
    }

    // ── buttons ─────────────────────────────────────────────────────────────

    private fun addButton() {
        val macro = Macro(
            id = MacroStore.id(),
            label = "New",
            kind = Macro.Kind.KEY,
            payload = "ENTER",
            // Dropped near the middle rather than at 0,0: a new button at the corner looks like it
            // failed to appear, especially under the status bar.
            x = 0.42f,
            y = 0.45f,
            size = 64,
        )

        // Added to the list the canvas is editing, not to store.active -- that parses a fresh
        // profile on every read, so the new button would land on a copy nothing ever sees again.
        binding.canvas.buttons += macro
        save()
        binding.canvas.select(macro)
        editSelected()
    }

    /**
     * Editing a button reuses the macro pad's own dialog.
     *
     * The pad screen owns that dialog and it already knows every kind of thing a button can do,
     * including running a saved macro. Duplicating it here would mean two dialogs to keep in step.
     */
    private fun editSelected() {
        val macro = binding.canvas.selected ?: return

        startActivity(
            Intent(this, MacrosActivity::class.java)
                .putExtra(MacrosActivity.EXTRA_EDIT_ID, macro.id)
        )
    }

    private fun deleteSelected() {
        val macro = binding.canvas.selected ?: return

        AlertDialog.Builder(this)
            .setTitle(R.string.layouts_delete_button)
            .setMessage(getString(R.string.layouts_delete_confirm, macro.label))
            .setPositiveButton(R.string.profiles_delete) { _, _ ->
                binding.canvas.buttons.removeAll { it.id == macro.id }
                save()
                binding.canvas.select(null)
                binding.canvas.invalidate()
                showSelection()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the button dialog, which may have changed a label or a size.
        binding.canvas.buttons = store.active.macros
        binding.canvas.invalidate()
        showSelection()
    }

    // ── the menu ────────────────────────────────────────────────────────────

    private fun moreMenu() {
        val options = arrayOf(
            getString(R.string.layouts_new),
            getString(R.string.notes_rename),
            getString(R.string.profiles_duplicate),
            getString(R.string.layouts_reset),
            getString(R.string.profiles_delete),
            getString(R.string.layouts_export),
            getString(R.string.layouts_import),
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.layouts_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> newLayout()
                    1 -> renameLayout()
                    2 -> duplicateLayout()
                    3 -> resetLayout()
                    4 -> deleteLayout()
                    5 -> exportLayout()
                    6 -> importLayout()
                }
            }
            .show()
    }

    private fun newLayout() {
        val all = store.profiles
        all += MacroProfile(uniqueName(getString(R.string.layouts_new_name)), mutableListOf())
        store.profiles = all
        store.activeIndex = all.size - 1

        buildSpinner()
        loadActive()
    }

    private fun renameLayout() {
        val field = EditText(this).apply {
            setText(store.active.name)
            setSelection(store.active.name.length)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.notes_rename)
            .setView(field)
            .setPositiveButton(R.string.notes_save) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton

                val all = store.profiles
                all[store.activeIndex] = MacroProfile(name, store.active.macros)
                store.profiles = all
                buildSpinner()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun duplicateLayout() {
        val all = store.profiles
        all += MacroProfile(
            uniqueName(store.active.name + " copy"),
            // Copied one by one, and with fresh ids, so editing the copy cannot edit the original.
            store.active.macros.map { it.copy(id = MacroStore.id()) }.toMutableList(),
        )

        store.profiles = all
        store.activeIndex = all.size - 1
        buildSpinner()
        loadActive()
    }

    private fun resetLayout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.layouts_reset)
            .setMessage(R.string.layouts_reset_confirm)
            .setPositiveButton(R.string.layouts_reset) { _, _ ->
                val all = store.profiles
                all[store.activeIndex] = MacroStore.starterProfile(store.active.name)
                store.profiles = all
                loadActive()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteLayout() {
        if (store.profiles.size <= 1) {
            toast(getString(R.string.layouts_last))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_delete)
            .setMessage(getString(R.string.layouts_delete_layout_confirm, store.active.name))
            .setPositiveButton(R.string.profiles_delete) { _, _ ->
                val all = store.profiles
                all.removeAt(store.activeIndex)
                store.profiles = all
                store.activeIndex = 0
                buildSpinner()
                loadActive()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── sharing ─────────────────────────────────────────────────────────────

    private fun folder(): File {
        val shared = File(Environment.getExternalStorageDirectory(), "AynDualScreen/layouts")
        return if (Storage.hasWholeDeviceAccess() || shared.isDirectory) shared
        else File(filesDir, "layouts")
    }

    private fun exportLayout() {
        val json = LayoutPackage.export(store.active, scripts.scripts)
        val name = store.active.name.replace(Regex("[^A-Za-z0-9 _-]"), "-").trim().ifBlank { "layout" }
        val file = File(folder(), "$name.json")

        val written = runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json)
            file
        }.getOrNull()

        AlertDialog.Builder(this)
            .setTitle(R.string.layouts_export)
            .setMessage(
                if (written == null) getString(R.string.profiles_export_failed)
                else getString(R.string.layouts_export_done, written.path)
            )
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.notes_share) { _, _ -> share(json) }
            .show()
    }

    private fun share(json: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, store.active.name)
            putExtra(Intent.EXTRA_TEXT, json)
        }

        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.notes_share))) }
            .onFailure { toast(getString(R.string.notes_share_failed)) }
    }

    /**
     * Import from a file in the layouts folder, or from pasted text.
     *
     * The folder is listed rather than a system file picker: a layout arrives over this app's own FTP
     * server or through the share sheet, both of which land it somewhere this app can already see,
     * and a picker would be a permission prompt and three taps for the same result.
     */
    private fun importLayout() {
        val files = folder().listFiles { f -> f.isFile && f.extension.equals("json", true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        if (files.isEmpty()) {
            pasteImport()
            return
        }

        val names = (files.map { it.name } + getString(R.string.profiles_import_paste)).toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.layouts_import)
            .setItems(names) { _, which ->
                if (which >= files.size) pasteImport()
                else applyImport(runCatching { files[which].readText() }.getOrDefault(""))
            }
            .show()
    }

    private fun pasteImport() {
        val field = EditText(this).apply {
            hint = getString(R.string.profiles_import_hint)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            maxLines = 8
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.layouts_import)
            .setView(field)
            .setPositiveButton(R.string.profiles_import) { _, _ -> applyImport(field.text.toString()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun applyImport(text: String) {
        when (val result = LayoutPackage.import(text)) {
            is LayoutImport.Failed -> toast(
                getString(
                    when (result.reason) {
                        LayoutError.NOT_JSON -> R.string.layouts_bad_json
                        LayoutError.NOT_A_LAYOUT -> R.string.layouts_not_layout
                        LayoutError.TOO_NEW -> R.string.layouts_too_new
                        LayoutError.NO_BUTTONS -> R.string.layouts_no_buttons
                    }
                )
            )

            is LayoutImport.Ok -> {
                // Macros first, so the buttons that reference them find them the moment the layout
                // is in. Ids are kept: a macro already on this device with the same id is the same
                // macro, and importing the same layout twice should not double it.
                val existing = scripts.scripts
                for (incoming in result.scripts) {
                    if (existing.none { it.id == incoming.id }) scripts.save(incoming)
                }

                val all = store.profiles
                all += MacroProfile(uniqueName(result.layout.name), result.layout.macros)
                store.profiles = all
                store.activeIndex = all.size - 1

                buildSpinner()
                loadActive()
                toast(
                    getString(
                        R.string.layouts_imported,
                        result.layout.name,
                        result.layout.macros.size,
                        result.scripts.size,
                    )
                )
            }
        }
    }

    private fun uniqueName(wanted: String): String {
        val taken = store.profiles.map { it.name }.toSet()
        if (wanted !in taken) return wanted

        var n = 2
        while ("$wanted $n" in taken) n++
        return "$wanted $n"
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
