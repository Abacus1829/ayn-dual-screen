package com.abacus.dualscreen

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
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

    /** Where the session is, as far as anything outside this screen needs to know. */
    private enum class Link { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }

    private lateinit var binding: ActivityScreenBinding
    private lateinit var url: String
    private lateinit var game: Game

    private var autoReconnect = true

    /** What this session is called, for the status line. Falls back to the preset's name. */
    private var sessionName = ""

    private var awake = com.abacus.dualscreen.connect.Awake.ALWAYS
    private var orientation = com.abacus.dualscreen.connect.Orientation.AUTOMATIC

    private var link = Link.CONNECTING

    /**
     * The display this session is on, so its disappearance can be noticed.
     *
     * A handheld's second panel can be switched off mid-session and a dock can be unplugged. Android
     * tears down activities on a removed display, and doing nothing means the session simply
     * vanishes; catching it lets the page come back on the main screen instead.
     */
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null
    private var homeDisplayId = android.view.Display.DEFAULT_DISPLAY
    private var movingDisplay = false

    /**
     * Whether the load in flight already failed. onPageFinished still fires after onReceivedError
     * (for the error page), so without this the panel would be hidden the moment it appeared.
     */
    private var loadFailed = false

    private var attempt = 0
    private var healthMisses = 0
    private var leaving = false

    /**
     * Whether `/state` has ever answered at this address.
     *
     * The WebView is not a reliable witness to whether the connection is good — it reports a page
     * as finished before anything has arrived on some builds, which is how a dead address ended up
     * showing "Connected" over a blank screen. When the target is a companion server, this probe is
     * the authority and the WebView's opinion is ignored.
     */
    private var probedOk = false

    /**
     * Set once we conclude the address is not a companion server: an ordinary web page, which the
     * app is happy to show.
     *
     * It matters because the health check asks for `/state`, and a site that has no such endpoint
     * fails it forever — which used to tear the session down after eight seconds. Anything that is
     * not a companion server gets the health check switched off and is judged by the WebView alone.
     */
    private var plainSite = false

    /**
     * Set once the address has been shown to be answering, so the stall timer can tell "still
     * waiting" from "arrived".
     *
     * Set from the network probe rather than from onPageFinished, because that callback is not a
     * reliable witness — see [probedOk].
     */
    private var pageArrived = false

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
        sessionName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { getString(game.label) }

        /*
         * The awake mode, or the old boolean if that is all the caller sent.
         *
         * Both paths still arrive here: profiles send a mode, and the original connect screen sends
         * EXTRA_KEEP_AWAKE. Reading the extra first and falling back keeps that screen working
         * unchanged rather than requiring both to be updated in lockstep.
         */
        awake = intent.getStringExtra(EXTRA_AWAKE)?.let { com.abacus.dualscreen.connect.Awake.byId(it) }
            ?: if (intent.getBooleanExtra(EXTRA_KEEP_AWAKE, true)) com.abacus.dualscreen.connect.Awake.ALWAYS
            else com.abacus.dualscreen.connect.Awake.NEVER

        orientation = com.abacus.dualscreen.connect.Orientation.byId(intent.getStringExtra(EXTRA_ORIENTATION))

        if (url.isEmpty()) {
            finish()
            return
        }

        applyOrientation()
        applyFocus()
        watchDisplay()
        goImmersive()
        configureWebView()
        buildControls()
        setLink(Link.CONNECTING)

        /*
         * Let macros reach this session.
         *
         * A game step needs somewhere to POST and a tap step needs a screen to land on, and this is
         * the only thing that knows either. Both are cleared in onDestroy so a macro run after the
         * session closes fails honestly instead of posting into the void.
         */
        com.abacus.dualscreen.macro.MacroRunner.target = url
        com.abacus.dualscreen.macro.MacroRunner.tapper = { fx, fy -> tapPage(fx, fy) }

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

        binding.focusButton.setOnClickListener {
            com.abacus.dualscreen.ui.Feedback.tap(it)
            val settings = Settings(this)
            settings.keepGameFocus = !settings.keepGameFocus
            applyFocus()
            com.abacus.dualscreen.ui.Feedback.toast(
                this,
                getString(
                    if (settings.keepGameFocus) R.string.focus_now_game else R.string.focus_now_screen
                ),
                long = true,
            )
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

    /**
     * Decide whether this window takes the controller, and say so on the button.
     *
     * The default is that it does not. A second screen exists to sit beside a game, and Android
     * focuses one window at a time across every display — so without this, opening a session on the
     * lower panel silently stops the pad reaching the game on the upper one until you touch the game
     * again. See [com.abacus.dualscreen.ui.Focus].
     *
     * Re-applied whenever it changes rather than only at startup, so the toggle in the menu takes
     * effect on the session already open instead of the next one.
     */
    private fun applyFocus() {
        val keepGameFocus = Settings(this).keepGameFocus
        com.abacus.dualscreen.ui.Focus.passive(this, keepGameFocus)
        binding.focusButton.setText(
            if (keepGameFocus) R.string.focus_take_input else R.string.focus_give_back
        )
    }

    /** A second screen that sleeps after 30 seconds is useless, so hold the screen on by default. */
    private fun goImmersive() {
        applyAwake()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Hold the screen on, or stop holding it.
     *
     * Applied to this window, which is the one on the display the session is actually on — the flag
     * is per-window, so setting it anywhere else would keep the wrong panel awake. Re-applied on
     * every state change because the "while connected" mode depends on the state.
     */
    private fun applyAwake() {
        binding.root.keepScreenOn = when (awake) {
            com.abacus.dualscreen.connect.Awake.ALWAYS -> true
            com.abacus.dualscreen.connect.Awake.NEVER -> false
            com.abacus.dualscreen.connect.Awake.CONNECTED -> link == Link.CONNECTED
        }
    }

    /**
     * Lock the session's orientation, or leave it alone.
     *
     * UNSPECIFIED rather than SENSOR for automatic: the Thor's lower panel has its own idea of which
     * way up it is, and forcing sensor rotation onto it fights the system rather than following it.
     */
    private fun applyOrientation() {
        requestedOrientation = when (orientation) {
            com.abacus.dualscreen.connect.Orientation.AUTOMATIC ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            com.abacus.dualscreen.connect.Orientation.LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            com.abacus.dualscreen.connect.Orientation.PORTRAIT ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * Notice the display this session is on going away.
     *
     * Rather than letting the system quietly destroy the activity — which from the user's side is
     * the app disappearing — the page is relaunched on the main display. [movingDisplay] stops the
     * teardown that follows from being mistaken for the user leaving.
     */
    private fun watchDisplay() {
        homeDisplayId = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.displayId
            else @Suppress("DEPRECATION") windowManager.defaultDisplay?.displayId
        }.getOrNull() ?: android.view.Display.DEFAULT_DISPLAY

        if (homeDisplayId == android.view.Display.DEFAULT_DISPLAY) return

        displayListener = com.abacus.dualscreen.connect.Displays.listen(this) {
            if (leaving || movingDisplay) return@listen
            if (com.abacus.dualscreen.connect.Displays.exists(this, homeDisplayId)) return@listen

            movingDisplay = true
            runOnUiThread { moveToMainDisplay() }
        }
    }

    private fun moveToMainDisplay() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(healthCheck)
        handler.removeCallbacks(waitForHost)

        val again = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(again) }
            .onFailure { quitToMenu(getString(R.string.screen_display_gone)) }

        finish()
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
                    /*
                     * Nothing here is believed without corroboration from the network.
                     *
                     * Some WebView builds call this the moment a load is *started*, so acting on it
                     * alone reported a connection to an address with nothing at it — and, worse,
                     * hid the retry panel a moment after the retry put it up. The probe knows
                     * whether anything answered; until it says so, this callback changes nothing.
                     *
                     * Off-site is the wiki rather than the session, and says nothing about whether
                     * the mod is still running either way.
                     */
                    if (!offSite && (plainSite || probedOk)) {
                        binding.errorPanel.visibility = View.GONE
                        attempt = 0
                        healthMisses = 0
                        setLink(Link.CONNECTED)
                    }
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

    // ── connection state ────────────────────────────────────────────────────

    /**
     * Move to a state and show it.
     *
     * One place, so the pill, the menu line and the screen-awake flag can never disagree about what
     * is happening. The pill is shown for anything that is not a healthy connection and fades itself
     * out once things are well again — a permanent badge over the page would cost more than it says.
     */
    private fun setLink(state: Link) {
        link = state
        applyAwake()

        val label = getString(
            when (state) {
                Link.CONNECTING -> R.string.link_connecting
                Link.CONNECTED -> R.string.link_connected
                Link.RECONNECTING -> R.string.link_reconnecting
                Link.DISCONNECTED -> R.string.link_disconnected
                Link.FAILED -> R.string.link_failed
            }
        )

        val colour = getColor(
            when (state) {
                Link.CONNECTED -> R.color.state_ok
                Link.FAILED, Link.DISCONNECTED -> R.color.state_bad
                else -> R.color.state_busy
            }
        )

        binding.statusPill.text = label
        binding.statusPill.setTextColor(colour)
        binding.menuStatus.text = getString(R.string.link_detail, sessionName, host(), label)

        handler.removeCallbacks(hidePill)

        if (state == Link.CONNECTED) {
            // Shown briefly on success, then out of the way. Seeing it turn green is worth one
            // second; leaving it there is not.
            binding.statusPill.animate().alpha(0.85f).setDuration(120).start()
            handler.postDelayed(hidePill, PILL_LINGER_MS)
        } else {
            binding.statusPill.animate().alpha(0.9f).setDuration(120).start()
        }
    }

    private val hidePill = Runnable {
        binding.statusPill.animate().alpha(0f).setDuration(400).start()
    }

    /** The address without the scheme, which is all anybody reads off it. */
    private fun host(): String = url.removePrefix("http://").removePrefix("https://")

    // ── page controls ───────────────────────────────────────────────────────

    /**
     * The row of page controls in the menu.
     *
     * Built in code because whether Back and Forward are useful depends on where the WebView has
     * been, and because a settings switch can take the whole row away — which is the point of
     * putting them here rather than on the screen.
     */
    private fun buildControls() {
        binding.controlsRow.removeAllViews()

        if (!Settings(this).showControls) {
            binding.controlsRow.visibility = View.GONE
            return
        }

        binding.controlsRow.visibility = View.VISIBLE

        control(R.string.control_back) {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        control(R.string.control_forward) {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        control(R.string.control_reload) { binding.webView.reload() }
        control(R.string.control_reconnect) {
            binding.menuPanel.visibility = View.GONE
            attempt = 0
            healthMisses = 0
            setLink(Link.CONNECTING)
            load()
        }
        control(R.string.control_zoom) { resetZoom() }
        control(R.string.control_fullscreen) { goImmersive() }
    }

    private fun control(label: Int, onClick: () -> Unit) {
        binding.controlsRow.addView(android.widget.Button(this).apply {
            text = getString(label)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.text))
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(getColor(R.color.card_hi))
                setStroke(dp(1), getColor(R.color.edge))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        })
    }

    /**
     * Put the page back to its natural size.
     *
     * setInitialScale(0) is the documented way to say "no forced scale"; calling it alone does not
     * repaint, so the page is reloaded afterwards. Pinch zoom is off in this WebView, but a page can
     * still end up scaled by its own viewport handling after a rotation.
     */
    private fun resetZoom() {
        binding.webView.setInitialScale(0)
        binding.webView.reload()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun load() {
        loadFailed = false
        pageArrived = false
        handler.removeCallbacks(retry)
        handler.removeCallbacks(waitForHost)

        // A WebView pointed at an address that is simply not there can sit on its own connect
        // timeout for the better part of two minutes, showing a blank screen and saying nothing.
        // This is the promise that something will be said before then.
        handler.removeCallbacks(stall)
        handler.postDelayed(stall, STALL_MS)

        binding.webView.loadUrl(url)

        // Asked straight away rather than waiting for the first scheduled check: it decides whether
        // this address is a companion server, which decides what the rest of this screen believes.
        probeSession()
    }

    /**
     * Nothing has arrived in a reasonable time.
     *
     * Treated as a failed load rather than left alone, which puts it into the same retry path as a
     * refused connection — the user gets the same panel and the same countdown either way, and the
     * difference between "refused" and "no answer at all" is not one they can act on differently.
     */
    private val stall = Runnable {
        if (leaving || probedOk || pageArrived) return@Runnable
        showError(getString(R.string.error_no_answer))
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
            val alive = statusOf("$url/state") == HttpURLConnection.HTTP_OK

            /*
             * Whether anything at all is at that address.
             *
             * Asked only when /state did not answer, and it is what separates "an ordinary web page,
             * which is fine" from "nothing there". Inferring that from the WebView instead was
             * wrong: some builds report a page as finished while the connection is still pending, so
             * a dead address showed a confident green "Connected" over a blank screen.
             */
            val anything = !alive && statusOf(url) != null

            runOnUiThread {
                if (leaving) return@runOnUiThread

                if (alive) {
                    probedOk = true
                    plainSite = false
                    pageArrived = true
                    healthMisses = 0
                    if (link != Link.CONNECTED && !offSite) setLink(Link.CONNECTED)
                    return@runOnUiThread
                }

                /*
                 * Something is there, but it is not a companion server — an ordinary web page,
                 * which this app is perfectly happy to show full-screen. Health checking it means
                 * asking for an endpoint it will never have, so the check is switched off rather
                 * than being allowed to close a working session eight seconds in.
                 */
                if (!probedOk && anything) {
                    plainSite = true
                    pageArrived = true
                    healthMisses = 0
                    if (!offSite) setLink(Link.CONNECTED)
                    return@runOnUiThread
                }

                if (plainSite) return@runOnUiThread

                healthMisses++
                if (healthMisses >= HEALTH_MISSES_ALLOWED)
                    sessionLost()
            }
        }.start()
    }

    /**
     * The HTTP status at a URL, or null if nothing answered.
     *
     * Blocking; called from the probe thread. Null and a 404 are very different answers here — one
     * means nothing is listening, the other means something is and it simply does not have that
     * path — so the status is returned rather than a boolean.
     */
    private fun statusOf(target: String): Int? = runCatching {
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HEALTH_TIMEOUT_MS
            readTimeout = HEALTH_TIMEOUT_MS
        }
        try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * The session has gone. Either wait for it to come back, or hand control back to the picker.
     */
    private fun sessionLost() {
        if (!autoReconnect) {
            setLink(Link.DISCONNECTED)
            quitToMenu(getString(R.string.session_ended))
            return
        }

        // give it a bounded number of tries — a game being restarted comes back in well under this,
        // and anything longer means the session is genuinely over
        if (attempt >= MAX_ATTEMPTS) {
            setLink(Link.FAILED)
            quitToMenu(getString(R.string.session_gave_up))
            return
        }

        attempt++
        setLink(Link.RECONNECTING)
        binding.errorTitle.text = getString(R.string.error_title, sessionName)
        binding.errorDetail.text = getString(R.string.session_lost, attempt)
        binding.errorPanel.visibility = View.VISIBLE

        handler.removeCallbacks(retry)
        handler.postDelayed(waitForHost, backoff(attempt))
    }

    /** See [com.abacus.dualscreen.connect.Backoff] for why the delays are shaped as they are. */
    private fun backoff(attempt: Int): Long =
        com.abacus.dualscreen.connect.Backoff.delayFor(attempt)

    /**
     * Wait for the host to come back, and only then reload the page.
     *
     * This is the difference between recovering and thrashing. Reloading a WebView at an address
     * that is still down throws the page away, shows a browser error, and has to be undone when the
     * host returns. Asking with one cheap HTTP request first costs a few hundred bytes and means the
     * reload happens once, at the moment it will succeed.
     */
    private val waitForHost = object : Runnable {
        override fun run() {
            if (leaving) return

            Thread {
                val back = statusOf("$url/state") == HttpURLConnection.HTTP_OK ||
                    statusOf(url) != null

                runOnUiThread {
                    if (leaving) return@runOnUiThread

                    if (back) {
                        // The page is very often still fine — the mod restarted underneath it and
                        // its own polling has resumed. Reload only when the page is actually
                        // broken, so a recovered session keeps its scroll position and its state.
                        healthMisses = 0
                        if (loadFailed) load() else setLink(Link.CONNECTED)
                        if (!loadFailed) binding.errorPanel.visibility = View.GONE
                        return@runOnUiThread
                    }

                    if (attempt >= MAX_ATTEMPTS) {
                        setLink(Link.FAILED)
                        quitToMenu(getString(R.string.session_gave_up))
                        return@runOnUiThread
                    }

                    attempt++
                    binding.errorDetail.text = getString(R.string.session_lost, attempt)
                    handler.postDelayed(this, backoff(attempt))
                }
            }.start()
        }
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
        binding.errorTitle.text = getString(R.string.error_title, sessionName)

        if (!autoReconnect) {
            setLink(Link.DISCONNECTED)
            binding.errorDetail.text = if (detail.isEmpty()) url else "$url\n\n$detail"
            binding.errorPanel.visibility = View.VISIBLE
            return
        }

        if (attempt >= MAX_ATTEMPTS) {
            setLink(Link.FAILED)
            quitToMenu(getString(R.string.session_gave_up))
            return
        }

        attempt++
        setLink(Link.RECONNECTING)
        binding.errorDetail.text = "$url\n\n${getString(R.string.error_retrying, attempt)}"
        binding.errorPanel.visibility = View.VISIBLE

        handler.removeCallbacks(retry)
        handler.removeCallbacks(waitForHost)
        handler.postDelayed(waitForHost, backoff(attempt))
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
        handler.removeCallbacks(waitForHost)

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

    /**
     * Synthesise a tap on the page.
     *
     * Dispatched into this app's own WebView, which is the only surface it may touch -- injecting
     * pointer events into another app needs root or an accessibility service. Coordinates arrive as
     * fractions so a macro works on any panel size.
     */
    private fun tapPage(fx: Float, fy: Float) {
        val x = fx.coerceIn(0f, 1f) * binding.webView.width
        val y = fy.coerceIn(0f, 1f) * binding.webView.height
        val now = android.os.SystemClock.uptimeMillis()

        for (action in intArrayOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, now, action, x, y, 0)
            binding.webView.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    override fun onDestroy() {
        leaving = true
        com.abacus.dualscreen.macro.MacroRunner.target = null
        com.abacus.dualscreen.macro.MacroRunner.tapper = null
        handler.removeCallbacks(retry)
        handler.removeCallbacks(healthCheck)
        handler.removeCallbacks(waitForHost)
        handler.removeCallbacks(hidePill)
        com.abacus.dualscreen.connect.Displays.stopListening(this, displayListener)
        displayListener = null
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_GAME = "game"
        const val EXTRA_RECONNECT = "reconnect"

        /**
         * The old two-state awake flag.
         *
         * Still sent by the original connect screen, which has not been rewritten and does not need
         * to be. Profiles send [EXTRA_AWAKE] instead and this is ignored.
         */
        const val EXTRA_KEEP_AWAKE = "keepAwake"

        const val EXTRA_NAME = "name"
        const val EXTRA_ORIENTATION = "orientation"
        const val EXTRA_AWAKE = "awake"
        const val EXTRA_PROFILE = "profile"

        private const val IDLE_FADE_MS = 4000L
        private const val HEALTH_INTERVAL_MS = 4000L
        private const val HEALTH_TIMEOUT_MS = 2500
        private const val HEALTH_MISSES_ALLOWED = 2
        private val MAX_ATTEMPTS = com.abacus.dualscreen.connect.Backoff.MAX_ATTEMPTS

        /** How long the pill stays up after a successful connection before fading. */
        private const val PILL_LINGER_MS = 1800L

        /**
         * How long a load may produce nothing before it is called a failure.
         *
         * Well inside the WebView's own connect timeout, which is long enough that somebody would
         * reasonably conclude the app had hung.
         */
        private const val STALL_MS = 12_000L
    }
}
