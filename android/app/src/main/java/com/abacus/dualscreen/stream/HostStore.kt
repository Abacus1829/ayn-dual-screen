package com.abacus.dualscreen.stream

import android.content.Context
import android.util.Log
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The hosts this Thor has paired with, and the certificate that proves which is which.
 *
 * Pairing is a one-time cost that involves a person reading a PIN off one screen and typing it into
 * another, so losing it is genuinely annoying. That makes this small class more important than it
 * looks: it is the difference between "open the app and play" and "do the PIN dance again".
 *
 * The certificate is the part that matters. An address is a hint — machines move between IPs — but
 * the certificate is the host's identity, and keeping it is what lets [HostConnection] refuse an
 * impostor answering at the address we remembered.
 */
class HostStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "stream/hosts").apply { mkdirs() }

    /** The address last used, so the screen opens where it was left. */
    var lastAddress: String
        get() = prefs.getString(KEY_LAST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    /** Remember a pairing: the host's certificate, filed under its own unique id. */
    fun save(host: HostInfo, address: String, certificate: X509Certificate) {
        val id = host.uniqueId.ifEmpty { address }

        runCatching {
            File(directory, "${id.safe()}.crt").writeBytes(certificate.encoded)

            prefs.edit()
                .putString(nameKey(id), host.hostname)
                .putString(addressKey(id), address)
                .putString(KEY_LAST, address)
                .apply()
        }.onFailure { Log.w(TAG, "Could not save the pairing", it) }
    }

    /** The certificate for a host, if we have paired with it before. */
    fun certificateFor(host: HostInfo, address: String): X509Certificate? {
        val id = host.uniqueId.ifEmpty { address }
        val file = File(directory, "${id.safe()}.crt")
        if (!file.exists()) return null

        return runCatching {
            file.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
        }.getOrElse {
            // A corrupt certificate is worse than none: every connection would fail the pin check
            // with a message about impostors. Drop it and let the user pair again.
            Log.w(TAG, "Stored certificate unreadable; forgetting it", it)
            file.delete()
            null
        }
    }

    /** Forget a host entirely. The host keeps its own record until it is unpaired there too. */
    fun forget(host: HostInfo, address: String) {
        val id = host.uniqueId.ifEmpty { address }
        File(directory, "${id.safe()}.crt").delete()
        prefs.edit().remove(nameKey(id)).remove(addressKey(id)).apply()
    }

    fun isPaired(host: HostInfo, address: String): Boolean = certificateFor(host, address) != null

    /** Host ids come from the network, so they are never used as a filename unexamined. */
    private fun String.safe(): String = replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    private fun nameKey(id: String) = "host_name_$id"
    private fun addressKey(id: String) = "host_address_$id"

    companion object {
        private const val TAG = "AynStream"
        private const val PREFS = "stream_hosts"
        private const val KEY_LAST = "last_address"
    }
}
