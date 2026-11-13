package it.ferdiu.opencodewrapper.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import it.ferdiu.opencodewrapper.R
import it.ferdiu.opencodewrapper.api.ApiVersionDetector
import it.ferdiu.opencodewrapper.data.ServerConfig
import it.ferdiu.opencodewrapper.data.ServerConfigStore
import it.ferdiu.opencodewrapper.data.ServerProfileStore
import it.ferdiu.opencodewrapper.databinding.ActivityMainBinding
import it.ferdiu.opencodewrapper.service.OpenCodeEventService

/**
 * Deliberately thin: loads the real OpenCode web app in a WebView and gets
 * out of the way. All the "does this survive backgrounding" work lives in
 * OpenCodeEventService, not here - this activity does not need to stay alive
 * for notifications to keep working.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: ServerConfigStore
    private lateinit var profileStore: ServerProfileStore

    /** Base URL the WebView was last loaded for; used to detect server switches. */
    private var loadedBaseUrl: String? = null

    /** Enabled only while the WebView can go back; the enabled state is
     *  refreshed by [refreshWebViewBackState] on every history change, so
     *  the callback never needs to disable itself permanently. */
    private val webViewBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.webView.goBack()
        }
    }

    private fun refreshWebViewBackState() {
        webViewBackCallback.isEnabled = binding.webView.canGoBack()
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = ServerConfigStore(this)
        profileStore = ServerProfileStore(this)
        setSupportActionBar(binding.toolbar)

        setupWebView()
        // The WebView owns the back gesture while it has history: the user
        // navigates inside the OpenCode web app (session -> home, dialogs
        // that push history entries, ...), so "back" must mean browser-back,
        // not "close the app". The callback's enabled state tracks
        // canGoBack() (refreshed on every history change), so once the
        // history is exhausted the default behavior (finish) applies - and
        // the callback re-engages by itself as soon as new history appears.
        onBackPressedDispatcher.addCallback(this, webViewBackCallback)
        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        maybeRequestNotificationPermission()
        maybeRequestBatteryOptimizationExemption()
    }

    override fun onResume() {
        super.onResume()
        val config = configStore.get()
        if (config == null) {
            binding.emptyState.visibility = android.view.View.VISIBLE
            binding.webView.visibility = android.view.View.GONE
        } else {
            binding.emptyState.visibility = android.view.View.GONE
            binding.webView.visibility = android.view.View.VISIBLE
            if (binding.webView.url == null || loadedBaseUrl != config.normalizedBaseUrl) {
                loadServer(
                    config,
                    intent?.getStringExtra(EXTRA_SESSION_ID),
                    intent?.getStringExtra(EXTRA_DIRECTORY),
                )
            }
            OpenCodeEventService.start(this)
        }
        supportActionBar?.subtitle = profileStore.getActive()?.name
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val directory = intent.getStringExtra(EXTRA_DIRECTORY) ?: return
        val config = configStore.get() ?: return
        navigateToSession(config, sessionId, directory)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep normal in-app navigation inside the WebView; only the
                // configured host is ever loaded here in the first place.
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.progressBar.visibility = android.view.View.GONE
                refreshWebViewBackState()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                // Fires on every new history entry, including SPA pushState
                // navigations that never trigger onPageFinished.
                refreshWebViewBackState()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.visibility = if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
                binding.progressBar.progress = newProgress
            }
        }
    }

    private fun loadServer(config: ServerConfig, sessionId: String?, directory: String?) {
        val url = if (sessionId != null && directory != null) sessionUrl(config, sessionId, directory)
            else config.normalizedBaseUrl
        loadedBaseUrl = config.normalizedBaseUrl
        binding.webView.loadUrl(url)
    }

    private fun navigateToSession(config: ServerConfig, sessionId: String, directory: String) {
        configStore.setLastSessionId(sessionId)
        loadedBaseUrl = config.normalizedBaseUrl
        binding.webView.loadUrl(sessionUrl(config, sessionId, directory))
    }

    /**
     * Deep link into a specific session in the OpenCode web UI, honoring the
     * server's detected API generation (v1: /{base64url(directory)}/session/{id};
     * v2: /server/{base64url(baseUrl)}/session/{id}) - see
     * [ApiVersionDetector.webUiSessionUrl]. The directory is learned from
     * the event envelope.
     */
    private fun sessionUrl(config: ServerConfig, sessionId: String, directory: String): String =
        ApiVersionDetector.webUiSessionUrl(config, sessionId, directory)

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybeRequestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    /** Static menu: just the server-list (settings) entry. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_hide_toolbar -> {
                setToolbarVisible(false)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Hides the toolbar for this session; it comes back on next activity
     *  creation (deliberately no on-screen way to restore it). */
    private fun setToolbarVisible(visible: Boolean) {
        binding.toolbar.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_DIRECTORY = "extra_directory"
    }
}
