// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.Detail
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.RequestScope
import com.hongguotv.core.Series
import com.hongguotv.core.VideoFrameMode
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One visible home work owns one decoder and one stream. No offscreen players or video prefetch. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TvHomePlayback(
    private val context: Context,
    private val repository: () -> ContentRepository,
    private val quality: () -> Int,
    private val speed: () -> Float,
    private val frame: () -> VideoFrameMode,
    private val autoNext: () -> Boolean,
    private val progress: (String) -> WatchProgress?,
    private val save: (WatchProgress) -> Unit,
    private val status: (State) -> Unit,
    private val screenOn: (Boolean) -> Unit,
) : Closeable {
    data class State(val seriesId: String, val label: String, val showCover: Boolean, val playing: Boolean, val failed: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { action -> Thread(action, "tv-home-playback") }
    private var task: Future<*>? = null
    private var scope: RequestScope? = null
    private var generation = 0
    private var closed = false
    private var active = false
    private var userPaused = false
    private var series: Series? = null
    /** 随心看下发的首集 ID；命中后无需再请求整套详情。 */
    private var trialEpisodeId: String? = null
    private var trialEpisodeCount = 0
    private var detail: Detail? = null
    private var episodeIndex = 0
    private var episodeId = ""
    private var video: RemoteVideo? = null
    private var player: ExoPlayer? = null
    private var firstFrame = false
    private var failed = false
    private var ended = false
    private var pendingStart: Runnable? = null
    private var pendingEpisode: Pair<Int, Long>? = null
    private var currentState: State? = null
    private val surface = PlayerView(context).apply {
        useController = false; isFocusable = false; isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setKeepContentOnPlayerReset(false)
        setShutterBackgroundColor(Color.BLACK)
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
    }
    val view: View get() = surface
    private val checkpointTick = object : Runnable {
        override fun run() {
            if (closed || !active) return
            if (player?.isPlaying == true) checkpoint()
            main.postDelayed(this, 5_000)
        }
    }

    fun select(next: Series, episodeId: String? = null, episodeCount: Int = 0) {
        if (closed || series?.id == next.id && trialEpisodeId == episodeId) return
        checkpoint()
        cancelLoad()
        clearStream()
        series = next; trialEpisodeId = episodeId?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
        trialEpisodeCount = episodeCount.coerceAtLeast(0)
        detail = null; episodeIndex = 0; episodeId = ""
        firstFrame = false; failed = false; userPaused = false; ended = false
        pendingEpisode = null
        publish("正在准备剧集…")
        if (active) schedule()
    }

    /** An empty refreshed shelf must also stop the previous work's audio. */
    fun clearSelection() {
        if (closed) return
        checkpoint(); cancelLoad(); clearStream()
        series = null; trialEpisodeId = null; trialEpisodeCount = 0; detail = null; episodeId = ""; pendingEpisode = null
        firstFrame = false; failed = false; ended = false; currentState = null
    }

    fun setActive(next: Boolean) {
        if (closed || active == next) return
        active = next
        main.removeCallbacks(checkpointTick)
        if (!next) {
            checkpoint()
            cancelLoad()
            player?.pause(); video?.setPlaybackActive(false)
            screenOn(false)
        } else {
            val p = player
            if (!userPaused && !failed && !ended) {
                val expires = video?.info?.expiresAt ?: 0L
                if (video != null && expires > 0 && expires <= System.currentTimeMillis() + 60_000) {
                    checkpoint(); clearStream(); firstFrame = false; schedule()
                } else if (video != null && p != null) { video?.setPlaybackActive(true); p.play() }
                else if (series != null) schedule()
            }
            main.postDelayed(checkpointTick, 5_000)
        }
    }

    fun toggle() {
        if (closed || series == null) return
        if (failed) {
            if (episodeId.isNotBlank()) repository().invalidatePlayback(episodeId)
            clearStream(); firstFrame = false
            failed = false; userPaused = false; ended = false
            if (active) schedule(0)
            return
        }
        if (ended) {
            ended = false; userPaused = false
            pendingEpisode = (if (episodeIndex + 1 < (detail?.episodes?.size ?: 0)) episodeIndex + 1 else 0) to 0L
            if (active) schedule(0)
            return
        }
        userPaused = !userPaused
        if (userPaused) {
            checkpoint(); cancelLoad(); player?.pause(); video?.setPlaybackActive(false)
            publish("播放已暂停 · 确定继续")
        } else if (active) {
            if (video != null) { video?.setPlaybackActive(true); player?.play(); publish(episodeLabel()) }
            else schedule(0)
        }
    }

    fun pauseForActions() {
        if (closed) return
        userPaused = true
        checkpoint(); cancelLoad(); player?.pause(); video?.setPlaybackActive(false)
        publish("播放已暂停 · 确定继续")
    }

    fun currentDetail(id: String): Detail? = detail?.takeIf { it.series.id == id || series?.id == id }

    private fun schedule(delay: Long = 240) {
        cancelLoad()
        if (closed || !active || userPaused || series == null) return
        publish("正在准备剧集…")
        pendingStart = Runnable { pendingStart = null; load() }.also { main.postDelayed(it, delay) }
    }

    private fun load() {
        val selected = series ?: return
        if (closed || !active || userPaused) return
        val token = generation
        val request = RequestScope(30_000)
        scope = request
        val cached = detail
        val remembered = progress(selected.id)
        val explicit = pendingEpisode
        val maxQuality = quality()
        task = worker.submit {
            var prepared: RemoteVideo? = null
            try {
                val result = request.run {
                    val data = cached ?: trialEpisodeId?.let { first ->
                        Detail(selected.copy(badge = if (trialEpisodeCount > 0) "共 $trialEpisodeCount 集" else selected.badge), listOf(first))
                    } ?: repository().seriesDetail(selected.id)
                    if (data.episodes.isEmpty()) throw java.io.IOException("暂无可播放剧集")
                    val savedIndex = remembered?.let { data.episodes.indexOf(it.episodeId) } ?: -1
                    val index = explicit?.first?.coerceIn(0, data.episodes.lastIndex)
                        ?: if (savedIndex >= 0) (savedIndex + if (remembered?.completed == true) 1 else 0).coerceAtMost(data.episodes.lastIndex) else 0
                    val position = explicit?.second ?: if (savedIndex == index && remembered?.completed != true) remembered?.position ?: 0L else 0L
                    val id = data.episodes[index]
                    val activeRepo = repository()
                    val first = activeRepo.stream(id, maxQuality, playback = true)
                    val fresh = if (first.expiresAt > 0 && first.expiresAt <= System.currentTimeMillis() + 60_000)
                        activeRepo.stream(id, maxQuality, refreshModel = true, playback = true) else first
                    val info = fresh.copy(idHint = id, qualityNumber = maxQuality)
                    val remote = RemoteVideo(activeRepo.http, info, chunkBytes = 256 * 1024, aheadBytes = 2 * 1024 * 1024L)
                    prepared = remote
                    remote.setPlaybackActive(false)
                    remote.prepare()
                    Triple(data, index, position.coerceAtLeast(0))
                }
                val remote = checkNotNull(prepared)
                main.post {
                    if (closed || generation != token || request.isCancelled || !active || userPaused) remote.close()
                    else {
                        task = null; scope = null; pendingEpisode = null
                        detail = result.first; episodeIndex = result.second; episodeId = result.first.episodes[result.second]
                        try { prepare(remote, result.third) }
                        catch (_: Exception) { remote.close(); showFailure() }
                    }
                }
            } catch (_: Exception) {
                prepared?.close()
                main.post {
                    if (!closed && generation == token && !request.isCancelled) {
                        task = null; scope = null; showFailure()
                    }
                }
            }
        }
    }

    private fun prepare(remote: RemoteVideo, position: Long) {
        clearStream()
        video = remote; firstFrame = false; failed = false; ended = false
        val p = player ?: createPlayer().also { player = it; surface.player = it }
        surface.resizeMode = when (frame()) {
            VideoFrameMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoFrameMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoFrameMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        p.setPlaybackSpeed(speed())
        val item = MediaItem.Builder().setMediaId(episodeId).setUri("hongguotv://home/$episodeId").setMimeType(MimeTypes.VIDEO_MP4).build()
        val source = Mp4MediaSources.factory { VideoDataSource(remote) }.createMediaSource(item)
        remote.setPlaybackActive(true)
        p.setMediaSource(source, position); p.prepare(); p.playWhenReady = active && !userPaused
        publish("${episodeLabel()} · 正在加载…")
    }

    private fun createPlayer() = ExoPlayer.Builder(context, Mp4MediaSources.factory())
        .setRenderersFactory(PlaybackRenderersFactory(context))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 8_000, 250, 750)
            .setTargetBufferBytes(6 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
        .build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                private fun owns() = !closed && video != null && currentMediaItem?.mediaId == episodeId
                override fun onRenderedFirstFrame() {
                    if (!owns()) return
                    firstFrame = true
                    publish(if (userPaused) "播放已暂停 · 确定继续" else episodeLabel())
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!owns()) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> publish("${episodeLabel()} · 正在缓冲…")
                        Player.STATE_READY -> publish(if (userPaused) "播放已暂停 · 确定继续" else episodeLabel())
                        Player.STATE_ENDED -> finishEpisode()
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!owns()) return
                    screenOn(active && isPlaying)
                    if (firstFrame && !ended && !failed) publish(when {
                        userPaused -> "播放已暂停 · 确定继续"
                        playbackState == Player.STATE_BUFFERING -> "${episodeLabel()} · 正在缓冲…"
                        else -> episodeLabel()
                    })
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (owns() && !playWhenReady && active &&
                        (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                        userPaused = true; checkpoint(); video?.setPlaybackActive(false)
                        publish("播放已暂停 · 确定继续")
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (owns()) showFailure()
                }
            })
        }

    private fun finishEpisode() {
        checkpoint(completed = true)
        val data = detail ?: return
        ended = true
        if (active && !userPaused && autoNext() && episodeIndex < data.episodes.lastIndex) {
            pendingEpisode = episodeIndex + 1 to 0L
            clearStream(); firstFrame = false; ended = false
            schedule(0)
        } else publish(if (episodeIndex == data.episodes.lastIndex) "本剧已播完 · 左右换剧" else "本集已播完 · 确定播放下一集")
    }

    private fun episodeLabel() = "第 ${episodeIndex + 1} 集 / 共 ${detail?.episodes?.size ?: 1} 集"
    private fun publish(label: String) {
        val id = series?.id ?: return
        val next = State(id, label, !firstFrame || failed, active && player?.isPlaying == true, failed)
        if (next != currentState) { currentState = next; status(next) }
    }

    fun checkpoint(completed: Boolean = ended) {
        val data = detail ?: return
        val p = player ?: return
        if (!firstFrame || episodeId.isBlank() || p.currentMediaItem?.mediaId != episodeId || failed) return
        val duration = p.duration.coerceAtLeast(0)
        val position = if (completed && duration > 0) duration else p.currentPosition.coerceAtLeast(0)
        if (position < 1_000 && !completed) return
        pendingEpisode = episodeIndex to position
        save(WatchProgress(data.series, episodeId, episodeIndex, position, duration, completed, System.currentTimeMillis()))
    }

    private fun showFailure() {
        checkpoint()
        failed = true
        player?.pause(); video?.setPlaybackActive(false); screenOn(false)
        publish("首页播放暂不可用 · 确定重试")
    }
    private fun cancelLoad() {
        generation++
        pendingStart?.let(main::removeCallbacks); pendingStart = null
        scope?.cancel(); scope = null
        task?.cancel(true); task = null
        (worker as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun clearStream() {
        val previous = video
        video = null
        previous?.setPlaybackActive(false)
        previous?.pauseReadAhead(cancelReads = true)
        player?.stop(); player?.clearMediaItems()
        previous?.close()
        screenOn(false)
    }
    override fun close() {
        if (closed) return
        checkpoint(); closed = true; active = false
        cancelLoad(); main.removeCallbacks(checkpointTick)
        clearStream(); surface.player = null; player?.release(); player = null
        worker.shutdownNow()
    }
}
