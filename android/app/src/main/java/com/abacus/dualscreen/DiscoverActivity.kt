package com.abacus.dualscreen

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.connect.Awake
import com.abacus.dualscreen.connect.ConnectionProfile
import com.abacus.dualscreen.connect.Connector
import com.abacus.dualscreen.connect.DisplayChoice
import com.abacus.dualscreen.connect.ProfileStore
import com.abacus.dualscreen.databinding.ActivityDiscoverBinding

/**
 * Find companion servers on this network.
 *
 * There is no discovery protocol to listen for. The companion mods are ordinary HTTP servers that
 * bind a port and announce nothing — no mDNS, no broadcast — so the only honest way to find one is
 * to ask every address on the subnet whether that port is open, then ask what answered. That is what
 * this does, and the limitation is stated on the screen rather than hidden: it cannot see past a
 * router, and it cannot find a server on a port nobody thought to try.
 *
 * The ports tried by default come from the mod presets the app already knows about, plus every port
 * a saved profile uses — so somebody running a companion server on an unusual port finds it on the
 * second scan without typing anything.
 */
class DiscoverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscoverBinding
    private lateinit var settings: Settings
    private lateinit var store: ProfileStore

    @Volatile
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscoverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = ProfileStore(this)

        com.abacus.dualscreen.ui.Nav.back(this, binding.backButton)
        binding.scanButton.setOnClickListener { scan() }
        binding.portsField.setText(defaultPorts().joinToString(", "))

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    /** Mod defaults and whatever the saved profiles use, without duplicates. */
    private fun defaultPorts(): List<Int> {
        val presets = Game.entries.filter { it.isMod }.map { it.defaultPort }
        val saved = store.profiles.map { it.port }
        return (presets + saved).distinct().sorted()
    }

    private fun readPorts(): List<Int> = binding.portsField.text.toString()
        .split(',', ' ', ';')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..65535 }
        .distinct()
        .take(MAX_PORTS)

    // ── scanning ────────────────────────────────────────────────────────────

    private fun scan() {
        if (scanning) return

        val ports = readPorts()
        if (ports.isEmpty()) {
            binding.discoverStatus.text = getString(R.string.status_bad_port)
            return
        }

        scanning = true
        binding.scanButton.isEnabled = false
        binding.resultList.removeAllViews()
        binding.discoverStatus.text = getString(R.string.status_scanning, 0)

        Thread {
            val results = Scanner.sweep(ports) { progress ->
                val percent = (progress * 100).toInt()
                runOnUiThread {
                    if (scanning) binding.discoverStatus.text = getString(R.string.status_scanning, percent)
                }
            }

            runOnUiThread {
                scanning = false
                binding.scanButton.isEnabled = true
                show(results, ports)
            }
        }.start()
    }

    private fun show(results: List<Found>, ports: List<Int>) {
        binding.resultList.removeAllViews()

        if (results.isEmpty()) {
            binding.discoverStatus.text =
                getString(R.string.discover_none, ports.joinToString(", "))
            return
        }

        val servers = results.count { it.game != null }
        binding.discoverStatus.text = getString(R.string.discover_found, results.size, servers)

        for (found in results) binding.resultList.addView(row(found))
    }

    private fun row(found: Found): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = Appearance.panel(
                this@DiscoverActivity, settings,
                getColor(R.color.card_hi),
                if (found.game != null) Appearance.accentOf(settings) else getColor(R.color.edge),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            setOnClickListener { connect(found) }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        column.addView(TextView(this).apply {
            text = "${found.host}:${found.port}"
            setTextColor(Appearance.accentOf(settings))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })

        column.addView(TextView(this).apply {
            text = status(found)
            setTextColor(getColor(if (found.game != null) R.color.state_ok else R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), 0, 0)
        })

        row.addView(column)

        row.addView(Button(this).apply {
            text = getString(R.string.discover_save)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.text_dim))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Appearance.panel(
                this@DiscoverActivity, settings, getColor(R.color.card), getColor(R.color.edge)
            )
            setOnClickListener { saveAsProfile(found) }
        })

        return row
    }

    /**
     * What was found there, as precisely as the probe could tell.
     *
     * Three genuinely different answers: a companion server with a world loaded, one sitting at its
     * menu, and a port that is open but is something else entirely. The third is not a failure and
     * is not hidden — knowing that something answered saves somebody assuming the scan is broken.
     */
    private fun status(found: Found): String = when {
        found.game != null && found.place != null ->
            getString(R.string.discover_in_game, getString(found.game.label), found.place)
        found.game != null ->
            getString(R.string.discover_at_menu, getString(found.game.label))
        else ->
            getString(R.string.discover_open_port)
    }

    // ── acting on a result ──────────────────────────────────────────────────

    private fun connect(found: Found) {
        val existing = store.profiles.firstOrNull { it.host == found.host && it.port == found.port }
        Connector.open(this, existing ?: temporary(found), store)
    }

    private fun temporary(found: Found) = ConnectionProfile(
        id = ConnectionProfile.newId(),
        name = found.game?.let { getString(it.label) } ?: found.host,
        host = found.host,
        port = found.port,
        preset = found.game?.id ?: "custom",
        display = DisplayChoice.byId(settings.displayChoice),
        orientation = com.abacus.dualscreen.connect.Orientation.byId(settings.orientation),
        awake = Awake.byId(settings.awakeMode),
    )

    private fun saveAsProfile(found: Found) {
        val already = store.profiles.firstOrNull { it.host == found.host && it.port == found.port }
        if (already != null) {
            toast(getString(R.string.discover_already, already.name))
            return
        }

        val profile = temporary(found).let { it.copy(name = store.uniqueName(it.name)) }
        store.save(profile)
        if (store.profiles.size == 1) store.defaultId = profile.id

        AlertDialog.Builder(this)
            .setTitle(R.string.discover_save)
            .setMessage(getString(R.string.discover_saved, profile.name, profile.address))
            .setPositiveButton(R.string.profiles_connect) { _, _ -> Connector.open(this, profile, store) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** A sweep per port, so the list is capped before somebody pastes a hundred of them. */
        const val MAX_PORTS = 8
    }
}
