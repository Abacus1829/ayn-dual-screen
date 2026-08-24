package com.abacus.dualscreen.update

import org.json.JSONObject

/**
 * The small JSON file published alongside the APK, and the only exact answer to "what version is
 * this release?".
 *
 * Everything else is a guess made from prose. This repository's release titles read
 * *"Ayn Dual Screen — app 0.14.0, Stardew 0.4.1, …"*, its tags are dates rather than versions, and
 * its asset filenames are deliberately version-free so the README can link
 * `releases/latest/download/AynDualScreen-App.apk` and have it keep working. Every one of those is
 * a good decision for the humans downloading it and none of them tells a program anything.
 *
 * So a release may carry `AynDualScreen-App.json`:
 *
 * ```json
 * {
 *   "schema": 1,
 *   "channel": "stable",
 *   "versionName": "0.15.0",
 *   "versionCode": 16,
 *   "apk": "AynDualScreen-App.apk",
 *   "sha256": "9f2c…",
 *   "size": 4031217,
 *   "minSdk": 26,
 *   "notes": "optional; the release body is used when this is absent"
 * }
 * ```
 *
 * It is optional on purpose. Six releases exist that predate it, and the app still has to do
 * something sensible with them — see [AppSource] for the fallbacks. Every field here is optional
 * too: a manifest that names only a version is still better than no manifest.
 *
 * The shape is deliberately not app-specific. A plugin release would publish the same document with
 * its own artefact name, which is the point: one format, one parser, one place to change.
 */
data class UpdateManifest(
    val schema: Int,
    val channel: String?,
    val versionName: String?,
    val versionCode: Long?,
    /** The asset holding the payload. Defaults to the source's own expected filename. */
    val artifact: String?,
    val sha256: String?,
    val size: Long?,
    val minSdk: Int?,
    val notes: String?,
) {

    val version: Version? get() = Version.parse(versionName)

    companion object {

        /** The newest schema this build understands. */
        const val SCHEMA = 1

        /**
         * Parse, or null.
         *
         * A manifest that cannot be read is not an error worth showing anybody: the release is still
         * there, the other ways of reading its version still work, and a parse failure here should
         * degrade to "no manifest" rather than to "no update".
         */
        fun parse(text: String?): UpdateManifest? = runCatching {
            val json = JSONObject(text.orEmpty())
            UpdateManifest(
                schema = json.optInt("schema", SCHEMA),
                channel = json.optString("channel").takeIf { it.isNotBlank() },
                versionName = json.optString("versionName").takeIf { it.isNotBlank() },
                versionCode = json.optLong("versionCode").takeIf { it > 0 },
                artifact = listOf("apk", "artifact", "file")
                    .firstNotNullOfOrNull { json.optString(it).takeIf { name -> name.isNotBlank() } },
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() }?.lowercase()
                    ?.removePrefix("sha256:"),
                size = json.optLong("size").takeIf { it > 0 },
                minSdk = json.optInt("minSdk").takeIf { it > 0 },
                notes = json.optString("notes").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}
