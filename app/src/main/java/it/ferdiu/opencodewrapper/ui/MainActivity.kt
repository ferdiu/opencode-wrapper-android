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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import it.ferdiu.opencodewrapper.R
import it.ferdiu.opencodewrapper.data.ServerConfig
import it.ferdiu.opencodewrapper.data.ServerConfigStore
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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = ServerConfigStore(this)

        setupWebView()
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
            if (binding.webView.url == null) {
                loadServer(
                    config,
                    intent?.getStringExtra(EXTRA_SESSION_ID),
                    intent?.getStringExtra(EXTRA_DIRECTORY),
                )
            }
            OpenCodeEventService.start(this)
        }
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
        binding.webView.loadUrl(url)
    }

    private fun navigateToSession(config: ServerConfig, sessionId: String, directory: String) {
        configStore.setLastSessionId(sessionId)
        binding.webView.loadUrl(sessionUrl(config, sessionId, directory))
    }

    /**
     * Deep link into a specific session in the OpenCode web UI.
     *
     * Confirmed against the v1.18.18 web app router
     * (packages/app/src/app.tsx + utils/session-route.ts): session pages
     * live at `/{base64url(directory)}/session/{id}`, where the directory
     * segment is URL-safe base64 without padding (legacySessionHref). The
     * directory is learned from the /global/event envelope.
     */
    private fun sessionUrl(config: ServerConfig, sessionId: String, directory: String): String =
        "${config.normalizedBaseUrl}/${base64Url(directory)}/session/$sessionId"

    private fun base64Url(value: String): String =
        android.util.Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_DIRECTORY = "extra_directory"
    }
}
