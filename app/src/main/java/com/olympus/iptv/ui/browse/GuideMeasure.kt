package com.olympus.iptv.ui.browse

import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.NameStrip
import com.olympus.iptv.data.PlaylistStore

internal fun BrowseActivity.widestNamePx(): Float {
    val size = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        13f,
        resources.displayMetrics
    )
    if (!namePaintReady || namePaint.textSize != size) {
        namePaint.textSize = size
        namePaint.typeface = android.graphics.Typeface.DEFAULT
        namePaintReady = true
        fittedList = null
    }
    var widest = 0f
    for (channel in visibleChannels) {
        val measured = namePaint.measureText(NameStrip.shown(channel.name))
        if (measured > widest) widest = measured
    }
    fittedTextPx = widest
    return widest
}

internal fun BrowseActivity.sameNames(previous: List<Channel>, channels: List<Channel>): Boolean {
    for (index in previous.indices) {
        if (previous[index].name != channels[index].name) return false
    }
    return true
}

internal fun BrowseActivity.baseGuideRowDp(): Int =
    if (touchPrimary) 56 else GuideMetrics.rowDp(guideDensity == PlaylistStore.GUIDE_COMPACT, tall = false)

internal fun BrowseActivity.tallGuideRowDp(): Int =
    if (touchPrimary) 64 else GuideMetrics.rowDp(guideDensity == PlaylistStore.GUIDE_COMPACT, tall = true)

internal fun BrowseActivity.syncProgramRowHeight() {
    applyGuideRowHeight(guideRowsTall)
}

internal fun BrowseActivity.applyGuideRowHeight(tall: Boolean) {
    guideRowsTall = tall
    val dp = if (tall) tallGuideRowDp() else baseGuideRowDp()
    val height = GuideMetrics.rowPx(dp, resources.displayMetrics.density)
    val programChanged = programAdapter.rowHeight != height
    val channelChanged = channelAdapter.guideRowHeight != height
    if (!programChanged && !channelChanged) return
    programAdapter.rowHeight = height
    channelAdapter.guideRowHeight = height
    if (programAdapter.itemCount > 0) {
        programAdapter.notifyItemRangeChanged(0, programAdapter.itemCount)
    }
    if (channelAdapter.itemCount > 0 && binding.programPane.visibility == android.view.View.VISIBLE) {
        channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount)
    }
    if (binding.programPane.visibility == android.view.View.VISIBLE) {
        binding.programList.post {
            alignGuide(binding.channelList, binding.programList)
        }
    }
}
