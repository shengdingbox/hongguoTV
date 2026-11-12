// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import com.hongguotv.core.RemoteVideo

/** 相邻集与葫芦娃共用同样的 Media3 预加载策略，不为后台分集创建播放器或解码器。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class EpisodePlaybackSession(context: Context, private val disk: (RemoteVideo) -> com.hongguotv.core.EpisodeDiskCache? = { null }) {
    data class Episode(
        val id: String,
        val index: Int,
        val quality: Int,
        val video: RemoteVideo,
        val startPositionMs: Long,
    )
    private data class Source(val episode: Episode, val item: MediaItem)

    // 同时参考系统低内存标记与应用堆预算，避免小堆电视因长预加载挤压解码内存。
    private val memoryManager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val lowMemoryDevice = memoryManager?.let { it.isLowRamDevice || it.memoryClass <= 128 } ?: true
    private val tvPlayback = !TouchControls.isHandheld(context)
    private val nextPreloadMs = if (lowMemoryDevice) 5_000L else 15_000L
    private val previousPreloadMs = if (lowMemoryDevice) 3_000L else 5_000L
    // 电视沿用首页单路预览的缓冲预算，避免起播阶段解析和解密过多样本。
    private val targetBufferBytes = (if (tvPlayback) 6 else if (lowMemoryDevice) 16 else 24) * 1024 * 1024
    private val sources = mutableMapOf<String, Source>()
    private var currentId: String? = null
    private var currentRanking = 0
    private var canPreload = false
    private var preloadingEnabled = false
    private var dirty = false
    private val builder = DefaultPreloadManager.Builder(context) { ranking ->
        if (!preloadingEnabled || !canPreload) null else {
            sources.values.firstOrNull { it.episode.index * 2 == ranking }?.let { source ->
                DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                    source.episode.startPositionMs,
                    if (ranking >= currentRanking) nextPreloadMs else previousPreloadMs,
                )
            }
        }
    }.setRenderersFactory(PlaybackRenderersFactory(context))
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (tvPlayback) 1_500 else 10_000, if (tvPlayback) 8_000 else 25_000, 250, 750)
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(!tvPlayback)
            .build())
    private val preloadManager = builder.build()
    val player = builder.buildExoPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                preloadWhenBuffered()
            }
        })
    }

    /** 等待新地址期间暂停旧集，但保留即将交接的媒体源及样本队列。 */
    fun pauseForSwitch() {
        canPreload = false
        currentId = null
        sources.values.forEach { it.episode.video.setPlaybackActive(false) }
        player.pause()
    }

    /** 手动切集和自动队列交接都更新同一份排序与预读所有权。 */
    fun select(id: String, index: Int, resetSource: Boolean = false) {
        if (resetSource) remove(id)
        currentId = id
        currentRanking = index * 2 + 1
        canPreload = false
        dirty = true
        preloadManager.setCurrentPlayingIndex(currentRanking)
        sources.values.forEach { it.episode.video.setPlaybackActive(it.episode.id == id) }
    }

    fun mediaSource(episode: Episode): MediaSource =
        checkNotNull(preloadManager.getMediaSource(source(episode).item))

    fun updatePreloads(episodes: List<Episode>) {
        episodes.forEach { source(it) }
        preloadWhenBuffered()
    }

    fun setPreloadingEnabled(enabled: Boolean) {
        if (preloadingEnabled == enabled) return
        preloadingEnabled = enabled
        dirty = true
        if (!enabled) {
            canPreload = false
            preloadManager.invalidate()
        }
    }

    /** 缓冲按实际倍速折算：积累三秒再预载，降到一秒或发生缓冲时立即让出带宽。 */
    private fun preloadWhenBuffered() {
        if (!preloadingEnabled || currentId == null) return
        val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0).toDouble() /
            player.playbackParameters.speed.coerceAtLeast(.1f)
        val enough = player.playbackState == Player.STATE_READY &&
            (player.bufferedPercentage == 100 || bufferedMs >= if (canPreload) 1_000L else 3_000L)
        if (canPreload != enough) {
            canPreload = enough
            dirty = true
        }
        if (dirty) {
            dirty = false
            preloadManager.invalidate()
        }
    }

    private fun source(episode: Episode): Source {
        val previous = sources[episode.id]
        if (previous != null && previous.episode.video === episode.video && previous.episode.quality == episode.quality) {
            if (previous.episode != episode) {
                dirty = true
                return previous.copy(episode = episode).also { sources[episode.id] = it }
            }
            return previous
        }
        remove(episode.id)
        episode.video.setPlaybackActive(episode.id == currentId)
        val item = MediaItem.Builder().setMediaId(episode.id)
            .setUri("hongguotv://episode/${episode.id}").setMimeType(MimeTypes.VIDEO_MP4).build()
        val mediaSource = ProgressiveMediaSource.Factory { VideoDataSource(episode.video, disk = { disk(episode.video) }) }.createMediaSource(item)
        return Source(episode, item).also {
            sources[episode.id] = it
            preloadManager.add(mediaSource, episode.index * 2)
            dirty = true
        }
    }

    /** 先释放窗口外的媒体源，再由地址缓存关闭对应 RemoteVideo。 */
    fun retain(ids: Set<String>, quality: Int) {
        sources.values.filter { it.episode.id !in ids || it.episode.quality != quality }
            .map { it.episode.id }.forEach(::remove)
    }

    private fun remove(id: String) {
        sources.remove(id)?.let {
            preloadManager.remove(it.item)
            it.episode.video.setPlaybackActive(false)
            dirty = true
        }
    }

    fun release() {
        currentId = null
        preloadingEnabled = false
        sources.values.forEach { it.episode.video.setPlaybackActive(false) }
        player.release()
        preloadManager.release()
        sources.clear()
    }
}
