package com.olympus.iptv.ui.browse

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.ui.browse.BrowseActivity.GuideMenu
import com.olympus.iptv.ui.browse.BrowseActivity.GuideSection
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.CARD_SPAN
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.PAGE_SIZE_TV
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.PAGE_SIZE_PHONE
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.PAGE_SIZE_HUGE
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.LARGE_LIVE_GROUP
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.HUGE_PREFETCH_REMAIN
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.DEFAULT_PREFETCH_REMAIN
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.MAX_EMPTY_PAGE_SKIPS
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.FOCUS_SETTLE_MS
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.EPG_CACHE_CAP
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.olympus.iptv.BuildConfig
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.vpn.AppTunnel
import com.olympus.iptv.vpn.TunnelState
import com.olympus.iptv.vpn.VpnUi
import com.olympus.iptv.data.Category
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ChannelNames
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.Favorites
import com.olympus.iptv.data.EpgNowNext
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.ListOrder
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.data.ShowNames
import com.olympus.iptv.data.WatchEntry
import com.olympus.iptv.databinding.ActivityBrowseBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.CatchupUrls
import com.olympus.iptv.playlist.CountryIndex
import com.olympus.iptv.playlist.EpisodeCache
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.playlist.EpgRepository
import com.olympus.iptv.playlist.EpgUtils
import com.olympus.iptv.playlist.LineupApplier
import com.olympus.iptv.remind.RemindPrompt
import com.olympus.iptv.remind.ReminderStore
import com.olympus.iptv.playlist.GroupLock
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.playlist.LoadedPlaylist
import com.olympus.iptv.playlist.PinAlert
import com.olympus.iptv.playlist.PlaylistLoader
import com.olympus.iptv.playlist.TitleRatings
import com.olympus.iptv.playlist.XtreamClient
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.olympus.iptv.record.RecordActivity
import com.olympus.iptv.record.RecordPrompt
import com.olympus.iptv.record.RecordingService
import com.olympus.iptv.ui.FocusMotion
import com.olympus.iptv.ui.Haptics
import com.olympus.iptv.ui.epg.EpgEditsFragment
import com.olympus.iptv.ui.epg.ParentalFragment
import com.olympus.iptv.ui.epg.SanitizeFragment
import com.olympus.iptv.ui.login.LoginActivity
import com.olympus.iptv.ui.player.PlayerActivity
import com.olympus.iptv.ui.player.SeriesPlay
import com.olympus.iptv.ui.search.SearchActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.NumberFormat

internal fun BrowseActivity.indexSection(sectionNow: GuideSection, channels: List<Channel>) {
        if (channels.isEmpty()) return
        when (sectionNow) {
            GuideSection.TV -> if (liveIndexing || liveByCategory.isNotEmpty()) return
            GuideSection.MOVIES -> if (vodIndexing || vodByCategory.isNotEmpty()) return
            GuideSection.SHOWS -> if (seriesIndexing || seriesByCategory.isNotEmpty()) return
        }
        when (sectionNow) {
            GuideSection.TV -> liveIndexing = true
            GuideSection.MOVIES -> vodIndexing = true
            GuideSection.SHOWS -> seriesIndexing = true
        }
        val generation = when (sectionNow) {
            GuideSection.TV -> ++liveIndexGeneration
            GuideSection.MOVIES -> ++vodIndexGeneration
            GuideSection.SHOWS -> ++seriesIndexGeneration
        }
        lifecycleScope.launch(Dispatchers.Default) {
            val indexed = indexChannels(channels)
            withContext(Dispatchers.Main) {
                if (isDestroyed || generation != generationFor(sectionNow)) return@withContext
                when (sectionNow) {
                    GuideSection.TV -> {
                        liveByCategory = indexed.first
                        liveById = indexed.second
                        liveIndexing = false
                    }
                    GuideSection.MOVIES -> {
                        vodByCategory = indexed.first
                        vodById = indexed.second
                        vodIndexing = false
                    }
                    GuideSection.SHOWS -> {
                        seriesByCategory = indexed.first
                        seriesById = indexed.second
                        seriesIndexing = false
                    }
                }
                val showsNeedMovies = sectionNow == GuideSection.MOVIES &&
                    section == GuideSection.SHOWS &&
                    source?.seriesChannels.isNullOrEmpty()
                if (binding.groupColumn.visibility == View.VISIBLE && (section == sectionNow || showsNeedMovies)) {
                    showChannels()
                }
            }
        }
    }

internal fun BrowseActivity.ensureSectionIndex(sectionNow: GuideSection) {
        val data = source ?: return
        when (sectionNow) {
            GuideSection.TV -> if (liveByCategory.isEmpty()) indexSection(GuideSection.TV, data.liveChannels)
            GuideSection.MOVIES -> if (vodByCategory.isEmpty()) indexSection(GuideSection.MOVIES, data.vodChannels)
            GuideSection.SHOWS -> {
                val series = data.seriesChannels.orEmpty()
                if (series.isNotEmpty()) {
                    if (seriesByCategory.isEmpty()) indexSection(GuideSection.SHOWS, series)
                } else if (vodByCategory.isEmpty()) {
                    indexSection(GuideSection.MOVIES, data.vodChannels)
                }
            }
        }
    }

internal fun BrowseActivity.tightenNavRail() {
        val inner = binding.navRail.getChildAt(0) as? android.view.ViewGroup ?: return
        val labels = listOf(
            binding.btnSearch,
            binding.tabLive,
            binding.tabVod,
            binding.tabShows,
            binding.btnLibrary,
            binding.btnRecord,
            binding.btnAdmin,
            binding.refreshLine
        )
        var needed = 0
        val density = resources.displayMetrics.density
        val icon = 32 * density
        val measured = labels
        for (label in measured) {
            if (label.visibility != View.VISIBLE) continue
            val text = label.text?.toString().orEmpty()
            if (text.isEmpty()) continue
            val reach = inner.paddingLeft + label.paddingLeft + icon +
                label.paint.measureText(text) + label.paddingRight + inner.paddingRight
            if (reach > needed) needed = reach.toInt()
        }
        if (binding.playlistTitle.visibility == View.VISIBLE) {
            val title = binding.playlistTitle
            val header = inner.paddingLeft + title.paddingLeft +
                title.paint.measureText(title.text?.toString().orEmpty()) +
                title.paddingRight + inner.paddingRight
            if (header > needed) needed = header.toInt()
        }
        if (needed <= 0) return
        val params = binding.navRail.layoutParams
        if (params.width == needed) return
        params.width = needed
        binding.navRail.layoutParams = params
    }

internal fun BrowseActivity.openSection(next: GuideSection) {
        if (next == section && binding.groupColumn.visibility == View.VISIBLE && guideMenu == GuideMenu.GROUPS) {
            armGroupFocus()
            focusGroups()
            return
        }
        setSection(next)
    }

internal fun BrowseActivity.setSection(next: GuideSection) {
        landOnOpened = false
        guideMenu = GuideMenu.GROUPS
        previousMenu = GuideMenu.GROUPS
        armGroupFocus()
        // Prefer the Continue strip when Movies/Shows have something to resume.
        landOnContinue = next != GuideSection.TV
        section = next
        if (next != GuideSection.SHOWS) {
            episodePrefetchJob?.cancel()
            episodePrefetchIds = emptyList()
        }
        applyChannelLayout()
        kind = if (next == GuideSection.TV) ContentKind.LIVE else ContentKind.VOD
        selectedCategoryId = PlaylistLoader.FAVORITES_ID
        showGuide()
        binding.tabLive.isSelected = next == GuideSection.TV
        binding.tabVod.isSelected = next == GuideSection.MOVIES
        binding.tabShows.isSelected = next == GuideSection.SHOWS
        rememberGuideSection(next)
        if (renderedSection == next && loaded != null) {
            showGuide()
            paintHomeContinue()
            if (landOnContinue && binding.homeContinue.visibility == View.VISIBLE) {
                focusHomeContinue()
            } else {
                focusGroups()
            }
            return
        }
        categoryAdapter.submit(emptyList(), null)
        channelAdapter.submit(emptyList())
        binding.groupColumn.post { render(restoreFocus = true) }
    }

internal fun BrowseActivity.rememberGuideSection(next: GuideSection) {
        val key = when (next) {
            GuideSection.TV -> "live"
            GuideSection.MOVIES -> "vod"
            GuideSection.SHOWS -> "series"
        }
        (application as OlympusIptvApp).appScope.launch {
            runCatching { (application as OlympusIptvApp).playlistStore.saveLastGuideSection(key) }
        }
    }

internal fun BrowseActivity.showDevicePane() {
        epgPrefetchJob?.cancel()
        binding.btnAdmin.isSelected = true
        binding.btnAbout.isSelected = false
        binding.tabLive.isSelected = false
        binding.tabVod.isSelected = false
        binding.tabShows.isSelected = false
        binding.groupColumn.visibility = View.GONE
        binding.contentDivider.visibility = View.GONE
        binding.contentColumn.visibility = View.GONE
        binding.programPane.visibility = View.GONE
        binding.blankPane.visibility = View.GONE
        panes.hideAllSettingPanes()
        panes.ensureDevice().root.visibility = View.VISIBLE
        hidePreviewChrome()
        panes.deviceBack.post { panes.deviceBack.requestFocus() }
        val knownEmail = playlist?.email?.trim().orEmpty()
        if (knownEmail.isNotBlank()) panes.deviceEmailValue.text = knownEmail
        showDashboardUrl(LineupClient.peekDashboardUrl() ?: com.olympus.iptv.portal.DashboardHost.url())
        lifecycleScope.launch {
            if (knownEmail.isBlank()) {
                val email = (application as OlympusIptvApp).playlistStore.session.first()?.email?.trim().orEmpty()
                if (email.isNotBlank() && panes.isDeviceVisible()) {
                    panes.deviceEmailValue.text = email
                }
            }
            val url = runCatching { LineupClient(screen()).dashboardAddress() }.getOrNull()
            if (!url.isNullOrBlank() && panes.isDeviceVisible()) {
                showDashboardUrl(url)
            }
        }
    }

internal fun BrowseActivity.showAboutPane() {
        rememberReturnScreen()
        epgPrefetchJob?.cancel()
        binding.btnAbout.isSelected = true
        binding.btnAdmin.isSelected = false
        binding.tabLive.isSelected = false
        binding.tabVod.isSelected = false
        binding.tabShows.isSelected = false
        binding.groupColumn.visibility = View.GONE
        binding.contentDivider.visibility = View.GONE
        binding.contentColumn.visibility = View.GONE
        binding.programPane.visibility = View.GONE
        binding.blankPane.visibility = View.GONE
        panes.hideAllSettingPanes()
        panes.ensureAbout().root.visibility = View.VISIBLE
        hidePreviewChrome()
        updateGroupClock()
        paintBreadcrumb(getString(R.string.nav_about))
        if (touchPrimary) {
            binding.mobileTopBar.visibility = View.VISIBLE
            binding.mobileBack.visibility = View.VISIBLE
            binding.mobileTitle.text = getString(R.string.nav_about)
            paintBottomTabs()
            paintMobileSettingsChips()
        }
    }

internal fun BrowseActivity.showAdminPane(focus: View = panes.cardAdminRefresh) {
        rememberReturnScreen()
        epgPrefetchJob?.cancel()
        binding.btnAdmin.isSelected = true
        binding.btnAbout.isSelected = false
        binding.tabLive.isSelected = false
        binding.tabVod.isSelected = false
        binding.tabShows.isSelected = false
        binding.groupColumn.visibility = View.GONE
        binding.contentDivider.visibility = View.GONE
        binding.contentColumn.visibility = View.GONE
        binding.programPane.visibility = View.GONE
        binding.blankPane.visibility = View.GONE
        panes.hideAllSettingPanes()
        panes.ensureAdmin().root.visibility = View.VISIBLE
        hidePreviewChrome()
        updateGroupClock()
        paintAdminParentalSummary()
        paintBreadcrumb(getString(R.string.nav_admin))
        if (touchPrimary) {
            binding.mobileTopBar.visibility = View.VISIBLE
            binding.mobileBack.visibility = View.VISIBLE
            binding.mobileTitle.text = getString(R.string.nav_admin)
            paintBottomTabs()
            paintMobileSettingsChips()
        }
        focus.post { if (focus.isShown) focus.requestFocus() }
    }

internal fun BrowseActivity.paintAdminParentalSummary() {
        if (!viewReady()) return
        lifecycleScope.launch {
            val prefs = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.devicePrefs()
            }
            if (isDestroyed) return@launch
            val locked = activeConfig.groupPins.orEmpty().size
            val base = if (locked <= 0) {
                getString(R.string.admin_parental_none, prefs.parentalTimeoutMinutes)
            } else {
                getString(R.string.admin_parental_summary, locked, prefs.parentalTimeoutMinutes)
            }
            val quiet = getString(if (prefs.quietHours) R.string.admin_quiet_on else R.string.admin_quiet_off)
            panes.adminOrNull?.let { admin ->
                admin.adminParentalSummary.text = base + quiet
                admin.adminQuietToggle.text = getString(
                    if (prefs.quietHours) R.string.quiet_hours_on else R.string.quiet_hours_off
                )
                admin.adminQuietSchedule.text = getString(
                    R.string.admin_quiet_schedule,
                    clockLabel(prefs.quietStartMinute),
                    clockLabel(prefs.quietEndMinute)
                )
            }
            paintParentalChip(locked)
            paintLastGroupChip()
            maybeShowBackupTip()
        }
    }

internal fun BrowseActivity.clockLabel(minuteOfDay: Int): String {
        val hour = (minuteOfDay / 60) % 24
        val minute = minuteOfDay % 60
        val am = hour < 12
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format("%d:%02d %s", h12, minute, if (am) "AM" else "PM")
    }

internal fun BrowseActivity.toggleAdminQuietHours() {
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val prefs = withContext(Dispatchers.IO) { store.devicePrefs() }
            withContext(Dispatchers.IO) {
                store.saveQuietHours(
                    !prefs.quietHours,
                    prefs.quietStartMinute,
                    prefs.quietEndMinute
                )
            }
            com.olympus.iptv.remind.ReminderClock.arm(screen())
            paintAdminParentalSummary()
        }
    }

internal fun BrowseActivity.cycleAdminQuietSchedule() {
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val prefs = withContext(Dispatchers.IO) { store.devicePrefs() }
            val presets = listOf(
                22 * 60 to 7 * 60,
                23 * 60 to 7 * 60,
                0 to 6 * 60,
                21 * 60 to 8 * 60
            )
            val index = presets.indexOfFirst {
                it.first == prefs.quietStartMinute && it.second == prefs.quietEndMinute
            }
            val next = presets[(index + 1).coerceAtLeast(0) % presets.size]
            withContext(Dispatchers.IO) {
                store.saveQuietHours(prefs.quietHours || true, next.first, next.second)
            }
            com.olympus.iptv.remind.ReminderClock.arm(screen())
            paintAdminParentalSummary()
        }
    }

internal fun BrowseActivity.openBackupShortcut() {
        Haptics.tick(panes.adminBackupLink)
        showDevicePane()
        Toast.makeText(this, R.string.admin_backup_detail, Toast.LENGTH_LONG).show()
    }

internal fun BrowseActivity.offerVpnConsent() {
        if (!AppTunnel.needsConsent || isDestroyed) return
        val consent = android.net.VpnService.prepare(this)
        AppTunnel.needsConsent = false
        if (consent != null) vpnConsent.launch(consent) else AppTunnel.connect(this)
    }

internal fun BrowseActivity.visibleFocusId(): String? {
        val slot = currentFocus?.tag as? ProgramGuideAdapter.Slot
        if (slot != null) return visibleChannels.getOrNull(slot.row)?.id
        val focus = currentFocus ?: return null
        val index = binding.channelList.findContainingViewHolder(focus)?.bindingAdapterPosition ?: -1
        return visibleChannels.getOrNull(index)?.id
    }

internal fun BrowseActivity.alignGuideToSystemClock() {
        if (!guideReady() || !viewReady()) return
        if (section != GuideSection.TV || binding.programPane.visibility != View.VISIBLE) {
            styleGuideHeader()
            return
        }
        val now = com.olympus.iptv.playlist.EpgUtils.now()
        val start = now / halfHourMs * halfHourMs
        programAdapter.setWindow(start, start + 8L * 60L * 60L * 1000L)
        programAdapter.scrollX = 0
        if (visibleChannels.isNotEmpty()) programAdapter.submit(visibleChannels)
        binding.programHeader.removeAllViews()
        buildProgramHeader()
        scrollProgramGuide(0)
        styleGuideHeader()
    }

internal fun BrowseActivity.sectionKey(sectionNow: GuideSection): String = when (sectionNow) {
        GuideSection.TV -> "live"
        GuideSection.MOVIES -> "vod"
        GuideSection.SHOWS -> "series"
    }

internal fun BrowseActivity.entrySection(entry: WatchEntry): String {
        val raw = entry.section?.trim().orEmpty()
        return if (raw.isEmpty()) "live" else raw
    }

internal fun BrowseActivity.focusIsIn(root: View): Boolean {
        var current = currentFocus
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

internal fun BrowseActivity.setupMobileChrome() {
        binding.mobileTopBar.visibility = View.VISIBLE
        binding.mobileBottomTabs.visibility = View.VISIBLE
        binding.mobileSectionRow.visibility = View.GONE
        binding.mobileBack.setOnClickListener { onMobileBack() }
        binding.mobileTitle.setOnClickListener { /* section switching is the bottom tabs */ }
        binding.mobileAdmin.setOnClickListener { showAdminPane() }
        binding.mobileAbout.setOnClickListener { showAboutPane() }
        binding.mobileSearch.setOnClickListener { openSearch() }
        binding.groupsSearch.visibility = View.VISIBLE
        binding.groupsSearch.setOnClickListener { openSearch() }
        binding.groupsFilter.visibility = View.VISIBLE
        binding.groupsFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                categoryAdapter.setFilter(s?.toString().orEmpty())
            }
        })
        binding.nowPlayingResume.setOnClickListener { resumeNowPlaying() }
        binding.nowPlayingBar.setOnClickListener { resumeNowPlaying() }
        binding.groupsRefresh.isEnabled = true
        binding.groupsRefresh.setColorSchemeResources(R.color.accent)
        binding.groupsRefresh.setProgressBackgroundColorSchemeResource(R.color.bg_elevated)
        binding.groupsRefresh.setOnRefreshListener {
            Haptics.tick(binding.groupsRefresh)
            refreshPlaylist(quiet = true)
        }
        paintNowPlayingBar()
        val openLive = View.OnClickListener { openSection(GuideSection.TV) }
        val openMovies = View.OnClickListener { openSection(GuideSection.MOVIES) }
        val openShows = View.OnClickListener { openSection(GuideSection.SHOWS) }
        binding.mobileTabLive.setOnClickListener(openLive)
        binding.mobileTabMovies.setOnClickListener(openMovies)
        binding.mobileTabShows.setOnClickListener(openShows)
        binding.bottomTabLive.setOnClickListener(openLive)
        binding.bottomTabMovies.setOnClickListener(openMovies)
        binding.bottomTabShows.setOnClickListener(openShows)
        paintBottomTabs()
        paintMobileSettingsChips()
    }

internal fun BrowseActivity.paintBottomTabs() {
        if (!touchPrimary || !viewReady()) return
        val settingsOpen = panes.isAdminVisible() ||
            panes.isAboutVisible()
        binding.bottomTabLive.isSelected = !settingsOpen && section == GuideSection.TV
        binding.bottomTabMovies.isSelected = !settingsOpen && section == GuideSection.MOVIES
        binding.bottomTabShows.isSelected = !settingsOpen && section == GuideSection.SHOWS
        binding.bottomTabLive.setTextColor(
            getColor(
                if (!settingsOpen && section == GuideSection.TV) R.color.accent else R.color.text_primary
            )
        )
        binding.bottomTabMovies.setTextColor(
            getColor(
                if (!settingsOpen && section == GuideSection.MOVIES) R.color.accent else R.color.text_primary
            )
        )
        binding.bottomTabShows.setTextColor(
            getColor(
                if (!settingsOpen && section == GuideSection.SHOWS) R.color.accent else R.color.text_primary
            )
        )
    }

internal fun BrowseActivity.paintMobileSettingsChips() {
        if (!touchPrimary || !viewReady()) return
        val adminOpen = panes.isAdminVisible()
        val aboutOpen = panes.isAboutVisible()
        binding.mobileAdmin.isSelected = adminOpen
        binding.mobileAbout.isSelected = aboutOpen
        binding.mobileAdmin.setTextColor(
            getColor(if (adminOpen) R.color.accent else R.color.text_primary)
        )
        binding.mobileAbout.setTextColor(
            getColor(if (aboutOpen) R.color.accent else R.color.text_primary)
        )
    }

internal fun BrowseActivity.onMobileBack() {
        when {
            binding.groupPinWrong.visibility == View.VISIBLE -> dismissWrongPinToGroups()
            binding.groupPin.visibility == View.VISIBLE -> cancelGroupPinToGroups()
            guideMenu == GuideMenu.CHANNELS || guideMenu == GuideMenu.PROGRAMS -> {
                rememberGroupScroll()
                showMenu(GuideMenu.GROUPS)
                armGroupFocus()
                focusGroups()
            }
            panes.isDeviceVisible() ||
                panes.isAboutVisible() ||
                panes.isAdminVisible() ||
                panes.isRefreshVisible() ||
                panes.isVpnVisible() -> {
                openSection(section)
            }
            else -> Unit
        }
    }

internal fun BrowseActivity.showMenu(next: GuideMenu) {
        if (next == GuideMenu.PROGRAMS && section != GuideSection.TV) return
        if (next == guideMenu) return
        if ((guideMenu == GuideMenu.CHANNELS || guideMenu == GuideMenu.PROGRAMS) &&
            next == GuideMenu.GROUPS
        ) {
            rememberGroupScroll()
        }
        previousMenu = guideMenu
        guideMenu = next
        val guideUp = binding.groupColumn.visibility == View.VISIBLE ||
            binding.contentColumn.visibility == View.VISIBLE ||
            binding.programPane.visibility == View.VISIBLE
        if (guideUp) applyGuideColumns()
    }

internal fun BrowseActivity.setNavExpanded(expanded: Boolean) {
        if (expanded != navExpanded) {
            navExpanded = expanded
            val items = listOf(
                binding.btnSearch,
                binding.tabLive,
                binding.tabVod,
                binding.tabShows,
                binding.btnRecord,
                binding.btnAdmin,
                binding.btnAbout
            )
            for (view in items) {
                if (view.id !in navLabels) navLabels[view.id] = view.text
                view.text = if (expanded) navLabels[view.id] else ""
            }
            binding.playlistTitle.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        if (!expanded) {
            val params = binding.navRail.layoutParams
            val width = (72 * resources.displayMetrics.density).toInt()
            if (params.width != width) {
                params.width = width
                binding.navRail.layoutParams = params
            }
            return
        }
        tightenNavRail()
    }

internal fun BrowseActivity.syncGroupPills() {
        val row = binding.groupPillRow
        val categories = categoryAdapter.categories()
        val same = row.childCount == categories.size && categories.indices.all { index ->
            row.getChildAt(index).tag == categories[index].id
        }
        if (!same) {
            row.removeAllViews()
            val density = resources.displayMetrics.density
            val padH = (14 * density).toInt()
            val padV = (8 * density).toInt()
            val gap = (8 * density).toInt()
            for (category in categories) {
                val selected = category.id == selectedCategoryId
                row.addView(TextView(this).apply {
                    tag = category.id
                    text = com.olympus.iptv.data.NameImages.strip(category.name)
                    textSize = 13f
                    isFocusable = false
                    isSelected = selected
                    setPadding(padH, padV, padH, padV)
                    setTextColor(getColor(if (selected) R.color.bg else R.color.text_primary))
                    setBackgroundResource(R.drawable.bg_group_pill)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = gap }
                })
            }
        } else {
            for (index in categories.indices) {
                val pill = row.getChildAt(index) as TextView
                val selected = categories[index].id == selectedCategoryId
                pill.isSelected = selected
                pill.setTextColor(getColor(if (selected) R.color.bg else R.color.text_primary))
            }
        }
        val selectedIndex = categories.indexOfFirst { it.id == selectedCategoryId }
        if (selectedIndex >= 0) {
            row.getChildAt(selectedIndex)?.let { child ->
                binding.groupPills.post { binding.groupPills.smoothScrollTo(child.left, 0) }
            }
        }
    }

internal fun BrowseActivity.focusOpenedGuide() {
        if (!landOnOpened) return
        if (channelsStamp != openStamp) {
            retryOpenedFocus()
            return
        }
        if (visibleChannels.isEmpty()) {
            landOnOpened = false
            openFocusTries = 0
            return
        }
        if (section == GuideSection.TV && touchPrimary) {
            if (binding.contentColumn.visibility != View.VISIBLE) {
                retryOpenedFocus()
                return
            }
            if (titleFocusHeld()) {
                landOnOpened = false
                openFocusTries = 0
                return
            }
            focusFirstTitle()
            return
        }
        if (section == GuideSection.TV && binding.programPane.visibility != View.VISIBLE) {
            retryOpenedFocus()
            return
        }
        if (section != GuideSection.TV && binding.contentColumn.visibility != View.VISIBLE) {
            retryOpenedFocus()
            return
        }
        if (section == GuideSection.TV) {
            if (programAdapter.itemCount == 0) {
                retryOpenedFocus()
                return
            }
            if (programFocusHeld()) {
                landOnOpened = false
                openFocusTries = 0
                return
            }
            if (waitingOnGuide()) return
            focusCurrentProgram(0)
            return
        }
        if (titleFocusHeld()) {
            landOnOpened = false
            openFocusTries = 0
            return
        }
        focusFirstTitle()
    }

internal fun BrowseActivity.retryOpenedFocus() {
        if (!landOnOpened || openFocusTries >= 80) return
        openFocusTries += 1
        focusHandler.postDelayed({ focusOpenedGuide() }, 40L)
    }

internal fun BrowseActivity.focusCurrentProgram(row: Int) {
        binding.programList.post {
            val count = programAdapter.itemCount
            if (count == 0) {
                if (landOnOpened && openFocusTries < 40) {
                    openFocusTries++
                    focusCurrentProgram(row)
                }
                return@post
            }
            val safe = row.coerceIn(0, count - 1)
            val channel = visibleChannels.getOrNull(safe) ?: return@post
            syncProgramRowHeight()
            val nowX = programAdapter.pxFor(com.olympus.iptv.playlist.EpgUtils.now())
            val pad = (48 * resources.displayMetrics.density).toInt()
            scrollProgramGuide((nowX - pad).coerceAtLeast(0))
            focusProgram(safe, programAdapter.columnAt(channel, com.olympus.iptv.playlist.EpgUtils.now()))
        }
    }

internal fun BrowseActivity.guideFocusTime(slot: ProgramGuideAdapter.Slot): Long {
        val now = com.olympus.iptv.playlist.EpgUtils.now()
        val channel = visibleChannels.getOrNull(slot.row) ?: return now
        val program = programAdapter.programAt(channel.id, slot.column) ?: return now
        if (now >= program.startMillis && now < program.endMillis) return now
        val atScroll = if (programAdapter.slotPx > 0) {
            programAdapter.windowStart +
                (programAdapter.scrollX.toLong() * halfHourMs / programAdapter.slotPx)
        } else {
            programAdapter.windowStart
        }
        if (atScroll >= program.startMillis && atScroll < program.endMillis) return atScroll
        return program.startMillis.coerceIn(
            programAdapter.windowStart,
            (programAdapter.windowEnd - 1L).coerceAtLeast(programAdapter.windowStart)
        )
    }

internal fun BrowseActivity.focusProgramAtTime(row: Int, time: Long) {
        val count = programAdapter.itemCount
        if (count == 0) return
        val safeRow = ((row % count) + count) % count
        val channel = visibleChannels.getOrNull(safeRow) ?: return
        focusProgram(safeRow, programAdapter.columnAt(channel, time))
    }


internal fun BrowseActivity.focusOnGuideRow(): Boolean =
        focusIsIn(binding.channelList) || focusIsIn(binding.programPane)

internal fun BrowseActivity.focusedCategory(): Category? {
        val item = binding.categoryList.findContainingItemView(currentFocus ?: return null) ?: return null
        val pos = binding.categoryList.getChildAdapterPosition(item)
        return categoryAdapter.categories().getOrNull(pos)
    }

internal fun BrowseActivity.scheduleGuideFocusRestore(action: () -> Unit) {
        guideFocusRestore?.let { focusHandler.removeCallbacks(it) }
        val next = Runnable { guideFocusRestore = null; action() }
        guideFocusRestore = next
        focusHandler.postDelayed(next, 48L)
    }


internal fun BrowseActivity.programFocusHeld(): Boolean {
        val slot = currentFocus?.tag as? ProgramGuideAdapter.Slot ?: return false
        if (slot.row != 0 || !focusIsIn(binding.programPane)) return false
        val channel = visibleChannels.getOrNull(0) ?: return false
        if (programAdapter.programsFor(channel.id) == null) return false
        return slot.column == programAdapter.columnAt(channel, com.olympus.iptv.playlist.EpgUtils.now())
    }

internal fun BrowseActivity.focusProgram(row: Int, column: Int, retry: Boolean = true) {
        val count = programAdapter.itemCount
        if (count == 0) return
        val safeRow = ((row % count) + count) % count
        guideScrollSync = true
        binding.programList.scrollToPosition(safeRow)
        binding.channelList.scrollToPosition(safeRow)
        binding.programList.post {
            guideScrollSync = false
            val track = binding.programList.findViewHolderForAdapterPosition(safeRow)
                ?.itemView
                ?.findViewById<android.widget.FrameLayout>(R.id.programTrack)
            if (track == null || track.childCount == 0) {
                if ((retry || landOnOpened) && openFocusTries < 40) {
                    openFocusTries++
                    binding.programList.post { focusProgram(safeRow, column, false) }
                }
                return@post
            }
            val target = track.getChildAt(column.coerceIn(0, track.childCount - 1))
            if (!target.requestFocus() || !target.hasFocus()) {
                if ((retry || landOnOpened) && openFocusTries < 40) {
                    openFocusTries++
                    binding.programList.post { focusProgram(safeRow, column, false) }
                }
                return@post
            }
            val channel = visibleChannels.getOrNull(safeRow)
            val listed = channel != null && programAdapter.programsFor(channel.id) != null
            if (landOnOpened && channelsStamp == openStamp && listed && safeRow == 0) {
                landOnOpened = false
                openFocusTries = 0
            } else if (!landOnOpened) {
                openFocusTries = 0
            }
        }
    }

internal fun BrowseActivity.focusRowAt(list: androidx.recyclerview.widget.RecyclerView, index: Int, focusId: Int) {
        if (index < 0) return
        list.scrollToPosition(index)
        list.post {
            if (list === binding.channelList) alignGuide(binding.channelList, binding.programList)
            if (list === binding.programList) alignGuide(binding.programList, binding.channelList)
            val row = list.findViewHolderForAdapterPosition(index)?.itemView
            if (row == null) {
                list.post { focusRow(list.findViewHolderForAdapterPosition(index)?.itemView, focusId) }
            } else {
                focusRow(row, focusId)
            }
        }
    }

internal fun BrowseActivity.focusRow(row: View?, focusId: Int) {
        if (row == null) return
        val same = if (focusId != View.NO_ID) row.findViewById<View>(focusId) else null
        val play = row.findViewById<View>(R.id.playTarget)
        val target = listOf(same, play, row).firstOrNull { it != null && it.isShown && it.isFocusable }
        target?.requestFocus()
    }

internal fun BrowseActivity.navItems(): List<View> = listOf(
        binding.btnSearch,
        binding.tabLive,
        binding.tabVod,
        binding.tabShows,
        binding.btnRecord,
        binding.btnAdmin,
        binding.btnAbout
    )

internal fun BrowseActivity.focusNav(direction: Int) {
        val items = navItems().filter { it.visibility == View.VISIBLE }
        val index = items.indexOf(currentFocus)
        val next = if (index < 0) 0 else index + direction
        if (next in items.indices) items[next].requestFocus()
    }

internal fun BrowseActivity.sectionButton(): View = when (section) {
        GuideSection.TV -> binding.tabLive
        GuideSection.MOVIES -> binding.tabVod
        GuideSection.SHOWS -> binding.tabShows
    }


internal fun BrowseActivity.cardIcon(category: Category): Int = when (category.id) {
        PlaylistLoader.RECENT_ID, PlaylistLoader.CONTINUE_ID, PlaylistLoader.TOP_WATCHED_ID ->
            R.drawable.ic_card_play
        PlaylistLoader.FAVORITES_ID -> R.drawable.ic_star
        PlaylistLoader.ALL_ID -> R.drawable.ic_nav_tv
        else -> R.drawable.ic_card_layers
    }

internal fun BrowseActivity.paintBreadcrumb(leaf: String? = null) {
        if (!viewReady()) return
        val sectionLabel = when {
            panes.isAdminVisible() -> getString(R.string.breadcrumb_admin)
            panes.isAboutVisible() -> getString(R.string.nav_about)
            panes.isDeviceVisible() -> getString(R.string.nav_device)
            panes.isVpnVisible() -> getString(R.string.vpn_card)
            panes.isRefreshVisible() -> getString(R.string.refresh_playlist)
            section == GuideSection.MOVIES -> getString(R.string.vod)
            section == GuideSection.SHOWS -> getString(R.string.nav_shows)
            else -> getString(R.string.breadcrumb_live)
        }
        val tip = leaf?.takeIf { it.isNotBlank() } ?: selectedCategoryName.takeIf { it.isNotBlank() }
        binding.contentTitle.text = if (tip.isNullOrBlank() || tip.equals(sectionLabel, ignoreCase = true)) {
            sectionLabel
        } else {
            "$sectionLabel › $tip"
        }
        binding.contentTitle.setTextColor(getColor(R.color.text_primary))
        binding.contentTitle.textSize = 15f
        binding.contentTitle.setTypeface(null, android.graphics.Typeface.BOLD)
    }

internal fun BrowseActivity.cardDetail(category: Category): String {
        val titles = section != GuideSection.TV
        val base = when (category.id) {
            PlaylistLoader.RECENT_ID -> {
                val wanted = sectionKey(section)
                val count = recentEntries.count { entrySection(it) == wanted }
                if (count > 0) getString(R.string.recent_count, count) else getString(R.string.recent_card)
            }
            PlaylistLoader.CONTINUE_ID -> {
                val wanted = sectionKey(section)
                val count = recentEntries.count { entrySection(it) == wanted && it.canResume() }
                if (count > 0) getString(R.string.continue_count, count) else getString(R.string.continue_card)
            }
            PlaylistLoader.TOP_WATCHED_ID -> getString(R.string.top_watched_card)
            PlaylistLoader.FAVORITES_ID -> {
                val prefix = favoriteSection(section) + ":"
                val label = if (titles) R.string.group_titles else R.string.group_channels
                getString(label, favoriteIds.count { it.startsWith(prefix) })
            }
            else -> {
                val count = groupCounts[category.id]
                if (count == null) ""
                else {
                    val label = if (titles) R.string.group_titles else R.string.group_channels
                    getString(label, count)
                }
            }
        }
        if (!GroupLock.locked(activeConfig, category.id)) return base
        val locked = getString(R.string.group_locked)
        return if (base.isBlank()) locked else "$base · $locked"
    }

internal fun BrowseActivity.favoriteSection(sectionNow: GuideSection): String = when (sectionNow) {
        GuideSection.TV -> Favorites.LIVE
        GuideSection.MOVIES -> Favorites.VOD
        GuideSection.SHOWS -> Favorites.SERIES
    }

internal fun BrowseActivity.openYoutubeTrailer(name: String, preferTv: Boolean) {
        if (BuildConfig.TMDB_API_KEY.isBlank()) {
            Toast.makeText(this, R.string.tmdb_missing_key, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val trailer = withContext(Dispatchers.IO) {
                TitleRatings.trailerFor(name, preferTv)
            }
            if (isDestroyed) return@launch
            if (trailer == null) {
                Toast.makeText(screen(), R.string.trailer_missing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${trailer.key}"))
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${trailer.key}"))
            runCatching { startActivity(appIntent) }.recoverCatching { startActivity(webIntent) }
                .onFailure {
                    Toast.makeText(screen(), R.string.trailer_missing, Toast.LENGTH_SHORT).show()
                }
        }
    }
