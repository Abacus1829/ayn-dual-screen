package com.abacus.dualscreen.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Does the hand-written DER encoder actually produce a certificate?
 *
 * The value of this test is that it does not check the bytes against anything I wrote. It hands them
 * to the platform's own X.509 parser and its own signature verifier, both of which were written by
 * people who know the format properly, and asks *them*. If the encoding is wrong in any way that
 * matters, `CertificateFactory` throws and this fails.
 *
 * Runs on the desktop JVM: nothing here touches Android. That is deliberate — it means the encoder
 * can be checked in seconds without a device, which is the only reason it gets checked at all.
 */
class Asn1Test {

    private val keyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private fun build(
        notBefore: Date = Date(System.currentTimeMillis() - 86_400_000),
        notAfter: Date = Date(System.currentTimeMillis() + 20L * 365 * 86_400_000),
        serial: BigInteger = BigInteger(64, SecureRandom()),
        subject: String = "CN=AynDualScreen",
    ) = Asn1.certificate(
        subject = X500Principal(subject),
        issuer = X500Principal(subject),
        serial = serial,
        notBefore = notBefore,
        notAfter = notAfter,
        publicKey = keyPair.public,
        signingKey = keyPair.private,
    )

    /** The whole point: the platform parses what we wrote, so it is a real certificate. */
    @Test
    fun `produces a parseable certificate`() {
        val certificate = build()
        assertEquals("X.509", certificate.type)
        assertEquals(3, certificate.version)
    }

    /**
     * Self-signed means the certificate verifies against its own public key. If the TBS bytes that
     * were signed differ by even one byte from the TBS bytes that were written, this fails — which
     * is the failure mode a hand-rolled encoder is most likely to have.
     */
    @Test
    fun `signature verifies against its own key`() {
        build().verify(keyPair.public)
    }

    @Test
    fun `carries the subject and issuer it was given`() {
        val certificate = build(subject = "CN=AynDualScreen")
        assertEquals("CN=AynDualScreen", certificate.subjectX500Principal.name)
        assertEquals("CN=AynDualScreen", certificate.issuerX500Principal.name)
    }

    @Test
    fun `is valid now and not before it was issued`() {
        val certificate = build()
        certificate.checkValidity()                       // throws if we are outside the window

        assertTrue(certificate.notBefore.before(Date()))
        assertTrue(certificate.notAfter.after(Date()))
    }

    /**
     * DER lengths are encoded one way below 128 bytes and another way above, and the switch is the
     * classic place to get this wrong. A 2048-bit key's SubjectPublicKeyInfo is comfortably over
     * that, so the long form is already exercised — but a large serial number pushes another field
     * around and is worth pinning too.
     */
    @Test
    fun `survives a large serial number`() {
        val serial = BigInteger(159, SecureRandom())
        val certificate = build(serial = serial)
        assertEquals(serial, certificate.serialNumber)
    }

    /**
     * A serial that happens to have its top bit set must stay positive.
     *
     * DER integers are two's complement, so 0x80... needs a leading zero byte or it reads as
     * negative. BigInteger.toByteArray() already does that, and this test is here so that anyone
     * who "optimises" the leading zero away finds out immediately.
     */
    @Test
    fun `keeps a high-bit serial positive`() {
        val serial = BigInteger("F0000000000000FF", 16)
        val certificate = build(serial = serial)

        assertEquals(serial, certificate.serialNumber)
        assertEquals(1, certificate.serialNumber.signum())
    }

    /** The encoding must round-trip: re-parsing what the platform gave back gives the same bytes. */
    @Test
    fun `round-trips through the platform parser`() {
        val certificate = build()
        val again = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(certificate.encoded.inputStream())

        assertTrue(certificate.encoded.contentEquals(again.encoded))
    }
}
