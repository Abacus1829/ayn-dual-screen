package com.abacus.dualscreen

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.Display
import android.view.View
import android.widget.RadioButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.abacus.dualscreen.databinding.ActivityMirrorBinding

/**
 * Mirror this device's main screen onto its second display.
 *
 * Worth being plain about what this is: it copies the Thor's own screen to the Thor's own second panel.
 * It is not a view of the PC — Android cannot receive another machine's screen without something running
 * on that machine to send it.
 */
class MirrorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMirrorBinding
    private lateinit var settings: Settings

    private var targetDisplayId: Int = -1

    private val askProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            binding.mirrorStatus.text = getString(R.string.mirror_denied)
            return@registerForActivityResult
        }
        start(result.resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMirrorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        binding.backButton.setOnClickListener { finish() }
        binding.startButton.setOnClickListener { begin() }
        binding.stopButton.setOnClickListener { stop() }
        binding.overlayButton.setOnClickListener {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        populateDisplays()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        showState()
    }

    /*********
     * Displays
     *********/
    private fun populateDisplays() {
        val displays = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .displays.filter { it.isValid && it.displayId != Display.DEFAULT_DISPLAY }

        if (displays.isEmpty()) {
            binding.displayCard.visibility = View.GONE
            return
        }

        for (display in displays) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = display.name?.takeIf { it.isNotBlank() } ?: "Display ${display.displayId}"
                setTextColor(getColor(R.color.text))
                tag = display.displayId
            }
            binding.displayGroup.addView(button)
        }

        targetDisplayId = displays.first().displayId
        binding.displayGroup.children.firstOrNull { it.tag == targetDisplayId }?.let {
            binding.displayGroup.check(it.id)
        }
        binding.displayGroup.setOnCheckedChangeListener { _, checkedId ->
            targetDisplayId = findViewById<RadioButton>(checkedId)?.tag as? Int ?: -1
        }
    }

    /*********
     * Start and stop
     *********/
    private fun begin() {
        if (targetDisplayId < 0) {
            binding.mirrorStatus.text = getString(R.string.mirror_no_display)
            return
        }
        if (!canOverlay()) {
            binding.mirrorStatus.text = getString(R.string.mirror_need_overlay)
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        askProjection.launch(manager.createScreenCaptureIntent())
    }

    private fun start(code: Int, data: Intent) {
        val intent = Intent(this, MirrorService::class.java)
            .putExtra(MirrorService.EXTRA_CODE, code)
            .putExtra(MirrorService.EXTRA_DATA, data)
            .putExtra(MirrorService.EXTRA_DISPLAY, targetDisplayId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        // the service needs a moment to take the projection before its state is worth reading
        binding.root.postDelayed({ showState() }, 600)
    }

    private fun stop() {
        startService(
            Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP)
        )
        binding.root.postDelayed({ showState() }, 400)
    }

    private fun showState() {
        val overlay = canOverlay()
        binding.overlayButton.visibility = if (overlay) View.GONE else View.VISIBLE

        val running = MirrorService.running
        binding.startButton.isEnabled = !running && overlay && targetDisplayId >= 0
        binding.stopButton.isEnabled = running

        binding.mirrorStatus.text = when {
            targetDisplayId < 0 -> getString(R.string.mirror_no_display)
            !overlay -> getString(R.string.mirror_need_overlay)
            running -> getString(R.string.mirror_on)
            else -> getString(R.string.mirror_off)
        }
    }

    /** A Presentation put up by a service is an overlay window, so this permission is not optional. */
    private fun canOverlay(): Boolean = AndroidSettings.canDrawOverlays(this)
}
