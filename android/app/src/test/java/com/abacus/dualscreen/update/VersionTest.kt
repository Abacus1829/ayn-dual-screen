package com.abacus.dualscreen.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version ordering.
 *
 * The first test here is the reason this class exists rather than a string comparison, and it is not
 * hypothetical: this app is on 0.14.0, so every "is there something newer" question already involves
 * a two-digit minor number. Compared as text, "0.9.0" is greater than "0.14.0" and the updater would
 * quietly stop working — while continuing to report success.
 */
class VersionTest {

    @Test
    fun `a two digit minor is newer than a one digit minor`() {
        val nine = Version.parse("0.9.0")!!
        val fourteen = Version.parse("0.14.0")!!

        assertTrue("0.14.0 must be newer than 0.9.0", fourteen > nine)
        assertTrue(nine < fourteen)

        // The failure this is guarding against, stated plainly.
        assertTrue("string order disagrees, which is the whole point", "0.9.0" > "0.14.0")
    }

    @Test
    fun `a leading v is accepted, because releases are written by hand`() {
        assertEquals(Version.parse("1.2.3"), Version.parse("v1.2.3"))
    }

    @Test
    fun `missing trailing numbers count as zero`() {
        assertEquals(0, Version.parse("1.2")!!.compareTo(Version.parse("1.2.0")!!))
        assertTrue(Version.parse("1.2.1")!! > Version.parse("1.2")!!)
    }

    @Test
    fun `a finished release outranks its own pre-releases`() {
        val beta = Version.parse("1.0.0-beta.1")!!
        val final = Version.parse("1.0.0")!!

        assertTrue("somebody on 1.0.0 must never be offered 1.0.0-beta.1", final > beta)
    }

    @Test
    fun `pre-releases order by number, not by text`() {
        val second = Version.parse("1.0.0-beta.2")!!
        val tenth = Version.parse("1.0.0-beta.10")!!

        assertTrue("beta.10 comes after beta.2", tenth > second)
    }

    @Test
    fun `nonsense is refused rather than guessed at`() {
        assertNull(Version.parse("latest"))
        assertNull(Version.parse(""))
        assertNull(Version.parse(null))
        assertNull(Version.parse("v"))
    }

    @Test
    fun `the text form survives a round trip`() {
        assertEquals("0.15.0-beta.2", Version.parse("v0.15.0-beta.2")!!.text)
        assertEquals("0.14.0", Version.parse("0.14.0")!!.text)
    }

    @Test
    fun `find picks a version out of a sentence`() {
        assertEquals("0.4.0", Version.find("Stardew v0.4.0 - the in-game world map")!!.text)
        assertNull(Version.find("no numbers here at all"))
    }

    @Test
    fun `the shipped beta version is recognised as a pre-release`() {
        /*
         * The one thing that must be true before publishing a beta.
         *
         * Channel.STABLE refuses any version that reports isPreRelease, and that refusal is the only
         * thing standing between a beta build and everybody who never asked for one. If the suffix
         * this project actually ships were parsed as part of the version number instead of as a
         * pre-release tag, stable users would be offered it and there would be no error anywhere —
         * it would simply work, on the wrong devices.
         *
         * So this pins the literal string the build file carries.
         */
        val beta = Version.parse("0.28.0-beta.1")

        assertNotNull(beta)
        assertTrue("0.28.0-beta.1 is not being treated as a pre-release", beta!!.isPreRelease)
        assertFalse("a stable channel would offer this beta", Channel.STABLE.accepts(beta))
        assertTrue("the beta channel would not offer it", Channel.BETA.accepts(beta))

        // And it is genuinely older than the release it precedes, so 0.28.0 supersedes it later.
        val final = Version.parse("0.28.0")!!
        assertTrue("the beta should sort below its own final release", beta < final)

        // But newer than what it follows, or nobody on 0.27.0 would be offered it at all.
        assertTrue("the beta should sort above the previous release", beta > Version.parse("0.27.0")!!)
    }

}
