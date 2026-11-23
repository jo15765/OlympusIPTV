package com.olympus.iptv.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.olympus.iptv.R
import com.olympus.iptv.data.Category
import com.olympus.iptv.databinding.ItemCategoryBinding
import com.olympus.iptv.ui.FocusMotion
import com.olympus.iptv.ui.Haptics
import com.olympus.iptv.ui.channelLogo
import java.util.concurrent.atomic.AtomicInteger

class CategoryAdapter(
    private val onSelect: (Category, Boolean) -> Unit,
    private val detail: (Category) -> String,
    private val icon: (Category) -> Int,
    private val onHold: (Category) -> Unit = {},
    private val locked: (Category) -> Boolean = { false },
    private val previewLogos: (Category) -> List<String> = { emptyList() },
    private val liveGroup: (Category) -> Boolean = { false }
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

    enum class GenreFilter(val label: String) {
        ALL("All"),
        SPORTS("Sports"),
        NEWS("News"),
        LOCALS("Locals"),
        ENTERTAINMENT("Entertainment"),
        KIDS("Kids")
    }

    private val allItems = mutableListOf<Category>()
    private val items = mutableListOf<Category>()
    private val submitGeneration = AtomicInteger()
    private var selectedId: String? = null
    private var filterQuery = ""
    private var genreFilter = GenreFilter.ALL
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
        applyFilter(query.trim(), genreFilter, force = false)
    }

    fun setGenreFilter(genre: GenreFilter) {
        applyFilter(filterQuery, genre, force = false)
    }

    fun currentGenreFilter(): GenreFilter = genreFilter

    private fun matchesGenre(category: Category, genre: GenreFilter): Boolean {
        if (genre == GenreFilter.ALL) return true
        // Always keep pinned smart cards visible at the top
        if (category.id.startsWith("__")) return true
        val lower = category.name.lowercase()
        return when (genre) {
            GenreFilter.ALL -> true
            GenreFilter.SPORTS ->
                "sport" in lower || "espn" in lower || "nfl" in lower || "nba" in lower ||
                    "mlb" in lower || "nhl" in lower || "mls" in lower || "ufc" in lower ||
                    "ppv" in lower || "dazn" in lower || "ncaa" in lower || "soccer" in lower ||
                    "football" in lower || "baseball" in lower || "hockey" in lower || "golf" in lower ||
                    "racing" in lower || "f1" in lower || "nascar" in lower || "fox sports" in lower
            GenreFilter.NEWS ->
                "news" in lower || "cnn" in lower || "msnbc" in lower || "fox news" in lower ||
                    "cnbc" in lower || "bloomberg" in lower || "weather" in lower || "bbc" in lower ||
                    "c-span" in lower || "cspan" in lower
            GenreFilter.LOCALS ->
                "local" in lower || "abc" in lower || "cbs" in lower || "nbc" in lower ||
                    "fox" in lower || "pbs" in lower || "cw" in lower || "network" in lower ||
                    "affiliate" in lower || "east" in lower || "west" in lower
            GenreFilter.KIDS ->
                "kid" in lower || "disney" in lower || "nick" in lower || "cartoon" in lower ||
                    "family" in lower || "junior" in lower || "boomerang" in lower || "pbs kids" in lower
            GenreFilter.ENTERTAINMENT ->
                "entertain" in lower || "movie" in lower || "cinema" in lower || "hbo" in lower ||
                    "showtime" in lower || "starz" in lower || "comedy" in lower || "drama" in lower ||
                    "music" in lower || "mtv" in lower || "hgtv" in lower || "food" in lower ||
                    "discovery" in lower || "history" in lower || "a&e" in lower || "bravo" in lower ||
                    "amc" in lower || "tnt" in lower || "tbs" in lower || "usa" in lower || "syfy" in lower ||
                    "hallmark" in lower || "lifetime" in lower || "24/7" in lower
        }
    }

    private fun applyFilter(query: String, genre: GenreFilter = genreFilter, force: Boolean = false) {
        val normalized = query.trim()
        if (!force && normalized.equals(filterQuery, ignoreCase = true) && genre == genreFilter && items.isNotEmpty()) return
        filterQuery = normalized
        genreFilter = genre
        val next = allItems.filter { category ->
            val matchesText = normalized.isBlank() ||
                category.name.contains(normalized, ignoreCase = true) ||
                category.id.contains(normalized, ignoreCase = true)
            matchesText && matchesGenre(category, genre)
        }
        val old = items.toList()
        if (old.isEmpty() || next.isEmpty()) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
            return
        }
        val token = submitGeneration.incrementAndGet()
        ListDiff.dispatch(
            submitGeneration,
            token,
            old,
            next,
            sameItem = { left, right -> left.id == right.id },
            sameContent = { left, right ->
                left.name == right.name && (left.id == selectedId) == (right.id == selectedId)
            },
            payload = { left, right -> if (left.name == right.name) DETAIL else null }
        ) { diff, rows ->
            items.clear()
            items.addAll(rows)
            diff.dispatchUpdatesTo(this)
        }
    }

    override fun getItemId(position: Int): Long = stableRowId(items.getOrNull(position)?.id)

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
            binding.icon.imageTintList = android.content.res.ColorStateList.valueOf(genreTint(binding.root.context, item))
            bindPreviews(item)
            binding.liveBadge.visibility = if (liveGroup(item)) View.VISIBLE else View.GONE
            binding.root.isSelected = item.id == selectedId
            showDetail(item)
            if (phoneListMode) {
                binding.root.isFocusableInTouchMode = false
                binding.root.minimumHeight =
                    (64 * binding.root.resources.displayMetrics.density).toInt()
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(
                    binding.label,
                    androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE
                )
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(
                    binding.detail,
                    androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE
                )
                binding.label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                binding.detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                val cardParams = binding.root.layoutParams
                if (cardParams != null && cardParams.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                    cardParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                    binding.root.layoutParams = cardParams
                }
                val pad = (12 * binding.root.resources.displayMetrics.density).toInt()
                binding.root.setPadding(pad, pad, pad, pad)
                (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    val gap = (4 * binding.root.resources.displayMetrics.density).toInt()
                    params.setMargins(gap, gap, gap, gap)
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
                binding.root.isSelected = binding.root.isFocused
                binding.root.setOnFocusChangeListener { _, hasFocus ->
                    FocusMotion.animate(binding.root, hasFocus)
                    binding.root.isSelected = hasFocus
                    paintFocusChrome(hasFocus)
                    if (hasFocus) {
                        onSelect(item, false)
                    }
                }
                FocusMotion.animate(binding.root, binding.root.isFocused)
                paintFocusChrome(binding.root.isFocused)
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

        private fun bindPreviews(item: Category) {
            val urls = previewLogos(item)
            if (urls.isEmpty()) {
                binding.icon.visibility = View.VISIBLE
                binding.preview0.visibility = View.GONE
                binding.preview1.visibility = View.GONE
                binding.preview2.visibility = View.GONE
                return
            }
            binding.icon.visibility = View.GONE
            val px = (28 * binding.root.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val views = listOf(binding.preview0, binding.preview1, binding.preview2)
            views.forEachIndexed { idx, view ->
                val u = urls.getOrNull(idx)
                if (u.isNullOrBlank()) {
                    view.visibility = View.GONE
                } else {
                    view.visibility = View.VISIBLE
                    loadPreview(view, u, px)
                }
            }
        }

        private fun loadPreview(view: ImageView, url: String, px: Int) {
            view.load(url) {
                channelLogo(px)
                scale(Scale.FILL)
                placeholder(R.drawable.ic_channel_placeholder)
                error(R.drawable.ic_channel_placeholder)
            }
        }
    }

    private fun genreTint(context: android.content.Context, item: Category): Int {
        val lower = item.name.lowercase()
        val res = when {
            "sport" in lower || "espn" in lower || "nfl" in lower || "nba" in lower || "mlb" in lower || "nhl" in lower -> R.color.epg_sports_stroke
            "movie" in lower || "cinema" in lower || "hbo" in lower || "showtime" in lower -> R.color.epg_movie_stroke
            "news" in lower || "cnn" in lower || "fox news" in lower || "msnbc" in lower -> R.color.epg_live_stroke
            else -> R.color.accent
        }
        return context.getColor(res)
    }

    private companion object {
        const val DETAIL = "detail"
    }
}
