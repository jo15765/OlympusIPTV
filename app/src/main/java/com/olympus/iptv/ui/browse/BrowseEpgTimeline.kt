package com.olympus.iptv.ui.browse

import android.view.View
import com.olympus.iptv.R
import com.olympus.iptv.ui.browse.BrowseActivity.GuideMenu
import com.olympus.iptv.ui.browse.BrowseActivity.GuideSection

/** EPG header clock and horizontal timeline scrolling. */
internal fun BrowseActivity.tickGuideHeader() {
        if (binding.guideDate.visibility != View.VISIBLE) return
        val now = com.olympus.iptv.playlist.EpgUtils.now()
        val half = now / halfHourMs
        if (half == guideHeaderHalfHour) {
            positionNowLine(programAdapter.scrollX)
            return
        }
        styleGuideHeader()
    }

internal fun BrowseActivity.styleGuideHeader() {
        val guide = binding.programPane.visibility == View.VISIBLE && section == GuideSection.TV
        val density = resources.displayMetrics.density
        val height = ((if (guide) 48 else 36) * density).toInt()
        val channelHeader = binding.channelHeader.layoutParams
        if (channelHeader.height != height) {
            channelHeader.height = height
            binding.channelHeader.layoutParams = channelHeader
        }
        val programHeader = binding.programHeaderScroll.layoutParams
        if (guide && programHeader.height != height) {
            programHeader.height = height
            binding.programHeaderScroll.layoutParams = programHeader
        }
        binding.guideDate.visibility = if (guide) View.VISIBLE else View.GONE
        binding.guideNow.visibility = if (guide) View.VISIBLE else View.GONE
        if (selectedCategoryName.isEmpty()) return
        if (guide) {
            val now = com.olympus.iptv.playlist.EpgUtils.now()
            guideHeaderHalfHour = now / halfHourMs
            binding.guideDate.text = com.olympus.iptv.playlist.EpgUtils.formatWhen(now)
        } else {
            guideHeaderHalfHour = -1L
        }
        paintBreadcrumb(selectedCategoryName)
    }

internal fun BrowseActivity.jumpGuideToNow() {
        if (binding.programPane.visibility != View.VISIBLE || section != GuideSection.TV) return
        ensureProgramGuide()
        val now = com.olympus.iptv.playlist.EpgUtils.now()
        val start = GuideMetrics.start(now, halfHourMs)
        val end = GuideMetrics.end(start)
        if (programAdapter.setWindow(start, end)) {
            buildProgramHeader()
        }
        val row = when {
            focusIsIn(binding.programPane) ->
                (currentFocus?.tag as? ProgramGuideAdapter.Slot)?.row ?: focusedChannelRow()
            focusIsIn(binding.channelList) -> focusedChannelRow()
            else -> focusedChannelRow()
        }
        showMenu(GuideMenu.PROGRAMS)
        focusCurrentProgram(row)
    }

internal fun BrowseActivity.buildProgramHeader() {
        browseModel.rememberEpgWindow(programAdapter.windowStart, programAdapter.windowEnd)
        epgCtl.buildHeader()
    }

internal fun BrowseActivity.scrollProgramGuide(x: Int) {
        if (guideScrollSync) return
        guideScrollSync = true
        programAdapter.scrollX = x
        binding.programHeaderScroll.scrollTo(x, 0)
        positionNowLine(x)
        for (index in 0 until binding.programList.childCount) {
            val row = binding.programList.getChildAt(index)
            row.findViewById<android.widget.HorizontalScrollView>(R.id.programRowScroll)?.scrollTo(x, 0)
        }
        if (kotlin.math.abs(x - lastLabelShiftScroll) >= 12) {
            lastLabelShiftScroll = x
            val needTall = programAdapter.refreshLabelShift(binding.programList)
            applyGuideRowHeight(needTall)
        }
        guideScrollSync = false
    }

internal fun BrowseActivity.positionNowLine(scrollX: Int) {
        if (epgReady()) epgCtl.positionNowLine(scrollX)
    }

