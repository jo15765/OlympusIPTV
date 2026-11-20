package com.olympus.iptv.ui.player

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import java.text.DateFormat
import java.util.Date

/** Audio keeps playing. The picture is detached and the panel is black so a music or news channel does not burn in. */
internal fun PlayerActivity.toggleRadioMode() {
    if (radioOn) leaveRadioMode() else enterRadioMode()
}

internal fun PlayerActivity.enterRadioMode() {
    val exo = player ?: return
    radioOn = true
    exo.trackSelectionParameters = exo.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .build()
    runCatching { exo.clearVideoSurface() }
    binding.playerView.player = null
    val root = binding.root as FrameLayout
    radioOverlay(root).visibility = View.VISIBLE
    paintRadioClock()
    hideHandler.removeCallbacks(radioClock)
    hideHandler.postDelayed(radioClock, 1_000L)
    val attrs = window.attributes
    savedBrightness = attrs.screenBrightness
    attrs.screenBrightness = 0.01f
    window.attributes = attrs
}

internal fun PlayerActivity.leaveRadioMode() {
    if (!radioOn) return
    radioOn = false
    hideHandler.removeCallbacks(radioClock)
    (binding.root as? FrameLayout)?.findViewWithTag<View>(RADIO_TAG)?.visibility = View.GONE
    val exo = player
    if (exo != null) {
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        binding.playerView.player = exo
    }
    val attrs = window.attributes
    attrs.screenBrightness = savedBrightness
    window.attributes = attrs
}

private const val RADIO_TAG = "radio_mode"

internal fun PlayerActivity.holdRadioSurface() {
    if (!radioOn) return
    val exo = player ?: return
    exo.trackSelectionParameters = exo.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .build()
    runCatching { exo.clearVideoSurface() }
    binding.playerView.player = null
    paintRadioClock()
}

internal fun PlayerActivity.paintRadioClock() {
    val clock = (binding.root as? FrameLayout)?.findViewWithTag<TextView>(RADIO_TAG) ?: return
    val title = current?.name.orEmpty()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
    clock.text = if (title.isBlank()) time else "$time\n$title"
}

private fun PlayerActivity.radioOverlay(root: FrameLayout): TextView {
    val existing = root.findViewWithTag<TextView>(RADIO_TAG)
    if (existing != null) return existing
    val clock = TextView(this)
    clock.tag = RADIO_TAG
    clock.setBackgroundColor(Color.BLACK)
    clock.setTextColor(Color.WHITE)
    clock.textSize = 28f
    clock.gravity = Gravity.CENTER
    clock.isFocusable = true
    clock.setOnClickListener { leaveRadioMode() }
    root.addView(
        clock,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    )
    return clock
}
