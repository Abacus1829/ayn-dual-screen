package com.abacus.dualscreen.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the mod versions out of a release title.
 *
 * The title is written by hand when a release is cut, so this parses prose rather than a format.
 * Every case here is a real title this project has actually published, plus the ways one could
 * plausibly be written differently next time.
 */
class ModCatalogTest {

    private val real =
        "Ayn Dual Screen — app 0.27.0, Stardew 0.5.0, Terraria 0.3.1, Minecraft 0.7.0, Fallout NV 0.1.0"

    private val beta =
        "BETA — Ayn Dual Screen app 0.28.0-beta.2, Stardew 0.5.0, Terraria 0.3.1, " +
            "Minecraft 0.7.0, Fallout NV 0.1.0"

    @Test
    fun `every mod is read out of a real title`() {
        assertEquals("0.5.0", ModCatalog.versionIn(real, "Stardew")?.text)
        assertEquals("0.3.1", ModCatalog.versionIn(real, "Terraria")?.text)
        assertEquals("0.7.0", ModCatalog.versionIn(real, "Minecraft")?.text)
        assertEquals("0.1.0", ModCatalog.versionIn(real, "Fallout NV")?.text)
    }

    @Test
    fun `a beta title reads the same`() {
        // The app's own version carries a suffix here; the mods' must not pick it up.
        assertEquals("0.5.0", ModCatalog.versionIn(beta, "Stardew")?.text)
        assertEquals("0.1.0", ModCatalog.versionIn(beta, "Fallout NV")?.text)
    }

    @Test
    fun `a mod missing from the title does not borrow the next one's number`() {
        /*
         * The failure worth guarding against.
         *
         * The title is a comma-separated list, so a pattern that searched forward from a mod's name
         * without stopping would happily read the *following* mod's version for one whose own is
         * absent — and report it with complete confidence. Saying nothing is the only correct answer
         * there.
         */
        val partial = "Ayn Dual Screen — app 0.27.0, Stardew, Terraria 0.3.1"

        assertNull(
            "Stardew has no version here and must not be given Terraria's",
            ModCatalog.versionIn(partial, "Stardew"),
        )
        assertEquals("0.3.1", ModCatalog.versionIn(partial, "Terraria")?.text)
    }

    @Test
    fun `a mod that is not mentioned at all reads as nothing`() {
        assertNull(ModCatalog.versionIn(real, "Skyrim SE"))
        assertNull(ModCatalog.versionIn(null, "Stardew"))
        assertNull(ModCatalog.versionIn("", "Stardew"))
    }

    @Test
    fun `the app's own version is not mistaken for a mod's`() {
        // "app 0.27.0" comes first in every title, and a loose pattern anchored on nothing in
        // particular would hand it to whichever mod was asked for.
        assertEquals("0.5.0", ModCatalog.versionIn(real, "Stardew")?.text)
    }

    @Test
    fun `a v prefix and extra words are tolerated`() {
        val loose = "Release — app v1.0.0, Stardew mod v0.6.2, Terraria v0.4.0"

        assertEquals("0.6.2", ModCatalog.versionIn(loose, "Stardew")?.text)
        assertEquals("0.4.0", ModCatalog.versionIn(loose, "Terraria")?.text)
    }

    @Test
    fun `links are built from the tag, and only for mods the title names`() {
        val repo = GitHubRepo("Abacus1829", "ayn-dual-screen")
        val mods = ModCatalog.of(repo, "v2026.08.26", real)

        // Four in the title; Skyrim is not shipped and is not listed.
        assertEquals(4, mods.size)
        assertEquals("Stardew", mods[0].name)
        assertEquals("0.5.0", mods[0].version?.text)

        assertEquals(
            "https://github.com/Abacus1829/ayn-dual-screen/releases/download/v2026.08.26/AynDualScreen-Stardew.zip",
            mods[0].url,
        )
    }

    @Test
    fun `no tag means no links`() {
        // A link into a release that has no tag would be a link to nowhere, offered confidently.
        assertEquals(0, ModCatalog.of(GitHubRepo("a", "b"), "", real).size)
    }

}
