package com.abacus.dualscreen

import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.WindowManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityFtpConsoleBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The FTP console, laid out the way ftpd looks on a Switch.
 *
 * A blue status strip with the address, the clock and the free space; a menu row; the log with
 * `[COMMAND]` in green and `[RESPONSE]` in cyan; and a boxed panel at the bottom showing the
 * connected client and any transfer in flight, with size, rate and time remaining.
 *
 * It reads [FtpService.live] rather than owning a server, so closing this window stops nothing.
 */
class FtpConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFtpConsoleBinding

    /** Four times a second: a transfer readout at one hertz looks stuck. */
    private val refresh = object : Runnable {
        override fun run() {
            draw()
            binding.root.postDelayed(this, 250)
        }
    }

    private val version: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFtpConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // A console someone is watching a transfer on should not lock halfway through. Dropped in
        // onPause, so it never outlives the window.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.stopButton.setOnClickListener {
            FtpService.stop(this)
            finish()
        }

        binding.clearButton.setOnClickListener {
            FtpService.live?.clearConsole()
            draw()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.root.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(refresh)
    }

    // ── drawing ─────────────────────────────────────────────────────────────

    private fun draw() {
        binding.consoleClock.text = CLOCK.format(Date())
        binding.consoleStorage.text = "${freeSpace()}  ${battery()}%"

        val server = FtpService.live
        if (server == null) {
            drawStopped()
            return
        }

        val port = Settings(this).ftpPort
        val address = server.addresses().firstOrNull()

        // ftpd's strip reads:  ftpd v3.1.0 [10.10.200.224]:5000
        binding.consoleTitle.text = if (address == null) {
            "AynFTP v$version  [no network]"
        } else {
            "AynFTP v$version  [$address]:$port"
        }

        binding.stopButton.visibility = View.VISIBLE
        drawLog(server)
        drawSessions(server)
    }

    private fun drawStopped() {
        binding.stopButton.visibility = View.GONE
        binding.consoleTitle.text = "AynFTP v$version"

        val failure = FtpService.lastError
        binding.consoleText.text = if (failure != null) {
            "[ERROR] $failure\n\n${getString(R.string.ftp_failed_hint)}"
        } else {
            "[INFO] ${getString(R.string.ftp_console_stopped_hint)}"
        }

        binding.sessionsText.text = getString(R.string.ftp_console_stopped)
    }

    /**
     * The log, with the tags coloured.
     *
     * Built as a spannable rather than HTML: the log can be three hundred lines and re-parsing HTML
     * four times a second to colour two words per line would be absurd. Only the tag is spanned;
     * everything after it stays plain, which is what makes the tags readable at a glance.
     */
    private fun drawLog(server: FtpServer) {
        val lines = server.console()
        val text = if (lines.isEmpty()) "[INFO] ${getString(R.string.ftp_console_empty)}" else lines.joinToString("\n")

        // This runs four times a second and the log is usually unchanged.
        if (binding.consoleText.tag == text) return
        binding.consoleText.tag = text

        val spanned = SpannableStringBuilder(text)
        for (tag in TAGS) {
            var at = text.indexOf(tag.key)
            while (at >= 0) {
                spanned.setSpan(
                    ForegroundColorSpan(tag.value),
                    at, at + tag.key.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                at = text.indexOf(tag.key, at + tag.key.length)
            }
        }

        val scroller = binding.consoleScroll
        val wasAtBottom = !scroller.canScrollVertically(1)

        binding.consoleText.text = spanned

        if (wasAtBottom) scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * The session panel: who is connected, and what is moving right now.
     *
     * This is the part worth getting right — "is it doing anything, and how long" is the question
     * you stare at a transfer screen to answer.
     */
    private fun drawSessions(server: FtpServer) {
        val clients = server.clients()

        if (clients.isEmpty()) {
            binding.sessionsText.text = getString(R.string.ftp_nobody)
            return
        }

        binding.sessionsText.text = clients.joinToString("\n\n") { client ->
            val header = "Connected to ${client.address}"

            if (client.transferName.isEmpty()) {
                "$header\n${client.doing}"
            } else {
                val direction = if (client.sending) "Downloading" else "Uploading"
                val done = server.human(client.transferDone)
                val rate = server.human(client.rate)

                val size = if (client.transferTotal > 0) {
                    val percent = client.transferDone * 100 / client.transferTotal.coerceAtLeast(1)
                    "$done / ${server.human(client.transferTotal)}  ($percent%)"
                } else {
                    // An upload never declares its size, so there is no total and no percentage —
                    // showing one would mean inventing it.
                    done
                }

                val eta = when (val seconds = client.eta) {
                    -1L -> ""
                    else -> "  ETA ${"%d:%02d".format(seconds / 60, seconds % 60)}"
                }

                "$header\n$direction ${client.transferName}\n$size  $rate/s$eta"
            }
        }
    }

    // ── the status strip's right-hand side ──────────────────────────────────

    private fun freeSpace(): String {
        val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull()
            ?: return ""
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return "%.1fGiB".format(free.toDouble() / (1L shl 30))
    }

    private fun battery(): Int {
        val manager = getSystemService(BatteryManager::class.java) ?: return 0
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    companion object {
        private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)

        /** ftpd's colours: commands green, responses cyan, trouble red. */
        private val TAGS = linkedMapOf(
            "[COMMAND]" to Color.parseColor("#5BD75B"),
            "[RESPONSE]" to Color.parseColor("#6FD3E8"),
            "[INFO]" to Color.parseColor("#D0D0D0"),
            "[ERROR]" to Color.parseColor("#E56B6B"),
        )
    }
}
