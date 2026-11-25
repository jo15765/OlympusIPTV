package com.olympus.iptv.ui.browse

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.QueueItem
import com.olympus.iptv.data.ShowNames
import com.olympus.iptv.databinding.ActivityFreshBinding
import com.olympus.iptv.databinding.ItemFreshBinding
import com.olympus.iptv.databinding.ItemFreshEpisodeBinding
import com.olympus.iptv.databinding.ItemSeasonBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.EpisodeCache
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.playlist.Watchmode
import com.olympus.iptv.playlist.WatchmodeException
import com.olympus.iptv.playlist.XtreamClient
import com.olympus.iptv.ui.channelLogo
import com.olympus.iptv.ui.player.PlayerActivity
import com.olympus.iptv.ui.player.SeriesPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FreshFragment : Fragment() {
    private var _binding: ActivityFreshBinding? = null
    private val binding get() = _binding!!
    private val playlistId: String
        get() = arguments?.getString(EXTRA_PLAYLIST_ID).orEmpty()
    private val rows = ArrayList<CatalogDb.FreshTitle>()
    private val favoriteKeys = HashSet<String>()
    private val adapter = FreshAdapter()
    private val seasonAdapter = SeasonAdapter()
    private val episodeAdapter = EpisodeAdapter()
    private var shown: CatalogDb.FreshTitle? = null
    private var seriesEpisodes = emptyList<Channel>()
    private var episodesBySeason: Map<Int, List<Channel>> = emptyMap()
    private var seasonNumbers = emptyList<Int>()
    private var season = 1
    private var seriesJob: Job? = null
    private var seasonSwitchToken = 0
    private var listFocusPos = 0
    private var queuedInDetail = false
    private lateinit var cacheFile: File
    private var opening = false
    private var focusGuard: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private fun viewActive(): Boolean = _binding != null && view != null

    private val currentFocus: View?
        get() = activity?.currentFocus

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityFreshBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (playlistId.isBlank()) {
            exitFreshHost()
            return
        }
        val act = requireActivity()
        if (com.olympus.iptv.ui.DeviceForm.isTouchPrimary(requireContext())) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(act.window, false)
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.freshMain,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.freshDetail,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
        }
        cacheFile = File(requireContext().filesDir, "watchmode-week.json")
        if (hostedInLibrary()) {
            binding.freshHeader.visibility = View.GONE
            binding.freshSubtitle.visibility = View.GONE
            binding.freshMain.setPadding(
                binding.freshMain.paddingLeft,
                0,
                binding.freshMain.paddingRight,
                binding.freshMain.paddingBottom
            )
            binding.freshList.nextFocusUpId = View.NO_ID
        }
        val columns = if (com.olympus.iptv.ui.DeviceForm.isTouchPrimary(requireContext())) 2 else COLUMNS
        binding.freshList.layoutManager = GridLayoutManager(requireContext(), columns)
        binding.freshList.adapter = adapter
        binding.freshList.itemAnimator = null
        binding.freshList.setHasFixedSize(false)
        binding.freshList.setItemViewCacheSize(COLUMNS * 2)
        (binding.freshList.layoutManager as GridLayoutManager).initialPrefetchItemCount = COLUMNS
        binding.freshList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val grid = recyclerView.layoutManager as? GridLayoutManager ?: return
                prefetchFrom(grid.findLastVisibleItemPosition() + 1)
            }
        })
        binding.detailSeasonList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.detailSeasonList.itemAnimator = null
        binding.detailSeasonList.setHasFixedSize(true)
        binding.detailSeasonList.adapter = seasonAdapter
        binding.detailEpisodeList.layoutManager = LinearLayoutManager(requireContext())
        binding.detailEpisodeList.itemAnimator = null
        binding.detailEpisodeList.adapter = episodeAdapter
        binding.freshStatus.visibility = View.VISIBLE
        binding.freshStatus.text = getString(R.string.fresh_loading)
        binding.btnBack.setOnClickListener { leaveFresh() }
        binding.detailBack.setOnClickListener { leaveFresh() }
        binding.btnBack.nextFocusDownId = binding.freshList.id
        binding.detailBack.nextFocusDownId = binding.detailPlay.id
        binding.freshList.nextFocusUpId = binding.btnBack.id
        binding.detailPlay.nextFocusUpId = binding.detailBack.id
        binding.detailQueue.nextFocusUpId = binding.detailBack.id
        binding.detailSeasonList.nextFocusUpId = binding.detailBack.id
        binding.freshHero.visibility = View.GONE
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (PlayerActivity.consumeBrowseBack()) return
                    leaveFresh()
                }
            }
        )
        attachFocusGuard()
        viewLifecycleOwner.lifecycleScope.launch { load() }
    }

    override fun onDestroyView() {
        detachFocusGuard()
        _binding = null
        super.onDestroyView()
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!PlayerActivity.consumeBrowseBack()) leaveFresh()
            }
            return true
        }
        if (hostedInLibrary() && !detailOpen()) return false
        if (event.action == KeyEvent.ACTION_DOWN && detailOpen()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> return handleDetailDpad(event.keyCode)
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            if (trapDpad(event.keyCode)) return true
        }
        return false
    }

    fun focusFirstCard(): Boolean {
        if (!viewActive() || rows.isEmpty() || detailOpen()) return false
        focusFreshCard(listFocusPos)
        return true
    }

    private fun detailOpen(): Boolean = _binding?.let { it.freshDetail.visibility == View.VISIBLE } == true

    private fun activeBack(): View = if (detailOpen()) binding.detailBack else binding.btnBack

    private fun hostedInLibrary(): Boolean = activity is LibraryActivity

    private fun focusAllowed(view: View?): Boolean {
        if (view == null) return false
        if (!detailOpen() && hostedInLibrary()) return true
        if (view === activeBack()) return true
        return focusIsIn(if (detailOpen()) binding.freshDetail else binding.freshMain, view)
    }

    private fun focusIsIn(root: View, start: View? = currentFocus): Boolean {
        var v = start ?: return false
        while (true) {
            if (v === root) return true
            v = v.parent as? View ?: return false
        }
    }

    private fun trapDpad(keyCode: Int): Boolean {
        val focused = currentFocus ?: run {
            reclaimFreshFocus()
            return true
        }
        if (!focusAllowed(focused)) {
            reclaimFreshFocus()
            return true
        }
        if (detailOpen() && focusIsIn(binding.detailEpisodeList, focused)) return false
        if (detailOpen() && focusIsIn(binding.detailSeasonList, focused) &&
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            return false
        }
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        val next = focused.focusSearch(direction)
        if (next != null && focusAllowed(next)) return false
        if (next == null && detailOpen() &&
            (focusIsIn(binding.detailEpisodeList, focused) || focusIsIn(binding.detailSeasonList, focused))
        ) {
            return false
        }
        return true
    }

    private fun reclaimFreshFocus() {
        if (!viewActive()) return
        if (detailOpen()) {
            if (binding.detailPlay.visibility == View.VISIBLE && binding.detailPlay.requestFocus()) return
            if (binding.detailSeriesPane.visibility == View.VISIBLE) {
                val seasonHolder = binding.detailSeasonList.findViewHolderForAdapterPosition(
                    seasonNumbers.indexOf(season).coerceAtLeast(0)
                )
                if (seasonHolder?.itemView?.requestFocus() == true) return
                if (binding.detailEpisodeList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true) {
                    return
                }
            }
            binding.detailBack.requestFocus()
            return
        }
        focusFreshCard(listFocusPos)
    }

    private fun attachFocusGuard() {
        if (focusGuard != null) return
        val guard = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (!viewActive()) return@OnGlobalFocusChangeListener
            if (focusAllowed(newFocus)) return@OnGlobalFocusChangeListener
            binding.root.post {
                if (viewActive() && !focusAllowed(currentFocus)) reclaimFreshFocus()
            }
        }
        focusGuard = guard
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(guard)
    }

    private fun detachFocusGuard() {
        val guard = focusGuard ?: return
        focusGuard = null
        val observer = _binding?.root?.viewTreeObserver ?: return
        if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(guard)
    }

    private fun leaveFresh() {
        if (PlayerActivity.consumeBrowseBack()) return
        if (binding.freshDetail.visibility == View.VISIBLE) hideDetail() else exitFreshHost()
    }

    private fun exitFreshHost() {
        when {
            parentFragment != null -> parentFragmentManager.popBackStack()
            activity is LibraryHost -> (activity as LibraryHost).onFreshExit()
            else -> requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private suspend fun load() {
        val app = requireActivity().application as OlympusIptvApp
        try {
            val favs = withContext(Dispatchers.IO) { app.playlistStore.favoriteIds(playlistId) }
            favoriteKeys.clear()
            favoriteKeys.addAll(favs)
            val ready = withContext(Dispatchers.IO) { app.freshCards(playlistId) }
            if (!viewActive()) return
            if (!ready.isNullOrEmpty()) {
                showCards(ready)
            } else {
                showStatus(R.string.fresh_loading)
            }
            app.warmFresh(playlistId)
            val job = app.freshJob
            if (job != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    job.join()
                    if (!viewActive()) return@launch
                    val latest = withContext(Dispatchers.IO) { app.freshCards(playlistId) }
                    if (!viewActive()) return@launch
                    when {
                        !latest.isNullOrEmpty() -> showCards(latest)
                        rows.isNotEmpty() -> return@launch
                        app.freshFailure == WatchmodeException.Kind.MISSING_KEY -> showStatus(R.string.fresh_need_key)
                        app.freshFailure == WatchmodeException.Kind.REJECTED -> showStatus(R.string.fresh_rejected)
                        app.freshFailure != null -> showStatus(R.string.fresh_failed)
                        else -> showStatus(R.string.fresh_empty)
                    }
                }
            } else if (ready.isNullOrEmpty()) {
                when {
                    app.freshFailure == WatchmodeException.Kind.MISSING_KEY -> showStatus(R.string.fresh_need_key)
                    app.freshFailure == WatchmodeException.Kind.REJECTED -> showStatus(R.string.fresh_rejected)
                    app.freshFailure != null -> showStatus(R.string.fresh_failed)
                    else -> showStatus(R.string.fresh_empty)
                }
            }
        } catch (_: OutOfMemoryError) {
            runCatching { app.catalogDb.releaseSearchExport() }
            if (viewActive() && rows.isEmpty()) showStatus(R.string.fresh_failed)
        }
    }

    private fun showCards(found: List<CatalogDb.FreshTitle>) {
        if (!viewActive()) return
        val usable = ArrayList<CatalogDb.FreshTitle>(found.size)
        for (row in found) {
            if (row.id.isNullOrEmpty() || row.bucket.isNullOrEmpty()) continue
            usable.add(row)
        }
        val same = rows.size == usable.size && rows.indices.all { rows[it] == usable[it] }
        if (!same) {
            rows.clear()
            rows.addAll(usable)
            adapter.notifyDataSetChanged()
        }
        binding.freshStatus.visibility = View.GONE
        binding.freshList.post {
            if (!viewActive() || binding.freshDetail.visibility == View.VISIBLE) return@post
            val grid = binding.freshList.layoutManager as? GridLayoutManager
            val last = grid?.findLastVisibleItemPosition() ?: -1
            if (last >= 0) prefetchFrom(last + 1)
            val focus = currentFocus
            val onLibraryChrome = hostedInLibrary() && focus != null && !focusIsIn(binding.root, focus)
            if (!onLibraryChrome && (focus == null || !focusIsIn(binding.freshList, focus))) {
                focusFreshCard(listFocusPos)
            }
        }
    }

    private fun focusFreshCard(position: Int) {
        if (!viewActive() || rows.isEmpty()) return
        val index = position.coerceIn(0, rows.lastIndex)
        listFocusPos = index
        binding.freshList.scrollToPosition(index)
        binding.freshList.post {
            if (!viewActive() || binding.freshDetail.visibility == View.VISIBLE) return@post
            val target = binding.freshList.findViewHolderForAdapterPosition(index)?.itemView
            if (target != null) {
                target.requestFocus()
            } else {
                binding.freshList.post {
                    if (viewActive() && binding.freshDetail.visibility != View.VISIBLE) {
                        binding.freshList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun prefetchFrom(start: Int) {
        if (start < 0 || start >= rows.size) return
        val urls = com.olympus.iptv.ui.ImagePrefetcher.ahead(rows.map { it.logoUrl }, start)
        if (urls.isEmpty()) return
        val px = (160 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        com.olympus.iptv.ui.ImagePrefetcher.warm(requireContext(), urls, px)
    }

    private fun showStatus(message: Int) {
        if (!viewActive()) return
        binding.freshStatus.visibility = View.VISIBLE
        binding.freshStatus.text = getString(message)
    }

    private fun handleDetailDpad(keyCode: Int): Boolean {
        val focused = currentFocus ?: run {
            binding.detailPlay.requestFocus()
            return true
        }
        val isSeries = binding.detailSeriesPane.visibility == View.VISIBLE

        if (focused === binding.detailPlay) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (binding.detailQueue.visibility == View.VISIBLE) {
                        binding.detailQueue.requestFocus()
                    } else if (isSeries && seasonNumbers.isNotEmpty()) {
                        focusSeasonChip(0)
                    }
                    true
                }

                else -> true
            }
        }

        if (focused === binding.detailQueue) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    binding.detailPlay.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isSeries && seasonNumbers.isNotEmpty()) {
                        focusSeasonChip(0)
                    }
                    true
                }

                else -> true
            }
        }

        if (focusIsIn(binding.detailSeasonList, focused)) {
            val holder = binding.detailSeasonList.findContainingViewHolder(focused)
            val pos = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            val lastIdx = seasonNumbers.lastIndex
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos > 0) {
                        focusSeasonChip(pos - 1)
                    } else if (binding.detailQueue.visibility == View.VISIBLE) {
                        binding.detailQueue.requestFocus()
                    } else {
                        binding.detailPlay.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (pos in 0 until lastIdx) {
                        focusSeasonChip(pos + 1)
                    } else {

                        binding.detailPlay.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (episodeAdapter.itemCount > 0) {
                        focusEpisodeRow(0)
                    }
                    true
                }

                else -> true
            }
        }

        if (focusIsIn(binding.detailEpisodeList, focused)) {
            val holder = binding.detailEpisodeList.findContainingViewHolder(focused)
            val pos = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            val lastEp = episodeAdapter.itemCount - 1
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (pos > 0) {
                        focusEpisodeRow(pos - 1)
                    } else {

                        focusSeasonChip(seasonNumbers.indexOf(season).coerceAtLeast(0))
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (pos in 0 until lastEp) {
                        focusEpisodeRow(pos + 1)
                    } else if (lastEp >= 0) {

                        focusEpisodeRow(0)
                    }
                    true
                }

                else -> true
            }
        }

        binding.detailPlay.requestFocus()
        return true
    }

    private fun focusEpisodeRow(index: Int) {
        if (index < 0 || index >= episodeAdapter.itemCount) return
        binding.detailEpisodeList.scrollToPosition(index)
        binding.detailEpisodeList.post {
            if (!viewActive() || binding.freshDetail.visibility != View.VISIBLE) return@post
            val holder = binding.detailEpisodeList.findViewHolderForAdapterPosition(index)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                binding.detailEpisodeList.post {
                    if (viewActive()) {
                        binding.detailEpisodeList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun showDetail(title: CatalogDb.FreshTitle) {
        seriesJob?.cancel()
        seriesJob = null
        clearSeriesState()
        shown = title
        binding.freshDetail.visibility = View.VISIBLE
        binding.detailHero.visibility = View.VISIBLE
        val touch = com.olympus.iptv.ui.DeviceForm.isTouchPrimary(requireContext())
        binding.detailBack.visibility = if (touch) View.VISIBLE else View.GONE
        binding.detailService.text = if (title.day.isBlank()) {
            title.service
        } else {
            getString(R.string.fresh_when, title.service, title.day)
        }
        binding.detailName.text = title.name
        bindArt(binding.detailLogo, title.logoUrl, 84, 120)
        val waiting = title.detail.startsWith("Premieres ") || title.detail.matches(SEASON_ONLY)
        binding.detailBody.text = if (waiting) getString(R.string.fresh_detail_loading) else title.detail
        bindDetailQueue(title)
        val series = title.bucket == CatalogDb.SERIES
        if (series) {
            binding.detailPlay.visibility = View.VISIBLE
            binding.detailPlay.text = getString(R.string.fresh_play)
            binding.detailPlay.setOnClickListener {
                val first = episodesBySeason[season]?.firstOrNull() ?: seriesEpisodes.firstOrNull()
                if (first != null) playEpisode(first)
            }
            binding.detailSeriesPane.visibility = View.VISIBLE
            binding.detailEpisodeStatus.visibility = View.VISIBLE
            binding.detailEpisodeStatus.text = getString(R.string.episodes_loading)
            seasonAdapter.submit(emptyList())
            episodeAdapter.submit(emptyList())
            loadSeries(title)
            binding.detailPlay.post {
                if (viewActive() && shown?.id == title.id) binding.detailPlay.requestFocus()
            }
        } else {
            binding.detailSeriesPane.visibility = View.GONE
            binding.detailPlay.visibility = View.VISIBLE
            binding.detailPlay.text = getString(R.string.fresh_play)
            binding.detailPlay.setOnClickListener { openMovie(title) }
            binding.detailPlay.post {
                if (viewActive() && shown?.id == title.id) binding.detailPlay.requestFocus()
            }
        }
        if (waiting) loadDescription(title)
    }

    private fun bindDetailQueue(title: CatalogDb.FreshTitle) {
        val app = requireActivity().application as OlympusIptvApp
        val item = QueueItem(
            bucket = title.bucket,
            id = title.id,
            name = title.name,
            logoUrl = title.logoUrl,
            seriesId = if (title.bucket == CatalogDb.SERIES) title.id else null
        )
        queuedInDetail = false
        binding.detailQueue.text = getString(R.string.fresh_queue_add)
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                app.playlistStore.queue(playlistId).any { it.bucket == item.bucket && it.id == item.id }
            }
            if (!viewActive() || shown?.id != title.id) return@launch
            queuedInDetail = saved
            binding.detailQueue.text = getString(if (saved) R.string.fresh_queue_saved else R.string.fresh_queue_add)
        }
        binding.detailQueue.setOnClickListener {
            if (playlistId.isBlank()) return@setOnClickListener
            val next = !queuedInDetail
            queuedInDetail = next
            binding.detailQueue.text = getString(if (next) R.string.fresh_queue_saved else R.string.fresh_queue_add)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                if (next) app.playlistStore.addQueue(playlistId, item)
                else app.playlistStore.removeQueue(playlistId, item.bucket, item.id)
            }
        }
    }

    private fun updatePrimaryPlayButton(episodes: List<Channel>) {
        val first = episodes.firstOrNull()
        if (first == null) {
            binding.detailPlay.text = getString(R.string.fresh_play)
            return
        }
        val epSeason = first.season ?: season
        val epNum = first.number ?: 1
        binding.detailPlay.text = getString(R.string.fresh_play_episode, epSeason, epNum)
    }

    private fun hideDetail() {
        seriesJob?.cancel()
        seriesJob = null
        seasonSwitchToken += 1
        shown = null
        clearSeriesState()
        seasonAdapter.submit(emptyList())
        episodeAdapter.submit(emptyList())
        binding.detailSeriesPane.visibility = View.GONE
        binding.detailPlay.visibility = View.VISIBLE
        binding.detailEpisodeStatus.visibility = View.GONE
        binding.freshDetail.visibility = View.GONE
        focusFreshCard(listFocusPos)
    }

    private fun clearSeriesState() {
        seriesEpisodes = emptyList()
        episodesBySeason = emptyMap()
        seasonNumbers = emptyList()
        season = 1
    }

    private fun loadDescription(title: CatalogDb.FreshTitle) {
        val app = requireActivity().application as OlympusIptvApp
        viewLifecycleOwner.lifecycleScope.launch {
            val plot = withContext(Dispatchers.IO) {
                runCatching {
                    Watchmode.synopsis(cacheFile, title.watchmodeId)
                        ?: app.playlistStore.account(playlistId)?.let { saved ->
                            XtreamClient().titleLine(saved, title.bucket == CatalogDb.SERIES, title.id)
                        }
                }.getOrNull()
            }
            if (!viewActive() || requireActivity().isFinishing) return@launch
            if (plot.isNullOrBlank()) {
                if (shown?.id == title.id && shown?.service == title.service) {
                    binding.detailBody.text = title.detail
                }
                return@launch
            }
            val seasonLine = title.detail.takeIf { it.startsWith("Season ") }?.substringBefore('.')?.trim()
            val body = if (seasonLine.isNullOrBlank() || plot.startsWith(seasonLine)) plot else "$seasonLine. $plot"
            val index = rows.indexOfFirst { it.id == title.id && it.bucket == title.bucket && it.service == title.service }
            if (index >= 0) rows[index] = rows[index].copy(detail = body)
            if (shown?.id == title.id && shown?.service == title.service) binding.detailBody.text = body
        }
    }

    private fun loadSeries(title: CatalogDb.FreshTitle) {
        val app = requireActivity().application as OlympusIptvApp
        val scope = viewLifecycleOwner.lifecycleScope
        seriesJob = scope.launch {
            val channel = withContext(Dispatchers.IO) {
                runCatching { app.catalogDb.readChannel(playlistId, title.bucket, title.id) }.getOrNull()
            }
            if (!viewActive() || shown?.id != title.id) return@launch
            if (channel == null) {
                binding.detailEpisodeStatus.visibility = View.VISIBLE
                binding.detailEpisodeStatus.text = getString(R.string.channel_missing)
                return@launch
            }
            val seriesId = channel.seriesId?.takeIf { it.isNotBlank() } ?: channel.id
            val saved = withContext(Dispatchers.IO) {
                runCatching { app.playlistStore.account(playlistId) }.getOrNull()
            }
            if (saved == null || !viewActive() || shown?.id != title.id) return@launch
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    EpisodeSequence.load(requireContext().filesDir, saved, seriesId, refresh = scope)
                }.getOrDefault(emptyList())
            }
            if (!viewActive() || shown?.id != title.id) return@launch
            if (loaded.isEmpty()) {
                binding.detailEpisodeStatus.visibility = View.VISIBLE
                binding.detailEpisodeStatus.text = getString(R.string.episodes_none)
                return@launch
            }
            presentSeries(loaded, focusSeasons = false)
            scope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(2_500L)
                val refreshed = runCatching {
                    EpisodeCache.peek(requireContext().filesDir, playlistId, seriesId)
                }.getOrNull()
                if (refreshed.isNullOrEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    if (viewActive() && shown?.id == title.id && refreshed.size != seriesEpisodes.size) {
                        presentSeries(refreshed, focusSeasons = false)
                    }
                }
            }
        }
    }

    private fun presentSeries(loaded: List<Channel>, focusSeasons: Boolean) {
        if (!viewActive() || loaded.isEmpty()) return
        val playable = FreshSeriesNav.playable(loaded)
        val grouped = FreshSeriesNav.bySeason(playable)
        val numbers = FreshSeriesNav.seasons(playable)
        val prefer = shown?.season
        if (numbers.isEmpty()) {
            seriesEpisodes = emptyList()
            episodesBySeason = emptyMap()
            seasonNumbers = emptyList()
            seasonAdapter.submit(emptyList())
            episodeAdapter.submit(emptyList())
            binding.detailEpisodeStatus.visibility = View.VISIBLE
            binding.detailEpisodeStatus.text = if (prefer != null && prefer > 0) {
                getString(R.string.fresh_season_missing, prefer)
            } else {
                getString(R.string.episodes_none)
            }
            return
        }
        if (prefer != null && prefer > 0 && prefer !in numbers) {
            seriesEpisodes = playable
            episodesBySeason = grouped
            seasonNumbers = numbers
            seasonAdapter.submit(numbers)
            episodeAdapter.submit(emptyList())
            binding.detailEpisodeStatus.visibility = View.VISIBLE
            binding.detailEpisodeStatus.text = getString(R.string.fresh_season_missing, prefer)
            if (focusSeasons) focusSeasonChip(0)
            return
        }
        seriesEpisodes = playable
        episodesBySeason = grouped
        season = FreshSeriesNav.pickSeason(numbers, season, prefer)
        binding.detailEpisodeStatus.visibility = View.GONE
        val seasonsChanged = !FreshSeriesNav.sameSeasons(seasonNumbers, numbers)
        seasonNumbers = numbers
        if (seasonsChanged) {
            seasonAdapter.submit(numbers)
        } else {
            seasonAdapter.refreshSelection()
        }
        val episodes = grouped[season].orEmpty()
        episodeAdapter.submit(episodes)
        updatePrimaryPlayButton(episodes)
        if (focusSeasons) {
            focusSeasonChip(numbers.indexOf(season).coerceAtLeast(0))
        }
    }

    private fun selectSeason(number: Int) {
        if (!viewActive() || number !in seasonNumbers) return
        if (number == season) return
        val previous = season
        season = number
        val token = ++seasonSwitchToken
        val episodes = episodesBySeason[number].orEmpty()
        val previousIndex = seasonNumbers.indexOf(previous)
        val nextIndex = seasonNumbers.indexOf(number)
        if (previousIndex >= 0) seasonAdapter.notifyItemChanged(previousIndex)
        if (nextIndex >= 0) seasonAdapter.notifyItemChanged(nextIndex)
        episodeAdapter.submit(episodes)
        updatePrimaryPlayButton(episodes)
        binding.detailEpisodeStatus.visibility =
            if (episodes.isEmpty()) View.VISIBLE else View.GONE
        if (episodes.isEmpty()) {
            binding.detailEpisodeStatus.text = getString(R.string.fresh_season_missing, number)
        }
        binding.detailEpisodeList.post {
            if (!viewActive() || token != seasonSwitchToken || shown == null) return@post
            if (nextIndex >= 0) {
                binding.detailSeasonList.scrollToPosition(nextIndex)
                binding.detailSeasonList.findViewHolderForAdapterPosition(nextIndex)?.itemView?.requestFocus()
            }
        }
    }

    private fun focusSeasonChip(index: Int) {
        if (index < 0) return
        binding.detailSeasonList.scrollToPosition(index)
        binding.detailSeasonList.post {
            if (!viewActive() || binding.freshDetail.visibility != View.VISIBLE) return@post
            val holder = binding.detailSeasonList.findViewHolderForAdapterPosition(index)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                binding.detailSeasonList.post {
                    if (viewActive()) {
                        binding.detailSeasonList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun openMovie(title: CatalogDb.FreshTitle) {
        if (opening || playlistId.isBlank() || title.id.isBlank()) return
        opening = true
        val app = requireActivity().application as OlympusIptvApp
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val channel = withContext(Dispatchers.IO) {
                    runCatching { app.catalogDb.readChannel(playlistId, title.bucket, title.id) }.getOrNull()
                }
                if (!viewActive() || requireActivity().isFinishing) return@launch
                if (channel == null) {
                    Toast.makeText(requireContext(), R.string.channel_missing, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val playing = if (channel.kind == ContentKind.LIVE) channel.copy(kind = ContentKind.VOD) else channel
                app.playbackQueue = listOf(playing)
                startActivity(
                    PlayerActivity.intent(
                        requireActivity(),
                        channel = playing,
                        channelMeta = title.detail,
                        channelNext = "",
                        startIndex = 0,
                        playlistId = playlistId
                    )
                )
            } finally {
                opening = false
            }
        }
    }

    private fun playEpisode(episode: Channel) {
        if (opening || playlistId.isBlank() || episode.id.isBlank()) return
        if (episode.streamUrl.isBlank()) {
            Toast.makeText(requireContext(), R.string.fresh_episode_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val wantSeason = episode.season ?: season
        val queue = FreshSeriesNav.playQueue(seriesEpisodes, wantSeason)
        if (queue.isEmpty() || queue.none { it.id == episode.id }) {
            Toast.makeText(requireContext(), R.string.fresh_episode_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        opening = true
        try {
            SeriesPlay.open(requireActivity(), playlistId, queue, episode)
        } finally {
            opening = false
        }
    }

    private fun bindArt(view: ImageView, url: String?, widthDp: Int, heightDp: Int) {
        if (url.isNullOrBlank()) {
            view.setImageResource(R.drawable.ic_channel_placeholder)
            return
        }
        val density = view.resources.displayMetrics.density
        val px = (widthDp.coerceAtLeast(heightDp) * density).toInt().coerceAtLeast(1)
        view.load(url) {
            channelLogo(px)
            scale(Scale.FIT)
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
            if (view === binding.detailLogo) {
                listener(onSuccess = { _, result ->
                    val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@listener
                    com.olympus.iptv.ui.BackdropGlow.apply(bitmap, binding.freshDetail)
                })
            }
        }
    }

    private fun toggleFavorite(title: CatalogDb.FreshTitle) {
        val app = requireActivity().application as OlympusIptvApp
        val key = if (title.bucket == CatalogDb.SERIES) "series:${title.id}" else "vod:${title.id}"
        val adding = key !in favoriteKeys
        if (adding) favoriteKeys.add(key) else favoriteKeys.remove(key)
        adapter.notifyDataSetChanged()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            app.playlistStore.setFavorite(playlistId, key, adding)
        }
        Toast.makeText(
            requireContext(),
            if (adding) R.string.pill_favorite_add else R.string.pill_favorite_remove,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun markWatched(title: CatalogDb.FreshTitle) {
        val app = requireActivity().application as OlympusIptvApp
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            app.catalogDb.markWatched(playlistId, title.id, if (title.bucket == CatalogDb.SERIES) title.id else null, title.season)
        }
        Toast.makeText(requireContext(), R.string.marked_watched, Toast.LENGTH_SHORT).show()
    }

    private inner class FreshAdapter : RecyclerView.Adapter<FreshAdapter.Holder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long {
            val row = rows.getOrNull(position) ?: return position.toLong()
            return stableRowId(row.bucket, row.id, row.service)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val rowBinding = ItemFreshBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(rowBinding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            rows.getOrNull(position)?.let { holder.bind(it) }
        }

        override fun getItemCount(): Int = rows.size

        inner class Holder(private val rowBinding: ItemFreshBinding) : RecyclerView.ViewHolder(rowBinding.root) {
            fun bind(title: CatalogDb.FreshTitle) {
                rowBinding.freshTitle.text = com.olympus.iptv.data.Titles.clean(title.name).ifBlank { title.name }
                rowBinding.freshService.text = if (title.day.isBlank()) {
                    title.service
                } else {
                    getString(R.string.fresh_when, title.service, title.day)
                }
                val preferTv = title.bucket == CatalogDb.SERIES
                val rating = com.olympus.iptv.playlist.TitleRatings.peek(title.name, preferTv)
                    ?.ratings?.firstOrNull()?.scoreLabel
                if (!rating.isNullOrBlank()) {
                    val number = rating.substringBefore('/').substringBefore('%').trim()
                    val service = rowBinding.freshService.text
                    rowBinding.freshService.text = "★ $number  $service"
                }

                val badgeNum = Regex("\\d+").find(title.detail)?.value
                when {
                    !badgeNum.isNullOrBlank() -> {
                        rowBinding.freshCountBadge.visibility = View.VISIBLE
                        rowBinding.freshCountBadge.text = badgeNum
                    }
                    title.bucket == CatalogDb.SERIES -> {
                        rowBinding.freshCountBadge.visibility = View.VISIBLE
                        rowBinding.freshCountBadge.text = "NEW"
                    }
                    else -> rowBinding.freshCountBadge.visibility = View.GONE
                }

                val key = if (title.bucket == CatalogDb.SERIES) "series:${title.id}" else "vod:${title.id}"
                val isFav = key in favoriteKeys
                rowBinding.btnCardFavorite.setImageResource(
                    if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                )
                rowBinding.btnCardFavorite.setOnClickListener { toggleFavorite(title) }
                rowBinding.btnCardWatched.setOnClickListener { markWatched(title) }
                rowBinding.btnCardMore.setOnClickListener { showDetail(title) }

                bindArt(rowBinding.freshPoster, title.logoUrl, 160, 220)

                val frame = rowBinding.freshPoster.parent as? View
                val width = rowBinding.root.width
                if (width > 0 && frame != null) {
                    val height = (width * 1.32f).toInt().coerceAtLeast(width)
                    if (frame.layoutParams.height != height) {
                        frame.layoutParams.height = height
                        frame.layoutParams = frame.layoutParams
                    }
                } else if (frame != null) {
                    rowBinding.root.post {
                        val w = rowBinding.root.width
                        if (w <= 0) return@post
                        val height = (w * 1.32f).toInt().coerceAtLeast(w)
                        if (frame.layoutParams.height != height) {
                            frame.layoutParams.height = height
                            frame.requestLayout()
                        }
                    }
                }

                val touch = com.olympus.iptv.ui.DeviceForm.isTouchPrimary(requireContext())
                rowBinding.root.setOnFocusChangeListener { _, hasFocus ->
                    rowBinding.freshHoverOverlay.visibility = if (hasFocus) View.VISIBLE else View.GONE
                    if (hasFocus) {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) listFocusPos = pos
                        if (!touch) com.olympus.iptv.ui.FocusMotion.animate(rowBinding.root, true)
                    } else {
                        if (!touch) com.olympus.iptv.ui.FocusMotion.animate(rowBinding.root, false)
                    }
                }
                rowBinding.root.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) listFocusPos = pos
                    showDetail(title)
                }
                rowBinding.root.setOnLongClickListener {
                    if (playlistId.isBlank()) return@setOnLongClickListener true
                    WatchQueue.offer(
                        requireActivity() as AppCompatActivity,
                        playlistId,
                        QueueItem(
                            bucket = title.bucket,
                            id = title.id,
                            name = title.name,
                            logoUrl = title.logoUrl,
                            seriesId = if (title.bucket == CatalogDb.SERIES) title.id else null
                        )
                    )
                    true
                }
            }
        }
    }

    private inner class SeasonAdapter : RecyclerView.Adapter<SeasonAdapter.Holder>() {
        private val numbers = ArrayList<Int>()

        fun submit(list: List<Int>) {
            numbers.clear()
            numbers.addAll(list)
            notifyDataSetChanged()
        }

        fun refreshSelection() {
            if (numbers.isEmpty()) return
            notifyItemRangeChanged(0, numbers.size)
        }

        override fun getItemId(position: Int): Long =
            numbers.getOrNull(position)?.toLong() ?: position.toLong()

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ItemSeasonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            numbers.getOrNull(position)?.let { holder.bind(it) }
        }

        override fun getItemCount(): Int = numbers.size

        inner class Holder(private val row: ItemSeasonBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(number: Int) {
                row.seasonName.text = getString(R.string.season_number, number)
                row.seasonCheck.visibility = View.GONE
                row.root.isSelected = number == season
                row.root.setOnClickListener {
                    val index = bindingAdapterPosition
                    if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                    selectSeason(number)
                }
            }
        }
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        private val items = ArrayList<Channel>()

        fun submit(list: List<Channel>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long =
            stableRowId(items.getOrNull(position)?.id)

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ItemFreshEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            items.getOrNull(position)?.let { holder.bind(it, position) }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val row: ItemFreshEpisodeBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(episode: Channel, position: Int) {
                val number = episode.number ?: (position + 1)
                val title = shown?.name.orEmpty()
                row.episodeTitle.text = ShowNames.episodeHeading(
                    title,
                    episode.season ?: season,
                    number,
                    episode.name
                )
                val runtime = episode.runtime?.takeIf { it.isNotBlank() }.orEmpty()
                row.episodeRuntimeBadge.text = runtime
                row.episodeRuntimeBadge.visibility = if (runtime.isBlank()) View.GONE else View.VISIBLE
                val aired = episode.aired?.takeIf { it.isNotBlank() }.orEmpty()
                row.episodeMeta.text = aired
                row.episodeMeta.visibility = if (aired.isBlank()) View.GONE else View.VISIBLE
                val plot = episode.blurb.orEmpty()
                row.episodePlot.text = plot
                row.episodePlot.visibility = if (plot.isBlank()) View.GONE else View.VISIBLE
                val art = episode.logoUrl?.takeIf { it.isNotBlank() } ?: shown?.logoUrl
                if (row.episodeArt.tag != art) {
                    row.episodeArt.tag = art
                    bindArt(row.episodeArt, art, 116, 66)
                }
                val play = View.OnClickListener { playEpisode(episode) }
                row.root.setOnClickListener(play)
                row.episodePlay.setOnClickListener(play)
            }
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val COLUMNS = 4
        private val SEASON_ONLY = Regex("Season \\d+\\.")

        fun newInstance(playlistId: String): FreshFragment =
            FreshFragment().apply {
                arguments = Bundle().apply { putString(EXTRA_PLAYLIST_ID, playlistId) }
            }
    }
}
