package com.abacus.dualscreen.companion

import android.content.Context
import com.abacus.dualscreen.Game
import com.abacus.dualscreen.MacroStore
import com.abacus.dualscreen.Settings
import com.abacus.dualscreen.connect.ConnectionProfile
import com.abacus.dualscreen.connect.ProfileStore
import org.json.JSONObject

/**
 * What a companion can do.
 *
 * Declared rather than discovered, so the app can decide what to show before it has spoken to
 * anything. A capability absent from this set means the screen simply does not offer it — never an
 * error, because a companion that does not report inventory is not broken, it just does not have any.
 */
enum class Capability {
    /** Serves a second-screen web page. Every companion so far does. */
    SCREEN,

    /** Answers `/state` with a snapshot, which is what makes the dashboard live. */
    TELEMETRY,

    /** Accepts `POST /action`, which is what makes buttons and macros do anything. */
    ACTIONS,

    /** Names the place the player is in, when a world is loaded. */
    PLACE,
}

/**
 * One page a companion offers on the second screen.
 *
 * A path and a name. The page itself is the companion's own HTML — the app is a window onto it, not
 * a renderer of it — so a new page needs no app release, only an entry here or a companion that
 * reports one.
 */
data class Page(val id: String, val label: String, val path: String)

/**
 * A game the app knows how to be a companion for.
 *
 * This is the single definition the rest of the app reads: what the game is called, how to recognise
 * it, where its connection lives, which pages it has, what it can do. Before this, those facts were
 * spread between [Game], the connection store, the macro store and several screens.
 *
 * Adding a game is one entry in [Companions.ALL]. Nothing else in the app needs to change: the
 * picker, discovery, the dashboard and the profile list all read this list.
 */
data class GameProfile(
    val id: String,
    val name: String,
    val defaultPort: Int,

    /**
     * How to tell this companion apart from the others.
     *
     * [probePath] is a page only this mod serves; [stateName] is what it calls itself in `/state`.
     * Either is enough. Both are optional — a companion with neither is still perfectly usable, it
     * simply cannot be recognised automatically, which is a smaller loss than it sounds.
     */
    val probePath: String? = null,
    val stateName: String? = null,

    val capabilities: Set<Capability> = setOf(Capability.SCREEN),
    val pages: List<Page> = emptyList(),

    /** The [Game] entry this came from, for the colours and strings the app already has. */
    val game: Game? = null,
) {

    fun has(capability: Capability) = capability in capabilities

    // ── the per-game things the app stores ──────────────────────────────────

    /** The saved connection for this game, if one has been made. */
    fun connection(context: Context): ConnectionProfile? =
        ProfileStore(context).profiles.firstOrNull { it.preset == id }

    /** The control profile assigned to this game, falling back to the general one. */
    fun controls(context: Context) = MacroStore(context).profileFor(id)

    /**
     * The page the user was last on for this game.
     *
     * Per game rather than global: somebody who reads the map in one and the inventory in another
     * should get each back where they left it.
     */
    fun lastPage(context: Context): String =
        Settings(context).lastPageFor(id)

    fun rememberPage(context: Context, pageId: String) {
        Settings(context).setLastPageFor(id, pageId)
    }
}

/**
 * Every companion the app ships knowing about.
 *
 * Built from [Game] rather than replacing it: the enum still carries the strings and colours the UI
 * uses, and duplicating those here would mean two places to change a name. What this adds is the
 * part [Game] never had — capabilities, pages, and one list to read them from.
 */
object Companions {

    val ALL: List<GameProfile> by lazy {
        Game.entries.filter { it.isMod }.map { game ->
            GameProfile(
                id = game.id,
                name = game.id,
                defaultPort = game.defaultPort,
                probePath = game.probePath,
                stateName = game.id,
                capabilities = setOf(
                    Capability.SCREEN,
                    Capability.TELEMETRY,
                    Capability.ACTIONS,
                    Capability.PLACE,
                ),
                pages = pagesFor(game),
                game = game,
            )
        }
    }

    fun byId(id: String?): GameProfile? = id?.let { key -> ALL.firstOrNull { it.id == key } }

    /**
     * The pages each companion serves.
     *
     * Only the ones the app has a reason to name — the root is always there and needs no entry. A
     * companion that grows a page needs a line here, or nothing at all if the page is reachable from
     * its own UI, which is the usual case.
     */
    private fun pagesFor(game: Game): List<Page> = buildList {
        add(Page("main", "Main", "/"))
        game.probePath?.let { add(Page("map", "Map", it)) }
    }
}

/**
 * A snapshot from a companion.
 *
 * Deliberately shallow: the fields every mod in this project already sends, plus the raw JSON for
 * anything else. A companion that reports something new needs no app change to have it read — the
 * dashboard shows what it recognises and ignores the rest.
 */
data class Telemetry(
    val reachable: Boolean,
    val gameId: String? = null,
    val inGame: Boolean = false,
    val place: String? = null,
    /** Whatever else came back, for a companion that reports more than this app knows about. */
    val raw: JSONObject? = null,
) {

    /**
     * A coarse idea of what the player is doing, when the companion says.
     *
     * Used only to *offer* a page, never to switch to one. Unknown is the normal answer and means
     * the dashboard stays exactly as it is.
     */
    val state: String
        get() = raw?.optString("state")?.takeIf { it.isNotBlank() }
            ?: if (inGame) "playing" else "menu"

    companion object {
        val NOTHING = Telemetry(reachable = false)
    }
}
