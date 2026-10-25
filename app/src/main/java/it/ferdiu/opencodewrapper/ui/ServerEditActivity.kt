package it.ferdiu.opencodewrapper.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.ferdiu.opencodewrapper.R
import it.ferdiu.opencodewrapper.api.OpenCodeClientV2
import it.ferdiu.opencodewrapper.data.ServerProfile
import it.ferdiu.opencodewrapper.data.ServerProfileStore
import it.ferdiu.opencodewrapper.databinding.ActivityServerEditBinding
import it.ferdiu.opencodewrapper.service.OpenCodeEventService
import kotlinx.coroutines.launch

/** Add/edit form for a single ServerProfile. Saving health-checks the server
 *  and, when the edited profile is (or becomes) the active one, restarts the
 *  background service so it picks up the change immediately. */
class ServerEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerEditBinding
    private lateinit var profileStore: ServerProfileStore
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.action_add_server)

        profileStore = ServerProfileStore(this)
        editingId = intent.getStringExtra(EXTRA_PROFILE_ID)
        editingId?.let { id -> profileStore.get(id)?.let { fillForm(it) } }

        binding.advancedToggle.setOnClickListener {
            val show = binding.authHeaderLayout.visibility != View.VISIBLE
            binding.authHeaderLayout.visibility = if (show) View.VISIBLE else View.GONE
            binding.authPrecedenceHint.visibility = if (show) View.VISIBLE else View.GONE
        }
        binding.saveButton.setOnClickListener { onSaveClicked() }
    }

    private fun fillForm(p: ServerProfile) {
        title = p.name
        binding.serverNameInput.setText(p.name)
        binding.serverUrlInput.setText(p.baseUrl)
        binding.usernameInput.setText(p.username ?: "")
        binding.passwordInput.setText(p.password ?: "")
        if (!p.authHeaderValue.isNullOrBlank()) {
            binding.authHeaderInput.setText(p.authHeaderValue)
            binding.authHeaderLayout.visibility = View.VISIBLE
            binding.authPrecedenceHint.visibility = View.VISIBLE
        }
    }

    private fun onSaveClicked() {
        val name = binding.serverNameInput.text?.toString()?.trim().orEmpty()
        val url = binding.serverUrlInput.text?.toString()?.trim().orEmpty()
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString() ?: ""
        val authHeader = binding.authHeaderInput.text?.toString()?.trim().orEmpty()

        if (name.isEmpty()) {
            binding.serverNameLayout.error = getString(R.string.error_name_required)
            return
        }
        binding.serverNameLayout.error = null
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            binding.serverUrlLayout.error = getString(R.string.error_invalid_url)
            return
        }
        binding.serverUrlLayout.error = null

        binding.statusText.text = getString(R.string.status_checking)
        binding.saveButton.isEnabled = false

        val profile = ServerProfile(
            id = editingId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            baseUrl = url.trimEnd('/'),
            username = username.ifBlank { null },
            password = if (username.isBlank()) null else password,
            authHeaderValue = authHeader.ifBlank { null },
        )

        lifecycleScope.launch {
            val reachable = runCatching {
                OpenCodeClientV2(profile.toServerConfig()).healthCheck()
            }.getOrDefault(false)

            val isNew = editingId == null
            if (isNew) profileStore.add(profile) else profileStore.update(profile)

            // Restart the service if the saved profile is the active one
            // (add() auto-activates the first profile).
            if (profileStore.getActiveId() == profile.id) {
                OpenCodeEventService.stop(this@ServerEditActivity)
                OpenCodeEventService.start(this@ServerEditActivity)
            }

            binding.saveButton.isEnabled = true
            binding.statusText.text = getString(
                if (reachable) R.string.status_saved_reachable else R.string.status_saved_unreachable
            )
            if (reachable) finish()
        }
    }

    companion object {
        private const val EXTRA_PROFILE_ID = "profile_id"
        fun intent(context: Context, profileId: String?): Intent =
            Intent(context, ServerEditActivity::class.java)
                .apply { profileId?.let { putExtra(EXTRA_PROFILE_ID, it) } }
    }
}
