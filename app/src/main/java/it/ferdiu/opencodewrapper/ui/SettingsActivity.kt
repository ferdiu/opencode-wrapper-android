package it.ferdiu.opencodewrapper.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import it.ferdiu.opencodewrapper.R
import it.ferdiu.opencodewrapper.data.ServerProfileStore
import it.ferdiu.opencodewrapper.databinding.ActivitySettingsBinding
import it.ferdiu.opencodewrapper.service.OpenCodeEventService

/**
 * Server list management: add/edit/delete profiles and pick the active one.
 * The add/edit form lives in ServerEditActivity.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var profileStore: ServerProfileStore
    private lateinit var adapter: ServerListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_servers)

        profileStore = ServerProfileStore(this)
        adapter = ServerListAdapter(
            onActivate = { profile ->
                profileStore.setActive(profile.id)
                profileStore.setLastSessionId(null)
                OpenCodeEventService.stop(this)
                OpenCodeEventService.start(this)
                refresh()
            },
            onEdit = { profile ->
                startActivity(ServerEditActivity.intent(this, profile.id))
            },
            onDelete = { profile -> confirmDelete(profile.id) },
        )
        binding.serverList.adapter = adapter
        binding.addServerFab.setOnClickListener {
            startActivity(ServerEditActivity.intent(this, null))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submit(profileStore.list(), profileStore.getActiveId())
    }

    private fun confirmDelete(id: String) {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete_server)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val wasActive = profileStore.getActiveId() == id
                profileStore.delete(id)
                if (wasActive) OpenCodeEventService.stop(this)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
