package com.olympus.iptv.ui.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.olympus.iptv.R
import com.olympus.iptv.ui.DeviceForm
import com.olympus.iptv.data.AvSyncStore
import com.olympus.iptv.data.PlayerDecoder
import java.nio.ByteBuffer

@UnstableApi
class DelayAudioProcessor : BaseAudioProcessor() {
    @Volatile var delayMs: Int = 0
    private var applied = Int.MIN_VALUE
    private var skipLeft = 0
    private var silenceLeft = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat
    }

    override fun isActive(): Boolean = delayMs != 0 && super.isActive()

    override fun onFlush() {
        applied = Int.MIN_VALUE
        skipLeft = 0
        silenceLeft = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val bytesPerFrame = runCatching {
            Util.getPcmFrameSize(format.encoding, format.channelCount)
        }.getOrDefault(0)
        if (bytesPerFrame <= 0 || format.sampleRate <= 0) {
            val copy = replaceOutputBuffer(inputBuffer.remaining())
            copy.put(inputBuffer)
            copy.flip()
            return
        }
        if (applied != delayMs) {
            applied = delayMs
            val frames = (format.sampleRate.toLong() * delayMs.coerceIn(-AvSyncStore.LIMIT_MS, AvSyncStore.LIMIT_MS) / 1000L).toInt()
            if (frames >= 0) {
                silenceLeft = frames * bytesPerFrame
                skipLeft = 0
            } else {
                skipLeft = -frames * bytesPerFrame
                silenceLeft = 0
            }
        }
        if (skipLeft > 0 && inputBuffer.remaining() > 0) {
            val skip = minOf(skipLeft, inputBuffer.remaining())
            inputBuffer.position(inputBuffer.position() + skip)
            skipLeft -= skip
        }
        val incoming = inputBuffer.remaining()
        val pad = silenceLeft.coerceAtLeast(0)
        val out = replaceOutputBuffer(incoming + pad)
        if (pad > 0) {
            val zeros = ByteArray(pad)
            out.put(zeros)
            silenceLeft = 0
        }
        if (incoming > 0) out.put(inputBuffer)
        out.flip()
    }
}

@UnstableApi
class OffsetRenderersFactory(
    context: Context,
    mode: String?,
    private val delay: DelayAudioProcessor
) : DefaultRenderersFactory(context.applicationContext) {
    init {
        setEnableDecoderFallback(true)
        val extension = when (PlayerDecoder.normalize(mode)) {
            PlayerDecoder.HARDWARE_ASYNC -> EXTENSION_RENDERER_MODE_ON
            PlayerDecoder.HARDWARE_FAILSAFE, PlayerDecoder.SOFTWARE -> EXTENSION_RENDERER_MODE_PREFER
            else -> EXTENSION_RENDERER_MODE_OFF
        }
        setExtensionRendererMode(extension)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(delay))
            .build()
    }
}

internal fun PlayerActivity.applyChannelDelay(channel: com.olympus.iptv.data.Channel) {
    audioDelay.delayMs = AvSyncStore.get(channel.id)
}

internal fun PlayerActivity.nudgeAvSync(deltaMs: Int) {
    val id = current?.id.orEmpty()
    if (id.isBlank()) return
    val next = AvSyncStore.nudge(this, id, deltaMs)
    audioDelay.delayMs = next
    binding.subtitleStatus.text = getString(R.string.av_sync_status, next)
}
