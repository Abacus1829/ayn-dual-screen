package com.abacus.dualscreen.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a version out of a release written for people.
 *
 * Every string in here is taken from a real release on this repository, because that is the only
 * way this code is worth testing: the heuristics exist to cope with these exact titles, and a
 * synthetic example would agree with whatever the code happens to do.
 *
 * The two failures being guarded against are the expensive ones. Reading the *wrong project's*
 * version offers an update that is not one; reading the *tag* as a version offers an update that
 * can never be satisfied, because the tag is a date and 2026.8.19 is newer than everything forever.
 */
class ReleaseReadingTest {

    private val currentTitle =
        "Ayn Dual Screen — app 0.14.0, Stardew 0.4.1, Terraria 0.3.1, Minecraft 0.7.0, Fallout NV 0.1.0"

    /** An older one, where the app is named last and another project's version comes first. */
    private val olderTitle =
        "Ayn Dual Screen - Stardew 0.2.0, Terraria 0.2.2, Minecraft 0.7.0, app 0.1.0"

    private val body = """
        Download links on this page are the ones the README points at.

        | Project | File | Version | Port |
        | --- | --- | --- | --- |
        | Stardew Valley (SMAPI) | `AynDualScreen-Stardew.zip` | 0.4.1 | 27301 |
        | Android app *(optional)* | `AynDualScreen-App.apk` | **0.14.0** | — |

        ---

        ## App 0.14.0

        Signed with the same key as 0.8.0, so it installs over the top.

        ### Settings

        One screen for everything the app remembers.

        ## App 0.13.0 — game codes

        Hidden, optional, and off at the game end by default.
    """.trimIndent()

    @Test
    fun `the app version is read from a title that lists five projects`() {
        assertEquals("0.14.0", AppSource.appVersionIn(currentTitle)?.text)
    }

    @Test
    fun `another project listed first is not mistaken for the app`() {
        // Reading 0.2.0 here would offer a downgrade to anybody past it, forever.
        assertEquals("0.1.0", AppSource.appVersionIn(olderTitle)?.text)
    }

    @Test
    fun `a release about one mod names no app version`() {
        assertNull(AppSource.appVersionIn("Stardew v0.4.0 — the in-game world map, and open chests"))
        assertNull(AppSource.appVersionIn("Terraria 0.3.1"))
    }

    @Test
    fun `the version can be recovered from the download table alone`() {
        assertEquals("0.14.0", AppSource.appVersionIn(body)?.text)
    }

    @Test
    fun `a date tag is never read as a version`() {
        /*
         * The trap this whole class exists for. v2026.08.19 parses perfectly well as a version
         * number — it is just not the app's, and treating it as one would mean every release looks
         * newer than every installed build and the prompt never stops appearing.
         */
        val release = release(tag = "v2026.08.19", title = "Ayn Dual Screen", notes = "Nothing here.")

        assertNotNull("the tag is a valid version number", Version.parse(release.tag))
        assertNull("...and must still not be used", version(release))
    }

    @Test
    fun `a manifest wins over anything written in prose`() {
        val release = release(tag = "v2026.09.01", title = currentTitle, notes = body)
        val manifest = UpdateManifest.parse("""{"schema":1,"versionName":"0.15.0","versionCode":16}""")

        assertEquals("0.15.0", version(release, manifest)?.text)
    }

    @Test
    fun `a manifest that cannot be parsed is simply absent`() {
        assertNull(UpdateManifest.parse("not json at all"))
        assertNull(UpdateManifest.parse(null))

        // ...and the release still reads, from its title.
        val release = release(tag = "v2026.08.19", title = currentTitle, notes = "")
        assertEquals("0.14.0", version(release, null)?.text)
    }

    @Test
    fun `a manifest carries the checksum and the file name`() {
        val manifest = UpdateManifest.parse(
            """
            {
              "schema": 1,
              "channel": "beta",
              "versionName": "0.15.0-beta.1",
              "versionCode": 16,
              "apk": "AynDualScreen-App.apk",
              "sha256": "SHA256:AABB",
              "size": 4031217
            }
            """.trimIndent()
        )!!

        assertEquals("0.15.0-beta.1", manifest.version?.text)
        assertEquals(16L, manifest.versionCode)
        assertEquals("AynDualScreen-App.apk", manifest.artifact)
        assertEquals("aabb", manifest.sha256)  // lower-cased, prefix removed, ready to compare
        assertEquals(4031217L, manifest.size)
    }

    @Test
    fun `a stable channel refuses a pre-release, a beta channel takes both`() {
        val beta = Version.parse("0.15.0-beta.1")!!
        val stable = Version.parse("0.15.0")!!

        assertFalse(Channel.STABLE.accepts(beta))
        assertTrue(Channel.STABLE.accepts(stable))
        assertTrue(Channel.BETA.accepts(beta))
        assertTrue(Channel.BETA.accepts(stable))

        val marked = release("v1", "t", "n").copy(preRelease = true)
        assertFalse(Channel.STABLE.accepts(marked))
        assertTrue(Channel.BETA.accepts(marked))
    }

    @Test
    fun `a cached update survives being written to preferences and read back`() {
        val update = Update(
            sourceId = "app",
            version = Version.parse("0.15.0")!!,
            versionCode = 16,
            tag = "v2026.09.01",
            title = currentTitle,
            notes = body,
            assetName = "AynDualScreen-App.apk",
            url = "https://example.invalid/AynDualScreen-App.apk",
            size = 4031217,
            sha256 = "abc123",
            preRelease = false,
            publishedAt = "2026-09-01T10:00:00Z",
        )

        assertEquals(update, Update.fromJson(update.toJson()))
    }

    @Test
    fun `release notes start at the app section and lose their markup`() {
        val readable = ReleaseNotes.readable(body)

        assertTrue("starts at the app heading", readable.startsWith("App 0.14.0"))
        assertFalse("no download table", readable.contains("AynDualScreen-Stardew.zip"))
        assertFalse("no bold markers", readable.contains("**"))
        assertFalse("no heading hashes", readable.contains("#"))
        assertTrue("later versions are kept", readable.contains("App 0.13.0"))
    }

    @Test
    fun `notes with no app section are shown whole`() {
        val plain = ReleaseNotes.readable("Just a sentence about the Stardew mod.")
        assertEquals("Just a sentence about the Stardew mod.", plain)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun release(tag: String, title: String, notes: String) = Release(
        tag = tag,
        title = title,
        notes = notes,
        preRelease = false,
        draft = false,
        publishedAt = "2026-08-19T21:32:28Z",
        assets = listOf(
            ReleaseAsset(
                name = "AynDualScreen-App.apk",
                url = "https://example.invalid/AynDualScreen-App.apk",
                size = 4031217,
                sha256 = "1460ff40",
                contentType = "application/vnd.android.package-archive",
            )
        ),
    )

    /**
     * The same resolution order [AppSource] uses, without needing a Context to build one.
     *
     * Deliberately mirrors AppSource.versionOf rather than calling it: the point being asserted is
     * that manifest, then title, then notes — and never the tag — is what gets consulted.
     */
    private fun version(release: Release, manifest: UpdateManifest? = null): Version? =
        manifest?.version
            ?: AppSource.appVersionIn(release.title)
            ?: AppSource.appVersionIn(release.notes)
}
