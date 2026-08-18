package com.abacus.dualscreen

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityFtpBinding

/**
 * Turn the Thor into an FTP server, the way ftpd does it on a 3DS.
 *
 * The screen has one job above all others: show the address to type on the PC. Everything else —
 * port, login, what the server can see — is secondary and sits below it, because the moment after
 * you tap Start you are looking at a different device.
 */
class FtpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFtpBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFtpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        binding.backButton.setOnClickListener { finish() }
        binding.startButton.setOnClickListener { start() }
        binding.stopButton.setOnClickListener { stop() }
        binding.grantButton.setOnClickListener { Storage.requestWholeDeviceAccess(this) }
        binding.clearButton.setOnClickListener { FtpService.live?.clearConsole(); showState() }

        // Tap the address to copy it. Typing an IP and port into a PC by hand, from a handheld
        // screen, is the single most annoying moment in using this feature — and the address is
        // already the biggest thing on the screen, so it is the obvious thing to tap.
        binding.ftpAddress.setOnClickListener { copyAddress() }
        binding.openConsoleButton.setOnClickListener {
            startActivity(android.content.Intent(this, FtpConsoleActivity::class.java))
        }

        binding.portField.setText(settings.ftpPort.toString())
        binding.userField.setText(settings.ftpUser)
        binding.passField.setText(settings.ftpPassword)

        binding.autoStartCheck.isChecked = settings.ftpAutoStart
        binding.autoStartCheck.setOnCheckedChangeListener { _, on -> settings.ftpAutoStart = on }

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    /**
     * Refreshes while this screen is in front.
     *
     * Polled rather than pushed, at a rate a person can read. The server changes state from its own
     * threads, and a callback into a possibly-dead activity is a leak waiting to happen; a poll that
     * stops in onPause cannot outlive the screen. Twice a second is well under what a transfer
     * needs to look live and well over what a battery would notice.
     */
    private val refresh = object : Runnable {
        override fun run() {
            showState()
            binding.root.postDelayed(this, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.root.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(refresh)

        // Only the settings are saved here. The SERVER IS LEFT RUNNING on purpose: you start it,
        // leave the app, and copy files from the PC — a server that died when its screen closed
        // would be useless for the one thing it exists to do. The notification is how you stop it
        // from anywhere.
        save()
    }

    // ── actions ─────────────────────────────────────────────────────────────

    /**
     * Start the server and stay put.
     *
     * This screen deliberately does not finish() here. Starting a server and being thrown back to
     * the home grid tells you nothing about whether it worked; staying means the address, the
     * connection list and the console are all right there the moment your PC touches it — which is
     * the minute that matters.
     */
    /**
     * From Android 13, posting a notification needs permission asked for at runtime.
     *
     * The foreground service's notification IS the Stop control once you leave the app, so without
     * this the server runs invisibly and can only be stopped by coming back here. Asked for at the
     * moment it becomes relevant — on the first Start — rather than at launch, where a permission
     * prompt with no context gets refused out of hand.
     */
    private val askNotifications = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* Either answer is fine; the server does not depend on it. */ }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return

        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun start() {
        save()
        ensureNotificationPermission()

        // Belt as well as braces. The service reports its own failures on the console, but anything
        // thrown on THIS side — a missing platform method, a system refusing the service outright —
        // takes the activity down with it, and a screen that vanishes tells the user nothing. A
        // toast at least names it.
        try {
            FtpService.start(
                context = this,
                port = settings.ftpPort,
                user = settings.ftpUser,
                pass = settings.ftpPassword,
                wholeDevice = settings.ftpWholeDevice,
            )
        } catch (e: Throwable) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.ftp_start_failed, e.message ?: e.javaClass.simpleName),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }

        // Open the console window immediately, the way running a command opens a terminal.
        //
        // No delay waiting for the service: a button that appears to do nothing for a third of a
        // second reads as a button that did nothing, and the console handles an empty log on its
        // first paint perfectly well — it fills in a moment later.
        startActivity(android.content.Intent(this, FtpConsoleActivity::class.java))
    }

    private fun stop() {
        FtpService.stop(this)
    }

    /**
     * Put the first address on the clipboard.
     *
     * The first, not all of them: a device on Wi-Fi and a dock has two, and pasting both into a
     * file manager's address bar gives you neither. The console lists them all for the case where
     * the first is the wrong one.
     */
    private fun copyAddress() {
        val address = FtpServer.localAddresses().firstOrNull() ?: return
        val url = "ftp://$address:${settings.ftpPort}"

        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FTP address", url))

        android.widget.Toast.makeText(
            this, getString(R.string.ftp_copied, url), android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun save() {
        val port = binding.portField.text.toString().toIntOrNull()

        // Below 1024 needs root, which no app has. Anything unusable falls back rather than being
        // stored and failing to bind later, where the reason would be much less obvious.
        settings.ftpPort = if (port != null && port in 1024..65535) port else FtpServer.DEFAULT_PORT
        settings.ftpUser = binding.userField.text.toString().trim()
        settings.ftpPassword = binding.passField.text.toString()
    }

    // ── what the screen says ────────────────────────────────────────────────

    private fun showState() {
        val server = FtpService.live
        val running = server != null

        binding.startButton.visibility = if (running) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (running) View.VISIBLE else View.GONE
        binding.openConsoleButton.visibility = if (running) View.VISIBLE else View.GONE

        // Running, this screen IS the console: the setup fields go away entirely and the log takes
        // the space, the way ftpd looks on a 3DS. Stopped, it is the other way round.
        binding.setupGroup.visibility = if (running) View.GONE else View.VISIBLE

        binding.scopeText.text = Storage.describe()
        binding.grantButton.visibility =
            if (Storage.hasWholeDeviceAccess()) View.GONE else View.VISIBLE

        showAddress(running)
        showClients(server)
        showConsole(server)
    }

    /**
     * The console, and the small piece of care that makes a log usable: it follows new lines only
     * while you are already at the bottom.
     *
     * Auto-scrolling unconditionally would yank the view out from under anyone who scrolled up to
     * read something — which, on a log that moves every time a file manager breathes, is constant.
     */
    private fun showConsole(server: FtpServer?) {
        if (server == null) {
            binding.consoleCard.visibility = View.GONE
            return
        }

        binding.consoleCard.visibility = View.VISIBLE

        val lines = server.console()
        val text = if (lines.isEmpty()) getString(R.string.ftp_console_empty) else lines.joinToString("\n")

        // Skip the work when nothing changed; this runs twice a second.
        if (binding.consoleText.text.toString() == text) return

        val scroller = binding.consoleScroll
        val wasAtBottom = !scroller.canScrollVertically(1)

        binding.consoleText.text = text

        if (wasAtBottom) {
            scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * The address, shown whether or not the server is running.
     *
     * Running, it is the live one. Stopped, it is the address the server *would* have — because
     * knowing where the Thor lives on the network is useful before you start anything, and a screen
     * that says only "Server is off" makes you start it just to find out.
     */
    private fun showAddress(running: Boolean) {
        val addresses = FtpServer.localAddresses()
        val port = settings.ftpPort

        if (addresses.isEmpty()) {
            binding.ftpAddress.text = getString(R.string.ftp_no_network)
            binding.ftpStatus.text = getString(R.string.ftp_no_network_hint)
            return
        }

        binding.ftpAddress.text = addresses.joinToString("\n") { "ftp://$it:$port" }

        binding.ftpStatus.text = when {
            !running -> getString(R.string.ftp_off_hint)
            settings.ftpUser.isEmpty() -> getString(R.string.ftp_anonymous)
            else -> getString(R.string.ftp_login_as, settings.ftpUser)
        }
    }

    /**
     * Who is attached, on one line above the console.
     *
     * Deliberately a single line rather than the list it used to be: the console below already says
     * what each client is doing, in more detail and in order, so a second panel repeating it was
     * taking space from the log to say the same thing worse.
     */
    private fun showClients(server: FtpServer?) {
        if (server == null) return

        val clients = server.clients()
        binding.clientsText.text = when (clients.size) {
            0 -> getString(R.string.ftp_nobody)
            1 -> getString(R.string.ftp_one_client, clients[0].address)
            else -> getString(R.string.ftp_many_clients, clients.size)
        }
    }
}
