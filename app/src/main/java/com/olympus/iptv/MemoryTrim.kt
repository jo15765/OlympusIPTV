package com.olympus.iptv

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import coil.Coil

/** Drops picture and guide caches when a low-RAM stick asks the process to shrink. */
object MemoryTrim {
    /** Reclaim non-essential browse/search caches before full-screen playback on low-RAM sticks. */
    fun trimForPlayback(app: OlympusIptvApp) {
        runCatching { Coil.imageLoader(app).memoryCache?.clear() }
        runCatching { com.olympus.iptv.data.NameStrip.clearCaches() }
        runCatching { app.catalogDb.releaseMemory() }
        runCatching { com.olympus.iptv.portal.DashboardHost.clearGuideCache() }
    }

    fun install(app: OlympusIptvApp) {
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                @Suppress("DEPRECATION")
                val lowLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                if (level < lowLevel && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    return
                }
                runCatching { Coil.imageLoader(app).memoryCache?.clear() }
                runCatching { app.epgRepository.trimForPressure() }
                runCatching { com.olympus.iptv.ui.BackdropGlow.release() }
                runCatching { com.olympus.iptv.data.NameStrip.clearCaches() }
                runCatching { app.catalogDb.releaseMemory() }
                runCatching { com.olympus.iptv.portal.DashboardHost.clearGuideCache() }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            }
        })
    }
}
