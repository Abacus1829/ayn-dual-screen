package com.abacus.dualscreen.stream

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * The HTTP side of a streaming host: `/serverinfo`, `/pair`, `/applist`, `/launch`.
 *
 * A GameStream host answers on two ports and the difference matters:
 *
 * - **47989, plain HTTP** — used before pairing, because there is no client certificate yet to
 *   present. Only a few endpoints work here.
 * - **47984, HTTPS** — everything after pairing. The connection is authenticated by *our*
 *   certificate; the host looks up the fingerprint it stored when we paired.
 *
 * ## About the TLS, which looks alarming and isn't
 *
 * The host's certificate is self-signed and its name will not match the IP you typed, so the normal
 * checks are turned off below. That is not laziness — there is no certificate authority anywhere in
 * this system, and there is nothing for hostname verification to verify against on a device that
 * answers to `192.168.0.14`.
 *
 * What replaces it is stronger for this purpose: once paired, the host's certificate is **pinned**.
 * We keep the exact certificate the pairing handshake produced and refuse anything else. A machine
 * that impersonates your PC's address gets as far as the TLS handshake and no further, which is
 * more than hostname verification would have given us.
 *
 * Before pairing, there is nothing to pin and nothing worth protecting: `/serverinfo` is public
 * information and the pairing handshake carries its own challenge-response that a man in the middle
 * cannot answer without the PIN.
 */
class HostConnection(
    val address: String,
    private val identity: Identity,
    /** The host's certificate, once known. Null until paired — see the note above. */
    private var pinned: X509Certificate? = null,
) {

    /** Replace the pin after a successful pairing. */
    fun pin(certificate: X509Certificate) {
        pinned = certificate
    }

    // ── endpoints ───────────────────────────────────────────────────────────

    /**
     * Ask the host who it is.
     *
     * Tried over HTTPS first when we have an identity to present, because only then does the host
     * report `PairStatus=1` — over plain HTTP it always says we are unpaired, whoever we are, which
     * has fooled a good many people into re-pairing a host that was already paired.
     */
    fun serverInfo(): HostInfo? {
        if (pinned != null) {
            secure("serverinfo")?.let { return HostInfo.parse(it) }
        }
        return plain("serverinfo")?.let { HostInfo.parse(it) }
    }

    fun plain(path: String, vararg extra: Pair<String, String>): String? =
        request("http://$address:$HTTP_PORT/$path${query(extra)}", secure = false)

    fun secure(path: String, vararg extra: Pair<String, String>): String? =
        request("https://$address:$HTTPS_PORT/$path${query(extra)}", secure = true)

    /** Every request carries who we are; the host keys its state on these. */
    private fun query(extra: Array<out Pair<String, String>>): String {
        val parameters = buildList {
            add("uniqueid" to identity.uniqueId)
            add("uuid" to java.util.UUID.randomUUID().toString())
            addAll(extra)
        }

        return "?" + parameters.joinToString("&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, "UTF-8")
        }
    }

    private fun request(url: String, secure: Boolean): String? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection

            if (secure && connection is HttpsURLConnection) {
                connection.sslSocketFactory = socketFactory()

                // Nothing to verify a name against: the host is an IP address on your LAN and its
                // certificate says whatever the host software felt like. Identity is established by
                // the pin in the trust manager, not by this.
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }

            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"

            connection.use {
                if (it.responseCode !in 200..299) {
                    Log.w(TAG, "$url answered ${it.responseCode}")
                    return null
                }
                it.inputStream.bufferedReader().readText()
            }
        } catch (e: IOException) {
            Log.w(TAG, "$url failed: ${e.message}")
            null
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    // ── TLS ─────────────────────────────────────────────────────────────────

    private fun socketFactory(): SSLSocketFactory {
        // Our certificate and key, presented as the client identity. This is the whole point: the
        // host recognises us by this and by nothing else.
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                "client",
                identity.privateKey,
                KEY_PASSWORD,
                arrayOf(identity.certificate),
            )
        }

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, KEY_PASSWORD) }
            .keyManagers

        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val expected = pinned ?: return          // unpaired: nothing to compare against yet
                val presented = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("The host presented no certificate.")

                // Compared by encoding rather than by public key or fingerprint string: it is the
                // whole certificate we stored at pairing, and anything less is a narrower check.
                if (!presented.encoded.contentEquals(expected.encoded)) {
                    throw java.security.cert.CertificateException(
                        "This is not the host we paired with. Either the host was reinstalled, or " +
                            "something else is answering at ${address}."
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trust), SecureRandom())
        }.socketFactory
    }

    companion object {
        private const val TAG = "AynStream"

        /** Before pairing. Only a few endpoints answer here. */
        const val HTTP_PORT = 47989

        /** After pairing. Everything real happens over this one. */
        const val HTTPS_PORT = 47984

        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 8000

        /** In-memory keystore only; it never touches disk, so the password guards nothing. */
        private val KEY_PASSWORD = CharArray(0)
    }
}
