package com.abacus.dualscreen

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityUpdateBinding
import com.abacus.dualscreen.ui.Feedback
import com.abacus.dualscreen.ui.Motion
import com.abacus.dualscreen.ui.Nav
import com.abacus.dualscreen.update.AppSource
import com.abacus.dualscreen.update.ModCatalog
import com.abacus.dualscreen.ui.Ui
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import com.abacus.dualscreen.update.Channel
import com.abacus.dualscreen.update.Downloader
import com.abacus.dualscreen.update.Failure
import com.abacus.dualscreen.update.Installer
import com.abacus.dualscreen.update.ReleaseNotes
import com.abacus.dualscreen.update.Update
import com.abacus.dualscreen.update.UpdateError
import com.abacus.dualscreen.update.UpdateManager

/**
 * The update screen: check, read, download, install.
 *
 * It owns none of the work. Everything is in [UpdateManager], which outlives this screen, so a
 * download keeps going while somebody rotates the device or wanders off into a game and comes back.
 * This is a view of that state and a set of buttons that ask it to do things — which is why closing
 * it mid-download does not cancel anything, and why reopening it finds the progress bar where it
 * should be.
 *
 * One button carries the whole flow rather than a row of five that are mostly disabled. What it
 * says is what the next step is: *Check for updates*, then *Update now*, then *Cancel* while it
 * downloads, then *Install now*, or *Try again* when something went wrong.
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateBinding
    private lateinit var settings: Settings
    private lateinit var updates: UpdateManager

    /** What the one big button will do if it is pressed right now. */
    private var action: () -> Unit = {}

    private val listener: (UpdateManager.State) -> Unit = { render(it) }

    /**
     * The state last announced, so an outcome is sounded once rather than on every redraw.
     *
     * render() runs for any state change and for every resume; without this, coming back from the
     * installer would replay the confirmation chime for a download that finished ten minutes ago.
     */
    private var announced: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        updates = UpdateManager.get(this)

        Nav.back(this, binding.backButton)
        binding.primaryButton.setOnClickListener {
            Feedback.select(it)
            action()
        }
        Motion.pressable(binding.primaryButton)
        Motion.pressable(binding.laterButton)
        Motion.pressable(binding.skipButton)
        Motion.pressable(binding.grantButton)
        binding.laterButton.setOnClickListener {
            Feedback.tap(it)
            updates.remindLater()
            finish()
        }
        binding.skipButton.setOnClickListener {
            Feedback.tap(it)
            (updates.state as? UpdateManager.State.Available)?.let { state ->
                updates.skip(state.update.version)
            }
            finish()
        }
        binding.grantButton.setOnClickListener {
            Feedback.tap(it)
            Installer.requestPermission(this)
        }

        buildPreferences()

        binding.installedVersion.text = updates.installedName().ifBlank { getString(R.string.update_unknown) }
        binding.sourceLine.text = getString(R.string.update_source, AppSource.OWNER + "/" + AppSource.REPO)

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)

        com.abacus.dualscreen.control.ControlCenter.attach(this, settings)
        binding.progressBar.progressTintList =
            ColorStateList.valueOf(Appearance.accentOf(settings))

        /*
         * Launched from the prompt with "Update now" already pressed. The download starts here
         * rather than from the dialog so there is a screen showing progress from the first byte.
         */
        if (intent.getBooleanExtra(EXTRA_DOWNLOAD, false)) {
            (updates.state as? UpdateManager.State.Available)?.let { updates.download(it.update) }
        }
    }

    override fun onStart() {
        super.onStart()
        updates.observe(listener)
    }

    override fun onStop() {
        super.onStop()
        updates.forget(listener)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system installer or the permission page: both can have changed what
        // this screen should say, and neither reports back. A file that is already downloaded and
        // checked is offered again rather than left behind a failure message.
        updates.recover()
        binding.installedVersion.text = updates.installedName().ifBlank { getString(R.string.update_unknown) }
        render(updates.state)
    }

    /**
     * The game mods on the newest release: version, size, and a tap to download.
     *
     * Fed from whatever release the checker last saw, so it fills in after the first check and stays
     * filled from the cache afterwards — including offline, which is exactly when somebody is likely
     * to be looking at a list of things to install later.
     *
     * **A mod is marked NEW when its version differs from the one this device was last shown**, not
     * from what is installed. Nothing reports the version sitting on the PC — the companion serves
     * game state, not its own version — so "you need to update" would be invented. "This changed
     * since you last looked" is true, and is the question somebody actually has.
     */
    private fun showMods() {
        val cached = updates.prefs.cached
        val mods = cached?.let {
            ModCatalog.of(updates.repo, it.tag, it.title)
        }.orEmpty()

        binding.modsCard.visibility = if (mods.isEmpty()) View.GONE else View.VISIBLE
        if (mods.isEmpty()) return

        val seen = updates.prefs.seenMods
        binding.modsList.removeAllViews()

        for (mod in mods) {
            val version = mod.version?.text
            val fresh = version != null && seen[mod.name] != null && seen[mod.name] != version

            val detail = version ?: getString(R.string.update_mods_none)

            val title = if (fresh) {
                mod.name + "  ·  " + getString(R.string.update_mods_new)
            } else {
                mod.name
            }

            binding.modsList.addView(modRow(title, detail, fresh, mod.url))
        }

        // Seen now. A star that survived the visit would be a badge nobody could clear.
        updates.prefs.seenMods = mods
            .mapNotNull { mod -> mod.version?.text?.let { mod.name to it } }
            .toMap()
    }

    /**
     * One mod row.
     *
     * Built here rather than through Ui.link because that takes string resources and these are
     * runtime values — a mod name from a table, a version parsed out of a release title. It borrows
     * Ui.card so the surface, corners and accent still come from one place.
     */
    private fun modRow(title: String, detail: String, fresh: Boolean, url: String): View =
        Ui.card(this, settings).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = Ui.dp(this@UpdateActivity, 12)
            setPadding(pad, pad, pad, pad)
            minimumHeight = Ui.dp(this@UpdateActivity, 52)

            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { layoutParams = it }).topMargin = Ui.dp(this@UpdateActivity, 6)

            addView(TextView(this@UpdateActivity).apply {
                text = if (fresh) "★" else "⇩"
                setTextColor(Appearance.accentOf(settings))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = Ui.dp(this@UpdateActivity, 12) }
            })

            addView(LinearLayout(this@UpdateActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@UpdateActivity).apply {
                    text = title
                    setTextColor(
                        if (fresh) Appearance.accentOf(settings)
                        else getColor(R.color.text)
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                })

                addView(TextView(this@UpdateActivity).apply {
                    text = detail
                    setTextColor(getColor(R.color.text_dim))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            })

            setOnClickListener {
                Feedback.select(it)
                openLink(url)
            }

            Motion.pressable(this, scale = 0.985f)
            com.abacus.dualscreen.ui.Focus.reachable(this, Appearance.accentOf(settings))
        }

    /** Open a download link in whatever the device uses for links. */
    private fun openLink(url: String) {
        val opened = runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess

        if (!opened) {
            Feedback.failed(this, binding.root, getString(R.string.update_mods_open_failed))
        }
    }

    // ── the one button ──────────────────────────────────────────────────────

    private fun render(state: UpdateManager.State) {
        val update = when (state) {
            is UpdateManager.State.Available -> state.update
            is UpdateManager.State.Downloading -> state.update
            is UpdateManager.State.Verifying -> state.update
            is UpdateManager.State.Ready -> state.update
            is UpdateManager.State.Failed -> state.update
            else -> null
        }

        announce(state)

        showUpdate(update)
        binding.progressGroup.visibility = View.GONE
        binding.secondaryRow.visibility = View.GONE
        binding.permissionCard.visibility = View.GONE
        binding.primaryButton.isEnabled = true
        binding.primaryButton.alpha = 1f

        when (state) {
            is UpdateManager.State.Idle -> {
                say(Feedback.State.IDLE, getString(R.string.update_status_idle))
                primary(R.string.update_check_now) { updates.checkNow() }
            }

            is UpdateManager.State.Checking -> {
                say(Feedback.State.BUSY, getString(R.string.update_status_checking))
                primary(R.string.update_status_checking) {}
                binding.primaryButton.isEnabled = false
                binding.primaryButton.alpha = 0.6f
            }

            is UpdateManager.State.UpToDate -> {
                say(Feedback.State.OK, getString(R.string.update_status_current, updates.installedName()))
                primary(R.string.update_check_again) { updates.checkNow() }
            }

            is UpdateManager.State.Available -> {
                say(Feedback.State.OK, getString(R.string.update_status_available, state.update.version.text))
                binding.secondaryRow.visibility = View.VISIBLE
                primaryText(
                    getString(R.string.update_now_size, Downloader.bytes(state.update.size))
                ) { updates.download(state.update) }
            }

            is UpdateManager.State.Downloading -> {
                binding.progressGroup.visibility = View.VISIBLE
                val percent = if (state.total > 0) (state.done * 1000 / state.total).toInt() else 0
                binding.progressBar.isIndeterminate = state.total <= 0
                binding.progressBar.progress = percent
                binding.progressText.text = getString(
                    R.string.update_progress,
                    Downloader.bytes(state.done),
                    Downloader.bytes(state.total),
                    percent / 10,
                )
                say(Feedback.State.BUSY, getString(R.string.update_status_downloading))
                primary(R.string.action_cancel) { updates.cancel() }
            }

            is UpdateManager.State.Verifying -> {
                binding.progressGroup.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = true
                binding.progressText.text = getString(R.string.update_status_verifying)
                say(Feedback.State.BUSY, getString(R.string.update_status_verifying))
                primary(R.string.update_status_verifying) {}
                binding.primaryButton.isEnabled = false
                binding.primaryButton.alpha = 0.6f
            }

            is UpdateManager.State.Ready -> {
                binding.secondaryRow.visibility = View.VISIBLE
                val allowed = Installer.canInstall(this)
                binding.permissionCard.visibility = if (allowed) View.GONE else View.VISIBLE

                say(
                    if (allowed) Feedback.State.OK else Feedback.State.BAD,
                    getString(
                        if (allowed) R.string.update_status_ready else R.string.update_status_needs_permission
                    ),
                )

                primary(R.string.update_install) {
                    val failure = updates.install(this)
                    if (failure?.error == UpdateError.PERMISSION) {
                        binding.permissionCard.visibility = View.VISIBLE
                        Installer.requestPermission(this)
                    }
                }
            }

            is UpdateManager.State.Failed -> {
                // The buzz belongs to announce(), which fires once per outcome. Doing it here too
                // would buzz again on every redraw and on every return to this screen.
                say(Feedback.State.BAD, describe(state.failure))

                when {
                    state.failure.error == UpdateError.PERMISSION -> {
                        binding.permissionCard.visibility = View.VISIBLE
                        primary(R.string.update_permission_open) { Installer.requestPermission(this) }
                    }

                    state.stage == UpdateManager.Stage.DOWNLOAD && state.update != null ->
                        // Resumes from whatever arrived, rather than starting the file again.
                        primary(R.string.update_retry) { updates.download(state.update) }

                    else -> primary(R.string.update_check_again) { updates.checkNow() }
                }
            }
        }
    }

    /**
     * Say out loud what just happened, once per outcome.
     *
     * Only the two that are outcomes rather than progress. A download finishing and a download
     * failing are the moments somebody may not be looking at the screen — this app is used beside a
     * game — and they are exactly the moments a sound earns its place. Everything in between stays
     * silent, because a chime for each of five intermediate states is noise.
     */
    private fun announce(state: UpdateManager.State) {
        val key = when (state) {
            is UpdateManager.State.Ready -> "ready:" + state.update.version.text
            is UpdateManager.State.Failed -> "failed:" + state.failure.error.name
            else -> null
        }

        if (key == null || key == announced) {
            if (key == null) announced = null
            return
        }
        announced = key

        when (state) {
            is UpdateManager.State.Ready -> {
                Feedback.success(binding.primaryButton)
                Motion.pulse(binding.primaryButton)
            }

            is UpdateManager.State.Failed ->
                // Cancelling is a choice, not a failure, and buzzing at somebody for making it
                // would be the app disagreeing with them.
                if (state.failure.error != UpdateError.CANCELLED) Feedback.error(binding.statusText)

            else -> Unit
        }
    }

    private fun primary(label: Int, onPress: () -> Unit) = primaryText(getString(label), onPress)

    private fun primaryText(label: String, onPress: () -> Unit) {
        binding.primaryButton.text = label
        action = onPress
    }

    /** Both halves of an update, or neither. */
    private fun showUpdate(update: Update?) {
        val show = if (update == null) View.GONE else View.VISIBLE
        binding.arrow.visibility = show
        binding.latestGroup.visibility = show
        binding.releaseLine.visibility = show
        binding.notesCard.visibility = show

        update ?: return

        binding.latestVersion.text = update.version.text
        binding.releaseLine.text = getString(
            R.string.update_release_line,
            update.title.ifBlank { update.tag },
            update.publishedAt.take(10),
        )
        binding.notesText.text = ReleaseNotes.readable(update.notes)
            .ifBlank { getString(R.string.update_no_notes) }
    }

    /** The error sentence, with whatever technical detail there was after it. */
    private fun describe(failure: Failure): String {
        val message = getString(failure.error.message)
        return if (failure.detail.isNullOrBlank()) message else message + " (" + failure.detail + ")"
    }

    private fun say(state: Feedback.State, message: String) =
        Feedback.say(binding.statusText, binding.statusDot, state, message)

    // ── preferences ─────────────────────────────────────────────────────────

    private fun buildPreferences() {
        binding.autoCheckBox.isChecked = updates.prefs.autoCheck
        binding.autoCheckBox.setOnCheckedChangeListener { view, on ->
            Feedback.tap(view)
            updates.prefs.autoCheck = on
        }

        binding.bootAnimationBox.isChecked = settings.bootAnimation
        binding.bootAnimationBox.setOnCheckedChangeListener { view, on ->
            Feedback.tap(view)
            settings.bootAnimation = on
        }

        val labels = Channel.entries.map {
            getString(if (it == Channel.STABLE) R.string.update_channel_stable else R.string.update_channel_beta)
        }
        binding.channelSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val current = updates.prefs.channel
        binding.channelSpinner.setSelection(Channel.entries.indexOf(current))
        describeChannel(current)

        // Posted, because a Spinner delivers its first selection asynchronously and would otherwise
        // fire for a choice nobody made.
        binding.channelSpinner.post {
            binding.channelSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val chosen = Channel.entries[pos]
                        if (chosen == updates.prefs.channel) return

                        updates.prefs.channel = chosen
                        describeChannel(chosen)
                        // The cached answer was for the other channel; ask again rather than
                        // showing something that no longer applies.
                        updates.checkNow()
                    }

                    override fun onNothingSelected(p: AdapterView<*>?) = Unit
                }
        }
    }

    private fun describeChannel(channel: Channel) {
        binding.channelDetail.setText(
            if (channel == Channel.STABLE) R.string.update_channel_stable_detail
            else R.string.update_channel_beta_detail
        )
    }

    companion object {
        private const val EXTRA_DOWNLOAD = "start_download"

        /** Open the screen and begin downloading whatever the manager has found. */
        fun downloading(context: android.content.Context): Intent =
            Intent(context, UpdateActivity::class.java).putExtra(EXTRA_DOWNLOAD, true)
    }
}
