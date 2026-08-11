package com.abacus.dualscreen

import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A mod found on the network. */
data class Found(val host: String, val game: Game?, val place: String?)

/**
 * Finds the PC on the local network, so its address never has to be typed.
 *
 * Typing an IP on a handheld is the worst part of setting this up, and getting it wrong is
 * indistinguishable from a dozen other failures. Since the mods answer on a known port, the whole
 * subnet can just be asked.
 *
 * Every method here blocks. Call from a background thread.
 */
object Scanner {

    /** Short: a host on the local subnet either answers almost immediately or isn't there. */
    private const val CONNECT_TIMEOUT_MS = 350

    private const val THREADS = 48
    private const val SCAN_TIMEOUT_SECONDS = 25L

    /**
     * Probe every address on this device's /24 for a listening mod.
     *
     * @param port the port to look on.
     * @param onProgress called with 0..1 as the sweep proceeds, for the status line.
     */
    fun sweep(port: Int, onProgress: (Float) -> Unit = {}): List<Found> {
        val prefixes = localPrefixes()
        if (prefixes.isEmpty())
            return emptyList()

        val hits = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(THREADS)
        val total = prefixes.size * 254
        var done = 0

        for (prefix in prefixes) {
            for (last in 1..254) {
                val host = "$prefix.$last"
                pool.execute {
                    if (isOpen(host, port))
                        hits.add(host)

                    synchronized(prefixes) {
                        done++
                        onProgress(done.toFloat() / total)
                    }
                }
            }
        }

        pool.shutdown()
        pool.awaitTermination(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // identify only the handful that answered — a full probe per address would be far too slow
        return hits.sorted().map { host ->
            val result = Probe.run("http://$host:$port")
            Found(host, result.game, result.place)
        }
    }

    private fun isOpen(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * The first three octets of each network this device is really on.
     *
     * Read from the interface list rather than WifiManager so it needs no extra permission and also
     * covers Ethernet, which some handheld docks provide. Anything that isn't a /24 is skipped —
     * sweeping a larger subnet would take minutes.
     */
    private fun localPrefixes(): List<String> {
        val prefixes = mutableSetOf<String>()

        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback || nic.isVirtual)
                    continue

                for (address in nic.interfaceAddresses) {
                    val ip = address.address?.hostAddress ?: continue
                    if (address.networkPrefixLength < 24 || !ip.matches(IPV4))
                        continue
                    if (ip.startsWith("169.254."))
                        continue

                    prefixes += ip.substringBeforeLast('.')
                }
            }
        }

        return prefixes.toList()
    }

    private val IPV4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
}
