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
import com.abacus.dualscreen.ui.Nav
import com.abacus.dualscreen.update.AppSource
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        updates = UpdateManager.get(this)

        Nav.back(this, binding.backButton)
        binding.primaryButton.setOnClickListener {
            Feedback.tap(it)
            action()
        }
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
                say(Feedback.State.BAD, describe(state.failure))
                Feedback.error(binding.primaryButton)

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
