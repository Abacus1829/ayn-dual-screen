package com.abacus.dualscreen.update

import org.json.JSONObject

/**
 * A release that has been read, understood, and judged newer than what is installed.
 *
 * Flat and self-contained rather than holding a [Release]: it is written into preferences so a
 * check survives the app being closed, and so the prompt after a boot animation does not need the
 * network again. Anything that cannot be recovered from disk has no business in a cached answer.
 */
data class Update(
    val sourceId: String,
    val version: Version,
    /** From a manifest when there is one. Null means "read it from the APK after downloading". */
    val versionCode: Long?,
    val tag: String,
    val title: String,
    /** The release body, as published. Markdown, shown to the user. */
    val notes: String,
    val assetName: String,
    val url: String,
    val size: Long,
    /** Expected digest, from the manifest or from GitHub's own record of the asset. */
    val sha256: String?,
    val preRelease: Boolean,
    val publishedAt: String,
) {

    fun toJson(): String = JSONObject().apply {
        put("source", sourceId)
        put("version", version.text)
        versionCode?.let { put("versionCode", it) }
        put("tag", tag)
        put("title", title)
        put("notes", notes)
        put("asset", assetName)
        put("url", url)
        put("size", size)
        sha256?.let { put("sha256", it) }
        put("prerelease", preRelease)
        put("published", publishedAt)
    }.toString()

    companion object {

        fun fromJson(text: String?): Update? = runCatching {
            val json = JSONObject(text.orEmpty())
            Update(
                sourceId = json.optString("source"),
                version = Version.parse(json.optString("version")) ?: return null,
                versionCode = json.optLong("versionCode").takeIf { it > 0 },
                tag = json.optString("tag"),
                title = json.optString("title"),
                notes = json.optString("notes"),
                assetName = json.optString("asset"),
                url = json.optString("url"),
                size = json.optLong("size"),
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() },
                preRelease = json.optBoolean("prerelease"),
                publishedAt = json.optString("published"),
            )
        }.getOrNull()
    }
}
