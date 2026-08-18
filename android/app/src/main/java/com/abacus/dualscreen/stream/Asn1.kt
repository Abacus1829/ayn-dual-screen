package com.abacus.dualscreen.stream

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.security.auth.x500.X500Principal

/**
 * Just enough DER to build one self-signed X.509 certificate.
 *
 * Android can *read* certificates and can *sign* bytes, but it ships no public API for building a
 * certificate — the JDK's `sun.security.x509` is not there, and the platform's BouncyCastle is
 * deliberately hidden. The usual answer is to add the BouncyCastle PKIX library, which is a couple of
 * megabytes of dependency to emit about two hundred bytes of structure.
 *
 * So this writes the structure by hand, which is the same trade the Fallout mod in this repository
 * makes with its PNG encoder and its BSA reader. Two things make it a reasonable one:
 *
 * - **None of this is cryptography.** It is serialisation. The signature comes from
 *   `java.security.Signature`, the key from `KeyPairGenerator`, and the encoding of the public key
 *   from the platform, which already hands it over as DER. What is written here is the envelope.
 * - **It has one job.** Not a general ASN.1 library — one certificate shape, with fixed algorithms,
 *   and no parsing at all. The result is handed straight to `CertificateFactory`, which is a real
 *   parser and will reject anything malformed. If this file is wrong, it fails immediately and
 *   loudly at generation time rather than subtly later.
 *
 * ## The shape being built
 *
 * ```
 * Certificate  ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signature BIT STRING }
 * TBSCertificate ::= SEQUENCE {
 *     [0] version           -- v3
 *     serialNumber          INTEGER
 *     signature             AlgorithmIdentifier
 *     issuer                Name
 *     validity              SEQUENCE { notBefore, notAfter }
 *     subject               Name
 *     subjectPublicKeyInfo  -- taken verbatim from the platform
 * }
 * ```
 *
 * Extensions are omitted on purpose. A GameStream host does not read them: it pins the whole
 * certificate at pairing time and compares it on every later connection. Adding basicConstraints and
 * keyUsage would be decoration that something might one day validate differently.
 */
internal object Asn1 {

    // ── tags ────────────────────────────────────────────────────────────────

    private const val INTEGER = 0x02
    private const val BIT_STRING = 0x03
    private const val NULL = 0x05
    private const val OBJECT_ID = 0x06
    private const val UTC_TIME = 0x17
    private const val GENERALIZED_TIME = 0x18
    private const val SEQUENCE = 0x30

    /** Context-specific, constructed, tag 0 — the wrapper the version field sits in. */
    private const val CONTEXT_0 = 0xA0

    /** sha256WithRSAEncryption, 1.2.840.113549.1.1.11 */
    private val SHA256_WITH_RSA = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
    )

    // ── writing ─────────────────────────────────────────────────────────────

    /**
     * A DER length, which is the fiddly part of the format.
     *
     * Under 128 bytes it is a single byte. At or above, the first byte is 0x80 plus the number of
     * length bytes that follow, big-endian. Getting this wrong produces a certificate that parses on
     * some machines and not others, which is why it lives in one function.
     */
    private fun length(value: Int): ByteArray {
        if (value < 0x80) return byteArrayOf(value.toByte())

        var remaining = value
        val bytes = ArrayDeque<Byte>()
        while (remaining > 0) {
            bytes.addFirst((remaining and 0xFF).toByte())
            remaining = remaining ushr 8
        }

        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun tagged(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + length(content.size) + content

    private fun sequence(vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        return tagged(SEQUENCE, body.toByteArray())
    }

    private fun integer(value: BigInteger): ByteArray {
        // BigInteger.toByteArray() is already two's-complement big-endian with a leading zero when
        // needed, which is exactly what DER wants.
        return tagged(INTEGER, value.toByteArray())
    }

    private fun integer(value: Int): ByteArray = integer(BigInteger.valueOf(value.toLong()))

    private fun objectId(encoded: ByteArray): ByteArray = tagged(OBJECT_ID, encoded)

    private fun bitString(content: ByteArray): ByteArray =
        tagged(BIT_STRING, byteArrayOf(0) + content)   // 0 = no unused bits in the final byte

    /**
     * Times before 2050 go as UTCTime and later ones as GeneralizedTime.
     *
     * That is not a style choice — RFC 5280 requires exactly this, because UTCTime's two-digit year
     * is ambiguous past 2049. A twenty-year certificate issued today does not cross it, but one
     * issued from a device with a wrong clock might.
     */
    private fun time(date: Date): ByteArray {
        val year = SimpleDateFormat("yyyy", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date).toInt()

        return if (year < 2050) {
            val text = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(date)
            tagged(UTC_TIME, text.toByteArray(Charsets.US_ASCII))
        } else {
            val text = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(date)
            tagged(GENERALIZED_TIME, text.toByteArray(Charsets.US_ASCII))
        }
    }

    /** AlgorithmIdentifier for sha256WithRSAEncryption, whose parameters are an explicit NULL. */
    private fun signatureAlgorithm(): ByteArray =
        sequence(objectId(SHA256_WITH_RSA), tagged(NULL, ByteArray(0)))

    // ── the certificate ─────────────────────────────────────────────────────

    /**
     * Build and sign a certificate.
     *
     * The result is round-tripped through [CertificateFactory] before being returned, so a malformed
     * encoding surfaces here — at generation, once, on a device we control — rather than as an
     * inscrutable TLS failure against somebody's PC later.
     */
    fun certificate(
        subject: X500Principal,
        issuer: X500Principal,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
        publicKey: PublicKey,
        signingKey: PrivateKey,
    ): X509Certificate {

        val tbs = sequence(
            // [0] EXPLICIT version, and v3 is encoded as 2. Explicit because the field is optional
            // and defaults to v1; without the wrapper a parser reads the 2 as the serial number.
            tagged(CONTEXT_0, integer(2)),
            integer(serial),
            signatureAlgorithm(),
            issuer.encoded,                     // X500Principal hands over DER already
            sequence(time(notBefore), time(notAfter)),
            subject.encoded,
            publicKey.encoded,                  // SubjectPublicKeyInfo, likewise already DER
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signingKey)
            update(tbs)
            sign()
        }

        val whole = sequence(tbs, signatureAlgorithm(), bitString(signature))

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(whole.inputStream()) as X509Certificate
    }
}
