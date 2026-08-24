package com.abacus.dualscreen.update

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Something that can be updated from a GitHub repository.
 *
 * The app is the only one today. The interface exists because the roadmap's plugin system will want
 * exactly this and would otherwise get a second, subtly different copy of it: a plugin lives in a
 * repository, publishes an artefact under a stable filename, and has a version that has to be read
 * out of a release somehow. Everything downstream — the checker, the downloader, the retry and
 * cancel logic, the error vocabulary — is written against this interface and knows nothing about
 * APKs. Only [Installer] does, and a plugin would bring its own.
 */
interface UpdateSource {

    /** Stable identity, used in preferences. "app", later "plugin:stardew". */
    val id: String

    val repo: GitHubRepo

    /** The asset to download, by exact name. */
    val artifactName: String

    /** Accepted when [artifactName] is missing — the extension a matching asset would end in. */
    val artifactSuffix: String

    /** The optional machine-readable sidecar. Null for a source that publishes none. */
    val manifestName: String?

    /** What is installed right now, or null if that cannot be determined. */
    fun installedVersion(): Version?

    /**
     * Work out which version a release contains.
     *
     * Separate from the checker because this is the part that differs per project, and it is
     * guesswork whenever there is no manifest.
     */
    fun versionOf(release: Release, manifest: UpdateManifest?): Version?
}

/**
 * The Android app itself.
 *
 * Reading a version out of this repository's releases is the awkward part, and worth spelling out
 * because it looks like it should be one line:
 *
 * - **The tag is a date.** `v2026.08.19`. Parsed as a version that is 2026.8.19, which is newer than
 *   everything forever — every release would look like an update and the prompt would never stop.
 *   So the tag is never consulted. This is the trap this class exists to avoid.
 * - **The filename is deliberately version-free.** `AynDualScreen-App.apk`, so the README can link
 *   `releases/latest/download/…` and have it keep working. Nothing to read there either.
 * - **One release carries five projects.** The title says *"app 0.14.0, Stardew 0.4.1, …"*, so a
 *   plain "first number in the title" would find Stardew's version in the older releases where it
 *   is written first.
 *
 * What is left is: the manifest if the release has one, otherwise the number written next to the
 * word *app*, otherwise nothing — and "nothing" is reported rather than guessed, because a wrong
 * guess here means either a nag that cannot be dismissed or an update that is never offered.
 */
class AppSource(private val context: Context) : UpdateSource {

    override val id = "app"

    override val repo = GitHubRepo(OWNER, REPO)

    override val artifactName = "AynDualScreen-App.apk"

    override val artifactSuffix = ".apk"

    override val manifestName = "AynDualScreen-App.json"

    override fun installedVersion(): Version? = Version.parse(installed()?.versionName)

    /** The versionCode Android compares when installing. Authoritative, unlike the name. */
    fun installedCode(): Long = installed()?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
        else @Suppress("DEPRECATION") it.versionCode.toLong()
    } ?: 0L

    /** The name as shown to the user, even when it is not parseable as a version. */
    fun installedName(): String = installed()?.versionName.orEmpty()

    /**
     * The SHA-256 of the APK this app is running from.
     *
     * The reason this exists: releases here carry all five projects every time, so a Stardew-only
     * release re-uploads the *identical* app APK under a new tag with a new title. Comparing hashes
     * catches that in one step — same bytes, same build, nothing to install — no matter what the
     * title says. Without it, a release whose title mentions a version this device already has can
     * still be offered forever.
     *
     * Costs one read of about four megabytes, on a background thread, and only when a release has
     * otherwise looked newer.
     */
    fun installedFingerprint(): String? = runCatching {
        val path = context.applicationInfo.sourceDir ?: return null
        sha256(File(path))
    }.getOrNull()

    override fun versionOf(release: Release, manifest: UpdateManifest?): Version? =
        manifest?.version
            ?: appVersionIn(release.title)
            ?: appVersionIn(release.notes)

    private fun installed(): PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    companion object {

        /**
         * Where updates come from.
         *
         * One place, and a constant rather than a setting: an app that lets somebody point its
         * updater at an arbitrary repository is an app that can be talked into installing anything.
         * A fork changes these two lines and builds its own.
         */
        const val OWNER = "Abacus1829"
        const val REPO = "ayn-dual-screen"

        /**
         * The version written next to the word "app".
         *
         * The gap allows for the words and punctuation that sit between them in a release title or
         * a Markdown table row — *"Android app *(optional)* | `AynDualScreen-App.apk` | **0.14.0**"* —
         * while refusing to jump over another number on the way, which is what keeps it from
         * reading Stardew's version out of a title that lists Stardew first.
         */
        private val APP_VERSION = Regex(
            """(?i)\bapp\b[^0-9\n]{0,60}?v?(\d{1,5}(?:\.\d{1,5}){1,3}(?:-[0-9A-Za-z.\-]+)?)"""
        )

        fun appVersionIn(text: String?): Version? {
            val match = APP_VERSION.find(text.orEmpty()) ?: return null
            return Version.parse(match.groupValues[1])
        }

        /** Lower-case hex, matching the form GitHub reports in an asset digest. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
