package com.abacus.dualscreen.stream

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import javax.security.auth.x500.X500Principal

/**
 * Who this Thor claims to be when it talks to a streaming host.
 *
 * The GameStream protocol identifies a client by an X.509 certificate rather than by a password. You
 * pair once — the host stores your certificate's fingerprint against a PIN you typed — and from then
 * on every request is over TLS with that certificate as the client identity. Lose the key and the
 * host no longer knows you; keep it and pairing never has to happen again.
 *
 * So this class owns three things that must survive reinstalling nothing and losing nothing:
 * the key pair, the certificate built from it, and a stable device UUID.
 *
 * ## Where it is kept, and what that means
 *
 * In the app's private storage, unencrypted. Not the Android keystore, and that is a deliberate
 * trade rather than an oversight:
 *
 * - The keystore would protect the key from a *rooted* attacker with physical access to the Thor.
 * - It would also make the key non-exportable, which means it could never be backed up or moved to
 *   another device, and re-pairing every host after a factory reset is a genuinely worse day than
 *   the threat it defends against.
 *
 * What this key protects is the ability to start games on a PC on your own network. It is a house
 * key, not a bank card. Anyone who has rooted your handheld has already lost you more than this.
 */
class Identity private constructor(
    val keyPair: KeyPair,
    val certificate: X509Certificate,
    val uniqueId: String,
    val uuid: String,
) {

    val privateKey: PrivateKey get() = keyPair.private

    /** The certificate as PEM, which is the form the pairing handshake sends. */
    fun certificatePem(): String {
        val encoded = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
        val body = encoded.chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }

    companion object {
        private const val TAG = "AynStream"

        private const val KEY_FILE = "stream_client.key"
        private const val CERT_FILE = "stream_client.crt"
        private const val ID_FILE = "stream_client.id"

        /** GameStream hosts expect a 16-hex-digit client id. */
        private const val UNIQUE_ID_BYTES = 8

        /**
         * Load the existing identity, or make one the first time.
         *
         * Generating an RSA-2048 key takes a noticeable moment on a handheld, so this is worth doing
         * off the main thread the first time — the caller decides, because the second call is
         * instant and forcing everyone onto a background thread for a file read would be silly.
         */
        @Synchronized
        fun of(context: Context): Identity {
            val directory = File(context.filesDir, "stream").apply { mkdirs() }
            val keyFile = File(directory, KEY_FILE)
            val certFile = File(directory, CERT_FILE)
            val idFile = File(directory, ID_FILE)

            if (keyFile.exists() && certFile.exists() && idFile.exists()) {
                runCatching { return load(keyFile, certFile, idFile) }
                    .onFailure {
                        // A half-written identity is worse than none: every request would fail in a
                        // way that looks like the host's fault. Start over.
                        Log.w(TAG, "Stored identity is unreadable; generating a new one", it)
                    }
            }

            return generate(keyFile, certFile, idFile)
        }

        private fun load(keyFile: File, certFile: File, idFile: File): Identity {
            val keyFactory = java.security.KeyFactory.getInstance("RSA")
            val privateKey = keyFactory.generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(keyFile.readBytes()))

            val certificate = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }

            val parts = idFile.readText().trim().split(':')
            val pair = KeyPair(certificate.publicKey, privateKey)

            return Identity(pair, certificate, parts[0], parts.getOrElse(1) { UUID.randomUUID().toString() })
        }

        private fun generate(keyFile: File, certFile: File, idFile: File): Identity {
            Log.i(TAG, "Generating a client identity — this happens once")

            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val pair = generator.generateKeyPair()

            val certificate = selfSign(pair)

            val random = SecureRandom()
            val idBytes = ByteArray(UNIQUE_ID_BYTES).also { random.nextBytes(it) }
            val uniqueId = idBytes.joinToString("") { "%02x".format(it) }
            val uuid = UUID.randomUUID().toString()

            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(certificate.encoded)
            idFile.writeText("$uniqueId:$uuid")

            return Identity(pair, certificate, uniqueId, uuid)
        }

        /**
         * A self-signed certificate for the key.
         *
         * Self-signed is correct here rather than a shortcut: there is no authority in this system to
         * sign anything. The host does not check who issued your certificate — it checks that the
         * certificate presented on every later connection is byte-for-byte the one you pairing with.
         * A CA would add a party with nothing to verify.
         *
         * Built with the platform's own X.509 support rather than a certificate library, to keep the
         * app dependency-free like the rest of this project.
         */
        private fun selfSign(pair: KeyPair): X509Certificate {
            val name = X500Principal("CN=AynDualScreen")
            val serial = BigInteger(64, SecureRandom())

            val from = System.currentTimeMillis() - 24L * 60 * 60 * 1000        // yesterday, for clock skew
            val until = from + 20L * 365 * 24 * 60 * 60 * 1000                  // twenty years

            // android.security.keystore is not usable for this (it will not export a private key), and
            // sun.security.x509 is not on Android. What IS on Android is Conscrypt's BouncyCastle
            // fork, reachable through the standard JCA name below.
            return Asn1.certificate(
                subject = name,
                issuer = name,
                serial = serial,
                notBefore = java.util.Date(from),
                notAfter = java.util.Date(until),
                publicKey = pair.public,
                signingKey = pair.private,
            )
        }
    }
}
