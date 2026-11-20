package com.olympus.iptv.data

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory

object PlayerDecoder {
    const val HARDWARE = "hardware"
    const val HARDWARE_ASYNC = "hardware_async"
    const val HARDWARE_FAILSAFE = "hardware_failsafe"
    const val SOFTWARE = "software"

    val OPTIONS = listOf(HARDWARE, HARDWARE_ASYNC, HARDWARE_FAILSAFE, SOFTWARE)

    fun normalize(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return if (value in OPTIONS) value else HARDWARE
    }

    fun renderersFactory(context: Context, type: String?): RenderersFactory {
        val mode = normalize(type)
        val factory = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
        when (mode) {
            HARDWARE_ASYNC -> factory.setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            HARDWARE_FAILSAFE, SOFTWARE -> factory.setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )
            else -> factory.setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
        }
        return factory
    }
}
