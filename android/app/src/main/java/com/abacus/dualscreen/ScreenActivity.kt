package com.abacus.dualscreen

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.abacus.dualscreen.databinding.ActivityScreenBinding
import java.net.HttpURLConnection
import java.net.URL

/**
 * The second screen itself: a full-bleed WebView showing the page the mod serves.
 *
 * There is deliberately almost no chrome — no address bar, no scrollbars, no system bars. The page
 * already handles all interaction, so anything drawn around it just steals space from the inventory.
 * The one exception is a small menu button, because on a secondary display there may be no system back
 * gesture to fall back on.
 *
 * When the game closes, this returns to the picker rather than sitting on a dead page: the mod's
 * server disappearing *is* the end of the session, and the next thing anyone wants is to pick another.
 */
class ScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenBinding
    private lateinit var url: String
    private lateinit var game: Game

    private var autoReconnect = true
    private var keepAwake = true

    /**
     * Whether the load in flight already failed. onPageFinished still fires after onReceivedError
     * (for the error page), so without this the panel would be hidden the moment it appeared.
     */
    private var loadFailed = false

    private var attempt = 0
    private var healthMisses = 0
    private var leaving = false

    /**
     * Whether the WebView has wandered off the mod — onto the game's wiki, in practice.
     *
     * The wiki is opened by navigating this same window, because wiki.gg refuses to be iframed and a
     * kiosk screen has no tab bar to escape from. So while we're away, failures belong to the wiki and
     * must not be mistaken for the session dying.
     */
    private var offSite = false

    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { load() }
    private val healthCheck = object : Runnable {
        override fun run() {
            probeSession()
            handler.postDelayed(this, HEALTH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        game = Game.byId(intent.getStringExtra(EXTRA_GAME))
        autoReconnect = intent.getBooleanExtra(EXTRA_RECONNECT, true)
        keepAwake = intent.getBooleanExtra(EXTRA_KEEP_AWAKE, true)
        if (url.isEmpty()) {
            finish()
            return
        }

        goImmersive()
        configureWebView()

        // match the loading background to the game's own page, so there's no white flash on open
        binding.root.setBackgroundColor(getColor(game.background))
        binding.webView.setBackgroundColor(getColor(game.background))

        binding.retryButton.setOnClickListener { load() }
        binding.backButton.setOnClickListener { quitToMenu(null) }

        // Stop retrying and leave. The pending retry has to be cancelled explicitly: otherwise it fires
        // after this screen is gone and hauls it back up over the menu you just returned to.
        binding.errorQuitButton.setOnClickListener {
            handler.removeCallbacks(retry)
            quitToMenu(null)
        }

        binding.menuButton.setOnClickListener {
            markActive()
            binding.menuPanel.visibility = View.VISIBLE
        }
        markActive()
        binding.cancelButton.setOnClickListener { binding.menuPanel.visibility = View.GONE }
        binding.reloadButton.setOnClickListener {
            binding.menuPanel.visibility = View.GONE
            attempt = 0
            load()
        }
        binding.quitButton.setOnClickListener { quitToMenu(null) }
        binding.backToScreenButton.setOnClickListener {
            binding.menuPanel.visibility = View.GONE
            backToScreen()
        }

        // back steps out one layer at a time: menu, then the wiki, then the session
        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.menuPanel.visibility == View.VISIBLE -> binding.menuPanel.visibility = View.GONE
                binding.webView.canGoBack() && offSite -> binding.webView.goBack()
                offSite -> backToScreen()
                else -> quitToMenu(null)
            }
        }

        load()
        handler.postDelayed(healthCheck, HEALTH_INTERVAL_MS)
    }

    /** A second screen that sleeps after 30 seconds is useless, so hold the screen on by default. */
    private fun goImmersive() {
        binding.root.keepScreenOn = keepAwake

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // the page is served by the mod on the user's own machine
    private fun configureWebView() = with(binding.webView) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER

        webViewClient = object : WebViewClient() {
            /**
             * Decide off-site status here rather than waiting for onPageStarted.
             *
             * This fires before the load is even attempted, so it's the only place that reliably knows
             * where we're headed. onPageStarted may never fire at all if the address fails to resolve —
             * and when that happened the error below was mistaken for the game session dying, which
             * bounced the user straight back to the second screen the instant they tapped Wiki.
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame)
                    trackLocation(request.url?.toString())
                return false // let the WebView load it
            }

            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                trackLocation(pageUrl)
            }

            override fun onPageFinished(view: WebView, url: String) {
                trackLocation(url)
                if (!loadFailed) {
                    binding.errorPanel.visibility = View.GONE
                    attempt = 0
                    healthMisses = 0
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // sub-resource failures (a missing item icon) shouldn't blank the whole screen
                if (!request.isForMainFrame)
                    return

                // a wiki page failing to load says nothing about whether the game is still running
                if (offSite) {
                    Toast.makeText(
                        this@ScreenActivity,
                        getString(R.string.wiki_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    binding.backToScreenButton.visibility = View.VISIBLE
                    binding.menuPanel.visibility = View.VISIBLE
                    return
                }

                showError(error.description?.toString().orEmpty())
            }
        }
    }

    /** Note whether we're on the mod's own page or somewhere else, and offer the way back. */
    private fun trackLocation(pageUrl: String?) {
        offSite = pageUrl != null && !pageUrl.startsWith(url)
        binding.backToScreenButton.visibility = if (offSite) View.VISIBLE else View.GONE
        markActive()
    }

    /**
     * The menu button floats over the page, so at rest it covers whatever the page drew underneath —
     * on the Stardew screen that's the date in the top-left. Fade it out when nothing is happening and
     * bring it straight back on the next touch.
     */
    private fun markActive() {
        handler.removeCallbacks(fade)
        binding.menuButton.animate().alpha(if (offSite) 0.9f else 0.45f).setDuration(120).start()
        handler.postDelayed(fade, IDLE_FADE_MS)
    }

    private val fade = Runnable {
        binding.menuButton.animate().alpha(0.06f).setDuration(450).start()
    }

    // any touch anywhere, including inside the WebView, counts as activity
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        markActive()
        return super.dispatchTouchEvent(event)
    }

    private fun load() {
        loadFailed = false
        handler.removeCallbacks(retry)
        binding.webView.loadUrl(url)
    }

    /**
     * Ask the mod whether it's still there.
     *
     * The WebView can't tell us this: once the page has loaded, the server going away produces no
     * navigation error at all — the page's own polling just starts failing silently. So the session's
     * health is checked out of band, and a few consecutive misses mean the game has gone.
     */
    private fun probeSession() {
        if (leaving) return

        Thread {
            val alive = runCatching {
                val connection = (URL("$url/state").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = HEALTH_TIMEOUT_MS
                    readTimeout = HEALTH_TIMEOUT_MS
                }
                try {
                    connection.responseCode == HttpURLConnection.HTTP_OK
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)

            runOnUiThread {
                if (leaving) return@runOnUiThread

                if (alive) {
                    healthMisses = 0
                    return@runOnUiThread
                }

                healthMisses++
                if (healthMisses >= HEALTH_MISSES_ALLOWED)
                    sessionLost()
            }
        }.start()
    }

    /**
     * The session has gone. Either wait for it to come back, or hand control back to the picker.
     */
    private fun sessionLost() {
        if (!autoReconnect) {
            quitToMenu(getString(R.string.session_ended))
            return
        }

        // give it a bounded number of tries — a game being restarted comes back in well under this,
        // and anything longer means the session is genuinely over
        if (attempt >= MAX_ATTEMPTS) {
            quitToMenu(getString(R.string.session_gave_up))
            return
        }

        attempt++
        binding.errorTitle.text = getString(R.string.error_title, getString(game.label))
        binding.errorDetail.text = getString(R.string.session_lost, attempt)
        binding.errorPanel.visibility = View.VISIBLE

        handler.removeCallbacks(retry)
        handler.postDelayed(retry, if (attempt < 5) 1500L else 5000L)
    }

    /**
     * Show a failed page load, and — unless told not to — keep trying.
     *
     * Restarting the game is the normal case here, not an exception: the mod's server goes away and
     * comes back a minute later. Backs off to 5s so a genuinely absent PC isn't hammered, and gives up
     * to the picker once the retries are exhausted.
     */
    private fun showError(detail: String) {
        loadFailed = true
        binding.errorTitle.text = getString(R.string.error_title, getString(game.label))

        if (!autoReconnect) {
            binding.errorDetail.text = if (detail.isEmpty()) url else "$url\n\n$detail"
            binding.errorPanel.visibility = View.VISIBLE
            return
        }

        if (attempt >= MAX_ATTEMPTS) {
            quitToMenu(getString(R.string.session_gave_up))
            return
        }

        attempt++
        binding.errorDetail.text = "$url\n\n${getString(R.string.error_retrying, attempt)}"
        binding.errorPanel.visibility = View.VISIBLE

        handler.removeCallbacks(retry)
        handler.postDelayed(retry, if (attempt < 5) 1500L else 5000L)
    }

    /** Come back from the wiki to the mod's own page, clearing the wiki out of history. */
    private fun backToScreen() {
        binding.webView.clearHistory()
        attempt = 0
        load()
    }

    /** Back to the picker, so another session can be chosen. */
    private fun quitToMenu(reason: String?) {
        if (leaving) return
        leaving = true

        handler.removeCallbacks(retry)
        handler.removeCallbacks(healthCheck)

        reason?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }

        // Usually HomeActivity is still beneath this in the task, but a second screen can be open for
        // hours and the system is free to reclaim it — so ask for it by name rather than assuming.
        // CLEAR_TOP|SINGLE_TOP reuses the existing instance where there is one, on its own display,
        // instead of stacking a second copy onto this one.
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    override fun onDestroy() {
        leaving = true
        handler.removeCallbacks(retry)
        handler.removeCallbacks(healthCheck)
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_GAME = "game"
        const val EXTRA_RECONNECT = "reconnect"
        const val EXTRA_KEEP_AWAKE = "keepAwake"

        private const val IDLE_FADE_MS = 4000L
        private const val HEALTH_INTERVAL_MS = 4000L
        private const val HEALTH_TIMEOUT_MS = 2500
        private const val HEALTH_MISSES_ALLOWED = 2
        private const val MAX_ATTEMPTS = 8
    }
}
