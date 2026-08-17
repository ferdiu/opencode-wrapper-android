package com.opencode.wrapper.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.opencode.wrapper.R
import com.opencode.wrapper.api.OpenCodeClientV2
import com.opencode.wrapper.data.ServerConfig
import com.opencode.wrapper.data.ServerConfigStore
import com.opencode.wrapper.databinding.ActivitySettingsBinding
import com.opencode.wrapper.service.OpenCodeEventService
import kotlinx.coroutines.launch

/**
 * Minimal settings UI, per the design brief ("avoid elaborate settings"):
 * just the server URL and an optional auth header. Saving restarts the
 * background service so it picks up the new config immediately rather than
 * waiting for its next reconnect cycle.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var configStore: ServerConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.settings_title)

        configStore = ServerConfigStore(this)
        configStore.get()?.let { config ->
            binding.serverUrlInput.setText(config.baseUrl)
            binding.authHeaderInput.setText(config.authHeaderValue ?: "")
        }

        binding.saveButton.setOnClickListener { onSaveClicked() }
    }

    private fun onSaveClicked() {
        val url = binding.serverUrlInput.text?.toString()?.trim().orEmpty()
        val auth = binding.authHeaderInput.text?.toString()?.trim().orEmpty()

        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            binding.serverUrlLayout.error = "Enter a full URL, including http:// or https://"
            return
        }
        binding.serverUrlLayout.error = null

        binding.statusText.text = "Checking connection…"
        binding.saveButton.isEnabled = false

        val candidate = ServerConfig(baseUrl = url, authHeaderValue = auth.ifBlank { null })

        lifecycleScope.launch {
            val reachable = runCatching { OpenCodeClientV2(candidate).healthCheck() }.getOrDefault(false)

            configStore.save(url, auth.ifBlank { null })
            OpenCodeEventService.stop(this@SettingsActivity)
            OpenCodeEventService.start(this@SettingsActivity)

            binding.saveButton.isEnabled = true
            binding.statusText.text = if (reachable) {
                "Saved. Server reachable."
            } else {
                "Saved, but couldn't reach the server just now. " +
                    "The background service will keep retrying."
            }

            if (reachable) finish()
        }
    }
}
