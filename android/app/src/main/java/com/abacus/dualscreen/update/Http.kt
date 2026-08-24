package com.abacus.dualscreen.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * The plumbing shared by the release check and the download.
 *
 * HttpURLConnection rather than a client library, to match the rest of this app and to add no
 * dependency for what amounts to two GET requests. Two details earn the code they take:
 *
 * - **Redirects are followed by hand.** HttpURLConnection silently refuses to follow one that
 *   changes protocol, and a release asset URL redirects to another host on the way to the CDN.
 *   Following them here also keeps a Range header across the hop, which is what makes a resumed
 *   download work.
 * - **A User-Agent is mandatory.** GitHub rejects API requests without one, and the failure arrives
 *   looking like a 403 rather than like a missing header.
 */
internal object Http {

    const val USER_AGENT = "AynDualScreen-Updater"

    private const val MAX_REDIRECTS = 5

    /**
     * Open a GET and follow redirects manually.
     *
     * The caller owns the returned connection and must disconnect it.
     */
    @Throws(IOException::class)
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 15_000,
        readMs: Int = 30_000,
    ): HttpURLConnection {
        var target = url
        var hops = 0

        while (true) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectMs
                readTimeout = readMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                // identity, so Content-Length is the number of bytes the progress bar will see
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }

            val code = connection.responseCode
            val redirect = code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308

            if (!redirect) return connection

            val location = connection.getHeaderField("Location")
            connection.disconnect()

            if (location.isNullOrBlank() || ++hops > MAX_REDIRECTS)
                throw IOException("too many redirects from " + url)

            // Relative locations are legal and do happen; resolve against the URL just asked for.
            target = URL(URL(target), location).toString()
        }
    }

    /** The body, or null if the response was not a 2xx. Bounded, so a wrong URL cannot fill memory. */
    fun body(connection: HttpURLConnection, limit: Int = 1 shl 20): String? {
        if (connection.responseCode !in 200..299) return null

        return connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            val out = StringBuilder()
            while (out.length < limit) {
                val read = reader.read(buffer)
                if (read <= 0) break
                out.appendRange(buffer, 0, read)
            }
            out.toString()
        }
    }

    /**
     * Is there a network at all?
     *
     * Asked before every check, so "no internet" is reported as itself rather than as a timeout
     * fifteen seconds later. It answers whether a network exists, not whether GitHub is reachable —
     * a captive portal still says yes here and fails at the request, which is handled there.
     */
    fun online(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)  // if the system will not say, try the request rather than refusing

    /** Turn a thrown network exception into something with a sentence attached. */
    fun classify(error: Throwable): Failure = when (error) {
        is UnknownHostException -> Failure(UpdateError.OFFLINE, error.message)
        is SocketTimeoutException -> Failure(UpdateError.NETWORK, error.message)
        else -> Failure(UpdateError.NETWORK, error.message)
    }
}
