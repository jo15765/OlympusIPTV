package com.olympus.iptv.ui.browse

object GuideFeel {
    fun onFocus(view: android.view.View) {
        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
    }

    fun landOnContinueStrip(sectionIsLive: Boolean, stripVisible: Boolean): Boolean =
        !sectionIsLive && stripVisible

    fun chipDownTarget(continueVisible: Boolean): String =
        if (continueVisible) "homeContinue" else "categoryList"

    fun columnWidth(textPx: Float, chromePx: Float, parentPx: Int): Int {
        val floor = chromePx.toInt().coerceAtLeast(1)
        val ceiling = (parentPx * 0.40f).toInt().coerceAtLeast(floor)
        return (textPx + chromePx).toInt().coerceIn(floor, ceiling)
    }

    fun autoSkipIntro(seriesId: String?, remembered: Set<String>): Boolean {
        val id = seriesId?.trim().orEmpty()
        return id.isNotEmpty() && id in remembered
    }

    fun sectionKey(live: Boolean, movies: Boolean, shows: Boolean): String = when {
        movies -> "vod"
        shows -> "series"
        else -> "live"
    }

    fun sectionFromKey(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "vod", "movies" -> "vod"
        "series", "shows" -> "series"
        else -> "live"
    }
}
