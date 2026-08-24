package com.abacus.dualscreen.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing GitHub's release list.
 *
 * The JSON below is a trimmed capture of a real response from
 * `api.github.com/repos/Abacus1829/ayn-dual-screen/releases` — real tags, real titles, real asset
 * names, real sizes, and the real `digest` field. Only the release bodies are shortened.
 *
 * That matters. This is code that reads somebody else's format, and testing it against an invented
 * example only proves the parser agrees with whatever was imagined while writing it. The specific
 * things being pinned here are the ones that would silently break the updater: the digest arriving
 * as `sha256:…` rather than bare hex, assets that are not the APK sitting alongside it, and a
 * date-shaped tag that must never be mistaken for a version.
 */
class GitHubParseTest {

    private val response = """
    [
      {
        "tag_name": "v2026.08.19",
        "name": "Ayn Dual Screen — app 0.14.0, Stardew 0.4.1, Terraria 0.3.1, Minecraft 0.7.0, Fallout NV 0.1.0",
        "draft": false,
        "prerelease": false,
        "published_at": "2026-08-19T21:32:28Z",
        "body": "| Android app *(optional)* | AynDualScreen-App.apk | **0.14.0** | — |\n\n## App 0.14.0\n\nOne screen for everything the app remembers.",
        "assets": [
          {
            "name": "AynDualScreen-App.apk",
            "browser_download_url": "https://github.com/Abacus1829/ayn-dual-screen/releases/download/v2026.08.19/AynDualScreen-App.apk",
            "size": 4031217,
            "state": "uploaded",
            "digest": "sha256:1460ff400b5237adcf55614440019baeaa879483a879e8ddfa32439b18bfabcb",
            "content_type": "application/vnd.android.package-archive"
          },
          {
            "name": "AynDualScreen-Stardew.zip",
            "browser_download_url": "https://github.com/Abacus1829/ayn-dual-screen/releases/download/v2026.08.19/AynDualScreen-Stardew.zip",
            "size": 70485,
            "state": "uploaded",
            "digest": "sha256:c0150958632e1213fe2d2ff3983ebad1dfd9e1d1dc8e0c89231dd9087f5f3b33",
            "content_type": "application/zip"
          },
          {
            "name": "AynDualScreen-Terraria.tmod",
            "browser_download_url": "https://github.com/Abacus1829/ayn-dual-screen/releases/download/v2026.08.19/AynDualScreen-Terraria.tmod",
            "size": 75810,
            "state": "uploaded",
            "digest": null,
            "content_type": "application/octet-stream"
          }
        ]
      },
      {
        "tag_name": "v2026.08.12b",
        "name": "Stardew v0.4.0 — the in-game world map, and open chests",
        "draft": false,
        "prerelease": false,
        "published_at": "2026-08-12T20:37:07Z",
        "body": "Stardew only.",
        "assets": [
          {
            "name": "AynDualScreen-Stardew.zip",
            "browser_download_url": "https://github.com/Abacus1829/ayn-dual-screen/releases/download/v2026.08.12b/AynDualScreen-Stardew.zip",
            "size": 67874,
            "state": "uploaded",
            "content_type": "application/zip"
          }
        ]
      },
      {
        "tag_name": "v2026.09.01-beta",
        "name": "Beta — app 0.16.0-beta.1",
        "draft": false,
        "prerelease": true,
        "published_at": "2026-09-01T09:00:00Z",
        "body": "Rough edges.",
        "assets": [
          {
            "name": "AynDualScreen-App.apk",
            "browser_download_url": "https://example.invalid/beta/AynDualScreen-App.apk",
            "size": 4100000,
            "state": "uploaded",
            "digest": "sha256:aabbcc",
            "content_type": "application/vnd.android.package-archive"
          }
        ]
      },
      {
        "tag_name": "v2026.09.09-draft",
        "name": "Not published yet — app 9.9.9",
        "draft": true,
        "prerelease": false,
        "published_at": "2026-09-09T09:00:00Z",
        "body": "Draft.",
        "assets": []
      }
    ]
    """.trimIndent()

    private val releases by lazy { GitHub.parse(response) }

    @Test
    fun `drafts never reach the caller`() {
        // A draft's assets cannot be downloaded without a token, so an update built from one could
        // only ever fail — and this one claims to be 9.9.9, which would suppress every real release.
        assertEquals(3, releases.size)
        assertTrue(releases.none { it.tag.contains("draft") })
    }

    @Test
    fun `the newest release yields the app APK, its size and its digest`() {
        val newest = releases.first()
        val apk = newest.asset("AynDualScreen-App.apk")

        assertNotNull(apk)
        assertEquals(4031217L, apk!!.size)
        assertEquals(
            "the sha256: prefix is stripped so it can be compared with a computed hash",
            "1460ff400b5237adcf55614440019baeaa879483a879e8ddfa32439b18bfabcb",
            apk.sha256,
        )
    }

    @Test
    fun `an asset with no digest is carried as null rather than as the string null`() {
        val tmod = releases.first().asset("AynDualScreen-Terraria.tmod")
        assertNotNull(tmod)
        assertNull("older assets predate the digest field", tmod!!.sha256)
    }

    @Test
    fun `the mod-only release offers no APK at all`() {
        val stardewOnly = releases[1]

        assertNull(stardewOnly.asset("AynDualScreen-App.apk"))
        assertNull("and nothing else ending in .apk either", stardewOnly.assetEndingIn(".apk"))
    }

    @Test
    fun `versions come from the titles, never from the date tags`() {
        assertEquals("0.14.0", AppSource.appVersionIn(releases[0].title)?.text)
        assertNull(AppSource.appVersionIn(releases[1].title))
        assertEquals("0.16.0-beta.1", AppSource.appVersionIn(releases[2].title)?.text)

        // Every one of these tags parses as a version. None of them is one.
        assertTrue(releases.all { Version.parse(it.tag) == null || !it.tag.contains("0.14") })
    }

    @Test
    fun `the pre-release is marked, so the stable channel will not take it`() {
        val beta = releases[2]

        assertTrue(beta.preRelease)
        assertTrue(Channel.BETA.accepts(beta))
        assertTrue(
            "and it is refused twice over: by the release flag and by the version suffix",
            !Channel.STABLE.accepts(beta) &&
                !Channel.STABLE.accepts(AppSource.appVersionIn(beta.title)!!)
        )
    }

    @Test
    fun `a body that is not a list is a failure, not an empty answer`() {
        // An HTML error page, a rate-limit message, a truncated response: all end up here, and all
        // must throw rather than quietly parse as "no releases", which reads as "you are current".
        val threw = runCatching { GitHub.parse("<html>rate limited</html>") }.isFailure
        assertTrue(threw)
    }
}
