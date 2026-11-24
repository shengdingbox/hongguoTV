// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/** 补齐旧款小米 jobs 电视的输出窗口兼容处理，首页和全集共用同一配置。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    // Media3 已对其他旧款 MiTV 禁用动态切换 Surface，Android 9 的 jobs 未在其名单内。
    private val needsSurfaceWorkaround = Build.VERSION.SDK_INT <= 28 && Build.DEVICE == "jobs" &&
        Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)

    private val legacyAudio = BuildConfig.ANDROID5_COMPAT && Build.VERSION.SDK_INT <= 22

    init {
        setEnableDecoderFallback(true)
        // Android 5 的部分系统 AAC 解码器会卡死；兼容版优先使用精简音频软解。
        setExtensionRendererMode(if (legacyAudio) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_OFF)
        // 视频落后音频时钟超过 50ms 时允许跳过解码器输入，避免低性能电视持续追帧。
        experimentalSetLateThresholdToDropDecoderInputUs(50_000)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (!legacyAudio) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        // 为老系统预留足够 PCM，降低播放线程短暂阻塞时的音频断续。
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioTrackBufferSizeProvider(
                DefaultAudioTrackBufferSizeProvider.Builder().setMinPcmBufferDurationUs(650_000).build()
            ).build()
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        if (!needsSurfaceWorkaround) {
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector,
                enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
            return
        }
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(getCodecAdapterFactory())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            .experimentalSetLateThresholdToDropDecoderInputUs(50_000)
        out.add(object : MediaCodecVideoRenderer(builder) {
            // 同时阻止解码器先绑定占位窗口；真实窗口变化时由 Media3 释放并重建解码器。
            override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean =
                name.startsWith("OMX.amlogic.") || super.codecNeedsSetOutputSurfaceWorkaround(name)
        })
    }
}
