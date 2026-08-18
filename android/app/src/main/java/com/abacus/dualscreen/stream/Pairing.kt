package com.abacus.dualscreen.stream

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing with a streaming host: the one-time handshake that teaches a PC to trust this Thor.
 *
 * ## What it is really doing
 *
 * Both sides end up needing to know two things: that the other one knew the PIN, and which
 * certificate belongs to whom. The PIN is the only shared secret there will ever be, and it is four
 * digits — far too weak to protect anything on its own. So it is never sent. Instead it is stirred
 * into a key with a random salt, and each side proves it can encrypt and decrypt with that key,
 * challenge and response, before either commits to anything.
 *
 * The certificates ride along inside that exchange. By the end, the host has ours and we have the
 * host's, each one carried inside a message the other could only have produced knowing the PIN — so
 * a machine sitting in the middle cannot substitute its own.
 *
 * ## The five steps
 *
 * 1. **getservercert** — we send our salt and our certificate; the host sends back its own.
 * 2. **clientchallenge** — we encrypt a random challenge with the PIN-derived key.
 * 3. **serverchallengeresp** — the host proves it decrypted ours, and challenges us back.
 * 4. **clientpairingsecret** — we answer its challenge, signed with our private key.
 * 5. **pairchallenge** — a final handshake, this time over TLS with our certificate, which is what
 *    every later connection will use.
 *
 * A failure at any step leaves the host un-paired rather than half-paired: [unpair] is called on the
 * way out, because a host that thinks it knows us when we do not think so is a state that can only
 * be cleared from the host's own UI.
 *
 * ## Written from the protocol, not from Moonlight's source
 *
 * Deliberately. `moonlight-common-c` is GPLv3 and this project's licence is not compatible with it,
 * so copying its implementation — even transcribed into Kotlin — would put this repository in a
 * position it cannot be in. What is implemented here is the observable protocol: the request names,
 * the field order, and the standard primitives (AES-128-ECB, SHA-256, RSA signatures) that the
 * exchange is built from.
 */
class Pairing(
    private val connection: HostConnection,
    private val identity: Identity,
    private val info: HostInfo,
) {

    /** How far the handshake got, so the UI can say something better than "failed". */
    sealed class Result {
        /** Paired. The host's certificate is [certificate] and connections use it from now on. */
        data class Paired(val certificate: X509Certificate) : Result()

        /** The PIN was wrong, or was never entered. By far the most common outcome. */
        object WrongPin : Result()

        /** The host refused before the PIN mattered — usually already pairing with someone else. */
        data class Refused(val step: String) : Result()

        /** The network gave out mid-handshake. */
        data class Unreachable(val step: String) : Result()
    }

    /**
     * Run the handshake. Blocking — call it off the main thread.
     *
     * [pin] must already be on screen when this is called: the host shows its own prompt the moment
     * step 1 lands, and the user needs to be reading the number before that happens.
     */
    fun pair(pin: String): Result {
        val salt = randomBytes(16)
        val key = keyFrom(salt, pin)

        // ── 1. our certificate for theirs ───────────────────────────────────

        val certificateReply = connection.plain(
            "pair",
            "devicename" to "roth",              // the name GameStream hosts expect; not cosmetic
            "updateState" to "1",
            "phrase" to "getservercert",
            "salt" to salt.hex(),
            "clientcert" to identity.certificatePem().toByteArray(Charsets.UTF_8).hex(),
        ) ?: return Result.Unreachable("getservercert")

        if (!certificateReply.paired()) {
            // A host that is mid-pairing with another device says no here. Nothing has been created
            // yet, so there is nothing to clean up.
            return Result.Refused("getservercert")
        }

        val serverCertificate = certificateReply.tag("plaincert")
            ?.unhex()
            ?.let { runCatching { parseCertificate(it) }.getOrNull() }
            ?: return Result.Refused("plaincert")

        // ── 2. our challenge ────────────────────────────────────────────────

        val clientChallenge = randomBytes(16)

        val challengeReply = connection.plain(
            "pair",
            "devicename" to "roth",
            "updateState" to "1",
            "clientchallenge" to encrypt(clientChallenge, key).hex(),
        ) ?: return failWith("clientchallenge") { Result.Unreachable("clientchallenge") }

        if (!challengeReply.paired()) return failWith("clientchallenge") { Result.WrongPin }

        val encryptedResponse = challengeReply.tag("challengeresponse")?.unhex()
            ?: return failWith("challengeresponse") { Result.WrongPin }

        // ── 3. their answer, and their challenge to us ──────────────────────

        val decrypted = decrypt(encryptedResponse, key)
        val hashLength = if (info.usesSha256) 32 else 20

        if (decrypted.size < hashLength + 16) {
            return failWith("challengeresponse") { Result.WrongPin }
        }

        // The host's response to our challenge, then its own challenge back to us.
        val serverResponse = decrypted.copyOfRange(0, hashLength)
        val serverChallenge = decrypted.copyOfRange(hashLength, hashLength + 16)

        val clientSecret = randomBytes(16)

        // Our answer binds three things together: their challenge, our certificate's signature, and
        // a secret only we know yet. They cannot compute it without having decrypted our challenge,
        // and we cannot fake it without the certificate we actually sent.
        val answer = hash(serverChallenge + identity.certificate.signature + clientSecret)

        val secretReply = connection.plain(
            "pair",
            "devicename" to "roth",
            "updateState" to "1",
            "serverchallengeresp" to encrypt(answer, key).hex(),
        ) ?: return failWith("serverchallengeresp") { Result.Unreachable("serverchallengeresp") }

        if (!secretReply.paired()) return failWith("serverchallengeresp") { Result.WrongPin }

        val pairingSecret = secretReply.tag("pairingsecret")?.unhex()
            ?: return failWith("pairingsecret") { Result.WrongPin }

        // ── 4. check their proof, then send ours ────────────────────────────

        if (pairingSecret.size < 16) return failWith("pairingsecret") { Result.WrongPin }

        val serverSecret = pairingSecret.copyOfRange(0, 16)
        val serverSignature = pairingSecret.copyOfRange(16, pairingSecret.size)

        // Their earlier response should have been our challenge bound to their certificate and this
        // secret. If it is not, something answered that did not know the PIN — which is exactly the
        // machine-in-the-middle this handshake exists to catch, so it is worth failing loudly.
        val expected = hash(clientChallenge + serverCertificate.signature + serverSecret)
        if (!expected.contentEquals(serverResponse)) {
            Log.w(TAG, "The host's response did not match. Wrong PIN, or something is in the way.")
            return failWith("verify") { Result.WrongPin }
        }

        // And the secret must be signed by the certificate they claimed in step 1, which is what
        // ties the whole exchange to that specific certificate.
        if (!verify(serverSecret, serverSignature, serverCertificate)) {
            Log.w(TAG, "The host's signature did not verify against the certificate it sent.")
            return failWith("verify") { Result.Refused("signature") }
        }

        val ourProof = clientSecret + sign(clientSecret)

        val finalReply = connection.plain(
            "pair",
            "devicename" to "roth",
            "updateState" to "1",
            "clientpairingsecret" to ourProof.hex(),
        ) ?: return failWith("clientpairingsecret") { Result.Unreachable("clientpairingsecret") }

        if (!finalReply.paired()) return failWith("clientpairingsecret") { Result.WrongPin }

        // ── 5. prove it over TLS ────────────────────────────────────────────

        // From here on the host knows our certificate, so this last exchange happens over HTTPS with
        // it presented — the same way every request will from now on. Pinning theirs first means
        // this is also the first connection that is actually verified in both directions.
        connection.pin(serverCertificate)

        val confirmed = connection.secure("pair", "devicename" to "roth", "phrase" to "pairchallenge")
            ?: return failWith("pairchallenge") { Result.Unreachable("pairchallenge") }

        if (!confirmed.paired()) return failWith("pairchallenge") { Result.Refused("pairchallenge") }

        Log.i(TAG, "Paired with ${info.hostname}")
        return Result.Paired(serverCertificate)
    }

    /**
     * Tell the host to forget us.
     *
     * Called on every failure path. A handshake that stops halfway can leave the host holding a
     * half-made pairing that it will not replace on the next attempt, and the only other way to
     * clear it is the host's own interface — which is a miserable thing to have to explain to
     * somebody whose PIN was simply mistyped.
     */
    fun unpair() {
        runCatching { connection.plain("unpair", "devicename" to "roth") }
    }

    private inline fun failWith(step: String, result: () -> Result): Result {
        Log.w(TAG, "Pairing failed at $step")
        unpair()
        return result()
    }

    // ── the primitives ──────────────────────────────────────────────────────

    /**
     * The key both sides derive, and the reason a four-digit PIN is enough.
     *
     * It is never transmitted and it is only useful for the seconds this handshake lasts. The salt
     * is fresh every attempt, so a recording of one exchange tells an attacker nothing about the
     * next, and there is no offline guessing to do: getting it wrong just fails the pairing.
     *
     * SHA-256 from protocol 7 onwards, SHA-1 before it — see [HostInfo.usesSha256]. Choosing wrong
     * produces a handshake that completes every step and is rejected at the very end, with no error
     * that says why.
     */
    private fun keyFrom(salt: ByteArray, pin: String): ByteArray {
        val digest = hash(salt + pin.toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 16)          // AES-128, whichever hash produced it
    }

    private fun hash(data: ByteArray): ByteArray =
        MessageDigest.getInstance(if (info.usesSha256) "SHA-256" else "SHA-1").digest(data)

    /**
     * AES-128 in ECB with no padding.
     *
     * ECB is the wrong choice in almost every other context — it leaks structure, because equal
     * blocks encrypt equally. Here every payload is exactly one 16-byte block of random data, so
     * there is no structure to leak and no second block to compare against. It is what the protocol
     * specifies, and matching it is the job.
     */
    private fun encrypt(data: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data.padToBlock())
        }

    private fun decrypt(data: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data.padToBlock())
        }

    /** NoPadding means the input must be whole blocks; the protocol's payloads already are. */
    private fun ByteArray.padToBlock(): ByteArray =
        if (size % 16 == 0) this else copyOf(((size / 16) + 1) * 16)

    private fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(identity.privateKey)
            update(data)
            sign()
        }

    private fun verify(data: ByteArray, signature: ByteArray, certificate: X509Certificate): Boolean =
        runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(certificate.publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)

    private fun parseCertificate(pem: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.inputStream()) as X509Certificate

    private fun randomBytes(count: Int) = ByteArray(count).also { RANDOM.nextBytes(it) }

    // ── the XML, such as it is ──────────────────────────────────────────────

    /** Every reply carries `<paired>1</paired>` when the step was accepted. */
    private fun String.paired(): Boolean = tag("paired") == "1"

    private fun String.tag(name: String): String? {
        val open = "<$name>"
        val start = indexOf(open)
        if (start < 0) return null
        val end = indexOf("</$name>", start + open.length)
        if (end < 0) return null
        return substring(start + open.length, end).trim().ifEmpty { null }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.unhex(): ByteArray? {
        val text = trim()
        if (text.length % 2 != 0) return null
        return runCatching {
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "AynStream"
        private val RANDOM = SecureRandom()

        /** Four digits, which is what hosts show and what people expect to type. */
        fun newPin(): String = "%04d".format(SecureRandom().nextInt(10_000))
    }
}
