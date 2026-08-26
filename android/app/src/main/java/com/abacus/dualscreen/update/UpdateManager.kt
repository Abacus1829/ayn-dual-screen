package com.abacus.dualscreen.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * The update system, as one object that outlives any screen.
 *
 * Everything else in this package is a step; this is what sequences them and remembers where things
 * got to. It is a singleton on the application context on purpose, and the reason is the download:
 * four megabytes over a handheld's Wi-Fi takes long enough that somebody will rotate the screen,
 * open a game, or come back to the home menu while it runs. State that lived on an Activity would
 * die with it and start again. State that lives here does not, and the screen becomes a view of it
 * — it observes, it does not own.
 *
 * That is also what makes the boot animation work. The check starts when the app starts, runs on a
 * background thread, and finishes whenever it finishes. The animation plays regardless, asks
 * afterwards whether anything turned up, and the two never wait for each other.
 *
 * Nothing here touches the UI. Callbacks are delivered on the main thread as a convenience to the
 * screens, and any of them may be dropped without affecting the work.
 */
class UpdateManager private constructor(private val context: Context) {

    /** Where a failure happened, which decides what the retry button should do. */
    enum class Stage { CHECK, DOWNLOAD, INSTALL }

    sealed interface State {
        data object Idle : State

        data object Checking : State

        /** Checked, nothing newer. [latest] is what was seen, when the check could tell. */
        data class UpToDate(val latest: Version?, val at: Long) : State

        data class Available(val update: Update) : State

        data class Downloading(val update: Update, val done: Long, val total: Long) : State

        /** Downloaded, being checked over before anything is offered to the installer. */
        data class Verifying(val update: Update) : State

        /** A verified file, waiting for the user to start the system installer. */
        data class Ready(val update: Update, val file: File) : State

        data class Failed(val stage: Stage, val failure: Failure, val update: Update?) : State
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(State) -> Unit>()

    private val source = AppSource(context)
    val prefs = UpdatePrefs(context, source.id)

    @Volatile
    var state: State = State.Idle
        private set

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var cancelRequested = false

    /**
     * Whether the startup prompt has already been shown in this run of the app.
     *
     * Once per process, not once per screen: coming back to the home menu from a tool must not
     * re-open a dialog the user has already answered.
     */
    @Volatile
    var promptedThisRun = false

    /** What is installed, as text, for any screen that wants to show it. */
    fun installedName(): String = source.installedName()

    fun installedVersion(): Version? = source.installedVersion()

    // ── watching ────────────────────────────────────────────────────────────

    /** Observe state, and receive the current one immediately. */
    fun observe(listener: (State) -> Unit) {
        synchronized(listeners) { listeners += listener }
        main.post { listener(state) }
    }

    fun forget(listener: (State) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    /** The last file that passed every check, kept so [recover] can offer it again. */
    @Volatile
    private var lastReady: State.Ready? = null

    private fun publish(next: State) {
        state = next
        if (next is State.Ready) lastReady = next
        val snapshot = synchronized(listeners) { listeners.toList() }
        main.post { snapshot.forEach { it(next) } }
    }

    // ── checking ────────────────────────────────────────────────────────────

    /**
     * The check that runs while the app is starting up.
     *
     * Returns immediately whatever happens. It declines quietly — no error state, no message —
     * when automatic checks are off, when one ran recently, or when a download is already going,
     * because a startup path is the wrong place to tell somebody about a decision they already
     * made.
     */
    fun checkOnStartup() {
        forgetInstalledUpdate()

        if (!prefs.dueForCheck()) {
            // A recent answer is still worth showing, so a prompt can appear without the network.
            prefs.cached?.let { cached ->
                if (isNewer(cached)) publish(State.Available(cached))
            }
            return
        }

        check(manual = false)
    }

    /** The button in Settings. Always asks, and always reports what happened. */
    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (busy()) return

        publish(State.Checking)

        start {
            val outcome = UpdateChecker.check(context, source, prefs.channel, prefs.etag)
            prefs.lastCheck = System.currentTimeMillis()

            when (outcome) {
                is UpdateChecker.Outcome.Available -> {
                    prefs.etag = outcome.etag
                    prefs.cached = outcome.update
                    publish(State.Available(outcome.update))
                }

                is UpdateChecker.Outcome.UpToDate -> {
                    prefs.etag = outcome.etag ?: prefs.etag

                    /*
                     * A 304 says "nothing published since you last looked", not "you are current":
                     * the answer from that earlier look is still the live one, and dropping it here
                     * would lose an update that has already been found and merely not acted on.
                     */
                    val cached = prefs.cached
                    if (cached != null && isNewer(cached)) {
                        publish(State.Available(cached))
                    } else {
                        prefs.cached = null
                        publish(State.UpToDate(outcome.latest, System.currentTimeMillis()))
                    }
                }

                is UpdateChecker.Outcome.Broken -> {
                    Log.i(TAG, "check failed: " + outcome.failure)
                    if (manual) publish(State.Failed(Stage.CHECK, outcome.failure, null))
                    else publish(State.Idle)   // startup stays silent about a failed check
                }
            }
        }
    }

    // ── downloading ─────────────────────────────────────────────────────────

    /** Fetch and verify [update]. Safe to call again after a failure: it resumes. */
    fun download(update: Update) {
        if (busy()) return

        cancelRequested = false
        publish(State.Downloading(update, 0, update.size))

        start {
            val outcome = Downloader.download(
                context = context,
                update = update,
                cancelled = { cancelRequested },
                onProgress = { done, total -> publish(State.Downloading(update, done, total)) },
            )

            when (outcome) {
                is Downloader.Outcome.Broken -> {
                    val state = if (outcome.failure.error == UpdateError.CANCELLED)
                        State.Available(update)  // cancelled is not a failure; offer it again
                    else
                        State.Failed(Stage.DOWNLOAD, outcome.failure, update)
                    publish(state)
                }

                is Downloader.Outcome.Done -> {
                    publish(State.Verifying(update))

                    val problem = ApkCheck.verify(context, outcome.file, update)
                    if (problem != null) {
                        // A file that fails these checks is never kept: leaving it invites a retry
                        // that finds it "already downloaded" and offers the same broken thing.
                        outcome.file.delete()
                        publish(State.Failed(Stage.DOWNLOAD, problem, update))
                    } else {
                        publish(State.Ready(update, outcome.file))
                    }
                }
            }
        }
    }

    /** Stop a download in progress. Whatever arrived is kept, so starting again resumes. */
    fun cancel() {
        cancelRequested = true
    }

    // ── installing ──────────────────────────────────────────────────────────

    /**
     * Hand a verified file to the system installer.
     *
     * The permission is the user's to grant, so this reports [UpdateError.PERMISSION] rather than
     * trying to work around it. Nothing here can install anything by itself, and that is the point.
     */
    fun install(activity: Context): Failure? {
        val ready = state as? State.Ready ?: return Failure(UpdateError.INSTALL_FAILED, "nothing ready")

        /*
         * Stash the notes before handing the file over, not after.
         *
         * Once Android takes the APK this process is on borrowed time — it is about to be replaced —
         * so anything that needs writing has to be written first. WhatsNew keys them on the version
         * they describe, so an install the user backs out of at Android own confirmation dialog
         * simply never matches and the notes are discarded on the next launch instead of announcing
         * a version that is not there.
         */
        WhatsNew.remember(activity, ready.update.version.text, ready.update.notes)

        val failure = Installer.install(activity, ready.file)
        if (failure != null) {
            // It did not even reach the installer, so there is nothing to announce later.
            WhatsNew.forget(activity)
            publish(State.Failed(Stage.INSTALL, failure, ready.update))
        }
        return failure
    }

    /**
     * Put a downloaded, checked file back in front of somebody who has just been to a settings page.
     *
     * Without this, refusing at the install permission screen is a dead end: the state is a failure,
     * the failure offers the permission page, and coming back from the permission page finds the
     * same failure — a loop with the finished download sitting right there. Called when the update
     * screen resumes.
     */
    fun recover() {
        if (state !is State.Failed) return
        val ready = lastReady ?: return
        if (ready.file.isFile) publish(ready)
    }

    // ── the user's answers ──────────────────────────────────────────────────

    fun skip(version: Version) {
        prefs.skip(version)
        promptedThisRun = true
    }

    fun remindLater() {
        prefs.remindLater()
        promptedThisRun = true
    }

    /**
     * The update to interrupt somebody with, or null.
     *
     * Asked by the home screen once the boot animation has finished. Everything that decides
     * *whether* to interrupt lives here rather than in the screen, so a second caller — a settings
     * page, a future plugin manager — cannot get the rules subtly different.
     */
    fun promptable(): Update? {
        if (promptedThisRun) return null
        val update = (state as? State.Available)?.update ?: return null
        if (!prefs.shouldPrompt(update)) return null
        return update
    }

    // ── housekeeping ────────────────────────────────────────────────────────

    /**
     * Forget an update that has since been installed.
     *
     * The installer restarts this app after a successful install, so the first thing the new
     * process should notice is that the thing it was offering is now what it is running — and, with
     * it, that any "skip" or "remind me later" the user set for that version has served its purpose.
     */
    private fun forgetInstalledUpdate() {
        val cached = prefs.cached ?: return
        if (isNewer(cached)) return

        prefs.cached = null
        prefs.clearDeferrals()
        Downloader.folder(context).listFiles()?.forEach { it.delete() }
    }

    private fun isNewer(update: Update): Boolean {
        val installed = source.installedVersion() ?: return true
        return update.version > installed
    }

    private fun busy(): Boolean = worker?.isAlive == true

    private fun start(work: () -> Unit) {
        val thread = Thread {
            runCatching(work).onFailure { error ->
                // A crash on the worker must not take the app with it, and must not leave the UI
                // stuck on a spinner that will never move.
                Log.w(TAG, "update worker failed", error)
                publish(State.Failed(Stage.CHECK, Failure(UpdateError.NETWORK, error.message), null))
            }
            worker = null
        }
        thread.name = "update-" + source.id
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    companion object {
        private const val TAG = "Update"

        @Volatile
        private var instance: UpdateManager? = null

        fun get(context: Context): UpdateManager =
            instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
    }
}
