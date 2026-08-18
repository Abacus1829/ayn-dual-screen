package com.abacus.dualscreen

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * An FTP server for the Thor, in the spirit of the 3DS's ftpd: point a file manager on your PC at the
 * handheld and move files about, without a cable and without a desktop client to install.
 *
 * Hand-rolled rather than taken from a library, for the same reason every other server in this
 * repository is: FTP is a line-based protocol with about fifteen verbs worth supporting, and a
 * dependency would be more machinery than that justifies — plus this one has to answer to Android's
 * storage rules, which a general-purpose FTP library knows nothing about.
 *
 * ## What it speaks
 *
 * Enough of RFC 959 for real clients (WinSCP, FileZilla, Windows Explorer, curl) to work:
 * USER/PASS, PWD/CWD/CDUP, LIST/NLST, RETR/STOR/APPE, DELE/MKD/RMD/RNFR/RNTO, SIZE/MDTM, TYPE,
 * PASV and EPSV, PORT and EPRT, REST, NOOP, SYST, FEAT, QUIT. UTF-8 filenames throughout.
 *
 * Passive mode is the one that matters: a PC connecting to the Thor over Wi-Fi is nearly always
 * behind something that makes active mode fail, so PASV is what clients actually use. Active mode
 * is here because it costs thirty lines and some old clients still ask for it.
 *
 * ## What it deliberately does not do
 *
 * **No encryption.** This is plain FTP, on your own network, exactly like the 3DS one. Anything you
 * type at it — including the password, if you set one — goes over the air in the clear. It is off by
 * default and it is not something to leave running on a network you don't trust.
 */
class FtpServer(
    /** The port to listen on. 2121 rather than 21: binding below 1024 needs root, which we don't have. */
    private val port: Int = DEFAULT_PORT,

    /** Where a client starts, and — unless [restrictToRoot] is false — the highest it can climb. */
    private val root: File,

    /** Empty means anonymous: any username, any password. Matches ftpd's default on a 3DS. */
    private val username: String = "",
    private val password: String = "",

    /** Refuse to serve anything above [root]. Off only makes sense when root is already "/". */
    private val restrictToRoot: Boolean = true,

    /** Told whenever the connected set changes, so the UI can show who is attached. */
    private val onClients: (List<Client>) -> Unit = {},
) {

    /**
     * One attached client, as the screen shows it.
     *
     * [doing] is the last command received, which is the difference between a screen that says
     * "1 connected" and one that says what is actually happening — a transfer that has stalled looks
     * identical to an idle connection without it.
     */
    data class Client(
        val address: String,
        val since: Long = System.currentTimeMillis(),
        @Volatile var doing: String = "connected",

        /** The file moving right now, or empty between transfers. */
        @Volatile var transferName: String = "",

        /** Which way it is going, for the wording on screen. */
        @Volatile var sending: Boolean = true,

        /** Bytes moved so far, and the total when it is known — an upload's size is not. */
        @Volatile var transferDone: Long = 0,
        @Volatile var transferTotal: Long = 0,

        @Volatile var transferStarted: Long = 0,
    ) {
        /** Bytes a second since this transfer began, or 0 before there is anything to divide by. */
        val rate: Long
            get() {
                val elapsed = System.currentTimeMillis() - transferStarted
                return if (transferStarted == 0L || elapsed < 250) 0 else transferDone * 1000 / elapsed
            }

        /**
         * Seconds left, or -1 when it cannot be known.
         *
         * An upload has no declared size — FTP simply streams until the socket closes — so there is
         * nothing to subtract from and no honest estimate to give. Downloads know their total.
         */
        val eta: Long
            get() {
                val speed = rate
                return if (transferTotal <= 0 || speed <= 0) -1
                else (transferTotal - transferDone).coerceAtLeast(0) / speed
            }
    }

    private val running = AtomicBoolean(false)
    private var listener: ServerSocket? = null

    /** Guarded by itself; read by the UI thread and written by every session thread. */
    private val clients = mutableListOf<Client>()

    val isRunning: Boolean get() = running.get()

    /** A snapshot for the UI. A copy, because the live list moves under a reader. */
    fun clients(): List<Client> = synchronized(clients) { clients.toList() }

    // ── the console ─────────────────────────────────────────────────────────

    /**
     * The recent history of the conversation, for the on-screen console.
     *
     * A ring buffer rather than a growing list: this is a server that might sit running for hours
     * with a file manager polling it, and an unbounded log of that is a memory leak with a friendly
     * name. [CONSOLE_LINES] is about a screen and a half of scrollback, which is as far as anybody
     * reads before giving up and looking at the file instead.
     */
    private val console = ArrayDeque<String>()

    fun console(): List<String> = synchronized(console) { console.toList() }

    fun clearConsole() {
        synchronized(console) { console.clear() }
    }

    /**
     * Add a line, stamped with the time.
     *
     * Called from every session thread, so it is synchronised — and it never receives a password:
     * the session masks PASS before it gets here, because this text goes on a screen somebody might
     * be showing to a room.
     */
    private fun note(line: String) {
        val stamp = CONSOLE_TIME.format(System.currentTimeMillis())
        synchronized(console) {
            console.addLast("$stamp  $line")
            while (console.size > CONSOLE_LINES) console.removeFirst()
        }
    }

    private fun addClient(client: Client) {
        synchronized(clients) { clients += client }
        note("[INFO] Connected to ${client.address}")
        onClients(clients())
    }

    private fun removeClient(client: Client) {
        synchronized(clients) { clients -= client }
        note("[INFO] ${client.address} disconnected")
        onClients(clients())
    }

    /** Bytes as a person reads them. Two significant places is all a transfer readout needs. */
    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GiB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MiB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KiB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    /**
     * Bind and start accepting. Returns the port on success, or null if the port was taken — the
     * caller shows that rather than the app dying, the same as the mods do when their port is busy.
     */
    fun start(): Int? {
        if (running.get()) return port

        val socket = try {
            ServerSocket(port)
        } catch (e: IOException) {
            Log.w(TAG, "Could not listen on $port", e)
            return null
        }

        listener = socket
        running.set(true)

        // The console opens with what a person needs to act on, the way ftpd's does: where to point
        // the PC, and what it will find when it gets there.
        note("[INFO] Started server at [${addresses().firstOrNull() ?: "?"}]:$port")
        note("[INFO] Serving ${root.path}")
        note(if (username.isEmpty()) "[INFO] Anonymous login" else "[INFO] Login required as $username")

        thread(name = "ftp-accept") {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "Accept failed", e)
                    continue
                }

                val record = Client(client.inetAddress?.hostAddress ?: "unknown")
                addClient(record)

                thread(name = "ftp-session") {
                    try {
                        Session(client, record).run()
                    } catch (e: Exception) {
                        // A misbehaving client must never take the app down with it.
                        Log.w(TAG, "Session ended badly", e)
                    } finally {
                        try { client.close() } catch (_: IOException) {}
                        removeClient(record)
                    }
                }
            }
        }

        return port
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { listener?.close() } catch (_: IOException) {}   // unblocks accept()
        listener = null
    }

    /**
     * Every address a PC on the network could type at this server.
     *
     * Read off the interface list rather than WifiManager, the same way [Scanner] does it: no extra
     * permission, and it covers Ethernet from a dock as well as Wi-Fi. Loopback is left out because
     * an address only this device can reach is no use to the PC you are trying to copy files from,
     * and 169.254.x.x means DHCP never answered — printing it sends someone chasing a dead address.
     */
    fun addresses(): List<String> = localAddresses()

    // ── one client ──────────────────────────────────────────────────────────

    private inner class Session(private val control: Socket, private val record: Client) {

        private val reader = control.getInputStream().bufferedReader(Charsets.UTF_8)
        private val writer = control.getOutputStream().bufferedWriter(Charsets.UTF_8)

        /** Current directory, always absolute and always real — resolved, never a path fragment. */
        private var cwd: File = root

        private var authenticated = username.isEmpty()
        private var givenUser: String? = null

        /** Passive listener, alive between PASV and the transfer that uses it. */
        private var passive: ServerSocket? = null

        /** Active-mode target, from PORT/EPRT. */
        private var activeHost: InetAddress? = null
        private var activePort: Int = 0

        /** Byte offset from REST, consumed by the next RETR or STOR and then forgotten. */
        private var restartAt: Long = 0

        /** RNFR's subject, waiting for the RNTO that completes the rename. */
        private var renameFrom: File? = null

        fun run() {
            control.soTimeout = IDLE_TIMEOUT_MS
            reply(220, "Ayn Dual Screen FTP ready.")

            while (running.get()) {
                val line = try {
                    reader.readLine() ?: break
                } catch (e: SocketTimeoutException) {
                    reply(421, "Idle too long, closing.")
                    break
                }

                val space = line.indexOf(' ')
                val verb = (if (space < 0) line else line.substring(0, space)).uppercase(Locale.ROOT)
                val argument = if (space < 0) "" else line.substring(space + 1).trim()

                // The password is the one thing never written to the log, or shown on the screen.
                val shown = if (verb == "PASS") "PASS ****" else line
                Log.d(TAG, shown)
                note("[COMMAND] $shown")

                record.doing = describe(verb, argument)
                onClients(clients())

                if (!handle(verb, argument)) break
            }

            closePassive()
        }

        /**
         * The line the screen shows for this client: plain English, and never the password.
         *
         * FTP verbs are not something anybody should have to read to know what their PC is doing.
         * "Downloading save.dat" is the answer to the question actually being asked.
         */
        private fun describe(verb: String, argument: String): String {
            val name = argument.substringAfterLast('/').ifEmpty { argument }
            return when (verb) {
                "RETR" -> "Downloading $name"
                "STOR", "APPE" -> "Uploading $name"
                "LIST", "NLST" -> "Browsing ${if (name.isEmpty() || name.startsWith("-")) displayPath(cwd) else name}"
                "CWD", "CDUP" -> "Browsing $name"
                "DELE" -> "Deleting $name"
                "MKD", "XMKD" -> "Creating $name"
                "RMD", "XRMD" -> "Removing $name"
                "RNFR", "RNTO" -> "Renaming"
                "PASS", "USER" -> "Logging in"
                "SIZE", "MDTM" -> "Checking $name"
                "QUIT" -> "Disconnecting"
                else -> "Connected"
            }
        }

        /** Returns false when the session should end. */
        private fun handle(verb: String, argument: String): Boolean {
            // Everything except the handshake verbs needs a login first.
            if (!authenticated && verb !in setOf("USER", "PASS", "QUIT", "FEAT", "SYST", "NOOP")) {
                reply(530, "Log in first.")
                return true
            }

            when (verb) {
                "USER" -> {
                    givenUser = argument
                    if (username.isEmpty()) {
                        authenticated = true
                        reply(230, "Anonymous access, come in.")
                    } else {
                        reply(331, "Password required.")
                    }
                }

                "PASS" -> {
                    // Compared in full rather than short-circuiting, out of habit. This is plain FTP
                    // on a LAN, so it is a doorlock and not a secret worth defending properly.
                    authenticated = givenUser == username && argument == password

                    if (authenticated) {
                        reply(230, "Logged in.")
                    } else {
                        // Logged loudly. A failed login on a server sharing your whole device is
                        // worth seeing on the console, and it is the one line somebody scanning
                        // the log for trouble is looking for.
                        note("[ERROR] ${record.address} failed to log in as ${givenUser.orEmpty()}")
                        reply(530, "Wrong.")
                    }
                }

                "SYST" -> reply(215, "UNIX Type: L8")
                "NOOP" -> reply(200, "Still here.")
                "TYPE" -> reply(200, "Fine.")           // every transfer is binary; ASCII mangles files

                "FEAT" -> {
                    // UTF8 is the one that matters: without it, clients guess at Latin-1 and every
                    // non-English filename on the device turns to mojibake.
                    write("211-Features:")
                    write(" UTF8"); write(" SIZE"); write(" MDTM"); write(" REST STREAM"); write(" EPSV"); write(" EPRT")
                    reply(211, "End")
                }

                "OPTS" -> if (argument.uppercase(Locale.ROOT).startsWith("UTF8")) {
                    reply(200, "Always UTF-8.")
                } else {
                    reply(501, "Not an option here.")
                }

                "PWD", "XPWD" -> reply(257, "\"${displayPath(cwd)}\" is the current directory.")

                "CWD" -> {
                    val target = resolve(argument)
                    if (target != null && target.isDirectory) {
                        cwd = target
                        reply(250, "Now in ${displayPath(cwd)}")
                    } else {
                        reply(550, "No such directory.")
                    }
                }

                "CDUP" -> {
                    val up = resolve("..")
                    if (up != null && up.isDirectory) { cwd = up; reply(250, "Up.") }
                    else reply(550, "Cannot go up.")
                }

                "PASV" -> openPassive(extended = false)
                "EPSV" -> openPassive(extended = true)
                "PORT" -> parsePort(argument)
                "EPRT" -> parseExtendedPort(argument)

                "LIST" -> listing(argument, detailed = true)
                "NLST" -> listing(argument, detailed = false)

                "RETR" -> retrieve(argument)
                "STOR" -> store(argument, append = false)
                "APPE" -> store(argument, append = true)

                "REST" -> {
                    restartAt = argument.toLongOrNull() ?: 0
                    reply(350, "Restarting at $restartAt.")
                }

                "SIZE" -> {
                    val file = resolve(argument)
                    if (file != null && file.isFile) reply(213, file.length().toString())
                    else reply(550, "No such file.")
                }

                "MDTM" -> {
                    val file = resolve(argument)
                    if (file != null && file.exists()) reply(213, MDTM_FORMAT.format(file.lastModified()))
                    else reply(550, "No such file.")
                }

                "DELE" -> {
                    val file = resolve(argument)
                    if (file != null && file.isFile && file.delete()) reply(250, "Deleted.")
                    else reply(550, "Could not delete it.")
                }

                "MKD", "XMKD" -> {
                    val dir = resolve(argument)
                    if (dir != null && dir.mkdirs()) reply(257, "\"${displayPath(dir)}\" created.")
                    else reply(550, "Could not create it.")
                }

                "RMD", "XRMD" -> {
                    val dir = resolve(argument)
                    // Only empty directories, deliberately. An FTP verb that recursively deletes a
                    // tree is how somebody loses a folder to a fat-fingered path.
                    if (dir != null && dir.isDirectory && dir.delete()) reply(250, "Removed.")
                    else reply(550, "Could not remove it — is it empty?")
                }

                "RNFR" -> {
                    val file = resolve(argument)
                    if (file != null && file.exists()) { renameFrom = file; reply(350, "And rename it to?") }
                    else reply(550, "No such file.")
                }

                "RNTO" -> {
                    val from = renameFrom
                    val to = resolve(argument)
                    renameFrom = null
                    if (from != null && to != null && from.renameTo(to)) reply(250, "Renamed.")
                    else reply(550, "Could not rename it.")
                }

                "QUIT" -> { reply(221, "Bye."); return false }

                else -> reply(502, "$verb is not something this server does.")
            }

            return true
        }

        // ── paths ───────────────────────────────────────────────────────────

        /**
         * Turn whatever the client sent into a real file, or null if it escapes the root.
         *
         * Canonicalised first and checked afterwards: a client can say `../../..`, or a symlink can
         * point outside, and only the resolved path tells the truth. This is the one piece of this
         * class worth being fussy about — everything else at worst fails a transfer.
         */
        private fun resolve(argument: String): File? {
            val raw = if (argument.isEmpty()) cwd
            else if (argument.startsWith("/")) File(root, argument.removePrefix("/"))
            else File(cwd, argument)

            val real = try { raw.canonicalFile } catch (e: IOException) { return null }

            if (restrictToRoot) {
                val base = try { root.canonicalPath } catch (e: IOException) { return null }
                if (real.path != base && !real.path.startsWith(base + File.separator)) return null
            }

            return real
        }

        /** The path as the client should see it: relative to the root, with forward slashes. */
        private fun displayPath(file: File): String {
            val base = try { root.canonicalPath } catch (e: IOException) { root.path }
            val here = try { file.canonicalPath } catch (e: IOException) { file.path }
            val relative = here.removePrefix(base).replace(File.separatorChar, '/')
            return if (relative.isEmpty()) "/" else relative
        }

        // ── the data channel ────────────────────────────────────────────────

        private fun openPassive(extended: Boolean) {
            closePassive()

            val socket = try {
                ServerSocket(0, 1, control.localAddress)
            } catch (e: IOException) {
                reply(425, "Could not open a data port.")
                return
            }

            passive = socket
            activeHost = null

            val assigned = socket.localPort
            if (extended) {
                reply(229, "Entering extended passive mode (|||$assigned|)")
            } else {
                // The comma form: h1,h2,h3,h4,p1,p2 — the address as the CLIENT must reach us, which
                // is this connection's local address rather than anything we might prefer.
                val octets = control.localAddress.address
                val quad = octets.joinToString(",") { (it.toInt() and 0xFF).toString() }
                reply(227, "Entering passive mode ($quad,${assigned shr 8},${assigned and 0xFF})")
            }
        }

        private fun parsePort(argument: String) {
            val parts = argument.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size != 6) { reply(501, "Bad PORT."); return }

            activeHost = InetAddress.getByAddress(
                parts.take(4).map { it.toByte() }.toByteArray())
            activePort = (parts[4] shl 8) + parts[5]
            closePassive()
            reply(200, "Will connect to you.")
        }

        private fun parseExtendedPort(argument: String) {
            // |protocol|address|port|, with any delimiter the client fancies as the first character.
            val delimiter = argument.firstOrNull() ?: run { reply(501, "Bad EPRT."); return }
            val fields = argument.split(delimiter)
            if (fields.size < 4) { reply(501, "Bad EPRT."); return }

            activeHost = try { InetAddress.getByName(fields[2]) } catch (e: Exception) { null }
            activePort = fields[3].toIntOrNull() ?: 0

            if (activeHost == null || activePort == 0) reply(501, "Bad EPRT.")
            else { closePassive(); reply(200, "Will connect to you.") }
        }

        /** The data connection for the transfer about to happen, however it was arranged. */
        private fun dataSocket(): Socket? {
            val listenerSocket = passive
            return try {
                if (listenerSocket != null) {
                    listenerSocket.soTimeout = DATA_TIMEOUT_MS
                    listenerSocket.accept()
                } else {
                    val host = activeHost ?: return null
                    Socket(host, activePort)
                }
            } catch (e: IOException) {
                null
            } finally {
                closePassive()
            }
        }

        private fun closePassive() {
            try { passive?.close() } catch (_: IOException) {}
            passive = null
        }

        // ── transfers ───────────────────────────────────────────────────────

        private fun listing(argument: String, detailed: Boolean) {
            // Clients pass flags like "-al" where a path goes. Treat anything starting with a dash
            // as a flag and list the current directory, which is what they mean.
            val target = if (argument.startsWith("-")) cwd else (resolve(argument) ?: cwd)
            val directory = if (target.isDirectory) target else target.parentFile ?: cwd

            reply(150, "Here it comes.")
            val data = dataSocket()
            if (data == null) { reply(425, "No data connection."); return }

            try {
                data.getOutputStream().bufferedWriter(Charsets.UTF_8).use { out ->
                    val entries = directory.listFiles() ?: emptyArray()
                    for (entry in entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
                        out.write(if (detailed) unixListing(entry) else entry.name)
                        out.write("\r\n")
                    }
                }
                reply(226, "That's all.")
            } catch (e: IOException) {
                reply(426, "Listing failed.")
            } finally {
                try { data.close() } catch (_: IOException) {}
            }
        }

        /**
         * One line of `ls -l`, which is what every FTP client parses.
         *
         * The permissions are largely fiction — Android will not tell us the real mode, and clients
         * only look at the first character to decide "folder or file". Owner and group are likewise
         * cosmetic. The parts that must be right are the type, the size and the date.
         */
        private fun unixListing(file: File): String {
            val type = if (file.isDirectory) 'd' else '-'
            val read = if (file.canRead()) 'r' else '-'
            val write = if (file.canWrite()) 'w' else '-'
            val execute = if (file.isDirectory) 'x' else '-'

            val stamp = LIST_FORMAT.format(file.lastModified())
            val size = if (file.isDirectory) 4096L else file.length()

            return "$type$read$write$execute$read-$execute$read-$execute 1 thor thor %12d %s %s"
                .format(size, stamp, file.name)
        }

        private fun retrieve(argument: String) {
            val file = resolve(argument)
            if (file == null || !file.isFile) { reply(550, "No such file."); return }

            reply(150, "Sending ${file.name} (${file.length()} bytes).")
            val data = dataSocket()
            if (data == null) { reply(425, "No data connection."); return }

            try {
                val sent = file.inputStream().use { input ->
                    if (restartAt > 0) input.skip(restartAt)
                    pump(input, data.getOutputStream(), file.name, file.length(), sending = true)
                }
                note("[INFO] sent ${file.name} (${human(sent)})")
                reply(226, "Sent.")
            } catch (e: IOException) {
                note("[ERROR] sending ${file.name}: ${e.message}")
                reply(426, "Transfer failed.")
            } finally {
                restartAt = 0
                try { data.close() } catch (_: IOException) {}
            }
        }

        private fun store(argument: String, append: Boolean) {
            val file = resolve(argument)
            if (file == null) { reply(553, "Not a path I'll write to."); return }

            reply(150, "Ready for ${file.name}.")
            val data = dataSocket()
            if (data == null) { reply(425, "No data connection."); return }

            try {
                // APPE, and a STOR that follows REST, both continue an existing file rather than
                // truncating it — that is how an interrupted upload gets resumed instead of restarted.
                val received = java.io.FileOutputStream(file, append || restartAt > 0).use { output ->
                    // An upload's size is never declared, so total stays 0 and the screen shows
                    // bytes-so-far without a percentage rather than inventing one.
                    pump(data.getInputStream(), output, file.name, 0, sending = false)
                }
                note("[INFO] received ${file.name} (${human(received)})")
                reply(226, "Stored.")
            } catch (e: IOException) {
                note("[ERROR] receiving ${file.name}: ${e.message}")
                reply(426, "Transfer failed.")
            } finally {
                restartAt = 0
                try { data.close() } catch (_: IOException) {}
            }
        }

        /**
         * Copy, reporting progress as it goes.
         *
         * `copyTo` would do the copying and tell nobody, which is why this is written out: the
         * screen has to be able to say "38 of 120 MB, 4.2 MB/s, 20s left" while it happens, and
         * that means updating the client record every buffer rather than once at the end.
         *
         * The record is cleared in the finally, so a finished or failed transfer leaves no ghost
         * progress bar behind.
         */
        private fun pump(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            name: String,
            total: Long,
            sending: Boolean,
        ): Long {
            record.transferName = name
            record.transferTotal = total
            record.transferDone = 0
            record.transferStarted = System.currentTimeMillis()
            record.sending = sending

            val buffer = ByteArray(BUFFER)
            var moved = 0L

            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    moved += read
                    record.transferDone = moved
                }
                output.flush()
            } finally {
                record.transferName = ""
                record.transferTotal = 0
                record.transferDone = 0
                record.transferStarted = 0
            }

            return moved
        }

        // ── the control channel ─────────────────────────────────────────────

        private fun reply(code: Int, text: String) {
            // Only replies go in the console, not the multi-line FEAT block — a listing of
            // supported features scrolling past on every connection buries what matters.
            note("[RESPONSE] $code $text")
            write("$code $text")
        }

        private fun write(line: String) {
            try {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
            } catch (e: IOException) {
                // The client is gone. The session loop notices on its next read.
            }
        }
    }

    companion object {
        private const val TAG = "AynFtp"

        /** Ports below 1024 need root. 2121 is the conventional unprivileged FTP port. */
        const val DEFAULT_PORT = 2121

        private const val BUFFER = 64 * 1024
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000
        private const val DATA_TIMEOUT_MS = 30 * 1000

        /**
         * Every address a PC on the network could type at this device.
         *
         * A companion function, not an instance one, and that matters: the notification is built
         * BEFORE the server field is assigned, so asking a not-yet-stored instance for its
         * addresses returned an empty list and the notification said "No network" on a device that
         * had a perfectly good one. Address enumeration never depended on the server anyway — it is
         * a property of the device.
         *
         * Read off the interface list rather than WifiManager: no extra permission, and it covers
         * Ethernet from a dock as well as Wi-Fi. Loopback is useless to the PC you are copying
         * from, and 169.254.x.x means DHCP never answered — printing it sends someone chasing a
         * dead address.
         */
        fun localAddresses(): List<String> {
            val found = mutableListOf<String>()

            runCatching {
                for (nic in java.net.NetworkInterface.getNetworkInterfaces()) {
                    // Each interface is guarded on its own: one that throws when asked whether it
                    // is up must not take the whole enumeration down with it.
                    runCatching {
                        if (!nic.isUp || nic.isLoopback) return@runCatching

                        for (address in nic.inetAddresses) {
                            val ip = address.hostAddress ?: continue
                            if (address !is java.net.Inet4Address) continue
                            if (ip.startsWith("169.254.")) continue
                            found += ip
                        }
                    }
                }
            }

            return found
        }

        /** About a screen and a half of scrollback. See [note]. */
        private const val CONSOLE_LINES = 300

        private val CONSOLE_TIME = SimpleDateFormat("HH:mm:ss", Locale.US)

        private val LIST_FORMAT = SimpleDateFormat("MMM dd HH:mm", Locale.US)
        private val MDTM_FORMAT = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    }
}
