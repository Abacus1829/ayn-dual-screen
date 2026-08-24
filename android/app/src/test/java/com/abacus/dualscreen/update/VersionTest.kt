package com.abacus.dualscreen.update

import org.junit.Assert.assertEquals
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
}
