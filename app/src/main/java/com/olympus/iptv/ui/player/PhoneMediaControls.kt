package com.olympus.iptv.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import com.olympus.iptv.R
import com.olympus.iptv.ui.DeviceForm

/** Lock-screen and Bluetooth controls for phone playback, including radio mode. */
internal class PhoneMediaControls(private val activity: PlayerActivity) {
    private var session: MediaSession? = null

    fun start() {
        if (!DeviceForm.isTouchPrimary(activity) || DeviceForm.isTelevision(activity)) return
        if (session != null) return
        val created = MediaSession(activity, "OlympusIptv")
        created.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                activity.runOnUiThread {
                    activity.player?.play()
                    publish()
                }
            }

            override fun onPause() {
                activity.runOnUiThread {
                    activity.player?.pause()
                    publish()
                }
            }

            override fun onSkipToNext() {
                activity.runOnUiThread { activity.step(1) }
            }

            override fun onSkipToPrevious() {
                activity.runOnUiThread { activity.step(-1) }
            }

            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                activity.runOnUiThread { handle(action) }
            }
        })
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            created.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
        }
        created.isActive = true
        session = created
        val filter = IntentFilter().apply {
            addAction(ACTION_DOWN)
            addAction(ACTION_UP)
            addAction(ACTION_SLEEP)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(transport, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(transport, filter)
        }
        registered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    activity.getString(R.string.rail_title),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    fun publish() {
        val active = session ?: return
        val channel = activity.current
        val exo = activity.player
        val playing = exo?.isPlaying == true || activity.radioOn
        val title = channel?.name ?: activity.getString(R.string.rail_title)
        active.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, channel?.groupTitle.orEmpty())
                .build()
        )
        active.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .addCustomAction(ACTION_DOWN, activity.getString(R.string.channel_down), android.R.drawable.ic_media_previous)
                .addCustomAction(ACTION_UP, activity.getString(R.string.channel_up), android.R.drawable.ic_media_next)
                .addCustomAction(ACTION_SLEEP, activity.getString(R.string.sleep_30), android.R.drawable.ic_lock_idle_alarm)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    exo?.currentPosition ?: 0L,
                    if (playing) 1f else 0f
                )
                .build()
        )
        val note = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(activity, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(activity)
        }
        val openIntent = PendingIntent.getActivity(
            activity,
            0,
            Intent(activity, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val subtitle = activity.binding.channelMeta.text?.toString()
            ?.takeIf { it.isNotBlank() && it != "No EPG" && it != "Loading EPG…" }
            ?: channel?.groupTitle.orEmpty()
        fun noteAction(iconRes: Int, label: String, intent: PendingIntent): Notification.Action =
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(activity, iconRes),
                label,
                intent
            ).build()
        note.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(com.olympus.iptv.data.NameStrip.shown(title, channel?.groupTitle))
            .setContentText(subtitle)
            .setContentIntent(openIntent)
            .setOngoing(playing)
            .addAction(noteAction(android.R.drawable.ic_media_previous, activity.getString(R.string.channel_down), pending(ACTION_DOWN)))
            .addAction(
                noteAction(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    activity.getString(if (playing) R.string.player_pause else R.string.play),
                    pending(if (playing) ACTION_PAUSE else ACTION_PLAY)
                )
            )
            .addAction(noteAction(android.R.drawable.ic_media_next, activity.getString(R.string.channel_up), pending(ACTION_UP)))
            .addAction(noteAction(android.R.drawable.ic_lock_idle_alarm, activity.getString(R.string.sleep_30), pending(ACTION_SLEEP)))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(active.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        val manager = activity.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTE_ID, note.build()) }
    }

    fun release() {
        if (registered) {
            runCatching { activity.unregisterReceiver(transport) }
            registered = false
        }
        runCatching {
            activity.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID)
        }
        session?.isActive = false
        session?.release()
        session = null
    }

    private fun handle(action: String?) {
        when (action) {
            ACTION_DOWN -> activity.step(-1)
            ACTION_UP -> activity.step(1)
            ACTION_PLAY -> activity.player?.play()
            ACTION_PAUSE -> activity.player?.pause()
            ACTION_SLEEP -> activity.armSleepTimer(if (activity.sleepMinutes > 0) 0 else 30)
        }
        publish()
    }

    private fun pending(action: String): PendingIntent {
        val intent = Intent(action).setPackage(activity.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(activity, action.hashCode(), intent, flags)
    }

    private val transport = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handle(intent.action)
        }
    }

    private var registered = false

    private companion object {
        const val CHANNEL = "olympus_playback"
        const val NOTE_ID = 42
        const val ACTION_DOWN = "com.olympus.iptv.CHANNEL_DOWN"
        const val ACTION_UP = "com.olympus.iptv.CHANNEL_UP"
        const val ACTION_PLAY = "com.olympus.iptv.PLAY"
        const val ACTION_PAUSE = "com.olympus.iptv.PAUSE"
        const val ACTION_SLEEP = "com.olympus.iptv.SLEEP"
    }
}
