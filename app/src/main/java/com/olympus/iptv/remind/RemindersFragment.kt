package com.olympus.iptv.remind

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.databinding.ActivityRemindersBinding
import com.olympus.iptv.databinding.ItemReminderCardBinding
import com.olympus.iptv.playlist.EpgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemindersFragment : Fragment() {
    private var _binding: ActivityRemindersBinding? = null
    private val binding get() = _binding!!
    private val leads = intArrayOf(15, 30, 45, 60)
    private lateinit var adapter: RemindersAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityRemindersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.remindBack.visibility = View.GONE
        binding.quietHours.setOnClickListener { toggleQuietHours() }

        adapter = RemindersAdapter(
            onEdit = { edit(it) },
            onDelete = { delete(it) }
        )
        binding.remindList.layoutManager = LinearLayoutManager(requireContext())
        binding.remindList.adapter = adapter

        paintQuietHours()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun paintQuietHours() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            val enabled = withContext(Dispatchers.IO) { store.quietHours() }
            val start = withContext(Dispatchers.IO) { store.quietStartMinute() }
            val end = withContext(Dispatchers.IO) { store.quietEndMinute() }
            if (_binding == null) return@launch
            binding.quietHours.text = getString(
                if (enabled) R.string.quiet_hours_on else R.string.quiet_hours_off
            )
            binding.quietHoursHint.text = getString(
                R.string.quiet_hours_hint,
                clockLabel(start),
                clockLabel(end)
            )
        }
    }

    private fun toggleQuietHours() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            val enabled = withContext(Dispatchers.IO) { store.quietHours() }
            withContext(Dispatchers.IO) {
                store.saveQuietHours(
                    !enabled,
                    PlaylistStore.DEFAULT_QUIET_START,
                    PlaylistStore.DEFAULT_QUIET_END
                )
            }
            ReminderClock.arm(requireContext())
            paintQuietHours()
        }
    }

    private fun clockLabel(minuteOfDay: Int): String {
        val hour = (minuteOfDay / 60) % 24
        val minute = minuteOfDay % 60
        return String.format("%d:%02d", hour, minute)
    }

    private fun render() {
        val items = ReminderStore.listed(requireContext())
        binding.remindEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.remindList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(items)
    }

    private fun delete(item: ShowReminder) {
        val id = item.id ?: return
        ReminderStore.remove(requireContext(), id)
        ReminderClock.arm(requireContext())
        render()
    }

    private fun edit(item: ShowReminder) {
        val labels = leads.map { getString(R.string.remind_before, it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(item.title.orEmpty())
            .setItems(labels) { _, which ->
                val id = item.id ?: return@setItems
                ReminderStore.retimed(requireContext(), id, leads[which])
                ReminderClock.arm(requireContext())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        fun newInstance(): RemindersFragment = RemindersFragment()
    }
}

private class RemindersAdapter(
    private val onEdit: (ShowReminder) -> Unit,
    private val onDelete: (ShowReminder) -> Unit
) : ListAdapter<ShowReminder, RemindersAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemReminderCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemReminderCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ShowReminder) {
            val ctx = binding.root.context
            binding.remindTitle.text = item.title.orEmpty().ifBlank { item.channelName.orEmpty() }
            binding.remindSubtitle.text = ctx.getString(
                R.string.remind_line,
                item.channelNumber ?: 1,
                item.channelName.orEmpty(),
                EpgUtils.formatClock(item.startMillis ?: 0L)
            )
            val lead = item.leadMinutes
            if (lead != null) {
                binding.remindLead.visibility = View.VISIBLE
                binding.remindLead.text = ctx.getString(R.string.remind_lead, lead)
            } else {
                binding.remindLead.visibility = View.GONE
            }
            binding.btnEdit.setOnClickListener { onEdit(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ShowReminder>() {
        override fun areItemsTheSame(oldItem: ShowReminder, newItem: ShowReminder): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ShowReminder, newItem: ShowReminder): Boolean =
            oldItem.id == newItem.id &&
            oldItem.title == newItem.title &&
            oldItem.channelName == newItem.channelName &&
            oldItem.channelNumber == newItem.channelNumber &&
            oldItem.startMillis == newItem.startMillis &&
            oldItem.fireAt == newItem.fireAt &&
            oldItem.leadMinutes == newItem.leadMinutes
    }
}
