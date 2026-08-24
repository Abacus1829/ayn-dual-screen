package com.abacus.dualscreen.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * What the app checks before it hands a file to Android's installer.
 *
 * The digest check in [Downloader] proves the bytes arrived intact. This proves they are the right
 * bytes: a real APK, this application, a newer build, and signed by whoever signed the copy already
 * running. Four questions, each with a different wrong answer worth naming.
 *
 * The last one is the one that matters. A sideloaded app has no store between it and the file it
 * downloads, so "was this built by the same person as the version I am replacing?" is the only
 * question standing in for that. Android asks it too and refuses the install if the answer is no —
 * with an error nobody can act on. Asking first means the app can say what actually happened.
 *
 * None of this is a substitute for the system installer, and none of it grants any privilege: the
 * app still hands the file over and the user still confirms. These checks only decide whether it is
 * worth their time.
 */
object ApkCheck {

    /** Null when the file is safe to offer to the installer; a named failure otherwise. */
    fun verify(context: Context, file: File, update: Update): Failure? {
        if (!file.isFile || file.length() == 0L)
            return Failure(UpdateError.NOT_AN_APK, "no file")

        val archive = archiveInfo(context, file)
            ?: return Failure(UpdateError.NOT_AN_APK, file.name)

        if (archive.packageName != context.packageName)
            return Failure(UpdateError.WRONG_PACKAGE, archive.packageName)

        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, signatureFlag())
        }.getOrNull()

        /*
         * Android compares versionCode and refuses anything not greater. The name is for people;
         * this is the number that decides whether the install can happen at all, and it is read
         * from the downloaded file rather than trusted from the release notes.
         */
        val downloaded = versionCode(archive)
        val current = installed?.let { versionCode(it) } ?: 0L
        if (downloaded <= current)
            return Failure(
                UpdateError.NOT_NEWER,
                "versionCode " + downloaded + ", installed " + current,
            )

        val theirs = certificates(archive)
        val ours = installed?.let { certificates(it) }.orEmpty()

        if (theirs.isEmpty())
            return Failure(UpdateError.NOT_AN_APK, "unsigned")

        // An empty "ours" means the installed package could not be read at all, which should not
        // happen for our own package; not comparing is better than failing on a phantom mismatch.
        if (ours.isNotEmpty() && theirs.intersect(ours).isEmpty())
            return Failure(UpdateError.WRONG_SIGNER, short(theirs.first()))

        return null
    }

    /** The versionCode inside a downloaded APK, for a screen that wants to show it. */
    fun versionCodeOf(context: Context, file: File): Long? =
        archiveInfo(context, file)?.let { versionCode(it) }

    // ── the pieces ──────────────────────────────────────────────────────────

    private fun archiveInfo(context: Context, file: File): PackageInfo? = runCatching {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlag())
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signatureFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    /**
     * Every certificate that signed a package, as SHA-256 hex.
     *
     * A v3-signed APK can carry a rotation history; taking the whole set and intersecting means a
     * key that has legitimately been rotated still matches, while an unrelated key never does.
     */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures: Array<Signature> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                val signing = info.signingInfo
                when {
                    signing == null -> emptyArray()
                    signing.hasMultipleSigners() -> signing.apkContentsSigners ?: emptyArray()
                    else -> signing.signingCertificateHistory ?: emptyArray()
                }
            }

            else -> info.signatures ?: emptyArray()
        }

        return signatures.map { fingerprint(it) }.toSet()
    }

    private fun fingerprint(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The first few bytes of a fingerprint, which is all a message has room to show. */
    private fun short(fingerprint: String): String =
        fingerprint.take(16).chunked(4).joinToString(":")
}
