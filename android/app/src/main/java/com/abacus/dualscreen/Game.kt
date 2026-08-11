package com.abacus.dualscreen

import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/**
 * Something the app can connect to.
 *
 * Two of these are game mods that speak the second-screen protocol; [CUSTOM] is any other address you
 * want to open full-screen, which is the hook for this being more than a mod companion. Adding a new
 * entry here is all it takes — the dropdown, the saved addresses and the detection all read this list.
 */
enum class Game(
    val id: String,
    @StringRes val label: Int,
    @StringRes val hint: Int,
    val defaultPort: Int,

    /**
     * The endpoint that identifies this mod at the main menu, when `/state` says nothing useful.
     *
     * Null for entries that can't be recognised this way: [CUSTOM], which isn't a mod at all, and
     * [FALLOUT], which is one but serves no endpoint the others don't. Probing `/` would match every
     * mod at once and confidently name the wrong one.
     */
    val probePath: String?,

    @ColorRes val accent: Int,
    @ColorRes val background: Int,

    /** Whether this entry speaks the second-screen protocol, as opposed to being an arbitrary address. */
    val isMod: Boolean = true
) {
    STARDEW(
        id = "stardew",
        label = R.string.game_stardew,
        hint = R.string.hint_stardew,
        defaultPort = 27301,
        probePath = "/map",
        accent = R.color.stardew_accent,
        background = R.color.stardew_bg
    ),

    TERRARIA(
        id = "terraria",
        label = R.string.game_terraria,
        hint = R.string.hint_terraria,
        defaultPort = 27301,
        probePath = "/minimap",
        accent = R.color.terraria_accent,
        background = R.color.terraria_bg
    ),

    MINECRAFT(
        id = "minecraft",
        label = R.string.game_minecraft,
        hint = R.string.hint_minecraft,
        // 27302, not 27301: Minecraft and Terraria are both likely to be installed on the same PC, and
        // two mods fighting over one port fails in a way that looks like the app's fault
        defaultPort = 27302,
        probePath = "/map",
        accent = R.color.minecraft_accent,
        background = R.color.minecraft_bg
    ),

    FALLOUT(
        id = "falloutnv",
        label = R.string.game_fallout,
        hint = R.string.hint_fallout,
        // 27303. Stardew and Terraria are on 27301, Minecraft 27302, and all four could be installed
        // on one PC
        defaultPort = 27303,
        // no probe path: the plugin serves only /, /state and /action, and / answers for every mod
        probePath = null,
        accent = R.color.fallout_accent,
        background = R.color.fallout_bg
    ),

    /** Any other web address, opened with the same full-screen no-chrome treatment. */
    CUSTOM(
        id = "custom",
        label = R.string.game_custom,
        hint = R.string.hint_custom,
        defaultPort = 80,
        probePath = null,
        accent = R.color.accent,
        background = R.color.card,
        isMod = false
    );

    companion object {
        fun byId(id: String?): Game = entries.firstOrNull { it.id == id } ?: STARDEW

        /**
         * Only the entries a main-menu probe can actually tell apart.
         *
         * Not the same as "every mod": a mod with no distinguishing endpoint is identified from the
         * snapshot once a save is loaded, and left alone until then.
         */
        val detectable: List<Game> get() = entries.filter { it.probePath != null }
    }
}
