package com.abacus.aynsecondscreen

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.abacus.aynsecondscreen.databinding.ActivityScreenBinding

/**
 * The second screen itself: a full-bleed WebView showing the page the mod serves.
 *
 * There is deliberately no chrome — no address bar, no scrollbars, no system bars. The page already
 * handles all interaction, so anything drawn around it just steals space from the inventory.
 */
class ScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenBinding
    private lateinit var url: String

    /**
     * Whether the load in flight already failed. onPageFinished still fires after onReceivedError
     * (for the error page), so without this the panel would be hidden the moment it appeared.
     */
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isEmpty()) {
            finish()
            return
        }

        goImmersive()
        configureWebView()

        binding.retryButton.setOnClickListener { load() }
        binding.backButton.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this) { finish() }

        load()
    }

    /** A second screen that sleeps after 30 seconds is useless, so hold the screen on. */
    private fun goImmersive() {
        binding.root.keepScreenOn = true

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

        setBackgroundColor(Color.BLACK)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!loadFailed)
                    binding.errorPanel.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // sub-resource failures (a missing item icon) shouldn't blank the whole screen
                if (request.isForMainFrame)
                    showError(error.description?.toString().orEmpty())
            }
        }
    }

    private fun load() {
        loadFailed = false
        binding.errorPanel.visibility = View.GONE
        binding.webView.loadUrl(url)
    }

    private fun showError(detail: String) {
        loadFailed = true
        binding.errorDetail.text = if (detail.isEmpty()) url else "$url\n\n$detail"
        binding.errorPanel.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
