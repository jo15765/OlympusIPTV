package com.olympus.iptv

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import coil.Coil

/** Drops picture and guide caches when a low-RAM stick asks the process to shrink. */
object MemoryTrim {
    fun install(app: OlympusIptvApp) {
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                    level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                ) {
                    return
                }
                runCatching { Coil.imageLoader(app).memoryCache?.clear() }
                runCatching { app.epgRepository.trimForPressure() }
                runCatching { com.olympus.iptv.ui.BackdropGlow.release() }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        })
    }
}
