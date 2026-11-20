package com.olympus.iptv.ui.browse

/**
 * Pure helpers for guide UX decisions that should stay unit-testable.
 */
object GuideFeel {
    fun onFocus(view: android.view.View) {
        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
    }

    fun landOnContinueStrip(sectionIsLive: Boolean, stripVisible: Boolean): Boolean =
        !sectionIsLive && stripVisible

    fun chipDownTarget(continueVisible: Boolean): String =
        if (continueVisible) "homeContinue" else "categoryList"

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
