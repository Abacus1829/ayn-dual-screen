package com.abacus.dualscreen

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityControlsBinding

/**
 * Volume and brightness for the handheld itself.
 *
 * Two limits worth knowing, both Android's rather than choices made here:
 *
 * - There is **no per-app volume** on Android. The sliders below are the system's audio *streams*,
 *   which is as granular as any app can get.
 * - There is **no public way to set two displays' brightness separately**. The system slider moves the
 *   whole device. The app slider only affects this app's own window, which is the one thing an app can
 *   control precisely and without permission.
 */
class ControlsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlsBinding
    private lateinit var settings: Settings
    private lateinit var audio: AudioManager

    /** The streams worth exposing, in the order people actually reach for them. */
    private val streams by lazy {
        listOf(
            Triple(AudioManager.STREAM_MUSIC, R.string.vol_media, binding.volMedia),
            Triple(AudioManager.STREAM_RING, R.string.vol_ring, binding.volRing),
            Triple(AudioManager.STREAM_ALARM, R.string.vol_alarm, binding.volAlarm),
            Triple(AudioManager.STREAM_NOTIFICATION, R.string.vol_notification, binding.volNotification)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        com.abacus.dualscreen.ui.Nav.back(this, binding.backButton)
        binding.grantButton.setOnClickListener { requestWriteSettings() }

        wireVolume()
        wireBrightness()

        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        refreshVolume()
        refreshSystemBrightness()
    }

    /*********
     * Volume
     *********/
    private fun wireVolume() {
        for ((stream, label, bar) in streams) {
            bar.max = audio.getStreamMaxVolume(stream)
            bar.contentDescription = getString(label)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    runCatching {
                        audio.setStreamVolume(stream, value, 0)
                    }.onFailure {
                        // ring/notification are refused while Do Not Disturb is on, and that throws
                        binding.volumeNote.visibility = View.VISIBLE
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
    }

    private fun refreshVolume() {
        for ((stream, _, bar) in streams)
            bar.progress = runCatching { audio.getStreamVolume(stream) }.getOrDefault(0)
    }

    /*********
     * Brightness
     *********/
    private fun wireBrightness() {
        binding.brightApp.max = 100
        binding.brightApp.progress = 100
        binding.brightApp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                // never all the way to zero, or the screen becomes unreadable and the slider unusable
                setWindowBrightness(value.coerceAtLeast(5) / 100f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.brightSystem.max = 255
        binding.brightSystem.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser || !canWriteSettings()) return
                runCatching {
                    AndroidSettings.System.putInt(
                        contentResolver,
                        AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
                        AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    AndroidSettings.System.putInt(
                        contentResolver,
                        AndroidSettings.System.SCREEN_BRIGHTNESS,
                        value.coerceIn(5, 255)
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun setWindowBrightness(level: Float) {
        window.attributes = window.attributes.apply { screenBrightness = level }
    }

    private fun refreshSystemBrightness() {
        val allowed = canWriteSettings()
        binding.brightSystem.isEnabled = allowed
        binding.grantButton.visibility = if (allowed) View.GONE else View.VISIBLE

        if (allowed) {
            binding.brightSystem.progress = runCatching {
                AndroidSettings.System.getInt(contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS)
            }.getOrDefault(128)
        }
    }

    private fun canWriteSettings(): Boolean = AndroidSettings.System.canWrite(this)

    /** Opens the system page; there is no in-app prompt for this one. */
    private fun requestWriteSettings() {
        startActivity(
            Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
