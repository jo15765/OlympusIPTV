package com.olympus.iptv.ui.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import com.olympus.iptv.portal.DashboardHost
import java.util.Locale

/**
 * Lightweight "Stats for Nerds" diagnostics HUD.
 * Runs a 1-second ticker ONLY while visible; zero CPU/RAM overhead when disabled.
 */
internal class StreamStatsHud(
    context: Context,
    private val playerProvider: () -> ExoPlayer?,
    private val decoderProvider: () -> String
) {
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    val view: TextView = TextView(context).apply {
        visibility = View.GONE
        setTextColor(0xFFF8F4EC.toInt())
        textSize = 11.5f
        typeface = Typeface.MONOSPACE
        setLineSpacing(2f * density, 1f)
        val padH = (12 * density).toInt()
        val padV = (8 * density).toInt()
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            setColor(Color.argb(205, 18, 16, 13))
            cornerRadius = 10f * density
            setStroke((1f * density).toInt().coerceAtLeast(1), 0x88E6A65C.toInt())
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    fun attachTo(root: ViewGroup) {
        if (view.parent != null) return
        val margin = (18 * density).toInt()
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = margin
            marginEnd = margin
        }
        root.addView(view, lp)
    }

    fun setVisible(show: Boolean) {
        if (show == running) return
        running = show
        handler.removeCallbacks(ticker)
        view.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            refresh()
            handler.postDelayed(ticker, 1000L)
        }
    }

    fun release() {
        running = false
        handler.removeCallbacks(ticker)
    }

    private fun refresh() {
        val exo = playerProvider()
        if (exo == null) {
            view.text = "STATS · Idle"
            return
        }
        val vf = exo.videoFormat
        val af = exo.audioFormat
        val res = if (vf != null && vf.width > 0 && vf.height > 0) "${vf.width}×${vf.height}" else "—"
        val fps = if (vf != null && vf.frameRate > 0f) String.format(Locale.US, "%.2f fps", vf.frameRate) else "—"
        val vCodec = vf?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "—"
        val vBitrate = if (vf != null && vf.bitrate > 0) String.format(Locale.US, "%.1f Mbps", vf.bitrate / 1_000_000f) else "—"
        val aCodec = af?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "—"
        val aCh = if (af != null && af.channelCount > 0) "${af.channelCount}ch" else "—"
        val dropped = exo.videoDecoderCounters?.droppedBufferCount ?: 0
        val bufSec = ((exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0L)) / 1000.0
        val speed = exo.playbackParameters.speed
        val wan = DashboardHost.cachedWanIp().ifBlank { "LAN" }
        view.text = buildString {
            append("VIDEO   ").append(res).append(" · ").append(fps).append(" · ").append(vCodec).append('\n')
            append("BITRATE ").append(vBitrate).append(" · DEC ").append(decoderProvider().uppercase(Locale.US)).append('\n')
            append("AUDIO   ").append(aCodec).append(' ').append(aCh).append(" · SPEED ").append(String.format(Locale.US, "%.2f×", speed)).append('\n')
            append("BUFFER  ").append(String.format(Locale.US, "%.1fs", bufSec)).append(" · DROPPED ").append(dropped).append(" · ").append(wan)
        }
    }
}
