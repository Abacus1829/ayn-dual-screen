package com.abacus.dualscreen.update

/**
 * A version number, ordered the way a person would order it rather than the way strings sort.
 *
 * String comparison gets this wrong in the one place it matters: "0.9.0" sorts *after* "0.14.0",
 * so an app that compares its version as text stops offering updates exactly when the minor number
 * rolls past nine. This repository is already past that point.
 *
 * The grammar accepted is the loose one people actually type into release notes — an optional `v`,
 * two to four numbers, and an optional `-beta.2` style suffix:
 *
 * ```
 * 0.14.0        v0.14.0        1.2       0.15.0-beta.2
 * ```
 *
 * A pre-release sorts *below* the same numbers without one, which is the rule everybody expects and
 * the reason 1.0.0-beta.1 must not be offered to somebody already on 1.0.0.
 */
data class Version(
    val numbers: List<Int>,
    /** The `-beta.2` part, lowercased, or empty for a final release. */
    val preRelease: String = "",
) : Comparable<Version> {

    /** How it is written back out — the input's own form, minus any `v`. */
    val text: String
        get() = numbers.joinToString(".") + if (preRelease.isEmpty()) "" else "-$preRelease"

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: Version): Int {
        // Missing trailing numbers count as zero, so 1.2 and 1.2.0 are the same version.
        val length = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until length) {
            val mine = numbers.getOrElse(i) { 0 }
            val theirs = other.numbers.getOrElse(i) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }

        // Equal numbers: a final release outranks any pre-release of itself.
        if (preRelease == other.preRelease) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1

        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String = text

    companion object {

        /** `v1.2.3`, `1.2.3-beta.1`, `1.2` — anything else returns null rather than guessing. */
        private val PATTERN = Regex("""^v?(\d{1,5}(?:\.\d{1,5}){1,3})(?:[-+]([0-9A-Za-z.\-]+))?$""")

        /** The same shape, found anywhere inside a longer line of prose. */
        private val LOOSE = Regex("""\bv?(\d{1,5}(?:\.\d{1,5}){1,3})(?:-([0-9A-Za-z.\-]+))?\b""")

        fun parse(text: String?): Version? {
            val match = PATTERN.matchEntire(text?.trim().orEmpty()) ?: return null
            return of(match)
        }

        /**
         * The first version-shaped thing in a sentence.
         *
         * Used on release titles and notes, which are written for people and are not going to be a
         * bare version string. Returns null when there is nothing that looks like one — an honest
         * "I could not tell" that the caller reports rather than papers over.
         */
        fun find(text: String?): Version? {
            val match = LOOSE.find(text.orEmpty()) ?: return null
            return of(match)
        }

        private fun of(match: MatchResult): Version? {
            val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            return Version(numbers, match.groupValues.getOrElse(2) { "" }.lowercase())
        }

        /**
         * Dot-separated identifiers, numbers before text, as semantic versioning defines it.
         *
         * `beta.2` after `beta.10` is the trap here: compared as text, 10 comes first.
         */
        private fun comparePreRelease(mine: String, theirs: String): Int {
            val a = mine.split('.')
            val b = theirs.split('.')

            for (i in 0 until maxOf(a.size, b.size)) {
                val left = a.getOrNull(i) ?: return -1  // the shorter one sorts first
                val right = b.getOrNull(i) ?: return 1

                val leftNumber = left.toIntOrNull()
                val rightNumber = right.toIntOrNull()

                val result = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1                   // numeric identifiers rank lower
                    rightNumber != null -> 1
                    else -> left.compareTo(right)
                }
                if (result != 0) return result
            }
            return 0
        }
    }
}
