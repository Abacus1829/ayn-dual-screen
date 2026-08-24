package com.abacus.dualscreen.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The same request the app makes on startup, against the real repository.
 *
 * **Skipped unless `AYN_LIVE_UPDATE_TEST=1` is in the environment.** A test that needs the internet
 * has no business failing somebody's build because their train went into a tunnel, and GitHub's
 * anonymous limit is sixty requests an hour — a test that runs on every build would spend them.
 *
 * ```
 * AYN_LIVE_UPDATE_TEST=1 ./gradlew testDebugUnitTest --tests '*LiveReleasesTest'
 * ```
 *
 * What it is for: the parser in [GitHubParseTest] is checked against a captured response, which
 * proves it agrees with the bytes GitHub sent *once*. This proves the whole path still works today —
 * the URL, the headers GitHub requires, the redirect, the JSON shape, and the version actually
 * resolving out of a real title. Run it after changing anything in this package, and before a
 * release.
 *
 * It runs on the desktop JVM, so it exercises [GitHub] and [Http] but not the Android-specific parts:
 * no Context, so no connectivity check, no package manager, no installer.
 */
class LiveReleasesTest {

    private val repo = GitHubRepo(AppSource.OWNER, AppSource.REPO)

    @Test
    fun `the live release list parses and yields an app version`() {
        assumeTrue("set AYN_LIVE_UPDATE_TEST=1 to run", System.getenv("AYN_LIVE_UPDATE_TEST") == "1")

        val answer = GitHub.releases(repo)
        assertTrue("GitHub said: " + answer, answer is GitHub.Answer.Ok)

        val ok = answer as GitHub.Answer.Ok
        assertTrue("the repository has releases", ok.releases.isNotEmpty())
        assertNotNull("an ETag is returned, which is what keeps checks free", ok.etag)

        // The first release carrying an APK, exactly as UpdateChecker walks the list.
        val release = ok.releases.first { it.asset("AynDualScreen-App.apk") != null }
        val apk = release.asset("AynDualScreen-App.apk")!!

        println("newest release with an APK: " + release.tag + " — " + release.title)
        println("  asset " + apk.name + ", " + apk.size + " bytes, digest " + apk.sha256)

        assertTrue("the APK is a plausible size", apk.size > 1_000_000)
        assertTrue("the download URL is https", apk.url.startsWith("https://"))

        val version = AppSource.appVersionIn(release.title) ?: AppSource.appVersionIn(release.notes)
        assertNotNull(
            "no version could be read from the newest release — the title has stopped naming the " +
                "app version, and every device would silently stop being offered updates",
            version,
        )
        println("  resolved app version: " + version)
    }

    @Test
    fun `sending the etag back makes the next check free`() {
        assumeTrue("set AYN_LIVE_UPDATE_TEST=1 to run", System.getenv("AYN_LIVE_UPDATE_TEST") == "1")

        val first = GitHub.releases(repo) as GitHub.Answer.Ok
        val second = GitHub.releases(repo, etag = first.etag)

        // A 304 does not count against the rate limit, which is the whole reason for carrying it.
        assertEquals(GitHub.Answer.NotModified, second)
    }

    @Test
    fun `a repository that does not exist is reported as such, not as up to date`() {
        assumeTrue("set AYN_LIVE_UPDATE_TEST=1 to run", System.getenv("AYN_LIVE_UPDATE_TEST") == "1")

        val answer = GitHub.releases(GitHubRepo(AppSource.OWNER, "no-such-repository-here"))

        assertTrue(answer is GitHub.Answer.Broken)
        assertEquals(UpdateError.NOT_FOUND, (answer as GitHub.Answer.Broken).failure.error)
    }
}
