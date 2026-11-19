package com.olympus.iptv.ui.countries

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.databinding.ItemCountryBinding
import com.olympus.iptv.playlist.CountryOption

class CountryAdapter(
    private val onToggle: (String) -> Unit
) : RecyclerView.Adapter<CountryAdapter.Holder>() {
    private val items = mutableListOf<CountryOption>()
    private val shown = mutableSetOf<String>()

    fun submit(options: List<CountryOption>, selected: Set<String>) {
        items.clear()
        items.addAll(options)
        shown.clear()
        shown.addAll(selected)
        notifyDataSetChanged()
    }

    fun setShown(id: String, on: Boolean) {
        if (on) shown.add(id) else shown.remove(id)
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCountryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemCountryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CountryOption) {
            val on = item.id in shown
            binding.mark.text = if (on) "✓" else ""
            binding.name.text = item.label
            binding.count.text = item.count.toString()
            binding.root.isSelected = on
            binding.root.setOnClickListener { onToggle(item.id) }
        }
    }
}
