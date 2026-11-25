package com.olympus.iptv.ui.player

import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.ProFeatureStore
import com.olympus.iptv.portal.DashboardHost
import com.olympus.iptv.ui.login.QrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PlayerQuickSheet {

    fun showQuickSettings(activity: PlayerActivity) {
        val state = ProFeatureStore.load(activity)
        val profile = ProFeatureStore.activeProfile(activity)
        val pb = state.playback
        val subLabel = when {
            pb.subtitleScale <= 0.9f -> "Small (${pb.subtitleScale}×)"
            pb.subtitleScale <= 1.15f -> "Medium (${pb.subtitleScale}×)"
            pb.subtitleScale <= 1.45f -> "Large (${pb.subtitleScale}×)"
            else -> "XL (${pb.subtitleScale}×)"
        }
        val items = arrayOf(
            "📺  Recent Channels (Last 10)",
            "📊  Stats for Nerds: ${if (pb.statsOverlayEnabled) "ON" else "OFF"}",
            "🔤  Subtitle Size: $subLabel",
            "${profile.avatar}  Profile: ${profile.name}",
            "▶  Open in External Player",
            "📱  Web Dashboard QR Code"
        )
        AlertDialog.Builder(activity)
            .setTitle("Quick Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRecentChannels(activity)
                    1 -> {
                        val next = !pb.statsOverlayEnabled
                        ProFeatureStore.update(activity) { cur ->
                            cur.copy(playback = cur.playback.copy(statsOverlayEnabled = next))
                        }
                        Toast.makeText(
                            activity,
                            if (next) "Stats for Nerds ON" else "Stats for Nerds OFF",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    2 -> showSubtitleSizePicker(activity)
                    3 -> showProfilePicker(activity)
                    4 -> {
                        val ch = activity.currentChannel()
                        if (ch == null || ch.streamUrl.isBlank()) {
                            Toast.makeText(activity, "No active stream", Toast.LENGTH_SHORT).show()
                        } else {
                            val ok = ExternalPlayerBridge.launch(activity, ch, pb.externalPlayerPackage)
                            if (!ok) Toast.makeText(activity, "No external player app found", Toast.LENGTH_SHORT).show()
                        }
                    }
                    5 -> showDashboardQr(activity)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showRecentChannels(activity: PlayerActivity) {
        val savedId = activity.activePlaylistId() ?: return
        val app = activity.application as OlympusIptvApp
        activity.lifecycleScope.launch {
            val recentEntries = withContext(Dispatchers.IO) { app.playlistStore.recent(savedId) }
            if (activity.isDestroyed) return@launch
            val byId = app.playbackQueue.associateBy { it.id }
            val recentChannels = recentEntries.mapNotNull { byId[it.channelId] }.distinctBy { it.id }.take(10)
            if (recentChannels.isEmpty()) {
                Toast.makeText(activity, "No recent channels yet", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = recentChannels.map { ch ->
                val grp = ch.groupTitle?.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty()
                "${ch.name}$grp"
            }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("Recent Channels")
                .setItems(labels) { _, which ->
                    val chosen = recentChannels.getOrNull(which) ?: return@setItems
                    activity.tuneChannelById(chosen.id)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showSubtitleSizePicker(activity: PlayerActivity) {
        val labels = arrayOf("Small (0.85×)", "Medium (1.0×)", "Large (1.3×)", "Extra Large (1.6×)")
        val values = floatArrayOf(0.85f, 1.0f, 1.3f, 1.6f)
        AlertDialog.Builder(activity)
            .setTitle("Subtitle Size")
            .setItems(labels) { _, which ->
                ProFeatureStore.update(activity) { cur ->
                    cur.copy(playback = cur.playback.copy(subtitleScale = values[which]))
                }
                Toast.makeText(activity, "Subtitles: ${labels[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showProfilePicker(activity: PlayerActivity) {
        val state = ProFeatureStore.load(activity)
        val profiles = state.profiles
        val labels = profiles.map { p ->
            val active = if (p.id == state.activeProfileId) "  ✓" else ""
            val kids = if (p.kidsMode) " (Kids)" else ""
            "${p.avatar}  ${p.name}$kids$active"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Switch Household Profile")
            .setItems(labels) { _, which ->
                val picked = profiles.getOrNull(which) ?: return@setItems
                ProFeatureStore.update(activity) { cur -> cur.copy(activeProfileId = picked.id) }
                Toast.makeText(activity, "Switched to ${picked.avatar} ${picked.name}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDashboardQr(activity: PlayerActivity) {
        val url = DashboardHost.url()
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val qrPx = (220 * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad / 2)
            addView(ImageView(activity).apply {
                setImageBitmap(QrBitmap.encode(url, 320))
                layoutParams = LinearLayout.LayoutParams(qrPx, qrPx)
            })
            addView(TextView(activity).apply {
                text = "Scan with your phone camera or open:\n$url"
                gravity = Gravity.CENTER
                setPadding(0, (12 * density).toInt(), 0, 0)
                textSize = 14f
            })
        }
        AlertDialog.Builder(activity)
            .setTitle("Olympus-IPTV Web Dashboard")
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
