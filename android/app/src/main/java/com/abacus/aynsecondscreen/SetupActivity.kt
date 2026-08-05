package com.abacus.aynsecondscreen

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.View
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.abacus.aynsecondscreen.databinding.ActivitySetupBinding
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks where the game is and which display to show the second screen on, then hands off to
 * [ScreenActivity].
 *
 * The display picker is the point of the app: on a handheld with two panels, Android will happily
 * launch an activity onto the secondary display, but only if something asks it to.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var settings: Settings

    /** The display to launch onto, or null to use whichever display this activity is on. */
    private var targetDisplayId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        binding.hostField.setText(settings.host)
        binding.portField.setText(settings.port.toString())

        populateDisplays()

        binding.openButton.setOnClickListener { openScreen() }
        binding.testButton.setOnClickListener { testConnection() }
    }

    /** Offer a display choice only when there's actually more than one to choose from. */
    private fun populateDisplays() {
        val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = manager.displays.filter { it.isValid }
        if (displays.size < 2)
            return

        binding.displayLabel.visibility = View.VISIBLE
        binding.displayGroup.visibility = View.VISIBLE

        displays.forEachIndexed { index, display ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = describe(display, index)
                setTextColor(getColor(R.color.text))
                tag = display.displayId
            }
            binding.displayGroup.addView(button)
        }

        // default to the second display, which is the whole reason someone opens this app
        val preferred = displays.getOrNull(1) ?: displays.first()
        targetDisplayId = preferred.displayId
        binding.displayGroup.children.firstOrNull { it.tag == preferred.displayId }?.let {
            binding.displayGroup.check(it.id)
        }

        binding.displayGroup.setOnCheckedChangeListener { _, checkedId ->
            targetDisplayId = findViewById<RadioButton>(checkedId)?.tag as? Int
        }
    }

    private fun describe(display: Display, index: Int): String {
        val label = display.name?.takeIf { it.isNotBlank() } ?: "Display ${display.displayId}"
        return if (index == 0) "${getString(R.string.display_builtin)} — $label" else label
    }

    /**
     * Build the base URL, reporting the problem in the status line rather than failing silently.
     * Returns null if the form isn't filled in usefully.
     */
    private fun readUrl(): String? {
        val host = binding.hostField.text.toString().trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')

        if (host.isEmpty()) {
            binding.statusText.text = getString(R.string.status_need_host)
            return null
        }

        val port = binding.portField.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            binding.statusText.text = getString(R.string.status_bad_port)
            return null
        }

        settings.host = host
        settings.port = port
        return "http://$host:$port"
    }

    private fun openScreen() {
        val url = readUrl() ?: return

        val intent = Intent(this, ScreenActivity::class.java)
            .putExtra(ScreenActivity.EXTRA_URL, url)

        val options = targetDisplayId
            ?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }

        startActivity(intent, options)
    }

    /** A plain reachability check, so a failure to connect can be told apart from a blank screen. */
    private fun testConnection() {
        val url = readUrl() ?: return
        binding.statusText.text = getString(R.string.status_testing)

        Thread {
            val result = runCatching {
                val connection = (URL("$url/state").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                try {
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }

            runOnUiThread {
                binding.statusText.text = result.fold(
                    onSuccess = { code ->
                        if (code == HttpURLConnection.HTTP_OK) getString(R.string.status_ok)
                        else getString(R.string.status_bad_code, code)
                    },
                    onFailure = { error ->
                        getString(R.string.status_unreachable, error.message ?: error.javaClass.simpleName)
                    }
                )
            }
        }.start()
    }

    private companion object {
        const val TIMEOUT_MS = 3000
    }
}
