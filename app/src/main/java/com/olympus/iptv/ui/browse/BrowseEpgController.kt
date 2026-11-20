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
    private val epgCache: () -> Map<String, EpgNowNext>,
    private val onRecord: (Channel) -> Unit,
    private val onRemind: (Channel) -> Unit
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
            layoutParams = LinearLayout.LayoutParams(width, (10 * density).toInt())
        }
        rule.addView(View(activity).apply {
            setBackgroundColor(activity.getColor(R.color.stroke))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                density.toInt().coerceAtLeast(1)
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        })
        val dot = (5 * density).toInt()
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
        val pane = binding.programSynopsis
        if (binding.programPane.visibility != View.VISIBLE) {
            pane.visibility = View.GONE
            return
        }
        val now = EpgUtils.now()
        val epg = program ?: epgCache()[channel.id]?.now
            ?: programAdapter.programsFor(channel.id)?.firstOrNull { now >= it.startMillis && now < it.endMillis }
        synopsisChannel = channel
        synopsisProgram = epg
        if (epg == null) {
            pane.visibility = View.GONE
            return
        }
        pane.visibility = View.VISIBLE
        binding.synopsisTitle.text = epg.title
        val kind = EpgProgramStyle.kind(epg, channel, now)
        val pill = EpgProgramStyle.pill(kind)
        val badge = EpgProgramStyle.episodeBadge(epg.title)
        binding.synopsisMeta.text = buildString {
            append(epg.timeRangeLabel())
            if (badge != null) {
                append(" · ")
                append(badge)
            }
            if (pill != null) {
                append(" · ")
                append(pill)
            }
        }
        val plot = epg.description?.trim().orEmpty()
        binding.synopsisPlot.text = plot
        binding.synopsisPlot.visibility = if (plot.isBlank()) View.GONE else View.VISIBLE
        binding.synopsisRecord.setOnClickListener { onRecord(channel) }
        binding.synopsisRemind.setOnClickListener { onRemind(channel) }
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
