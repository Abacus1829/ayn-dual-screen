package com.abacus.dualscreen.scribble

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Somebody else on this network, running this app. */
data class Peer(val name: String, val address: String, val port: Int, val seen: Long)

/**
 * Everyone on the Wi-Fi, talking to each other directly.
 *
 * Two halves, because one protocol cannot do both jobs well:
 *
 * - **Presence** is a UDP broadcast every few seconds carrying a name and a port. Broadcast is right
 *   for "who is out there" — nobody has to type an address, and a device that walks out of range
 *   simply stops being mentioned.
 * - **Messages** go over TCP to each peer in turn. A doodle is several kilobytes, which is past the
 *   point where a datagram is safe, and a message that silently vanishes because it did not fit in
 *   an MTU is a far worse bug than a slightly slower send.
 *
 * There is no server and no account. Two handhelds on the same Wi-Fi find each other and that is the
 * whole setup, which is the part worth keeping about the thing this imitates.
 */
class ScribbleNet(
    private val context: Context,
    @Volatile var name: String,
    private val onMessage: (who: String, room: String, text: String, image: ByteArray?) -> Unit,
    private val onPeersChanged: () -> Unit,
) {

    @Volatile
    private var running = false

    private var server: ServerSocket? = null
    private var presence: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    /** The port we ended up on, which is not always the one we asked for. */
    @Volatile
    var port: Int = 0
        private set

    private val known = java.util.concurrent.ConcurrentHashMap<String, Peer>()

    /** Who has been heard from recently. Anyone silent for [STALE] is dropped. */
    fun peers(): List<Peer> {
        val now = System.currentTimeMillis()
        val gone = known.filterValues { now - it.seen > STALE }.keys
        if (gone.isNotEmpty()) {
            gone.forEach { known.remove(it) }
            onPeersChanged()
        }
        return known.values.sortedBy { it.name.lowercase() }
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true

        /*
         * Wi-Fi hardware drops broadcast packets while the radio is dozing unless something holds a
         * multicast lock. Without this the app sends presence happily and hears nobody, which looks
         * exactly like "there is nobody there" and is the single most confusing way for a LAN
         * feature to fail.
         */
        runCatching {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            lock = wifi?.createMulticastLock("scribble")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        Thread({ serve() }, "scribble-accept").apply { isDaemon = true }.start()
        Thread({ listen() }, "scribble-presence").apply { isDaemon = true }.start()
        Thread({ announce() }, "scribble-announce").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { presence?.close() }
        runCatching { lock?.release() }
        server = null
        presence = null
        lock = null
        known.clear()
    }

    // ── incoming messages ───────────────────────────────────────────────────

    private fun serve() {
        val socket = runCatching { ServerSocket(TCP_PORT) }
            .recoverCatching { ServerSocket(0) }        // taken; any free port will do
            .onFailure { Log.w(TAG, "no port to listen on", it) }
            .getOrNull() ?: return

        server = socket
        port = socket.localPort

        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread({ receive(client) }, "scribble-in").apply { isDaemon = true }.start()
        }
    }

    private fun receive(client: Socket) {
        client.use {
            runCatching {
                it.soTimeout = 15_000
                val input = it.getInputStream()

                if (readLine(input) != MAGIC) return@runCatching
                val header = JSONObject(readLine(input) ?: return@runCatching)

                val length = header.optInt("img")
                val image = if (length in 1..MAX_IMAGE) readExactly(input, length) else null

                onMessage(
                    header.optString("who").ifBlank { "?" },
                    header.optString("room"),
                    header.optString("text"),
                    image,
                )
            }.onFailure { e -> Log.w(TAG, "bad message", e) }
        }
    }

    // ── outgoing messages ───────────────────────────────────────────────────

    /**
     * Send to everyone currently known.
     *
     * One thread for the lot rather than one each: they are sequential connections to a handful of
     * devices on the same Wi-Fi, and a failure to reach one peer must not stop the others — hence
     * the runCatching inside the loop rather than around it.
     */
    fun send(room: String, text: String, image: ByteArray?) {
        val targets = peers()
        if (targets.isEmpty()) return

        Thread({
            val header = JSONObject()
                .put("who", name)
                .put("room", room)
                .put("text", text)
                .put("img", image?.size ?: 0)
                .toString()

            for (peer in targets) {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(peer.address, peer.port), 3_000)
                        val out = socket.getOutputStream()
                        out.write((MAGIC + "\n").toByteArray())
                        out.write((header + "\n").toByteArray())
                        if (image != null) out.write(image)
                        out.flush()
                    }
                }.onFailure { Log.w(TAG, "could not reach ${peer.name}", it) }
            }
        }, "scribble-out").apply { isDaemon = true }.start()
    }

    // ── presence ────────────────────────────────────────────────────────────

    private fun listen() {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(UDP_PORT))
            }
        }.onFailure { Log.w(TAG, "presence socket failed", it) }.getOrNull() ?: return

        presence = socket
        val buffer = ByteArray(512)

        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            if (runCatching { socket.receive(packet) }.isFailure) break

            runCatching {
                val json = JSONObject(String(packet.data, 0, packet.length))
                val who = json.optString("who").ifBlank { return@runCatching }
                val theirPort = json.optInt("port").takeIf { it > 0 } ?: return@runCatching
                val address = packet.address.hostAddress ?: return@runCatching

                // Our own broadcast comes straight back to us. Recognised by port and address
                // together, because two devices on one network can share a name and often will —
                // both handhelds arrive called the same thing out of the box.
                if (theirPort == port && address in localAddresses()) return@runCatching

                val existing = known.put(address, Peer(who, address, theirPort, System.currentTimeMillis()))
                if (existing == null || existing.name != who) onPeersChanged()
            }
        }
    }

    private fun announce() {
        while (running) {
            val payload = JSONObject()
                .put("who", name)
                .put("port", port)
                .toString()
                .toByteArray()

            for (address in broadcastAddresses()) {
                runCatching {
                    presence?.send(DatagramPacket(payload, payload.size, address, UDP_PORT))
                }
            }

            // Also prunes anybody who has gone quiet, so the peer count decays on its own.
            peers()

            runCatching { Thread.sleep(BEAT) }.onFailure { return }
        }
    }

    /**
     * Where to shout.
     *
     * Each interface's own broadcast address, not just 255.255.255.255 — the global one is dropped
     * by plenty of Android builds and by most access points, so a device with it alone is heard by
     * nobody. The global address stays in the list as a fallback for the case where an interface
     * reports no broadcast address at all.
     */
    private fun broadcastAddresses(): List<InetAddress> {
        val found = mutableListOf<InetAddress>()

        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                runCatching {
                    if (!nic.isUp || nic.isLoopback) return@runCatching
                    for (address in nic.interfaceAddresses) {
                        address.broadcast?.let { found += it }
                    }
                }
            }
        }

        runCatching { found += InetAddress.getByName("255.255.255.255") }
        return found
    }

    private fun localAddresses(): Set<String> =
        com.abacus.dualscreen.FtpServer.localAddresses().toSet()

    // ── framing ─────────────────────────────────────────────────────────────

    /**
     * One newline-terminated line, read a byte at a time.
     *
     * Byte-wise rather than through a BufferedReader because the image bytes follow immediately
     * afterwards on the same stream, and a reader would have swallowed the first chunk of the PNG
     * into its buffer. UTF-8 survives this: no continuation byte can be 0x0A.
     */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()

        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8")
            if (out.size() > 8192) return null              // no sane header is this long
            out.write(b)
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var read = 0

        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) return null
            read += n
        }

        return bytes
    }

    private companion object {
        const val TAG = "Scribble"
        const val MAGIC = "AYNSCRIB1"

        const val TCP_PORT = 27310
        const val UDP_PORT = 27311

        /** How often presence goes out, and how long silence is tolerated before a peer is dropped. */
        const val BEAT = 3_000L
        const val STALE = 12_000L

        /** A doodle that will not fit in this is not a doodle. Stops a bad header eating memory. */
        const val MAX_IMAGE = 4 * 1024 * 1024
    }
}
