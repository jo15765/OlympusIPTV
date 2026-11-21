package com.olympus.iptv.ui.browse

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.EpgNowNext
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.databinding.ActivityBrowseBinding
import com.olympus.iptv.playlist.EpgUtils

/**
 * EPG header, NOW line, and synopsis dock — kept out of BrowseActivity's mega-file.
 */
class BrowseEpgController(
    private val activity: AppCompatActivity,
    private val binding: ActivityBrowseBinding,
    private val programAdapter: ProgramGuideAdapter,
    private val halfHourMs: Long,
    private val epgCache: () -> Map<String, EpgNowNext>
) {
    var synopsisChannel: Channel? = null
        private set
    var synopsisProgram: EpgProgram? = null
        private set

    fun buildHeader() {
        val header = binding.programHeader
        header.removeAllViews()
        val start = programAdapter.windowStart
        val count = ((programAdapter.windowEnd - start) / halfHourMs).toInt().coerceAtLeast(1)
        val width = programAdapter.timelineWidth()
        val nowIndex = ((EpgUtils.now() - start) / halfHourMs).toInt()
        val times = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(width, 0, 1f)
        }
        repeat(count) { index ->
            times.addView(TextView(activity).apply {
                text = EpgUtils.formatClock(start + index * halfHourMs)
                gravity = Gravity.CENTER
                setTextColor(
                    activity.getColor(if (index == nowIndex) R.color.accent else R.color.text_secondary)
                )
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(programAdapter.slotPx, ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
        val density = activity.resources.displayMetrics.density
        val rule = android.widget.FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(width, (8 * density).toInt())
        }
        rule.addView(View(activity).apply {
            setBackgroundColor(activity.getColor(R.color.stroke))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                density.toInt().coerceAtLeast(1)
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        })
        val dot = (4 * density).toInt()
        repeat(count) { index ->
            rule.addView(View(activity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(activity.getColor(if (index == nowIndex) R.color.accent else R.color.text_secondary))
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(dot, dot).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = index * programAdapter.slotPx + programAdapter.slotPx / 2 - dot / 2
                }
            })
        }
        header.addView(times)
        header.addView(rule)
        binding.programHeaderScroll.scrollTo(programAdapter.scrollX, 0)
        positionNowLine(programAdapter.scrollX)
    }

    fun positionNowLine(scrollX: Int) {
        val line = binding.programNowLine
        val x = programAdapter.pxFor(EpgUtils.now()) - scrollX
        val width = binding.programList.width
        if (width > 0 && x in 0..width) {
            line.visibility = View.VISIBLE
            line.translationX = (x - 1).toFloat()
            line.alpha = 1f
        } else {
            line.visibility = View.INVISIBLE
        }
    }

    fun paintSynopsis(channel: Channel, program: EpgProgram?) {
        val channelAdapter = binding.channelList.adapter as? ChannelAdapter
        if (binding.programPane.visibility != View.VISIBLE) {
            synopsisChannel = null
            synopsisProgram = null
            channelAdapter?.setGuideFocused(null)
            return
        }
        val now = EpgUtils.now()
        synopsisChannel = channel
        synopsisProgram = program ?: epgCache()[channel.id]?.now
                ?: programAdapter.programsFor(channel.id)?.firstOrNull { now >= it.startMillis && now < it.endMillis }
        channelAdapter?.setGuideFocused(channel.id)
    }

    fun syncChannelOffset() {
        // Hide the redundant top-right clock whenever the full guide is visible
        if (binding.programPane.visibility == View.VISIBLE) {
            binding.groupClock.visibility = View.GONE
        }
        setSpacer(0)
        binding.channelList.setPadding(0, 0, 0, binding.channelList.paddingBottom)
        binding.programList.setPadding(0, 0, 0, binding.programList.paddingBottom)
        if (binding.programPane.visibility != View.VISIBLE) return
        binding.guideRow.post {
            if (binding.programPane.visibility != View.VISIBLE) return@post
            val channelAt = IntArray(2)
            val programAt = IntArray(2)
            binding.channelList.getLocationInWindow(channelAt)
            binding.programList.getLocationInWindow(programAt)
            val delta = GuideMetrics.verticalDelta(channelAt[1], programAt[1])
            if (delta > 1) {
                binding.channelList.setPadding(0, delta, 0, binding.channelList.paddingBottom)
            } else if (delta < -1) {
                binding.programList.setPadding(0, -delta, 0, binding.programList.paddingBottom)
            }
            val browse = activity as? BrowseActivity ?: return@post
            binding.programList.post {
                if (binding.programPane.visibility == View.VISIBLE) {
                    browse.alignGuide(binding.channelList, binding.programList)
                }
            }
        }
    }

    private fun setSpacer(height: Int) {
        val params = binding.channelGuideSpacer.layoutParams ?: return
        if (params.height == height) return
        params.height = height
        binding.channelGuideSpacer.layoutParams = params
    }

    fun restoreScrolls(model: BrowseViewModel, playlistId: String?) {
        val snap = model.snapshot()
        if (snap.playlistId != null && snap.playlistId != playlistId) return
        if (snap.categoryScroll > 0) {
            binding.categoryList.scrollToPosition(snap.categoryScroll)
        }
        if (snap.channelScroll > 0) {
            binding.channelList.post {
                binding.channelList.scrollToPosition(snap.channelScroll)
            }
        }
        if (snap.programScrollX > 0) {
            programAdapter.scrollX = snap.programScrollX
            binding.programHeaderScroll.scrollTo(snap.programScrollX, 0)
            positionNowLine(snap.programScrollX)
        }
    }

    fun captureScrolls(model: BrowseViewModel, playlistId: String?, sectionKey: String, categoryId: String?, categoryName: String, menu: String) {
        val categoryLayout = binding.categoryList.layoutManager as? LinearLayoutManager
        val channelLayout = binding.channelList.layoutManager as? LinearLayoutManager
        model.rememberPlaylist(playlistId)
        model.rememberSection(sectionKey)
        model.rememberCategory(categoryId, categoryName)
        model.rememberMenu(menu)
        model.rememberScrolls(
            categoryScroll = categoryLayout?.findFirstVisibleItemPosition() ?: 0,
            channelScroll = channelLayout?.findFirstVisibleItemPosition() ?: 0,
            programScrollX = programAdapter.scrollX,
            programRow = channelLayout?.findFirstVisibleItemPosition() ?: 0
        )
        model.rememberFocused(
            channelId = synopsisChannel?.id,
            programTitle = synopsisProgram?.title
        )
    }
}