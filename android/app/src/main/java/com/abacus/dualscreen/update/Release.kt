package com.abacus.dualscreen.update

/** A repository to ask about releases. Nothing here is specific to this app. */
data class GitHubRepo(val owner: String, val name: String) {

    /** Newest first, which is the order the API returns them in. */
    fun releasesUrl(count: Int): String =
        "https://api.github.com/repos/$owner/$name/releases?per_page=$count"

    override fun toString(): String = "$owner/$name"
}

/**
 * One file attached to a release.
 *
 * [sha256] comes from the API's own `digest` field when GitHub reports one. It is worth having even
 * though the download is over TLS: it is computed by GitHub from the bytes it stored, so it catches
 * a truncated download, a proxy that rewrote the body, and the case that actually happens on a
 * handheld — a Wi-Fi drop halfway through leaving a plausible-looking half file on disk.
 */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
    val contentType: String,
)

/**
 * A GitHub release, reduced to the parts an updater needs.
 *
 * [notes] is the release body, written in Markdown for people to read on the web. It is shown to
 * the user, so it is carried as-is rather than parsed.
 */
data class Release(
    val tag: String,
    val title: String,
    val notes: String,
    val preRelease: Boolean,
    val draft: Boolean,
    /** ISO-8601, as GitHub sends it. Only ever displayed, never compared. */
    val publishedAt: String,
    val assets: List<ReleaseAsset>,
) {

    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name.equals(name, true) }

    fun assetEndingIn(suffix: String): ReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
}
