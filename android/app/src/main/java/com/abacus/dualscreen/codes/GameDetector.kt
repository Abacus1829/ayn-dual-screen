package com.abacus.dualscreen.codes

import android.content.Context
import com.abacus.dualscreen.Game
import com.abacus.dualscreen.Probe
import com.abacus.dualscreen.connect.ConnectionProfile
import com.abacus.dualscreen.connect.ProfileStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Which game is running *right now*.
 *
 * The codes screen used to take the first saved connection that looked usable and ask that one for
 * its catalogue. "Usable" there means the host and port are filled in — not that anything is
 * listening, and certainly not that it is the game you are playing. So somebody with three saved
 * connections got the codes for whichever was saved first, whether or not it was even switched on.
 *
 * This asks instead. Every saved connection is probed at once, and the answer is whichever one is
 * actually running a game — preferring one with a save loaded, because a companion sitting at its
 * main menu can advertise codes it cannot yet run.
 *
 * The three outcomes are kept apart deliberately, because they need different things from the user:
 * one game found, nothing found, or nothing to look at in the first place. "Detection failed" is
 * not a state here — a probe that times out *is* "nothing found", and inventing a distinction the
 * network cannot support would only produce a message nobody can act on.
 *
 * Blocking. Call [detect] from a background thread, or use [detectAsync].
 */
object GameDetector {

    sealed interface Result {
        /** A game is running here. [place] is the world or farm name when one is loaded. */
        data class Found(
            val profile: ConnectionProfile,
            val game: Game,
            val inGame: Boolean,
            val place: String?,
        ) : Result

        /** Saved connections exist; none of them answered as a game. */
        data object NothingRunning : Result

        /** Nothing is saved, so there was nothing to ask. A setup problem, not a detection one. */
        data object NothingSaved : Result
    }

    /** Probing every saved connection in parallel; one slow host must not hold up the rest. */
    private const val THREADS = 6
    private const val TIMEOUT_SECONDS = 6L

    fun detect(context: Context): Result {
        val profiles = ProfileStore(context).ordered().filter { it.usable }
        if (profiles.isEmpty()) return Result.NothingSaved

        val pool = Executors.newFixedThreadPool(minOf(THREADS, profiles.size))
        val found = java.util.Collections.synchronizedList(mutableListOf<Result.Found>())

        for (profile in profiles) {
            pool.execute {
                val probe = runCatching { Probe.run(profile.url) }.getOrNull() ?: return@execute
                val game = probe.game ?: return@execute
                found += Result.Found(profile, game, probe.inGame, probe.place)
            }
        }

        pool.shutdown()
        runCatching { pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

        /*
         * A loaded save beats a main menu, and after that the order the user put them in wins.
         *
         * Both halves matter. A companion at its menu will happily list its codes and then refuse
         * every one of them, which looks like the app is broken rather than like the game is not
         * ready. And when two are genuinely in game, the user's own ordering is a better guess than
         * whichever thread happened to answer first.
         */
        return found
            .sortedWith(
                compareByDescending<Result.Found> { it.inGame }
                    .thenBy { profiles.indexOf(it.profile) }
            )
            .firstOrNull()
            ?: Result.NothingRunning
    }

    /** Detect off the main thread and deliver the answer back on it. */
    fun detectAsync(context: Context, onMain: (Result) -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val result = detect(context)
            handler.post { onMain(result) }
        }.apply {
            name = "code-detect"
            isDaemon = true
        }.start()
    }
}
