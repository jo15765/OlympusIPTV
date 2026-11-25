package com.olympus.iptv.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.olympus.iptv.data.Channel

object ExternalPlayerBridge {
    const val PKG_VLC = "org.videolan.vlc"
    const val PKG_MX_FREE = "com.mxtech.videoplayer.ad"
    const val PKG_MX_PRO = "com.mxtech.videoplayer.pro"
    const val PKG_NOVA = "org.courville.nova"

    fun launch(context: Context, channel: Channel, preferredPackage: String = ""): Boolean {
        val url = channel.streamUrl.trim()
        if (url.isBlank()) return false
        val uri = Uri.parse(url)
        val base = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            putExtra("title", channel.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pkg = preferredPackage.trim()
        if (pkg.isNotBlank()) {
            val targeted = Intent(base).setPackage(pkg)
            if (targeted.resolveActivity(context.packageManager) != null) {
                return runCatching { context.startActivity(targeted); true }.getOrDefault(false)
            }
        }
        val chooser = Intent.createChooser(base, "Open with external player").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrElse {
            Toast.makeText(context, "No external video player found", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
