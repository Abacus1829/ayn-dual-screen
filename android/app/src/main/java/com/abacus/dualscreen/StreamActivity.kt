package com.abacus.dualscreen

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityStreamBinding
import com.abacus.dualscreen.stream.HostConnection
import com.abacus.dualscreen.stream.HostInfo
import com.abacus.dualscreen.stream.HostStore
import com.abacus.dualscreen.stream.Identity
import com.abacus.dualscreen.stream.Pairing
import kotlin.concurrent.thread

/**
 * Pair this Thor with a PC running Sunshine or GeForce Experience.
 *
 * **This screen does not stream anything yet, and says so at the top.** Pairing is the half that is
 * built: the app can find a host, complete the handshake, and remember it. Nothing decodes video.
 * Somebody who pairs successfully and then hunts for a Play button should have been told before they
 * started rather than after.
 *
 * What it is good for today is proving the hard part works. Pairing is where the cryptography lives
 * and where a mistake is invisible until the last step, so a screen that can run it, name the step
 * that failed, and be run again is worth having on its own.
 */
class StreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamBinding
    private lateinit var settings: Settings
    private lateinit var hosts: HostStore

    private val main = Handler(Looper.getMainLooper())

    /** Everything below is set on a worker thread and read on the main one, hence the volatiles. */
    @Volatile private var identity: Identity? = null
    @Volatile private var info: HostInfo? = null
    @Volatile private var busy = false

    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        hosts = HostStore(this)

        com.abacus.dualscreen.ui.Nav.back(this, binding.backButton)
        binding.connectButton.setOnClickListener { connect() }
        binding.pairButton.setOnClickListener { pair() }
        binding.unpairButton.setOnClickListener { forget() }

        binding.hostField.setText(hosts.lastAddress)

        // Generating an RSA key takes a second or two on a handheld, and it only ever happens once.
        // Doing it now, off the main thread, means the Connect button is never the thing that
        // stutters.
        thread { identity = Identity.of(applicationContext) }

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    // ── talking to the host ─────────────────────────────────────────────────

    private fun connect() {
        val address = binding.hostField.text.toString().trim()
        if (address.isEmpty() || busy) return

        hosts.lastAddress = address
        busy = true
        note("Asking $address who it is…")

        thread {
            val me = identity ?: Identity.of(applicationContext).also { identity = it }

            // If we have paired before, present the stored certificate — over plain HTTP a host
            // always reports you as unpaired, which is the single most confusing thing about this
            // protocol.
            val probe = HostConnection(address, me)
            val first = probe.serverInfo()

            val found = if (first != null) {
                val pinned = hosts.certificateFor(first, address)
                if (pinned != null) {
                    HostConnection(address, me, pinned).serverInfo() ?: first
                } else first
            } else null

            main.post {
                busy = false
                info = found

                if (found == null) {
                    note("Nothing answered. Is Sunshine running, and is this the right address?")
                    binding.hostCard.visibility = View.GONE
                } else {
                    note("Found ${found.hostname.ifEmpty { address }}.")
                    showHost(found, address)
                }
            }
        }
    }

    private fun showHost(host: HostInfo, address: String) {
        val paired = host.paired || hosts.isPaired(host, address)

        binding.hostCard.visibility = View.VISIBLE
        binding.hostName.text = host.hostname.ifEmpty { address }

        binding.hostDetail.text = buildString {
            appendLine(if (paired) getString(R.string.stream_state_paired) else getString(R.string.stream_state_unpaired))
            appendLine(getString(R.string.stream_detail_software, if (host.isSunshine) "Sunshine" else "GeForce Experience"))
            appendLine(getString(R.string.stream_detail_version, host.appVersion.ifEmpty { "unknown" }))
            if (host.hevcSupported) appendLine(getString(R.string.stream_detail_hevc))
            if (host.busy) appendLine(getString(R.string.stream_detail_busy))
        }.trim()

        binding.pairButton.visibility = if (paired) View.GONE else View.VISIBLE
        binding.unpairButton.visibility = if (paired) View.VISIBLE else View.GONE
    }

    // ── pairing ─────────────────────────────────────────────────────────────

    private fun pair() {
        val host = info ?: return
        val address = binding.hostField.text.toString().trim()
        val me = identity ?: return
        if (busy) return

        val pin = Pairing.newPin()

        // The PIN goes up BEFORE the handshake starts. The host shows its prompt the moment the
        // first request lands, and a PIN that appears after that is a PIN nobody can type in time.
        binding.pinText.text = pin
        binding.pinCard.visibility = View.VISIBLE
        binding.pairButton.isEnabled = false
        busy = true

        note("Pairing with ${host.hostname.ifEmpty { address }}…")
        note("Type $pin into Sunshine's PIN page.")

        thread {
            val connection = HostConnection(address, me)
            val result = Pairing(connection, me, host).pair(pin)

            main.post {
                busy = false
                binding.pairButton.isEnabled = true
                binding.pinCard.visibility = View.GONE

                when (result) {
                    is Pairing.Result.Paired -> {
                        hosts.save(host, address, result.certificate)
                        note("Paired. This PC will be remembered.")
                        connect()          // re-read, so the card shows the new state honestly
                    }

                    is Pairing.Result.WrongPin ->
                        note("The host refused. Usually the PIN was wrong or was never entered.")

                    is Pairing.Result.Refused ->
                        note("The host refused at ${result.step}. It may already be pairing with something else.")

                    is Pairing.Result.Unreachable ->
                        note("Lost the host at ${result.step}.")
                }
            }
        }
    }

    private fun forget() {
        val host = info ?: return
        val address = binding.hostField.text.toString().trim()

        hosts.forget(host, address)
        note("Forgotten on this device. The PC still lists it — unpair there too if you want it gone.")
        connect()
    }

    // ── the log ─────────────────────────────────────────────────────────────

    /**
     * One line in the on-screen log.
     *
     * Pairing has five steps and each fails differently, so "it didn't work" is a useless thing for
     * this screen to say. Every step that happens gets a line, and the failures name the step.
     */
    private fun note(line: String) {
        log.append(line).append('\n')
        binding.logCard.visibility = View.VISIBLE
        binding.logText.text = log.toString().trim()
    }
}
