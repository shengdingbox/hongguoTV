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
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
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

/** 当前展示的作品独占一个解码器和一路流；后面几部剧在预取池中提前备好，按右换剧零网络秒切。 */
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
    /** 换剧预取候选：当前焦点之后的（剧、首集提示、集数），由首页 feed 提供。 */
    private val upcoming: () -> List<Triple<Series,String?,Int>> = { emptyList() },
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
    /** 换剧预取池：prepare 完成的下一部剧（流 + 详情），按右命中即零网络切换。 */
    private class Prepared(val remote: RemoteVideo, val data: Detail, val episodeId: String) {
        var mediaItem: MediaItem? = null
        var source: MediaSource? = null
        var slot = 0
    }
    private val pool = LinkedHashMap<String, Prepared>()
    private var prefetchTask: Future<*>? = null
    private var prefetchScope: RequestScope? = null
    private var prefetchTarget: String? = null
    private val attempted = mutableSetOf<String>()
    private val prefetchFailures = mutableMapOf<String, Int>()
    /** 当前剧首帧时刻：驱动「0.5 秒备一部、3 秒两部、6 秒三部」的递进预取。 */
    private var firstFrameAt = 0L
    /** MediaPeriod 级预载器：池内的流后台解析到选轨完成、下一部再多缓冲 3 秒，换剧即挂即播。 */
    private var preloadManager: DefaultPreloadManager? = null
    private var preloadThrottled = false
    private var slotCounter = 0
    private var currentSlot = 0
    private var startedAt = 0L
    private var frameFrom = ""
    /** 当前挂载中的媒体项：换剧替换后注销旧注册，避免预载器持有已关闭的流。 */
    private var activeItem: MediaItem? = null
    private val surface = PlayerView(context).apply {
        useController = false; isFocusable = false; isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // 换剧过渡保留上一帧画面，不闪黑屏（离开 feed 时再显式关闭）
        setKeepContentOnPlayerReset(true)
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
    /** 剧级预取心跳：轮询驱动递进预取，缓冲、加载中自然让路。 */
    private val prefetchTick = object : Runnable {
        override fun run() {
            if (closed || !active) return
            ensureUpcoming()
            main.postDelayed(this, 500)
        }
    }

    fun select(next: Series, episodeId: String? = null, episodeCount: Int = 0) {
        if (closed || series?.id == next.id && trialEpisodeId == episodeId) return
        checkpoint()
        cancelLoad()
        clearStream()
        // 正在预取目标剧时不打断，等它完成直接转正接管（快速按右不重复取址）。
        val inFlight = prefetchTask != null && prefetchTarget == next.id
        if (!inFlight) cancelPrefetchTask()
        series = next; trialEpisodeId = episodeId?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
        trialEpisodeCount = episodeCount.coerceAtLeast(0)
        detail = null; episodeIndex = 0; this.episodeId = ""
        firstFrame = false; failed = false; userPaused = false; ended = false
        pendingEpisode = null
        publish("正在准备剧集…")
        if (!active) return
        val hit = if (hasResume(next.id)) null else takePooled(next.id)
        when {
            hit != null -> attachPrepared(hit)
            inFlight -> {} // 预取完成回调里转正挂载，不再发起新请求
            else -> schedule()
        }
    }

    /** An empty refreshed shelf must also stop the previous work's audio. */
    fun clearSelection() {
        if (closed) return
        checkpoint(); cancelLoad(); cancelPrefetchTask()
        clearStream()
        surface.setKeepContentOnPlayerReset(false)
        series = null; trialEpisodeId = null; trialEpisodeCount = 0; detail = null; episodeId = ""; pendingEpisode = null
        firstFrame = false; failed = false; ended = false; currentState = null
    }

    fun setActive(next: Boolean) {
        if (closed || active == next) return
        active = next
        main.removeCallbacks(checkpointTick)
        main.removeCallbacks(prefetchTick)
        if (!next) {
            checkpoint()
            cancelLoad(); cancelPrefetchTask()
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
            main.postDelayed(prefetchTick, 500)
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

    /** 当前剧首帧稳定后递进准备后面的剧：0.5 秒一部、3 秒两部、6 秒三部；缓冲或加载中让路。 */
    private fun ensureUpcoming() {
        if (closed || !active || prefetchTask != null || failed || !firstFrame || video == null) return
        if (task != null || pendingStart != null) return
        if (player?.playbackState == Player.STATE_BUFFERING) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - firstFrameAt < 500) return
        trimPool()
        val depth = when {
            now - firstFrameAt >= 6000 -> 3
            now - firstFrameAt >= 3000 -> 2
            else -> 1
        }
        val target = upcoming().take(depth)
            .firstOrNull { it.first.id !in pool && it.first.id !in attempted && !hasResume(it.first.id) }
            ?: return
        val (next, hintEpisode, hintCount) = target
        attempted += next.id
        prefetchTarget = next.id
        android.util.Log.i("HongguoTV", "Home preload start series=${next.id}")
        val request = RequestScope(12_000)
        prefetchScope = request
        val maxQuality = quality()
        prefetchTask = worker.submit {
            var prepared: RemoteVideo? = null
            try {
                val result = request.run {
                    val data = hintEpisode?.let { first ->
                        Detail(next.copy(badge = if (hintCount > 0) "共 $hintCount 集" else next.badge), listOf(first))
                    } ?: repository().seriesDetail(next.id)
                    if (data.episodes.isEmpty()) throw java.io.IOException("暂无可播放剧集")
                    val id = data.episodes.first()
                    val info = repository().stream(id, maxQuality, playback = true)
                        .copy(idHint = id, qualityNumber = maxQuality)
                    val remote = RemoteVideo(repository().http, info, chunkBytes = 256 * 1024, aheadBytes = 2 * 1024 * 1024L)
                    prepared = remote
                    remote.setPlaybackActive(false)
                    remote.prepare()
                    Prepared(remote, data, id)
                }
                main.post { onPrefetchDone(next, result) }
            } catch (_: Exception) {
                prepared?.close()
                if (!request.isCancelled) main.post { onPrefetchDone(next, null) }
            }
        }
    }

    /** 预取收尾：select 正在等这部剧就转正接管，否则入池备按右换剧。 */
    private fun onPrefetchDone(next: Series, result: Prepared?) {
        prefetchTask = null; prefetchScope = null; prefetchTarget = null
        if (closed) { result?.remote?.close(); return }
        if (result == null) {
            val failures = (prefetchFailures[next.id] ?: 0) + 1
            prefetchFailures[next.id] = failures
            android.util.Log.i("HongguoTV", "Home preload failed series=${next.id} failures=$failures")
            // 失败一次 15 秒后允许再试，连续两次放弃
            if (failures < 2) main.postDelayed({ if (!closed) attempted.remove(next.id) }, 15_000)
            // select 正在等这次预取（转正等待）却失败：立即回退正常取址，不能停在占位文案上
            if (series?.id == next.id && active && !userPaused && episodeId.isBlank() &&
                task == null && pendingStart == null
            ) schedule(0)
            return
        }
        if (series?.id == next.id && active && !userPaused && !failed &&
            episodeId.isBlank() && task == null && pendingStart == null
        ) attachPrepared(result)
        else {
            pool.remove(next.id)?.let(::releasePrepared)
            registerPreloaded(result)
            pool[next.id] = result
            trimPool()
        }
        android.util.Log.i("HongguoTV", "Home preload finished series=${next.id} ok=${result != null}")
    }

    /** 池内流注册进 MediaPeriod 预载器：后台解析选轨，紧邻的下一部再预缓冲 3 秒数据。 */
    private fun registerPreloaded(hit: Prepared) {
        val manager = preloadManager ?: return
        val item = MediaItem.Builder().setMediaId(hit.episodeId)
            .setUri("hongguotv://home/${hit.episodeId}").setMimeType(MimeTypes.VIDEO_MP4).build()
        val source = Mp4MediaSources.factory { VideoDataSource(hit.remote) }.createMediaSource(item)
        hit.mediaItem = item; hit.source = source
        hit.slot = currentSlot + (upcoming().indexOfFirst { it.first.id == hit.data.series.id } + 1).coerceAtLeast(1)
        manager.add(source, hit.slot)
        manager.invalidate()
    }

    /** 池内条目淘汰时同步注销预载注册，释放预载器资源。 */
    private fun releasePrepared(hit: Prepared) {
        hit.mediaItem?.let { preloadManager?.remove(it) }
        hit.mediaItem = null; hit.source = null
        hit.remote.close()
    }

    /** 挂载预取好的流：详情与集号就位后直接起播，零网络请求。 */
    private fun attachPrepared(hit: Prepared) {
        detail = hit.data
        episodeIndex = hit.data.episodes.indexOf(hit.episodeId).coerceAtLeast(0)
        episodeId = hit.episodeId
        pendingEpisode = null
        try { prepare(hit.remote, 0L, hit) }
        catch (_: Exception) { hit.remote.close(); showFailure() }
    }

    /** 取出预取好的剧；地址临近过期（60 秒口径，同恢复播放）则弃用走正常取址。 */
    private fun takePooled(id: String): Prepared? {
        val hit = pool.remove(id) ?: return null
        val expires = hit.remote.info.expiresAt
        if (expires > 0 && expires <= System.currentTimeMillis() + 60_000) { releasePrepared(hit); return null }
        return hit
    }

    /** 有观看记录的剧换过去会续播或跳到下一集，position=0 的第一集预取用不上，跳过。 */
    private fun hasResume(id: String) =
        progress(id)?.let { it.completed || it.episodeIndex > 0 || it.position > 0 } == true

    private fun trimPool() {
        while (pool.size > 3) {
            val eldest = pool.entries.iterator().next()
            pool.remove(eldest.key)
            releasePrepared(eldest.value)
        }
    }

    /** 取消进行中的剧级预取；因让路或换目标被打断的剧允许稍后重试。 */
    private fun cancelPrefetchTask() {
        prefetchTarget?.let { attempted.remove(it) }
        prefetchTarget = null
        prefetchScope?.cancel(); prefetchScope = null
        prefetchTask?.cancel(true); prefetchTask = null
    }

    /** 挂载一路流：正常加载或预取池命中统一走这里。预载过的源解析与缓冲早已就绪，prepare 近乎瞬时。 */
    private fun prepare(remote: RemoteVideo, position: Long, preloaded: Prepared? = null) {
        val previous = video
        video = remote; firstFrame = false; failed = false; ended = false
        frameFrom = if (preloaded != null) "pool" else "load"
        val p = player ?: createPlayer().also { player = it; surface.player = it }
        surface.setKeepContentOnPlayerReset(true)
        surface.resizeMode = when (frame()) {
            VideoFrameMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoFrameMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoFrameMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        p.setPlaybackSpeed(speed())
        val item = preloaded?.mediaItem ?: MediaItem.Builder().setMediaId(episodeId)
            .setUri("hongguotv://home/$episodeId").setMimeType(MimeTypes.VIDEO_MP4).build()
        val manager = preloadManager
        var source: MediaSource? = null
        if (preloaded != null && preloaded.source != null) {
            currentSlot = preloaded.slot
            source = preloaded.source
        } else if (manager != null) {
            slotCounter += 1; currentSlot = slotCounter
            source = Mp4MediaSources.factory { VideoDataSource(remote) }.createMediaSource(item)
            manager.add(source, currentSlot)
        } else {
            slotCounter += 1; currentSlot = slotCounter
            source = Mp4MediaSources.factory { VideoDataSource(remote) }.createMediaSource(item)
        }
        manager?.setCurrentPlayingIndex(currentSlot)
        startedAt = android.os.SystemClock.elapsedRealtime()
        remote.setPlaybackActive(true)
        previous?.setPlaybackActive(false)
        previous?.pauseReadAhead(cancelReads = true)
        // 不 stop 播放器：直接替换媒体源，KeepContent 保留上一帧，画面无黑场过渡。
        p.setMediaSource(manager?.getMediaSource(item) ?: source, position)
        p.prepare(); p.playWhenReady = active && !userPaused
        previous?.close()
        val stale = activeItem
        activeItem = item
        if (stale != null && stale != item) manager?.remove(stale)
        publish("${episodeLabel()} · 正在加载…")
    }

    /** 当前剧缓冲充足才允许预载深度推进；缓冲中先让路（与网络层预取同一节流口径）。 */
    private fun preloadAllowed() =
        !preloadThrottled && player?.let { it.isPlaying && it.totalBufferedDuration >= 3000 } == true

    private fun suspendPreload() {
        if (!preloadThrottled) { preloadThrottled = true; preloadManager?.invalidate() }
    }

    private fun resumePreload() {
        if (preloadThrottled) { preloadThrottled = false; preloadManager?.invalidate() }
    }

    private fun createPlayer(): ExoPlayer {
        // 播放器与 MediaPeriod 预载器配套构建（共享 loader），池内的流由预载器提前准备到可播状态。
        val builder = DefaultPreloadManager.Builder(
            context,
            TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { index ->
                // 保持在制进度不丢弃；不允许时统一退到「轨道已选」。
                if (!preloadAllowed() || index == currentSlot)
                    DefaultPreloadManager.PreloadStatus.TRACKS_SELECTED
                else
                    when (index - currentSlot) {
                        1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3000L)
                        -1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1000L)
                        2, 3 -> DefaultPreloadManager.PreloadStatus.TRACKS_SELECTED
                        else -> null
                    }
            },
        )
            .setRenderersFactory(PlaybackRenderersFactory(context))
            // 高码率片源可能先达到字节上限；电视端优先生成最短播放时长，减少起播后的缓冲等待。
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_500, 10_000, 500, 1_000)
                .setTargetBufferBytes(6 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build())
        preloadManager = builder.build()
        return builder.buildExoPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                private fun owns() = !closed && video != null && currentMediaItem?.mediaId == episodeId
                override fun onRenderedFirstFrame() {
                    if (!owns()) return
                    firstFrame = true
                    firstFrameAt = android.os.SystemClock.elapsedRealtime()
                    android.util.Log.i("HongguoTV", "Home first-frame elapsedMs=${firstFrameAt - startedAt} from=$frameFrom")
                    publish(if (userPaused) "播放已暂停 · 确定继续" else episodeLabel())
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!owns()) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> { cancelPrefetchTask(); suspendPreload(); publish("${episodeLabel()} · 正在缓冲…") }
                        Player.STATE_READY -> publish(if (userPaused) "播放已暂停 · 确定继续" else episodeLabel())
                        Player.STATE_ENDED -> finishEpisode()
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!owns()) return
                    if (isPlaying) resumePreload()
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
        cancelPrefetchTask()
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
        activeItem?.let { preloadManager?.remove(it) }
        activeItem = null
        screenOn(false)
    }
    override fun close() {
        if (closed) return
        checkpoint(); closed = true; active = false
        cancelLoad(); cancelPrefetchTask()
        main.removeCallbacks(checkpointTick); main.removeCallbacks(prefetchTick)
        clearStream()
        pool.values.forEach { it.remote.close() }; pool.clear()
        surface.setKeepContentOnPlayerReset(false); surface.player = null
        preloadManager?.release(); preloadManager = null
        player?.release(); player = null
        worker.shutdownNow()
    }
}
