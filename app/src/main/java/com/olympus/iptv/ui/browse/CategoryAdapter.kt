package com.olympus.iptv.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.data.Category
import com.olympus.iptv.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onSelect: (Category, Boolean) -> Unit,
    private val detail: (Category) -> String,
    private val icon: (Category) -> Int,
    private val onHold: (Category) -> Unit = {},
    private val locked: (Category) -> Boolean = { false }
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

    private val items = mutableListOf<Category>()
    private var selectedId: String? = null
    /** Phone list rows: taller taps, no TV focus-select while scrolling. */
    var phoneListMode = false

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<Category>, selectedId: String?) {
        val sameItems = items.size == list.size && items.indices.all { index ->
            val current = items[index]
            val next = list[index]
            current.id == next.id && current.name == next.name
        }
        if (sameItems) {
            if (this.selectedId != selectedId) setSelected(selectedId)
            return
        }
        if (items.isNotEmpty() && items.size == list.size && items.indices.all { items[it].id == list[it].id }) {
            for (index in list.indices) {
                if (items[index].name != list[index].name) {
                    items[index] = list[index]
                    notifyItemChanged(index)
                }
            }
            if (this.selectedId != selectedId) setSelected(selectedId)
            return
        }
        if (items.isNotEmpty() && preservesOrder(list)) {
            insertArrivals(list, selectedId)
            return
        }
        items.clear()
        items.addAll(list)
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    private fun preservesOrder(list: List<Category>): Boolean {
        var index = 0
        for (item in list) {
            if (index < items.size && items[index].id == item.id) index++
        }
        return index == items.size
    }

    private fun insertArrivals(list: List<Category>, selectedId: String?) {
        var index = 0
        for (item in list) {
            if (index < items.size && items[index].id == item.id) {
                index++
            } else {
                items.add(index, item)
                notifyItemInserted(index)
                index++
            }
        }
        this.selectedId = selectedId
    }

    fun categories(): List<Category> = items.toList()

    fun selectedPosition(): Int {
        val index = items.indexOfFirst { it.id == selectedId }
        return if (index >= 0) index else 0
    }

    fun setSelected(id: String?) {
        val old = items.indexOfFirst { it.id == selectedId }
        val next = items.indexOfFirst { it.id == id }
        selectedId = id
        if (old >= 0 && old != next) notifyItemChanged(old)
    }

    fun refreshDetails() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, DETAIL)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) holder.showDetail(items[position]) else holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Category) {
            binding.label.text = com.olympus.iptv.data.NameImages.strip(item.name)
            binding.icon.setImageResource(icon(item))
            binding.root.isSelected = item.id == selectedId
            showDetail(item)
            if (phoneListMode) {
                binding.root.isFocusableInTouchMode = false
                binding.root.minimumHeight =
                    (56 * binding.root.resources.displayMetrics.density).toInt()
                binding.label.textSize = 16f
                binding.detail.textSize = 13f
                val pad = (14 * binding.root.resources.displayMetrics.density).toInt()
                binding.root.setPadding(pad, pad, pad, pad)
                (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    val gap = (4 * binding.root.resources.displayMetrics.density).toInt()
                    params.setMargins(gap * 2, gap, gap * 2, gap)
                    binding.root.layoutParams = params
                }
            }
            binding.root.setOnClickListener { onSelect(item, true) }
            binding.root.setOnLongClickListener {
                onHold(item)
                true
            }
            if (phoneListMode) {
                binding.root.onFocusChangeListener = null
            } else {
                binding.root.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        binding.root.isSelected = true
                        onSelect(item, false)
                    }
                }
            }
        }

        fun showDetail(item: Category) {
            val text = detail(item)
            binding.detail.text = text
            binding.detail.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            binding.lock.visibility = if (locked(item)) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val DETAIL = "detail"
    }
}
