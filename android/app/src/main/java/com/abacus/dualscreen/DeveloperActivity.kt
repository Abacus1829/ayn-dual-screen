package com.abacus.dualscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityDeveloperBinding
import com.abacus.dualscreen.ui.Sounds
import com.abacus.dualscreen.companion.DeviceStats
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Nav

/**
 * The tools for working on this app *on the device it runs on*.
 *
 * It exists because of a specific frustration: the handheld is where the interesting bugs live, and
 * every way of investigating one of them starts with a cable. Half of what a developer needs to know
 * — what version is installed, which panels the system reports, what the buttons actually send — is
 * knowable on the device itself, and the other half only needs the device to be reachable over the
 * network.
 *
 * Nothing here changes a system setting. **Wireless debugging cannot be switched on by an app**, and
 * it should not be: it is the switch that lets another machine on the network install software. What
 * this screen does is the honest half — show the address, show the exact command, say whether the
 * switch appears to be on, and open the system page where the user turns it on themselves.
 */
class DeveloperActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeveloperBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeveloperBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        Nav.back(this, binding.backButton)

        binding.inputButton.setOnClickListener {
            Feedback.tap(it)
            startActivity(Intent(this, InputTestActivity::class.java))
        }

        binding.developerOptionsButton.setOnClickListener {
            Feedback.tap(it)
            openDeveloperOptions()
        }

        binding.copyCommandButton.setOnClickListener {
            Feedback.tap(it)
            copy(binding.commandText.text.toString())
        }

        binding.copyInfoButton.setOnClickListener {
            Feedback.tap(it)
            copy(deviceReport())
        }

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        // Both halves can have changed while the user was on a system page.
        showConnection()
        binding.deviceText.text = deviceReport()
    }

    // ── connecting over Wi-Fi ───────────────────────────────────────────────

    /**
     * The address a PC would connect to, and whether the device looks willing.
     *
     * The port is the interesting part. Android 10 and earlier accept `adb connect <ip>:5555` once
     * ADB over TCP is running. Android 11 introduced **wireless debugging**, which allocates a
     * *random* port per session and shows it on its own settings page — no app can read it, so the
     * honest thing is to say where to look rather than to print a port that will be wrong.
     */
    private fun showConnection() {
        val addresses = FtpServer.localAddresses()
        val address = addresses.firstOrNull()

        binding.addressText.text = when {
            address == null -> getString(R.string.dev_no_network)
            addresses.size == 1 -> address
            else -> addresses.joinToString("\n")
        }

        binding.commandText.text = when {
            address == null -> getString(R.string.dev_no_network)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "adb connect $address:<port>"
            else -> "adb connect $address:5555"
        }

        /*
         * Pairing, and only where it applies.
         *
         * Android 11 added it and it is the step that catches everybody out: a PC that has never
         * paired is refused by `adb connect` with a message that says nothing about pairing. It is
         * once per PC. Before Android 11 there is no such thing, so the whole block goes away rather
         * than sitting there as an instruction that cannot be followed.
         */
        val pairs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && address != null
        val pairing = if (pairs) View.VISIBLE else View.GONE
        binding.pairLabel.visibility = pairing
        binding.pairCommandText.visibility = pairing
        binding.pairNote.visibility = pairing
        if (pairs) binding.pairCommandText.text = "adb pair $address:<pairing port>"

        binding.portNote.setText(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) R.string.dev_port_modern
            else R.string.dev_port_legacy
        )

        val enabled = runCatching {
            AndroidSettings.Global.getInt(contentResolver, AndroidSettings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)

        // adb_wifi_enabled is what the wireless debugging switch writes. Readable, not writable.
        val wireless = runCatching {
            AndroidSettings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)

        val state = when {
            wireless -> R.string.dev_adb_wireless_on
            enabled -> R.string.dev_adb_usb_only
            else -> R.string.dev_adb_off
        }
        binding.adbStateText.setText(state)
        binding.copyCommandButton.visibility = if (address == null) View.GONE else View.VISIBLE
    }

    /** The system page. Developer options may not be unlocked yet, so fall back to Settings itself. */
    private fun openDeveloperOptions() {
        val pages = listOf(
            AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            AndroidSettings.ACTION_SETTINGS,
        )

        for (page in pages) {
            val opened = runCatching {
                startActivity(Intent(page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }

        Feedback.failed(this, binding.root, getString(R.string.dev_no_settings_page))
    }

    // ── what this device is ─────────────────────────────────────────────────

    /**
     * Everything worth pasting into a bug report, gathered.
     *
     * The display list is here rather than anywhere prettier because this app is about second
     * screens, and "how many displays does the system say you have, and how big are they" is the
     * first question every display bug turns into.
     */
    /** This activity's display, for the readings that are a property of the panel itself. */
    private val panelDisplay: android.view.Display?
        get() = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display
            else @Suppress("DEPRECATION") windowManager.defaultDisplay
        }.getOrNull()

    private fun deviceReport(): String = buildString {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val code = when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> @Suppress("DEPRECATION") info.versionCode.toLong()
        }

        append("app       ").append(info?.versionName ?: "?").append("  (versionCode ").append(code).append(")\n")
        append("device    ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("android   ").append(Build.VERSION.RELEASE).append("  (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("build     ").append(Build.DISPLAY).append('\n')
        append("abi       ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')

        val manager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val displays = manager?.displays.orEmpty().filter { it.isValid }
        append("displays  ").append(displays.size).append('\n')
        displays.forEach { display ->
            val metrics = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
            append("          [").append(display.displayId).append("] ")
                .append(display.name.ifBlank { "unnamed" }).append("  ")
                .append(metrics.widthPixels).append('x').append(metrics.heightPixels)
                .append("  ").append(metrics.densityDpi).append("dpi\n")
        }

        /*
         * What the hardware will actually tell us, and what it will not.
         *
         * Attached to the report that already has a Copy button rather than given a screen of its
         * own, because its whole purpose is to be read off the console and sent back. Half of what
         * a hardware dashboard wants to show sits behind sysfs paths that some builds expose and
         * others refuse, and there is no finding out which this device is from a desk.
         */
        append("\n── hardware readings ──\n")
        append(DeviceStats.describe(this@DeveloperActivity, panelDisplay))

        append("\n\n── interface sound ──\n")
        append(Sounds.diagnose(this@DeveloperActivity))
        append('\n')
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ayn dual screen", text))
        Feedback.toast(this, getString(R.string.dev_copied))
    }
}
