package com.olympus.iptv.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.data.Category
import com.olympus.iptv.databinding.ItemCategoryBinding
import com.olympus.iptv.ui.FocusMotion
import com.olympus.iptv.ui.Haptics

class CategoryAdapter(
    private val onSelect: (Category, Boolean) -> Unit,
    private val detail: (Category) -> String,
    private val icon: (Category) -> Int,
    private val onHold: (Category) -> Unit = {},
    private val locked: (Category) -> Boolean = { false }
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

    private val allItems = mutableListOf<Category>()
    private val items = mutableListOf<Category>()
    private var selectedId: String? = null
    private var filterQuery = ""
    /** Phone list rows: taller taps, no TV focus-select while scrolling. */
    var phoneListMode = false

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<Category>, selectedId: String?) {
        val sameItems = allItems.size == list.size && allItems.indices.all { index ->
            val current = allItems[index]
            val next = list[index]
            current.id == next.id && current.name == next.name
        }
        if (sameItems) {
            if (this.selectedId != selectedId) setSelected(selectedId)
            return
        }
        if (allItems.isNotEmpty() && allItems.size == list.size && allItems.indices.all { allItems[it].id == list[it].id }) {
            for (index in list.indices) {
                if (allItems[index].name != list[index].name) {
                    allItems[index] = list[index]
                }
            }
            this.selectedId = selectedId
            applyFilter(filterQuery, force = true)
            return
        }
        if (allItems.isNotEmpty() && filterQuery.isBlank() && preservesOrder(list)) {
            insertArrivals(list, selectedId)
            return
        }
        allItems.clear()
        allItems.addAll(list)
        this.selectedId = selectedId
        applyFilter(filterQuery, force = true)
    }

    fun setFilter(query: String) {
        applyFilter(query.trim(), force = false)
    }

    private fun applyFilter(query: String, force: Boolean) {
        val normalized = query.trim()
        if (!force && normalized.equals(filterQuery, ignoreCase = true) && items.isNotEmpty()) return
        filterQuery = normalized
        val next = if (normalized.isBlank()) {
            allItems.toList()
        } else {
            allItems.filter {
                it.name.contains(normalized, ignoreCase = true) ||
                    it.id.contains(normalized, ignoreCase = true)
            }
        }
        val old = items.toList()
        items.clear()
        items.addAll(next)
        if (old.isEmpty() || next.isEmpty()) {
            notifyDataSetChanged()
            return
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].id == next[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val a = old[oldItemPosition]
                val b = next[newItemPosition]
                return a.id == b.id && a.name == b.name &&
                    (a.id == selectedId) == (b.id == selectedId)
            }
        }, true).dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    private fun preservesOrder(list: List<Category>): Boolean {
        var index = 0
        for (item in list) {
            if (index < allItems.size && allItems[index].id == item.id) index++
        }
        return index == allItems.size
    }

    private fun insertArrivals(list: List<Category>, selectedId: String?) {
        var index = 0
        for (item in list) {
            if (index < allItems.size && allItems[index].id == item.id) {
                index++
            } else {
                allItems.add(index, item)
                index++
            }
        }
        this.selectedId = selectedId
        applyFilter(filterQuery, force = true)
    }

    fun categories(): List<Category> = items.toList()

    fun selectedPosition(): Int {
        val index = items.indexOfFirst { it.id == selectedId }
        return if (index >= 0) index else 0
    }

    fun setSelected(id: String?) {
        val old = items.indexOfFirst { it.id == selectedId }
        selectedId = id
        val next = items.indexOfFirst { it.id == id }
        if (old >= 0) notifyItemChanged(old)
        if (next >= 0 && next != old) notifyItemChanged(next)
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
            binding.root.setOnClickListener {
                if (phoneListMode) Haptics.tick(binding.root)
                onSelect(item, true)
            }
            binding.root.setOnLongClickListener {
                if (phoneListMode) Haptics.tick(binding.root)
                onHold(item)
                true
            }
            if (phoneListMode) {
                binding.root.onFocusChangeListener = null
                binding.root.scaleX = 1f
                binding.root.scaleY = 1f
                binding.root.elevation = 0f
                binding.label.alpha = 1f
                binding.detail.alpha = 1f
                binding.icon.alpha = 1f
            } else {
                binding.root.setOnFocusChangeListener { _, hasFocus ->
                    FocusMotion.animate(binding.root, hasFocus)
                    paintFocusChrome(hasFocus || item.id == selectedId)
                    if (hasFocus) {
                        binding.root.isSelected = true
                        onSelect(item, false)
                    }
                }
                FocusMotion.animate(binding.root, binding.root.isFocused)
                paintFocusChrome(binding.root.isFocused || item.id == selectedId)
            }
        }

        private fun paintFocusChrome(active: Boolean) {
            val alpha = if (active) 1f else 0.55f
            binding.label.alpha = alpha
            binding.detail.alpha = if (active) 0.95f else 0.45f
            binding.icon.alpha = if (active) 1f else 0.5f
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
