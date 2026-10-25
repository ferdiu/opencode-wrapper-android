package it.ferdiu.opencodewrapper.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import it.ferdiu.opencodewrapper.data.ServerProfile
import it.ferdiu.opencodewrapper.databinding.ItemServerBinding

class ServerListAdapter(
    private val onActivate: (ServerProfile) -> Unit,
    private val onEdit: (ServerProfile) -> Unit,
    private val onDelete: (ServerProfile) -> Unit,
) : RecyclerView.Adapter<ServerListAdapter.Holder>() {

    private val profiles = mutableListOf<ServerProfile>()
    private var activeId: String? = null

    fun submit(list: List<ServerProfile>, activeId: String?) {
        profiles.clear()
        profiles.addAll(list)
        this.activeId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = profiles.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = profiles[position]
        holder.binding.serverName.text = p.name
        holder.binding.serverUrl.text = p.baseUrl
        holder.binding.activeRadio.isChecked = p.id == activeId
        holder.binding.root.setOnClickListener { onEdit(p) }
        holder.binding.activeRadio.setOnClickListener { onActivate(p) }
        holder.binding.deleteButton.setOnClickListener { onDelete(p) }
    }

    class Holder(val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root)
}
