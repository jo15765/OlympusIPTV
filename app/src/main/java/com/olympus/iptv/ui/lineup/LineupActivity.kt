package com.olympus.iptv.ui.lineup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Category
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ChannelNames
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.ListOrder
import com.olympus.iptv.databinding.ActivityLineupBinding
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.LoadedPlaylist
import com.olympus.iptv.playlist.PlaylistLoader
import com.olympus.iptv.ui.SoftKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LineupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLineupBinding
    private lateinit var playlistId: String
    private var source: LoadedPlaylist? = null
    private var kind = ContentKind.LIVE
    private var query = ""
    private var groupFilter = GroupFilter.BOTH
    private var selectedGroupId: String? = null
    private var placedFocus = false

    private val groupOrder = mutableListOf<String>()
    private val hiddenGroups = mutableSetOf<String>()
    private val hiddenChannels = mutableSetOf<String>()
    private val groupNames = mutableMapOf<String, String>()
    private val channelNames = mutableMapOf<String, String>()
    private val channelOrder = mutableMapOf<String, MutableList<String>>()
    private val hiddenCountries = mutableListOf<String>()
    private var namePrefixes = emptyList<String>()
    private var groupFind = ""
    private var groupReplace = ""
    private var nameSuffixes = emptyList<String>()
    private var nameEpg = false
    private var includeInternational = false
    private var groupPins: Map<String, String> = emptyMap()
    private var revision: Long = 0

    private val groupAdapter = LineupRowAdapter(
        onToggle = { id, shown -> toggle(hiddenGroups, id, shown) },
        onRename = { id -> renameGroup(id) },
        onMove = { id, direction -> moveGroup(id, direction) },
        onSelect = { id -> selectGroup(id) }
    )
    private val channelAdapter = LineupRowAdapter(
        onToggle = { id, shown -> toggle(hiddenChannels, id, shown) },
        onRename = { id -> renameChannel(id) },
        onMove = { id, direction -> moveChannel(id, direction) },
        onSelect = { id -> onChannelFocused(id) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLineupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        source = (application as OlympusIptvApp).guideSource
        if (playlistId.isBlank() || source == null) {
            finish()
            return
        }
        kind = if (intent.getStringExtra(EXTRA_KIND) == ContentKind.VOD.name) ContentKind.VOD else ContentKind.LIVE
        val includeVod = intent.getBooleanExtra(EXTRA_INCLUDE_VOD, false)
        binding.tabVod.visibility = if (includeVod) View.VISIBLE else View.GONE

        binding.groupList.layoutManager = GridLayoutManager(this, 1)
        binding.channelList.layoutManager = GridLayoutManager(this, 1)
        binding.groupList.adapter = groupAdapter
        binding.channelList.adapter = channelAdapter
        binding.groupList.itemAnimator = null
        binding.channelList.itemAnimator = null
        binding.groupList.setHasFixedSize(true)
        binding.channelList.setHasFixedSize(true)
        binding.groupList.setItemViewCacheSize(8)
        binding.channelList.setItemViewCacheSize(12)
        binding.channelList.recycledViewPool.setMaxRecycledViews(0, 16)
        binding.channelList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy == 0 || lineupExhausted || lineupLoading) return
                loadMoreChannelsIfNeeded(recyclerView)
            }

            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) return
                loadMoreChannelsIfNeeded(recyclerView)
            }
        })

        binding.tabLive.setOnClickListener { setKind(ContentKind.LIVE) }
        binding.tabVod.setOnClickListener { setKind(ContentKind.VOD) }
        binding.btnDone.setOnClickListener { finish() }
        binding.btnGroupsShow.setOnClickListener {
            hiddenGroups.removeAll(groups().map { it.id }.toSet())
            groupWindow = GROUP_PAGE
            render()
        }
        binding.btnGroupsHide.setOnClickListener {
            hiddenGroups.addAll(groups().map { it.id })
            groupWindow = GROUP_PAGE
            render()
        }
        binding.btnGroupsVisible.setOnClickListener { setGroupFilter(GroupFilter.VISIBLE) }
        binding.btnGroupsHidden.setOnClickListener { setGroupFilter(GroupFilter.HIDDEN) }
        binding.btnGroupsBoth.setOnClickListener { setGroupFilter(GroupFilter.BOTH) }
        binding.btnHideKeyboard.setOnClickListener { hideKeyboard() }
        SoftKeyboard.keepOffUntilAsked(this, binding.search)
        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.search.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty().trim()
                groupWindow = GROUP_PAGE
                render()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        lifecycleScope.launch {
            read(store().lineup(playlistId))
            render()
            paintKind()
        }
    }

    private var closing = false

    override fun finish() {
        if (closing) return
        closing = true
        if (source == null || !::playlistId.isInitialized) {
            super.finish()
            return
        }
        val config = snapshot()
        val app = application as OlympusIptvApp
        app.stagedLineup = playlistId to config
        app.appScope.launch {
            runCatching { app.playlistStore.saveLineup(playlistId, config) }
            val email = app.playlistStore.session.first()?.email
            if (!email.isNullOrBlank()) {
                runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), config) }
            }
        }
        setResult(RESULT_OK)
        super.finish()
    }

    private fun store() = (application as OlympusIptvApp).playlistStore

    private fun read(config: LineupConfig) {
        groupOrder.clear()
        groupOrder.addAll(config.groupOrder.orEmpty())
        hiddenGroups.clear()
        hiddenGroups.addAll(config.hiddenGroups.orEmpty())
        hiddenChannels.clear()
        hiddenChannels.addAll(config.hiddenChannels.orEmpty())
        groupNames.clear()
        groupNames.putAll(config.groupNames.orEmpty())
        channelNames.clear()
        channelNames.putAll(config.channelNames.orEmpty())
        channelOrder.clear()
        hiddenCountries.clear()
        hiddenCountries.addAll(config.hiddenCountries.orEmpty())
        namePrefixes = config.namePrefixes.orEmpty()
        nameSuffixes = config.nameSuffixes.orEmpty()
        groupFind = config.groupFind.orEmpty()
        groupReplace = config.groupReplace.orEmpty()
        nameEpg = config.nameEpg == true
        includeInternational = config.includeInternational == true
        groupPins = config.groupPins.orEmpty()
        revision = config.revision ?: 0L
    }

    private val movedChannelGroups = mutableSetOf<String>()
    private val focusHandler = Handler(Looper.getMainLooper())
    private var groupLoad: Runnable? = null
    private var groupChannelsJob: Job? = null
    private var loadedGroupId: String? = null
    private var loadedGroupChannels: List<Channel> = emptyList()
    private var lineupCursor: CatalogDb.PageCursor? = null
    private var lineupExhausted = false
    private var pendingChannelFocus = -1
    private var pendingChannelFocusId = View.NO_ID
    private var lineupLoading = false
    private var groupWindow = GROUP_PAGE

    private fun snapshot(): LineupConfig = LineupConfig(
        groupOrder = groupOrder.toList(),
        hiddenGroups = hiddenGroups.toList(),
        hiddenChannels = hiddenChannels.toList(),
        groupNames = groupNames.toMap(),
        channelNames = channelNames.toMap(),
        channelOrder = channelOrder.filterKeys { it in movedChannelGroups }.mapValues { it.value.toList() },
        hiddenCountries = hiddenCountries.toList(),
        namePrefixes = namePrefixes,
        nameSuffixes = nameSuffixes,
        nameEpg = nameEpg,
        groupFind = groupFind,
        groupReplace = groupReplace,
        includeInternational = includeInternational,
        groupPins = groupPins.ifEmpty { null },
        revision = System.currentTimeMillis()
    )

    private fun setKind(next: ContentKind) {
        kind = next
        selectedGroupId = null
        groupWindow = GROUP_PAGE
        paintKind()
        render()
    }

    private fun hideKeyboard() {
        SoftKeyboard.hide(this)
        binding.groupList.post {
            val row = binding.groupList.findViewHolderForAdapterPosition(0)?.itemView
            val checkbox = row?.findViewById<View>(R.id.shown)
            checkbox?.requestFocus() ?: binding.btnHideKeyboard.requestFocus()
        }
    }

    private fun setGroupFilter(next: GroupFilter) {
        groupFilter = next
        groupWindow = GROUP_PAGE
        paintGroupFilter()
        render()
    }

    private fun paintGroupFilter() {
        paintChoice(binding.btnGroupsBoth, groupFilter == GroupFilter.BOTH)
        paintChoice(binding.btnGroupsVisible, groupFilter == GroupFilter.VISIBLE)
        paintChoice(binding.btnGroupsHidden, groupFilter == GroupFilter.HIDDEN)
    }

    private fun paintChoice(button: android.widget.Button, selected: Boolean) {
        button.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_button else R.drawable.bg_button_secondary
        )
        button.setTextColor(ContextCompat.getColor(this, if (selected) R.color.bg else R.color.text_primary))
    }

    private fun paintKind() {
        val liveSelected = kind == ContentKind.LIVE
        binding.tabLive.background = ContextCompat.getDrawable(
            this,
            if (liveSelected) R.drawable.bg_button else R.drawable.bg_button_secondary
        )
        binding.tabVod.background = ContextCompat.getDrawable(
            this,
            if (liveSelected) R.drawable.bg_button_secondary else R.drawable.bg_button
        )
        binding.tabLive.setTextColor(ContextCompat.getColor(this, if (liveSelected) R.color.bg else R.color.text_primary))
        binding.tabVod.setTextColor(ContextCompat.getColor(this, if (liveSelected) R.color.text_primary else R.color.bg))
    }

    private fun groups(): List<Category> {
        val loaded = source ?: return emptyList()
        val categories = if (kind == ContentKind.LIVE) loaded.liveCategories else loaded.vodCategories
        val listed = categories.filter { it.id != PlaylistLoader.ALL_ID && it.id != PlaylistLoader.FAVORITES_ID }
        if (kind != ContentKind.LIVE) return listed
        val known = listed.map { it.id }.toSet()
        val extras = channelsForKind().map { it.groupKey() }
            .filter { it.isNotBlank() && it !in known }
            .distinct()
            .map { Category(id = it, name = it, kind = kind) }
        return listed + extras
    }

    private fun channelsForKind(): List<Channel> {
        val loaded = source ?: return emptyList()
        return if (kind == ContentKind.LIVE) loaded.liveChannels else loaded.vodChannels
    }

    private fun channelsInGroup(groupId: String): List<Channel> {
        val inMemory = channelsForKind().filter { it.groupKey() == groupId }
        if (inMemory.isNotEmpty()) return inMemory
        if (loadedGroupId == groupId) return loadedGroupChannels
        val bucket = if (kind == ContentKind.LIVE) CatalogDb.LIVE else CatalogDb.VOD
        return (application as OlympusIptvApp).catalogDb.readCategoryChannels(playlistId, bucket, groupId)
    }

    private fun orderedGroups(): List<Category> {
        val groups = groups()
        if (groupOrder.isEmpty()) return groups
        val byId = groups.associateBy { it.id }
        val listed = groupOrder.mapNotNull { byId[it] }
        val seen = listed.map { it.id }.toSet()
        return listed + groups.filter { it.id !in seen }
    }

    private fun matchingGroups(): List<Category> {
        val groups = orderedGroups().filter { group ->
            when (groupFilter) {
                GroupFilter.BOTH -> true
                GroupFilter.VISIBLE -> group.id !in hiddenGroups
                GroupFilter.HIDDEN -> group.id in hiddenGroups
            }
        }
        if (query.isBlank()) return groups
        val hits = channelsForKind().asSequence()
            .filter { displayChannel(it).contains(query, ignoreCase = true) }
            .map { it.groupKey() }
            .toSet()
        return groups.filter { displayGroup(it).contains(query, ignoreCase = true) || it.id in hits }
    }

    private fun visibleGroups(): List<Category> = matchingGroups().take(groupWindow)

    private fun groupRow(group: Category) = LineupRowAdapter.Row(
        group.id,
        displayGroup(group),
        group.id !in hiddenGroups,
        group.id == selectedGroupId
    )

    private fun orderChannels(groupId: String, current: List<Channel>): List<Channel> {
        val saved = channelOrder[groupId]
        val ordered = if (saved.isNullOrEmpty()) {
            current
        } else {
            val byId = current.associateBy { it.id }
            val listed = saved.mapNotNull { byId[it] }
            val seen = listed.map { it.id }.toSet()
            listed + current.filter { it.id !in seen }
        }
        return ordered.filter { query.isBlank() || displayChannel(it).contains(query, ignoreCase = true) }
    }

    private fun displayGroup(group: Category): String =
        com.olympus.iptv.data.GroupRename.shown(
            group.id,
            group.name,
            com.olympus.iptv.data.LineupConfig(
                groupNames = groupNames,
                groupFind = groupFind,
                groupReplace = groupReplace
            )
        )

    private fun displayChannel(channel: Channel): String =
        ChannelNames.present(
            channel,
            com.olympus.iptv.data.LineupConfig(
                channelNames = channelNames,
                namePrefixes = namePrefixes,
                nameSuffixes = nameSuffixes
            )
        )

    private fun render() {
        val groups = visibleGroups()
        if (selectedGroupId == null || groups.none { it.id == selectedGroupId }) {
            selectedGroupId = groups.firstOrNull()?.id
        }
        groupAdapter.submit(groups.map { groupRow(it) })
        showChannels()
        if (!placedFocus) {
            placedFocus = true
            binding.groupList.post { binding.groupList.requestFocus() }
        }
    }

    private fun showChannels() {
        val selected = visibleGroups().find { it.id == selectedGroupId }
        binding.channelHeading.text = selected?.let { displayGroup(it) } ?: getString(R.string.lineup_channels)
        val groupId = selected?.id
        if (groupId == null) {
            channelAdapter.submit(emptyList())
            return
        }
        val inMemory = channelsForKind().filter { it.groupKey() == groupId }
        if (inMemory.isNotEmpty()) {
            loadedGroupId = groupId
            loadedGroupChannels = inMemory
            lineupExhausted = true
            publishLineupChannels(groupId, inMemory)
            return
        }
        if (loadedGroupId == groupId && loadedGroupChannels.isNotEmpty()) {
            publishLineupChannels(groupId, loadedGroupChannels, reset = true)
            return
        }
        loadLineupPage(groupId, reset = true)
    }

    private fun loadLineupPage(groupId: String, reset: Boolean) {
        if (reset) {
            lineupCursor = null
            lineupExhausted = false
            lineupLoading = false
            loadedGroupId = groupId
            loadedGroupChannels = emptyList()
        }
        if (lineupExhausted || lineupLoading || selectedGroupId != groupId) return
        lineupLoading = true
        val cursor = lineupCursor
        val bucket = if (kind == ContentKind.LIVE) CatalogDb.LIVE else CatalogDb.VOD
        groupChannelsJob?.cancel()
        groupChannelsJob = lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).catalogDb.readCategoryPage(playlistId, bucket, groupId, LINEUP_PAGE, cursor)
            }
            if (selectedGroupId != groupId) {
                lineupLoading = false
                return@launch
            }
            page.lastOrNull()?.let { last ->
                lineupCursor = CatalogDb.PageCursor(last.number, last.name, last.id)
            }
            if (page.size < LINEUP_PAGE) lineupExhausted = true
            lineupLoading = false
            loadedGroupId = groupId
            loadedGroupChannels = loadedGroupChannels + page
            publishLineupChannels(groupId, page, reset = cursor == null)
        }
    }

    private fun publishLineupChannels(groupId: String, channels: List<Channel>, reset: Boolean = true) {
        val rows = orderChannels(groupId, channels).map {
            LineupRowAdapter.Row(it.id, displayChannel(it), it.id !in hiddenChannels, false)
        }
        if (reset) channelAdapter.submit(rows) else {
            channelAdapter.append(rows)
            revealPendingChannel()
        }
    }

    private fun extendGroups(index: Int) {
        if (index < 0 || index < groupAdapter.itemCount - 4) return
        val all = matchingGroups()
        if (groupWindow >= all.size) return
        val previous = groupWindow
        groupWindow = (groupWindow + GROUP_PAGE).coerceAtMost(all.size)
        groupAdapter.append(all.subList(previous, groupWindow).map { groupRow(it) })
    }

    private fun selectGroup(id: String) {
        groupAdapter.setSelected(id)
        revealRow(binding.groupList, groupAdapter.indexOf(id))
        extendGroups(groupAdapter.indexOf(id))
        if (selectedGroupId == id && groupLoad == null) return
        selectedGroupId = id
        groupLoad?.let { focusHandler.removeCallbacks(it) }
        val run = Runnable {
            groupLoad = null
            showChannels()
        }
        groupLoad = run
        focusHandler.postDelayed(run, 160L)
    }

    private fun onChannelFocused(id: String) {
        val index = channelAdapter.indexOf(id)
        revealRow(binding.channelList, index)
        if (index >= 0 && index >= channelAdapter.itemCount - 4) {
            val groupId = selectedGroupId ?: return
            loadLineupPage(groupId, reset = false)
        }
    }

    private fun loadMoreChannelsIfNeeded(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        if (lineupExhausted || lineupLoading) return
        val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
        if (layout.findLastVisibleItemPosition() >= channelAdapter.itemCount - 4) {
            val groupId = selectedGroupId ?: return
            loadLineupPage(groupId, reset = false)
        }
    }

    private fun revealRow(list: androidx.recyclerview.widget.RecyclerView, index: Int) {
        if (index < 0) return
        val layout = list.layoutManager as? LinearLayoutManager ?: return
        val last = layout.findLastVisibleItemPosition()
        val first = layout.findFirstVisibleItemPosition()
        if (last == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        if (index >= last || index <= first) {
            list.post {
                layout.scrollToPositionWithOffset(index, (list.height / 3).coerceAtLeast(0))
            }
        }
    }

    private fun toggle(hidden: MutableSet<String>, id: String, shown: Boolean) {
        if (shown) hidden.remove(id) else hidden.add(id)
        render()
    }

    private fun moveGroup(id: String, direction: Int) {
        if (groupOrder.isEmpty()) groupOrder.addAll(groups().map { it.id })
        val visible = visibleGroups().map { it.id }
        if (!ListOrder.swapVisible(groupOrder, visible, id, direction)) return
        render()
    }

    private fun moveChannel(id: String, direction: Int) {
        val groupId = selectedGroupId ?: return
        if (loadedGroupId == groupId && lineupExhausted && loadedGroupChannels.isNotEmpty()) {
            applyChannelMove(groupId, id, direction, loadedGroupChannels)
            return
        }
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { channelsInGroup(groupId) }
            if (selectedGroupId != groupId) return@launch
            loadedGroupId = groupId
            loadedGroupChannels = current
            lineupExhausted = true
            lineupCursor = current.lastOrNull()?.let { CatalogDb.PageCursor(it.number, it.name, it.id) }
            applyChannelMove(groupId, id, direction, current)
        }
    }

    private fun applyChannelMove(groupId: String, id: String, direction: Int, current: List<Channel>) {
        movedChannelGroups.add(groupId)
        val order = channelOrder.getOrPut(groupId) { mutableListOf() }
        if (order.isEmpty()) order.addAll(current.map { it.id })
        val visible = orderChannels(groupId, current).map { it.id }
        if (!ListOrder.swapVisible(order, visible, id, direction)) return
        publishLineupChannels(groupId, current)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && focusIsIn(binding.groupList)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveGroup(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (atListEdge(binding.groupList, -1) && leavesTheLists(View.FOCUS_UP)) {
                        return super.dispatchKeyEvent(event)
                    }
                    moveGroup(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!staysIn(binding.groupList, View.FOCUS_RIGHT)) {
                        focusList(binding.channelList, firstVisible(binding.channelList), R.id.shown)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!staysIn(binding.groupList, View.FOCUS_LEFT)) return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && focusIsIn(binding.channelList)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveChannel(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveChannel(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!staysIn(binding.channelList, View.FOCUS_LEFT)) {
                        val index = selectedGroupId?.let { groupAdapter.indexOf(it) }?.takeIf { it >= 0 }
                            ?: firstVisible(binding.groupList)
                        focusList(binding.groupList, index, R.id.shown)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!staysIn(binding.channelList, View.FOCUS_RIGHT)) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun focusIsIn(root: View): Boolean {
        var current = currentFocus
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun containsView(root: View, child: View?): Boolean {
        var current = child
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun staysIn(list: View, direction: Int): Boolean {
        val focused = currentFocus ?: return false
        val next = focused.focusSearch(direction) ?: return false
        return containsView(list, next)
    }

    private fun leavesTheLists(direction: Int): Boolean {
        val focused = currentFocus ?: return false
        val next = focused.focusSearch(direction) ?: return false
        return !containsView(binding.groupList, next) && !containsView(binding.channelList, next)
    }

    private fun atListEdge(list: androidx.recyclerview.widget.RecyclerView, direction: Int): Boolean {
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return true
        val holder = currentFocus?.let { list.findContainingViewHolder(it) }
        val current = holder?.bindingAdapterPosition ?: return true
        if (current == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return true
        return if (direction < 0) current == 0 else current == count - 1
    }

    private fun moveGroup(direction: Int) {
        val list = binding.groupList
        val count = groupAdapter.itemCount
        if (count == 0) return
        val current = adapterIndex(list)
        val focusId = currentFocus?.id ?: View.NO_ID
        if (direction > 0 && current == count - 1) {
            val before = count
            extendGroups(current)
            if (groupAdapter.itemCount > before) {
                focusList(list, before, focusId)
                return
            }
            focusList(list, 0, focusId)
            return
        }
        focusList(list, Math.floorMod(current + direction, count), focusId)
    }

    private fun moveChannel(direction: Int) {
        val list = binding.channelList
        val count = channelAdapter.itemCount
        if (count == 0) return
        val current = adapterIndex(list)
        val focusId = currentFocus?.id ?: View.NO_ID
        if (direction > 0 && current == count - 1) {
            if (!lineupExhausted) {
                pendingChannelFocus = count
                pendingChannelFocusId = focusId
                selectedGroupId?.let { loadLineupPage(it, reset = false) }
                return
            }
            focusList(list, 0, focusId)
            return
        }
        focusList(list, Math.floorMod(current + direction, count), focusId)
    }

    private fun adapterIndex(list: androidx.recyclerview.widget.RecyclerView): Int {
        val holder = currentFocus?.let { list.findContainingViewHolder(it) }
        val index = holder?.bindingAdapterPosition ?: 0
        return if (index == androidx.recyclerview.widget.RecyclerView.NO_POSITION) 0 else index
    }

    private fun firstVisible(list: androidx.recyclerview.widget.RecyclerView): Int {
        val manager = list.layoutManager as? LinearLayoutManager
        return manager?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
    }

    private fun revealPendingChannel() {
        val index = pendingChannelFocus
        if (index < 0) return
        val focusId = pendingChannelFocusId
        pendingChannelFocus = -1
        pendingChannelFocusId = View.NO_ID
        val count = channelAdapter.itemCount
        if (count == 0) return
        focusList(binding.channelList, if (index < count) index else 0, focusId)
    }

    private fun focusList(list: androidx.recyclerview.widget.RecyclerView, index: Int, focusId: Int) {
        if (index < 0) return
        list.scrollToPosition(index)
        list.post {
            val row = list.findViewHolderForAdapterPosition(index)?.itemView
            if (row == null) {
                list.post { focusLineupRow(list.findViewHolderForAdapterPosition(index)?.itemView, focusId) }
            } else {
                focusLineupRow(row, focusId)
            }
        }
    }

    private fun focusLineupRow(row: View?, focusId: Int) {
        if (row == null) return
        val same = if (focusId != View.NO_ID) row.findViewById<View>(focusId) else null
        val shown = row.findViewById<View>(R.id.shown)
        val target = listOf(same, shown, row).firstOrNull { it != null && it.isShown && it.isFocusable }
        target?.requestFocus()
    }

    private fun renameGroup(id: String) {
        val original = groups().find { it.id == id }?.name.orEmpty()
        promptName(groupNames[id] ?: original) { entered ->
            if (entered.isBlank() || entered == original) groupNames.remove(id) else groupNames[id] = entered
            render()
        }
    }

    private fun renameChannel(id: String) {
        val original = channelsForKind().find { it.id == id }?.name.orEmpty()
        promptName(channelNames[id] ?: original) { entered ->
            if (entered.isBlank() || entered == original) channelNames.remove(id) else channelNames[id] = entered
            render()
        }
    }

    private fun promptName(current: String, onSave: (String) -> Unit) {
        RenameDialog.show(this, current, onSave)
    }

    private fun Channel.groupKey(): String = categoryId ?: groupTitle.orEmpty()

    private enum class GroupFilter { BOTH, VISIBLE, HIDDEN }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_INCLUDE_VOD = "include_vod"
        private const val LINEUP_PAGE = 15
        private const val GROUP_PAGE = 15

        fun intent(context: Context, playlistId: String, kind: ContentKind, includeVod: Boolean): Intent =
            Intent(context, LineupActivity::class.java)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_INCLUDE_VOD, includeVod)
    }
}
