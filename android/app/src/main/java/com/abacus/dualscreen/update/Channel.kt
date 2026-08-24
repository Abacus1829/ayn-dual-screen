package com.abacus.dualscreen.update

/**
 * Which releases this device is willing to be offered.
 *
 * Two today, and the machinery reads the channel rather than hard-coding "not a pre-release", so a
 * third — a nightly, a per-device build — is a new entry here and nothing else.
 *
 * The rule a channel expresses is *what counts as a candidate*, not *what is newest*: beta sees
 * everything stable sees, because a stable release published after a beta is still the newer build
 * and refusing it would strand anybody who opted in.
 */
enum class Channel(val id: String) {

    /** Finished releases only. GitHub's "pre-release" tick, and any `-beta` suffix, are skipped. */
    STABLE("stable"),

    /** Everything, pre-releases included. */
    BETA("beta");

    fun accepts(release: Release): Boolean = when (this) {
        STABLE -> !release.preRelease
        BETA -> true
    }

    /** A pre-release version number is only offered on a channel that asked for pre-releases. */
    fun accepts(version: Version): Boolean = when (this) {
        STABLE -> !version.isPreRelease
        BETA -> true
    }

    companion object {
        fun byId(id: String?): Channel = entries.firstOrNull { it.id == id } ?: STABLE
    }
}
