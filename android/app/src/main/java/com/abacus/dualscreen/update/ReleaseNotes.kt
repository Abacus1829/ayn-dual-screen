package com.abacus.dualscreen.update

/**
 * Making a release body readable on a handheld.
 *
 * GitHub release notes are Markdown written for a browser. Dropped unchanged into a TextView they
 * arrive as a wall of hashes, asterisks and table pipes — technically the release notes, practically
 * unreadable, and the update prompt is the one screen where somebody is actually trying to read
 * something before deciding.
 *
 * This is not a Markdown renderer and should not become one. It removes the punctuation that only
 * means something to a parser, keeps the structure a person reads by — headings, bullets, blank
 * lines — and stops there.
 */
object ReleaseNotes {

    /** Beyond this, nobody is reading. Truncated with a line saying so. */
    private const val LIMIT = 12_000

    /**
     * The part of a release that is about this app.
     *
     * These releases carry five projects: the body opens with a download table for all of them and
     * then works through each project's changes. Somebody deciding whether to update the app does
     * not need the Terraria mod's version table first, so the notes start at the first heading that
     * names the app and run to the end — which keeps every intermediate version's notes, the whole
     * point when a device is several releases behind.
     *
     * A release that has no such heading is shown whole, because guessing further would be worse
     * than showing everything.
     */
    fun forApp(body: String): String {
        val heading = APP_HEADING.find(body) ?: return body.trim()
        return body.substring(heading.range.first).trim()
    }

    /** Markdown reduced to something worth putting in a TextView. */
    fun plain(markdown: String): String {
        val out = StringBuilder()

        for (raw in markdown.lineSequence()) {
            val line = raw.trimEnd()

            when {
                // A table separator carries no information once the pipes are gone.
                TABLE_RULE.matches(line.trim()) -> Unit

                // Horizontal rules become a blank line rather than a row of dashes.
                RULE.matches(line.trim()) -> out.append('\n')

                line.trim().startsWith("|") -> out.append(cells(line)).append('\n')

                else -> out.append(inline(heading(line))).append('\n')
            }
        }

        val text = out.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return if (text.length <= LIMIT) text
        else text.take(LIMIT).substringBeforeLast('\n') + "\n\n…"
    }

    /** Both at once, which is what every caller wants. */
    fun readable(body: String): String = plain(forApp(body))

    // ── the pieces ──────────────────────────────────────────────────────────

    private val APP_HEADING = Regex("""(?m)^#{1,6}\s+App\b.*$""")

    private val TABLE_RULE = Regex("""^\|?[\s:|-]+\|[\s:|-]*$""")

    private val RULE = Regex("""^([-*_])\1{2,}$""")

    /** Headings keep their text and gain a blank line, which is what made them stand out anyway. */
    private fun heading(line: String): String {
        val match = Regex("""^(#{1,6})\s+(.*)$""").matchEntire(line.trim()) ?: return line
        return "\n" + match.groupValues[2].trim()
    }

    private fun cells(line: String): String =
        line.trim().trim('|').split('|').joinToString("  ·  ") { it.trim() }.trim()

    /** Emphasis, code ticks and links: the punctuation that is markup rather than words. */
    private fun inline(line: String): String = line
        .replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")       // images, by their alt text
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")        // links, by their text
        .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        .replace(Regex("""(?<![*\w])\*([^*\n]+)\*(?!\*)"""), "$1")
        .replace(Regex("""`([^`]+)`"""), "$1")
        .replace(Regex("""^\s*[-*+]\s+"""), "• ")
        .replace(Regex("""^\s*>\s?"""), "")
}
