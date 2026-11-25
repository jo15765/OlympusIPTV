package com.olympus.iptv.ui.player

import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.ui.DeviceForm
import com.olympus.iptv.ui.Haptics
import kotlin.math.abs

internal object PlayerTouchGestures {
    fun attach(activity: PlayerActivity, surface: View) {
        if (!DeviceForm.isTouchPrimary(activity) || DeviceForm.isTelevision(activity)) return
        val audio = activity.getSystemService(AudioManager::class.java)
        val density = activity.resources.displayMetrics.density
        val hud = GestureHud(activity)
        (activity.binding.root as? ViewGroup)?.addView(
            hud,
            FrameLayout.LayoutParams((56 * density).toInt(), (220 * density).toInt(), Gravity.CENTER)
        )
        var originY = -1f
        var side = 0
        var startBrightness = 0.5f
        var startVolume = 0
        var lastStep = -1
        var speedBoosted = false
        var speedBeforeBoost = 1f
        var pinchHandled = false

        val scaleDetector = ScaleGestureDetector(
            activity,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pinchHandled = false
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (pinchHandled) return true
                    val factor = detector.scaleFactor
                    if (factor > 1.12f && activity.pictureMode != PlaylistStore.PICTURE_FILL) {
                        pinchHandled = true
                        activity.pictureMode = PlaylistStore.PICTURE_FILL
                        activity.applyPicture(PlaylistStore.PICTURE_FILL)
                        Haptics.confirm(surface)
                        hud.showSeek("Zoom")
                    } else if (factor < 0.88f && activity.pictureMode != PlaylistStore.PICTURE_FIT) {
                        pinchHandled = true
                        activity.pictureMode = PlaylistStore.PICTURE_FIT
                        activity.applyPicture(PlaylistStore.PICTURE_FIT)
                        Haptics.confirm(surface)
                        hud.showSeek("Fit")
                    }
                    return true
                }
            }
        )

        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                activity.togglePlaybackChrome()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val ch = activity.current ?: return
                val isLive = ch.kind == ContentKind.LIVE && ch.seriesId.isNullOrBlank()
                if (isLive) return
                val exo = activity.player ?: return
                if (!exo.isPlaying) return
                speedBeforeBoost = exo.playbackParameters.speed.coerceIn(0.5f, 2f)
                speedBoosted = true
                exo.setPlaybackSpeed(2.0f)
                Haptics.confirm(surface)
                hud.showSeek("2×")
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val exo = activity.player ?: return true
                val duration = exo.duration
                if (duration <= 0L) return true
                val width = surface.width.coerceAtLeast(1)
                val delta = if (e.x < width / 2f) -15_000L else 15_000L
                val next = (exo.currentPosition + delta).coerceIn(0L, duration)
                exo.seekTo(next)
                activity.showTransportChrome()
                Haptics.confirm(surface)
                hud.showSeek(if (delta < 0L) "−15s" else "+15s")
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val width = surface.width
                val height = surface.height
                if (width <= 0 || height <= 0) return false
                val edge = width * 0.22f
                if (start.x > edge && start.x < width - edge) {
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    val ch = activity.current
                    val isLive = ch != null && ch.kind == ContentKind.LIVE && ch.seriesId.isNullOrBlank()

                    if (isLive && abs(dy) > height * 0.14f && abs(dy) > abs(dx) * 1.4f && abs(velocityY) > 600f) {
                        Haptics.confirm(surface)
                        if (dy < 0f) {
                            hud.showSeek("CH ▲")
                            activity.step(1)
                        } else {
                            hud.showSeek("CH ▼")
                            activity.step(-1)
                        }
                        return true
                    }

                    if (!isLive && abs(dx) > width * 0.16f && abs(dx) > abs(dy) * 1.4f && abs(velocityX) > 600f) {
                        val exo = activity.player ?: return false
                        val duration = exo.duration
                        if (duration > 0L) {
                            val delta = if (dx < 0f) -30_000L else 30_000L
                            exo.seekTo((exo.currentPosition + delta).coerceIn(0L, duration))
                            activity.showTransportChrome()
                            Haptics.confirm(surface)
                            hud.showSeek(if (delta < 0L) "−30s" else "+30s")
                            return true
                        }
                    }
                }
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (speedBoosted || scaleDetector.isInProgress) return true
                val start = e1 ?: return false
                val width = surface.width
                val height = surface.height
                if (width <= 0 || height <= 0) return false
                val edge = width * 0.22f
                if (start.x > edge && start.x < width - edge) return false
                if (abs(e2.y - start.y) < abs(e2.x - start.x)) return false
                if (originY < 0f) {
                    originY = start.y
                    side = if (start.x <= edge) -1 else 1
                    val current = activity.window.attributes.screenBrightness
                    startBrightness = if (current >= 0f) {
                        current
                    } else {
                        runCatching {
                            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                        }.getOrDefault(0.5f).coerceIn(0.05f, 1f)
                    }
                    startVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    lastStep = -1
                }
                val drag = ((originY - e2.y) / height).coerceIn(-1f, 1f)
                val percent = if (side < 0) {
                    val level = (startBrightness + drag).coerceIn(0.01f, 1f)
                    val params = activity.window.attributes
                    params.screenBrightness = level
                    activity.window.attributes = params
                    (level * 100).toInt()
                } else if (audio != null) {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val level = (startVolume + drag * max).toInt().coerceIn(0, max)
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
                    (level * 100) / max
                } else {
                    return true
                }
                val step = percent / 10
                if (step != lastStep) {
                    lastStep = step
                    Haptics.clock(surface)
                }
                hud.showLevel(brightness = side < 0, percent = percent)
                return true
            }
        })
        surface.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            val handled = if (scaleDetector.isInProgress) true else detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (speedBoosted) {
                    speedBoosted = false
                    activity.player?.setPlaybackSpeed(speedBeforeBoost)
                }
                if (originY >= 0f || hud.visibility == View.VISIBLE) hud.fadeOut()
                originY = -1f
                side = 0
                lastStep = -1
                pinchHandled = false
            }
            handled
        }
    }
}
