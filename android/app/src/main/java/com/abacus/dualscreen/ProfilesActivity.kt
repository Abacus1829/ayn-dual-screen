package com.abacus.dualscreen

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.connect.ConnectionProfile
import com.abacus.dualscreen.connect.Connector
import com.abacus.dualscreen.connect.Displays
import com.abacus.dualscreen.connect.ProfileStore
import com.abacus.dualscreen.connect.Recent
import com.abacus.dualscreen.databinding.ActivityProfilesBinding

/**
 * The saved connections, and the shortest path from opening the app to being on the second screen.
 *
 * One tap on a row connects. Everything else — editing, the default, deleting — is behind the row's
 * own ⋯ button, because the common case is opening the thing you opened yesterday and anything that
 * makes that take two taps is in the way.
 */
class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfilesBinding
    private lateinit var settings: Settings
    private lateinit var store: ProfileStore

    /** Set once auto-connect has fired, so coming back from a session does not bounce straight in. */
    private var autoConnected = false

    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = ProfileStore(this)
        store.migrateFromGames(settings)

        com.abacus.dualscreen.ui.Nav.back(this, binding.backButton)
        binding.newButton.setOnClickListener { edit(null) }
        binding.findButton.setOnClickListener {
            startActivity(Intent(this, DiscoverActivity::class.java))
        }
        binding.menuButton.setOnClickListener { moreMenu() }
        binding.clearRecentButton.setOnClickListener {
            store.clearRecents()
            build()
        }

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)

    }

    override fun onResume() {
        super.onResume()
        build()

        // The display line in the subtitle has to be right when somebody folds the panel away while
        // this screen is open, so the list is rebuilt when the set of screens changes.
        displayListener = Displays.listen(this) { runOnUiThread { build() } }

        if (!autoConnected) {
            autoConnected = true
            store.autoConnectProfile()?.let { Connector.open(this, it, store) }
        }
    }

    override fun onPause() {
        super.onPause()
        Displays.stopListening(this, displayListener)
        displayListener = null
    }

    // ── the list ────────────────────────────────────────────────────────────

    private fun build() {
        binding.profileList.removeAllViews()
        val profiles = store.ordered()

        binding.statusText.text = subtitle(profiles.size)

        if (profiles.isEmpty()) {
            binding.profileList.addView(emptyState())
        } else {
            val default = store.defaultId
            for (profile in profiles) binding.profileList.addView(row(profile, profile.id == default))
        }

        buildRecents()
    }

    /**
     * The line under the title: how many saved, and where a session will land.
     *
     * The display half is the useful part — it is the one piece of state that changes without the
     * user touching the app, and finding out the second panel was off only after tapping connect is
     * the single most annoying way for this to go wrong.
     */
    private fun subtitle(count: Int): String {
        val saved = resources.getQuantityString(R.plurals.profiles_count, count, count)
        val screens = Displays.all(this).count { !it.isMain }

        return if (screens > 0) "$saved · ${getString(R.string.profiles_second_ready)}"
        else "$saved · ${getString(R.string.profiles_no_second)}"
    }

    private fun emptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = Appearance.panel(
            this@ProfilesActivity, settings, getColor(R.color.card), getColor(R.color.edge)
        )

        addView(TextView(this@ProfilesActivity).apply {
            text = getString(R.string.profiles_empty)
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })

        addView(Button(this@ProfilesActivity).apply {
            text = getString(R.string.profiles_find_long)
            isAllCaps = false
            setTextColor(Appearance.accentOf(settings))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Appearance.panel(
                this@ProfilesActivity, settings,
                getColor(R.color.card_hi), Appearance.accentOf(settings)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            setOnClickListener { startActivity(Intent(context, DiscoverActivity::class.java)) }
        })
    }

    private fun row(profile: ConnectionProfile, isDefault: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Generous vertical padding: this is the button people press on a handheld, often
            // without looking straight at it.
            setPadding(dp(14), dp(14), dp(10), dp(14))
            background = Appearance.panel(
                this@ProfilesActivity, settings,
                getColor(R.color.card_hi),
                if (isDefault) Appearance.accentOf(settings) else getColor(R.color.edge),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            setOnClickListener { Connector.open(this@ProfilesActivity, profile, store) }
            setOnLongClickListener { actions(profile); true }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        column.addView(TextView(this).apply {
            text = if (isDefault) "★ ${profile.name}" else profile.name
            setTextColor(if (profile.usable) Appearance.accentOf(settings) else getColor(R.color.state_bad))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        column.addView(TextView(this).apply {
            text = describe(profile)
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        row.addView(column)

        row.addView(Button(this).apply {
            text = "⋯"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_dim))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Appearance.panel(
                this@ProfilesActivity, settings, getColor(R.color.card), getColor(R.color.edge)
            )
            contentDescription = getString(R.string.profiles_more)
            setOnClickListener { actions(profile) }
        })

        return row
    }

    /** Address, then where it opens — the two things that decide whether this is the row you want. */
    private fun describe(profile: ConnectionProfile): String {
        if (!profile.usable) return getString(R.string.profile_unusable)

        val where = when (profile.display) {
            com.abacus.dualscreen.connect.DisplayChoice.MAIN -> getString(R.string.display_opt_main)
            com.abacus.dualscreen.connect.DisplayChoice.SECOND -> getString(R.string.display_opt_second)
            com.abacus.dualscreen.connect.DisplayChoice.EXTERNAL -> getString(R.string.display_opt_external)
            com.abacus.dualscreen.connect.DisplayChoice.ASK -> getString(R.string.display_opt_ask)
            com.abacus.dualscreen.connect.DisplayChoice.AUTO -> getString(R.string.display_opt_auto)
        }

        val auto = if (profile.autoConnect) " · ${getString(R.string.profile_autoconnect_short)}" else ""
        return "${profile.address} · $where$auto"
    }

    // ── recents ─────────────────────────────────────────────────────────────

    private fun buildRecents() {
        val recents = store.recents
        binding.recentList.removeAllViews()
        binding.recentCard.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE

        for (recent in recents) binding.recentList.addView(recentRow(recent))
    }

    private fun recentRow(recent: Recent): View = Button(this).apply {
        text = if (recent.name == recent.host) recent.address else "${recent.name} — ${recent.address}"
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(getColor(R.color.text))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Appearance.panel(
            this@ProfilesActivity, settings, getColor(R.color.card), getColor(R.color.edge)
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }

        // A history entry is not a profile, so connecting from here builds a throwaway one rather
        // than quietly adding to the saved list. Long-press is how it becomes permanent.
        setOnClickListener { Connector.open(this@ProfilesActivity, temporary(recent), store) }
        setOnLongClickListener {
            saveRecent(recent)
            true
        }
    }

    private fun temporary(recent: Recent) = ConnectionProfile(
        id = ConnectionProfile.newId(),
        name = recent.name,
        host = recent.host,
        port = recent.port,
        display = defaultDisplayChoice(),
        awake = if (settings.keepAwake) com.abacus.dualscreen.connect.Awake.ALWAYS
        else com.abacus.dualscreen.connect.Awake.NEVER,
    )

    private fun defaultDisplayChoice() = com.abacus.dualscreen.connect.DisplayChoice.byId(settings.displayChoice)

    private fun saveRecent(recent: Recent) {
        val profile = temporary(recent).copy(name = store.uniqueName(recent.name))
        store.save(profile)
        build()
        android.widget.Toast.makeText(
            this, getString(R.string.profiles_saved, profile.name), android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // ── menus ───────────────────────────────────────────────────────────────

    private fun actions(profile: ConnectionProfile) {
        val isDefault = store.defaultId == profile.id

        val options = arrayOf(
            getString(R.string.profiles_connect),
            getString(R.string.profiles_edit),
            getString(if (isDefault) R.string.profiles_unset_default else R.string.profiles_set_default),
            getString(R.string.profiles_duplicate),
            getString(R.string.profiles_delete),
        )

        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> Connector.open(this, profile, store)
                    1 -> edit(profile.id)
                    2 -> {
                        store.defaultId = if (isDefault) null else profile.id
                        build()
                    }
                    3 -> {
                        store.duplicate(profile.id)
                        build()
                    }
                    4 -> confirmDelete(profile)
                }
            }
            .show()
    }

    private fun confirmDelete(profile: ConnectionProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_delete)
            .setMessage(getString(R.string.profiles_delete_confirm, profile.name))
            .setPositiveButton(R.string.profiles_delete) { _, _ ->
                store.delete(profile.id)
                build()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun moreMenu() {
        val options = arrayOf(
            getString(R.string.profiles_defaults),
            getString(R.string.profiles_export),
            getString(R.string.profiles_import),
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_more)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> defaultsDialog()
                    1 -> exportProfiles()
                    2 -> importProfiles()
                }
            }
            .show()
    }

    /**
     * What a new connection starts out as, plus the one session setting that is not per-profile.
     *
     * Kept as defaults rather than as global overrides: a profile that says landscape means
     * landscape, and a setting here that quietly won that argument would make the per-profile
     * choices untrustworthy.
     */
    private fun defaultsDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, dp(2))
        }

        val displays = com.abacus.dualscreen.connect.DisplayChoice.entries
        val orientations = com.abacus.dualscreen.connect.Orientation.entries
        val awakes = com.abacus.dualscreen.connect.Awake.entries

        val displaySpinner = spinner(displays.map { displayLabel(it) })
        val orientationSpinner = spinner(orientations.map { orientationLabel(it) })
        val awakeSpinner = spinner(awakes.map { awakeLabel(it) })

        displaySpinner.setSelection(com.abacus.dualscreen.connect.DisplayChoice.byId(settings.displayChoice).ordinal)
        orientationSpinner.setSelection(com.abacus.dualscreen.connect.Orientation.byId(settings.orientation).ordinal)
        awakeSpinner.setSelection(com.abacus.dualscreen.connect.Awake.byId(settings.awakeMode).ordinal)

        val controls = android.widget.CheckBox(this).apply {
            text = getString(R.string.profiles_show_controls)
            setTextColor(getColor(R.color.text))
            isChecked = settings.showControls
        }

        body.addView(label(getString(R.string.profile_opens_on)))
        body.addView(displaySpinner)
        body.addView(label(getString(R.string.profile_orientation)))
        body.addView(orientationSpinner)
        body.addView(label(getString(R.string.profile_awake)))
        body.addView(awakeSpinner)
        body.addView(controls)

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_defaults)
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.notes_save) { _, _ ->
                settings.displayChoice = displays[displaySpinner.selectedItemPosition].id
                settings.orientation = orientations[orientationSpinner.selectedItemPosition].id
                settings.awakeMode = awakes[awakeSpinner.selectedItemPosition].id
                settings.showControls = controls.isChecked

                // Kept in step so the old connect screen, which still reads the boolean, does not
                // disagree with what was just chosen here.
                settings.keepAwake = settings.awakeMode != com.abacus.dualscreen.connect.Awake.NEVER.id
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun spinner(labels: List<String>) = android.widget.Spinner(this).apply {
        adapter = android.widget.ArrayAdapter(
            this@ProfilesActivity, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun displayLabel(choice: com.abacus.dualscreen.connect.DisplayChoice): String = getString(
        when (choice) {
            com.abacus.dualscreen.connect.DisplayChoice.MAIN -> R.string.display_opt_main
            com.abacus.dualscreen.connect.DisplayChoice.SECOND -> R.string.display_opt_second
            com.abacus.dualscreen.connect.DisplayChoice.EXTERNAL -> R.string.display_opt_external
            com.abacus.dualscreen.connect.DisplayChoice.AUTO -> R.string.display_opt_auto
            com.abacus.dualscreen.connect.DisplayChoice.ASK -> R.string.display_opt_ask
        }
    )

    private fun orientationLabel(orientation: com.abacus.dualscreen.connect.Orientation): String = getString(
        when (orientation) {
            com.abacus.dualscreen.connect.Orientation.AUTOMATIC -> R.string.orientation_auto
            com.abacus.dualscreen.connect.Orientation.LANDSCAPE -> R.string.orientation_landscape
            com.abacus.dualscreen.connect.Orientation.PORTRAIT -> R.string.orientation_portrait
        }
    )

    private fun awakeLabel(awake: com.abacus.dualscreen.connect.Awake): String = getString(
        when (awake) {
            com.abacus.dualscreen.connect.Awake.ALWAYS -> R.string.awake_always
            com.abacus.dualscreen.connect.Awake.CONNECTED -> R.string.awake_connected
            com.abacus.dualscreen.connect.Awake.NEVER -> R.string.awake_never
        }
    )

    private fun exportProfiles() {
        if (store.profiles.isEmpty()) {
            toast(getString(R.string.profiles_export_empty))
            return
        }

        val file = store.export()
        if (file == null) {
            toast(getString(R.string.profiles_export_failed))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_export)
            .setMessage(getString(R.string.profiles_export_done, file.path))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.notes_share) { _, _ -> shareExport() }
            .show()
    }

    /** Text rather than the file, for the same reason the notes screen shares text: no provider. */
    private fun shareExport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "AynDualScreen profiles")
            putExtra(Intent.EXTRA_TEXT, store.exportJson())
        }

        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.notes_share))) }
            .onFailure { toast(getString(R.string.notes_share_failed)) }
    }

    /**
     * Import from the export file, or from anything pasted in.
     *
     * The file path first because that is where an export lands and where somebody dropping a file
     * over FTP will put it; the text box is the fallback for a profile someone was sent in a message.
     */
    private fun importProfiles() {
        val file = store.exportFile()
        val fromFile = runCatching { if (file.isFile) file.readText() else null }.getOrNull()

        if (fromFile != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.profiles_import)
                .setMessage(getString(R.string.profiles_import_found, file.path))
                .setPositiveButton(R.string.profiles_import) { _, _ -> applyImport(fromFile) }
                .setNeutralButton(R.string.profiles_import_paste) { _, _ -> pasteImport() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }

        pasteImport()
    }

    private fun pasteImport() {
        val field = android.widget.EditText(this).apply {
            hint = getString(R.string.profiles_import_hint)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            maxLines = 8
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_import)
            .setView(field)
            .setPositiveButton(R.string.profiles_import) { _, _ -> applyImport(field.text.toString()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun applyImport(text: String) {
        val result = store.import(text)
        build()

        toast(
            if (result.total == 0 && result.skipped == 0) getString(R.string.profiles_import_nothing)
            else getString(R.string.profiles_import_done, result.added, result.merged, result.skipped)
        )
    }

    private fun edit(id: String?) {
        startActivity(
            Intent(this, ProfileEditActivity::class.java)
                .putExtra(ProfileEditActivity.EXTRA_ID, id)
        )
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
