package com.olympus.iptv.remind

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.databinding.ActivityRemindersBinding
import com.olympus.iptv.playlist.EpgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemindersFragment : Fragment() {
    private var _binding: ActivityRemindersBinding? = null
    private val binding get() = _binding!!
    private val leads = intArrayOf(15, 30, 45, 60)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityRemindersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.remindBack.visibility = View.GONE
        binding.quietHours.setOnClickListener { toggleQuietHours() }
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
        val cards = binding.remindCards
        cards.removeAllViews()
        val items = ReminderStore.listed(requireContext())
        binding.remindEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val density = resources.displayMetrics.density
        items.forEach { item ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = requireContext().getDrawable(R.drawable.bg_panel)
                setPadding((18 * density).toInt(), (16 * density).toInt(), (18 * density).toInt(), (16 * density).toInt())
            }
            card.addView(text(item.title.orEmpty().ifBlank { item.channelName.orEmpty() }, 20f, R.color.text_primary, true))
            card.addView(
                text(
                    getString(
                        R.string.remind_line,
                        item.channelNumber ?: 1,
                        item.channelName.orEmpty(),
                        EpgUtils.formatClock(item.startMillis ?: 0L)
                    ),
                    15f,
                    R.color.text_secondary,
                    false
                )
            )
            val lead = item.leadMinutes
            if (lead != null) card.addView(text(getString(R.string.remind_lead, lead), 14f, R.color.accent, false))
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(action(getString(R.string.remind_edit)) { edit(item) })
            row.addView(action(getString(R.string.remind_delete)) {
                val id = item.id ?: return@action
                ReminderStore.remove(requireContext(), id)
                ReminderClock.arm(requireContext())
                render()
            })
            card.addView(row)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = (12 * density).toInt()
            cards.addView(card, params)
        }
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

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = size
            setTextColor(requireContext().getColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
        }
    }

    private fun action(label: String, onPick: () -> Unit): Button {
        val gap = (8 * resources.displayMetrics.density).toInt()
        return Button(requireContext()).apply {
            text = label
            isAllCaps = false
            setTextColor(requireContext().getColor(R.color.bg))
            background = requireContext().getDrawable(R.drawable.bg_button)
            setOnClickListener { onPick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (48 * resources.displayMetrics.density).toInt()
            ).apply { marginEnd = gap; topMargin = gap }
        }
    }

    companion object {
        fun newInstance(): RemindersFragment = RemindersFragment()
    }
}
