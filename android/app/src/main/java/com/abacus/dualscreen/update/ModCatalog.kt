package com.abacus.dualscreen.update

/**
 * The game mods on a release: what version each is at, and where to get it.
 *
 * The app can update itself. The mods it talks to are files somebody has to put on a PC by hand, and
 * until now the app said nothing about them at all — you found out a mod had moved on by reading the
 * release page, if you thought to look. So the one place that already knows about releases now also
 * reports what is in them.
 *
 * ## Where the versions come from
 *
 * The release *title*, which by convention carries every version on one line:
 *
 * ```
 * BETA — Ayn Dual Screen app 0.28.0-beta.2, Stardew 0.5.0, Terraria 0.3.1, Minecraft 0.7.0, …
 * ```
 *
 * That is the same place [UpdateSource] reads the app's own version from, and for the same reason:
 * it is written once, by whoever cut the release, and it is what a human would read. A new mod
 * version therefore needs no change here.
 *
 * ## Where the links come from
 *
 * Built from the tag, not fetched. GitHub serves every asset at a predictable address, and the
 * project's own rule is that **every release carries every mod under a stable filename** — the
 * README's download links depend on exactly that. So a tag plus a filename is a working link, which
 * means this needs nothing beyond what a completed update check already has in hand and works from
 * the cache when there is no connection.
 *
 * ## What it deliberately does not claim
 *
 * **It cannot tell you a mod is out of date.** Nothing reports the version installed on the PC — the
 * companion serves game state, not its own version — so any "you need to update" would be invented.
 * What it can honestly say is what the newest one is, and whether that has changed since you last
 * looked, which is the question somebody actually has.
 */
object ModCatalog {

    /** One mod on a release. */
    data class Mod(
        /** How it is written in the release title: "Stardew", "Fallout NV". */
        val name: String,
        /** The asset filename, which is also its stable download name. */
        val asset: String,
        /** The version the title claims, or null when the title does not mention it. */
        val version: Version?,
        /** A direct link to the file on that release. */
        val url: String,
    )

    /**
     * Every mod this project ships, and how each is written in a title.
     *
     * A table rather than a rule derived from the filename, because the two genuinely differ —
     * `AynDualScreen-FalloutNV.zip` is written "Fallout NV", with a space — and a rule clever enough
     * to bridge that is a rule that will surprise somebody later.
     */
    private val MODS = listOf(
        "Stardew" to "AynDualScreen-Stardew.zip",
        "Terraria" to "AynDualScreen-Terraria.tmod",
        "Minecraft" to "AynDualScreen-Minecraft-mc1.21.1.jar",
        "Fallout NV" to "AynDualScreen-FalloutNV.zip",
        "Skyrim SE" to "AynDualScreen-SkyrimSE.zip",
    )

    /**
     * The mods described by a release title and tag.
     *
     * A mod whose version the title does not mention is left out entirely rather than listed with a
     * blank: this project's titles name every mod they ship, so a missing one means that release did
     * not carry it, and offering a link to a file that is not there would be worse than silence.
     */
    fun of(repo: GitHubRepo, tag: String, title: String?): List<Mod> {
        if (tag.isBlank()) return emptyList()

        return MODS.mapNotNull { (name, asset) ->
            val version = versionIn(title, name) ?: return@mapNotNull null
            Mod(
                name = name,
                asset = asset,
                version = version,
                url = "https://github.com/${repo.owner}/${repo.name}/releases/download/$tag/$asset",
            )
        }
    }

    /**
     * Pull one mod's version out of a release title.
     *
     * Anchored on the mod's own name and refusing to cross a comma, because the title is a list and a
     * looser pattern would happily read the *next* mod's number for a mod whose own is missing —
     * which is the one failure here that would be worse than saying nothing.
     */
    fun versionIn(title: String?, name: String): Version? {
        if (title.isNullOrBlank()) return null

        val pattern = Regex(
            """(?i)\b${Regex.escape(name)}\b[^0-9,\n]{0,12}?v?(\d{1,5}(?:\.\d{1,5}){1,3}(?:-[0-9A-Za-z.\-]+)?)"""
        )

        return Version.parse(pattern.find(title)?.groupValues?.get(1))
    }
}
