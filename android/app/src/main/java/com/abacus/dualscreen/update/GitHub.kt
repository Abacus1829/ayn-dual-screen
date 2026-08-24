package com.abacus.dualscreen.update

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * Reading a repository's releases, and nothing else.
 *
 * Deliberately knows nothing about this app: it is handed a repository and returns what is
 * published there. That is what lets the same class serve a future plugin update — a plugin lives
 * in somebody else's repository and the only thing that changes is the [GitHubRepo] passed in.
 *
 * **Unauthenticated on purpose.** No token ships in the APK, because a token in a sideloaded binary
 * is a token given away. The cost is GitHub's anonymous limit of 60 requests an hour per address,
 * which is generous for a check that runs once at startup and is throttled on top of that — and
 * when it is reached, [UpdateError.RATE_LIMITED] says so plainly instead of looking like an outage.
 *
 * Every method blocks. Call from a background thread.
 */
object GitHub {

    /** How many releases to ask for. Enough to find a stable one behind a run of pre-releases. */
    const val PAGE = 12

    sealed interface Answer {
        data class Ok(val releases: List<Release>, val etag: String?) : Answer

        /** The list has not changed since [etag]. Costs nothing and does not count against the limit. */
        data object NotModified : Answer

        data class Broken(val failure: Failure) : Answer
    }

    /**
     * The newest releases, newest first.
     *
     * [etag] is the one from the previous answer. Sending it back turns an unchanged list into a
     * 304, which GitHub does not count against the rate limit — the single most effective thing an
     * app that checks on every launch can do to stay under it.
     */
    fun releases(repo: GitHubRepo, count: Int = PAGE, etag: String? = null): Answer {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            if (!etag.isNullOrBlank()) put("If-None-Match", etag)
        }

        val connection = try {
            Http.get(repo.releasesUrl(count), headers)
        } catch (error: Exception) {
            return Answer.Broken(Http.classify(error))
        }

        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return Answer.NotModified

                HttpURLConnection.HTTP_OK -> Unit

                HttpURLConnection.HTTP_NOT_FOUND ->
                    return Answer.Broken(Failure(UpdateError.NOT_FOUND, repo.toString()))

                HttpURLConnection.HTTP_FORBIDDEN, 429 -> {
                    // 403 is both "rate limited" and "not allowed"; the header is what tells them apart.
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    return if (remaining == "0" || code == 429)
                        Answer.Broken(Failure(UpdateError.RATE_LIMITED, resetHint(connection)))
                    else
                        Answer.Broken(Failure(UpdateError.NOT_FOUND, "HTTP " + code))
                }

                in 500..599 -> return Answer.Broken(Failure(UpdateError.SERVER, "HTTP " + code))

                else -> return Answer.Broken(Failure(UpdateError.MALFORMED, "HTTP " + code))
            }

            val body = Http.body(connection)
                ?: return Answer.Broken(Failure(UpdateError.MALFORMED, "empty body"))

            val releases = runCatching { parse(body) }.getOrElse {
                return Answer.Broken(Failure(UpdateError.MALFORMED, it.message))
            }

            return Answer.Ok(releases, connection.getHeaderField("ETag"))
        } catch (error: Exception) {
            return Answer.Broken(Http.classify(error))
        } finally {
            connection.disconnect()
        }
    }

    /** A small text asset — the update manifest. Null when it is not there or will not come. */
    fun text(url: String, limit: Int = 64 * 1024): String? = runCatching {
        val connection = Http.get(url, mapOf("Accept" to "application/octet-stream"))
        try {
            Http.body(connection, limit)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    // ── parsing ─────────────────────────────────────────────────────────────

    /**
     * Drafts are dropped here rather than by the caller.
     *
     * A draft is not published: its assets are not downloadable without a token, so offering one
     * would produce an update that can never install. Everything else is passed on, and the channel
     * decides.
     *
     * Internal rather than private so the tests can hand it a real captured API response. Parsing
     * somebody else's JSON is exactly the code that should be tested against the actual bytes their
     * server sends, rather than against an example of what it is believed to send.
     */
    internal fun parse(body: String): List<Release> {
        val array = JSONArray(body)
        val releases = mutableListOf<Release>()

        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            if (json.optBoolean("draft", false)) continue

            releases += Release(
                tag = json.optString("tag_name"),
                title = json.optString("name").ifBlank { json.optString("tag_name") },
                notes = json.optString("body"),
                preRelease = json.optBoolean("prerelease", false),
                draft = false,
                publishedAt = json.optString("published_at"),
                assets = assets(json.optJSONArray("assets")),
            )
        }
        return releases
    }

    internal fun assets(array: JSONArray?): List<ReleaseAsset> {
        array ?: return emptyList()
        val assets = mutableListOf<ReleaseAsset>()

        for (i in 0 until array.length()) {
            val json: JSONObject = array.optJSONObject(i) ?: continue
            val url = json.optString("browser_download_url")
            if (url.isBlank()) continue

            // Only uploaded assets can be fetched; anything mid-upload would 404 on download.
            if (json.optString("state", "uploaded") != "uploaded") continue

            assets += ReleaseAsset(
                name = json.optString("name"),
                url = url,
                size = json.optLong("size"),
                // "sha256:abc…" when GitHub has computed one, absent on older assets.
                sha256 = json.optString("digest").takeIf { it.isNotBlank() }
                    ?.removePrefix("sha256:")?.lowercase(),
                contentType = json.optString("content_type"),
            )
        }
        return assets
    }

    /** Turn the reset header into something worth showing, when it is there. */
    private fun resetHint(connection: HttpURLConnection): String? {
        val reset = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull() ?: return null
        val minutes = ((reset * 1000 - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        return "resets in about " + minutes + " min"
    }
}
