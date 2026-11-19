package com.olympus.iptv.ui.lineup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.databinding.ItemLineupRowBinding

class LineupRowAdapter(
    private val onToggle: (String, Boolean) -> Unit,
    private val onRename: (String) -> Unit,
    private val onMove: (String, Int) -> Unit,
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<LineupRowAdapter.Holder>() {

    data class Row(
        val id: String,
        val name: String,
        val shown: Boolean,
        val selected: Boolean
    )

    private val rows = mutableListOf<Row>()

    fun indexOf(id: String): Int = rows.indexOfFirst { it.id == id }

    fun submit(next: List<Row>) {
        rows.clear()
        rows.addAll(next)
        notifyDataSetChanged()
    }

    fun append(more: List<Row>) {
        if (more.isEmpty()) return
        val start = rows.size
        rows.addAll(more)
        notifyItemRangeInserted(start, more.size)
    }

    fun setSelected(id: String) {
        val old = rows.indexOfFirst { it.selected }
        val next = rows.indexOfFirst { it.id == id }
        if (old == next) return
        if (old >= 0) rows[old] = rows[old].copy(selected = false)
        if (next >= 0) rows[next] = rows[next].copy(selected = true)
        if (old >= 0) notifyItemChanged(old)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemLineupRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    override fun getItemCount(): Int = rows.size

    inner class Holder(private val binding: ItemLineupRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row) {
            binding.name.text = row.name
            binding.root.isSelected = row.selected
            binding.shown.setOnCheckedChangeListener(null)
            binding.shown.isChecked = row.shown
            binding.shown.setOnCheckedChangeListener { _, checked ->
                if (checked != row.shown) onToggle(row.id, checked)
            }
            val selectOnFocus = View.OnFocusChangeListener { _, focused ->
                if (focused) {
                    binding.root.isSelected = true
                    onSelect(row.id)
                }
            }
            binding.root.isClickable = false
            binding.root.isFocusable = false
            binding.shown.onFocusChangeListener = selectOnFocus
            binding.btnRename.setOnClickListener { onRename(row.id) }
            binding.btnUp.setOnClickListener { onMove(row.id, -1) }
            binding.btnDown.setOnClickListener { onMove(row.id, 1) }
            binding.btnRename.onFocusChangeListener = selectOnFocus
            binding.btnUp.onFocusChangeListener = selectOnFocus
            binding.btnDown.onFocusChangeListener = selectOnFocus
        }
    }
}
