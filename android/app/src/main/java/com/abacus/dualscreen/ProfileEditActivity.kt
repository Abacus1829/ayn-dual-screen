package com.abacus.dualscreen

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.connect.Awake
import com.abacus.dualscreen.connect.ConnectionProfile
import com.abacus.dualscreen.connect.DisplayChoice
import com.abacus.dualscreen.connect.Displays
import com.abacus.dualscreen.connect.Orientation
import com.abacus.dualscreen.connect.ProfileStore
import com.abacus.dualscreen.databinding.ActivityProfileEditBinding

/**
 * One profile's settings.
 *
 * Everything on this screen has a working default, so a profile can be made by typing an address and
 * tapping Save. The rest is there for the people who want the second panel locked to landscape and
 * the screen allowed to sleep, and costs the people who do not exactly one scroll.
 */
class ProfileEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditBinding
    private lateinit var settings: Settings
    private lateinit var store: ProfileStore

    /** Null for a new profile; the id being edited otherwise. */
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = ProfileStore(this)
        editingId = intent.getStringExtra(EXTRA_ID)

        buildSpinners()

        val existing = store.byId(editingId)
        if (existing != null) fill(existing) else fillNew()

        binding.saveButton.setOnClickListener { save() }
        binding.testButton.setOnClickListener { test() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.deleteButton.visibility = if (existing == null) View.GONE else View.VISIBLE

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    private fun buildSpinners() {
        // Labels are built from the enums so a new option needs no layout change, and the position
        // in each spinner is the ordinal — which is why nothing here reorders the entries.
        adapt(binding.displaySpinner, DisplayChoice.entries.map { label(it) })
        adapt(binding.orientationSpinner, Orientation.entries.map { label(it) })
        adapt(binding.awakeSpinner, Awake.entries.map { label(it) })
    }

    private fun adapt(spinner: android.widget.Spinner, labels: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun label(choice: DisplayChoice): String = getString(
        when (choice) {
            DisplayChoice.MAIN -> R.string.display_opt_main
            DisplayChoice.SECOND -> R.string.display_opt_second
            DisplayChoice.EXTERNAL -> R.string.display_opt_external
            DisplayChoice.AUTO -> R.string.display_opt_auto
            DisplayChoice.ASK -> R.string.display_opt_ask
        }
    )

    private fun label(orientation: Orientation): String = getString(
        when (orientation) {
            Orientation.AUTOMATIC -> R.string.orientation_auto
            Orientation.LANDSCAPE -> R.string.orientation_landscape
            Orientation.PORTRAIT -> R.string.orientation_portrait
        }
    )

    private fun label(awake: Awake): String = getString(
        when (awake) {
            Awake.ALWAYS -> R.string.awake_always
            Awake.CONNECTED -> R.string.awake_connected
            Awake.NEVER -> R.string.awake_never
        }
    )

    // ── filling in ──────────────────────────────────────────────────────────

    private fun fill(profile: ConnectionProfile) {
        binding.editTitle.text = getString(R.string.profile_edit_title)
        binding.nameField.setText(profile.name)
        binding.hostField.setText(profile.host)
        binding.portField.setText(profile.port.toString())
        binding.autoConnectCheck.isChecked = profile.autoConnect
        binding.displaySpinner.setSelection(profile.display.ordinal)
        binding.orientationSpinner.setSelection(profile.orientation.ordinal)
        binding.awakeSpinner.setSelection(profile.awake.ordinal)
    }

    /**
     * A new profile, pre-filled with the device's own defaults.
     *
     * The port comes from the last entry the old picker used, because somebody making their first
     * profile on a device that has been connecting to a mod for months should not have to look it up.
     */
    private fun fillNew() {
        binding.editTitle.text = getString(R.string.profile_new_title)
        binding.portField.setText(settings.lastGame.defaultPort.toString())
        binding.displaySpinner.setSelection(DisplayChoice.byId(settings.displayChoice).ordinal)
        binding.orientationSpinner.setSelection(Orientation.byId(settings.orientation).ordinal)
        binding.awakeSpinner.setSelection(Awake.byId(settings.awakeMode).ordinal)
    }

    // ── saving ──────────────────────────────────────────────────────────────

    private fun read(): ConnectionProfile? {
        val host = binding.hostField.text.toString().trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')

        if (host.isEmpty()) {
            say(getString(R.string.status_need_host))
            return null
        }

        // Same trap the address fields already guard: the mods print localhost in their own logs,
        // which is the address to use on the PC and points this device at itself.
        if (host.lowercase() in LOOPBACK) {
            say(getString(R.string.status_localhost))
            return null
        }

        val port = binding.portField.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            say(getString(R.string.status_bad_port))
            return null
        }

        val name = binding.nameField.text.toString().trim().ifBlank { host }
        val existing = store.byId(editingId)

        return ConnectionProfile(
            id = existing?.id ?: ConnectionProfile.newId(),
            // Only uniquified for new profiles: renaming an existing one to what it already is must
            // not quietly turn it into "Living room 2".
            name = if (existing == null) store.uniqueName(name) else name,
            host = host,
            port = port,
            // "custom" rather than the last game picked: a profile somebody typed by hand is not
            // any particular mod, and inheriting one would put another game's name on this
            // profile's error messages. Discovery sets a real preset when it identifies one.
            preset = existing?.preset ?: "custom",
            autoConnect = binding.autoConnectCheck.isChecked,
            display = DisplayChoice.entries[binding.displaySpinner.selectedItemPosition],
            orientation = Orientation.entries[binding.orientationSpinner.selectedItemPosition],
            awake = Awake.entries[binding.awakeSpinner.selectedItemPosition],
            lastUsed = existing?.lastUsed ?: 0L,
        )
    }

    private fun save() {
        val profile = read() ?: return

        // Only one profile can open by itself, or two sessions race for the second screen on launch.
        if (profile.autoConnect) {
            val others = store.profiles.filter { it.id != profile.id && it.autoConnect }
            others.forEach { store.save(it.copy(autoConnect = false)) }
        }

        store.save(profile)
        if (store.profiles.size == 1) store.defaultId = profile.id

        finish()
    }

    private fun confirmDelete() {
        val profile = store.byId(editingId) ?: return

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_delete)
            .setMessage(getString(R.string.profiles_delete_confirm, profile.name))
            .setPositiveButton(R.string.profiles_delete) { _, _ ->
                store.delete(profile.id)
                finish()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── testing ─────────────────────────────────────────────────────────────

    /**
     * Ask the address what it is before saving.
     *
     * Uses the same probe the rest of the app uses, so the answer here means the same thing it means
     * on the connect screen: reachable, reachable but not a companion server, or not there at all.
     */
    private fun test() {
        val host = binding.hostField.text.toString().trim()
        val port = binding.portField.text.toString().trim().toIntOrNull()

        if (host.isEmpty() || port == null || port !in 1..65535) {
            say(getString(R.string.status_bad_port))
            return
        }

        binding.testButton.isEnabled = false
        say(getString(R.string.status_testing))

        Thread {
            val result = Probe.run("http://$host:$port")
            runOnUiThread {
                binding.testButton.isEnabled = true

                say(
                    when {
                        result.game != null && result.inGame -> getString(
                            R.string.status_ok_in_game,
                            getString(result.game.label),
                            result.place.orEmpty(),
                        )
                        result.game != null ->
                            getString(R.string.status_ok_menu, getString(result.game.label))
                        result.reachable ->
                            getString(R.string.profile_test_reachable)
                        result.failure == Failure.REFUSED ->
                            getString(R.string.profile_test_refused)
                        result.failure == Failure.UNKNOWN_HOST ->
                            getString(R.string.status_unknown_host)
                        result.failure == Failure.TIMEOUT ->
                            getString(R.string.status_timeout)
                        else ->
                            getString(R.string.status_unreachable, result.detail.orEmpty())
                    }
                )
            }
        }.start()
    }

    private fun say(message: String) {
        binding.testResult.text = message
    }

    companion object {
        const val EXTRA_ID = "id"

        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}
