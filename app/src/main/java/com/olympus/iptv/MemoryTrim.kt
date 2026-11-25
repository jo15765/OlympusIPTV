package com.olympus.iptv

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import coil.Coil

object MemoryTrim {

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
