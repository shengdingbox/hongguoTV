// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable
import java.io.File
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/** 播放页持有的相邻集缓存；最终地址、解密索引和开头分块随同一个 RemoteVideo 一起复用。 */
class EpisodePlaybackCache(
    @Volatile private var repository: ContentRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPrepared: (String, Int) -> Unit = { _, _ -> },
    private val cachedEpisode: (String, Int) -> EpisodeDiskCache.Meta? = { _, _ -> null },
    private val localEpisode: (String) -> File? = { null },
    private val localOnly: () -> Boolean = { false },
) : Closeable {
    // 包装结果，资源始终由缓存持有；取消某个页面的等待不能关闭其他页面即将复用的视频。
    data class PreparedEpisode(val video: RemoteVideo, val createdAt: Long, val diskMeta: EpisodeDiskCache.Meta? = null)
    private data class Key(val id: String, val quality: Int)
    private class Entry {
        val scope = RequestScope()
        lateinit var task: FutureTask<PreparedEpisode>
        var prepared: PreparedEpisode? = null
        @Volatile var warmStart = false
    }
    private data class Attempts(val count: Int, val lastAt: Long)
    private data class Recent(
        val info: StreamInfo,
        val total: Long,
        val index: MediaCrypto.Prepared,
        val at: Long,
    )
    private val entries = mutableMapOf<Key, Entry>()
    private val attempts = mutableMapOf<Key, Attempts>()
    // 近期已解析的地址与 MP4 索引可直接复用，不持有仍由播放器使用的数据源。
    private val recent = linkedMapOf<Key, Recent>()
    // 本次播放失败的本地文件暂时绕过，保留文件供下次会话复用，重试可回落在线。
    private val rejectedDiskEntries = mutableSetOf<Key>()
    private var retained = emptySet<Key>()
    private var protectedVideos = emptySet<RemoteVideo>()
    private val retiredVideos = mutableSetOf<RemoteVideo>()
    private val executor = Executors.newFixedThreadPool(2) { action ->
        Thread(action, "episode-playback").apply { isDaemon = true }
    }

    /** 算法切换后只换取址仓库；已播放和已缓冲的 RemoteVideo 不受影响。 */
    @Synchronized fun rebind(next: ContentRepository) {
        repository = next
        // 旧身份的地址不能提供给切换后的签名器；已播放的缓冲仍由调用方保护。
        recent.clear()
    }

    /** 整集取址复用模型缓存、降级重试和即将过期重取；仅正式播放参与算法降级和连续失败计数。
     *  [ahead] 是顺带预取的相邻集：随本次 V5 请求一次带回，切集时零请求命中。 */
    fun resolveStream(id: String, quality: Int, playback: Boolean = false, prefetch: Boolean = false, ahead: List<String> = emptyList()): StreamInfo =
        repository.stream(id, quality, playback = playback, prefetch = prefetch, ahead = ahead).let { info ->
            if (info.expiresAt > 0 && info.expiresAt <= clock() + 60_000L)
                repository.stream(id, quality, refreshModel = true, playback = playback, prefetch = prefetch, ahead = ahead) else info
        }

    private fun fresh(prepared: PreparedEpisode): Boolean {
        if (prepared.video.isLocal) return true
        if (localOnly()) return false
        prepared.diskMeta?.let { meta ->
            val current = cachedEpisode(meta.id, meta.quality)
            return Key(meta.id, meta.quality) !in rejectedDiskEntries && current != null &&
                current.header.contentEquals(meta.header) && current.total == meta.total
        }
        val now = clock()
        if (now - prepared.createdAt !in 0..600_000L) return false
        val expires = prepared.video.info.expiresAt
        return expires <= 0 || expires > now + 60_000L
    }

    /** 界面只获取已准备的内存结果；落盘索引的完整校验交给 load 的后台任务。 */
    @Synchronized fun peek(id: String, quality: Int): PreparedEpisode? =
        entries[Key(id, quality)]?.prepared?.takeIf(::fresh)

    /** 最多复用最近 5 分钟、仍在有效期内的完整地址与索引，避免重复模型请求和首包校验。 */
    @Synchronized fun takeRecent(id: String, quality: Int): PreparedEpisode? {
        val key = Key(id, quality)
        val entry = recent[key] ?: return null
        val now = clock()
        if (now - entry.at !in 0..5 * 60_000L || !addressFresh(entry.info, now)) {
            recent.remove(key)
            return null
        }
        return try {
            PreparedEpisode(RemoteVideo.fromPrepared(repository.http, entry.info, entry.total, entry.index), now)
        } catch (_: IOException) {
            recent.remove(key)
            null
        }
    }

    private fun restoreLocal(meta: EpisodeDiskCache.Meta): PreparedEpisode? {
        val info = StreamInfo(meta.url, meta.key, meta.qualityLabel, meta.expiresAt, meta.id, meta.quality)
        return try {
            PreparedEpisode(RemoteVideo.fromPrepared(repository.http, info, meta.total, meta.prepared), clock(), meta)
        } catch (_: IOException) {
            onInvalidate(meta.id)
            null // 本地索引不可用时走在线准备。
        }
    }

    private fun addressFresh(info: StreamInfo, now: Long): Boolean {
        val expires = info.expiresAt
        return expires <= 0 || expires > now + 60_000L
    }

    private fun rememberRecent(key: Key, video: RemoteVideo) {
        synchronized(this) {
            val index = video.preparedSnapshot()
            recent[key] = Recent(video.info, video.total, index, clock())
            while (recent.size > 2) recent.remove(recent.keys.first())
        }
    }

    /** 正式播放等待当前集任务；取消等待者不取消仍在窗口内的取址和索引请求。 */
    fun load(id: String, quality: Int, ahead: List<String> = emptyList()): PreparedEpisode {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val task = checkNotNull(request(Key(id, quality), speculative = false, playback = true, ahead = ahead))
        try {
            return task.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("播放请求已取消")
        } catch (error: CancellationException) {
            throw InterruptedIOException("播放请求已取消")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    fun prefetch(id: String, quality: Int, warmStart: Boolean = false, ahead: List<String> = emptyList()) {
        request(Key(id, quality), speculative = true, warmStart = warmStart, ahead = ahead)
    }

    /** 首页试播流移交（对齐参考项目 preparedNext 预置）：用快照重建一个独立流实例放入缓存，
     *  正式播放 peek 直接命中，跳过取址与首包等待；原实例仍归试播播放器持有，随其关闭互不影响。 */
    @Synchronized fun adopt(video: RemoteVideo, quality: Int) {
        val id = video.info.idHint
        if (id.isNullOrBlank()) return
        val key = Key(id, quality)
        // 接管前清掉同集的在途任务或旧结果：既避免完成时反过来覆盖造成泄漏，也保证 peek 一定拿到移交流。
        entries.remove(key)?.let(::cancel)
        val clone = RemoteVideo.fromPrepared(repository.http, video.info, video.total, video.preparedSnapshot())
        val handoff = PreparedEpisode(clone, clock())
        val entry = Entry().apply {
            prepared = handoff
            // task 补一个已完成值，load/isLoading 等按 task 观察的路径不会踩到未初始化状态。
            task = FutureTask { handoff }.apply { run() }
        }
        entries[key] = entry
    }

    @Synchronized fun isLoading(id: String, quality: Int): Boolean =
        entries[Key(id, quality)]?.task?.isDone == false

    @Synchronized private fun request(key: Key, speculative: Boolean, warmStart: Boolean = false, playback: Boolean = false, ahead: List<String> = emptyList()): FutureTask<PreparedEpisode>? {
        if (key !in retained) {
            if (speculative) return null
            throw InterruptedIOException("播放请求已取消")
        }
        peek(key.id, key.quality)?.let { return entries[key]?.task }
        val previous = entries[key]
        if (previous != null) {
            if (warmStart) previous.warmStart = true
            val prepared = previous.prepared
            if (prepared != null && fresh(prepared)) return previous.task
            if (prepared == null && !previous.task.isDone) return previous.task
            entries.remove(key)
            cancel(previous)
        }
        val now = clock()
        val tried = attempts[key]
        if (speculative && tried != null && (tried.count >= 3 || now - tried.lastAt < 15_000L)) return null
        if (speculative) attempts[key] = Attempts((tried?.count ?: 0) + 1, now)
        val entry = Entry().apply { this.warmStart = warmStart }
        entry.task = FutureTask {
            var video: RemoteVideo? = null
            try {
                var diskMeta: EpisodeDiskCache.Meta? = null
                val prepared: RemoteVideo = entry.scope.run<RemoteVideo> {
                    val file = localEpisode(key.id)
                    if (file != null) {
                        val local = RemoteVideo.fromFile(repository.http, file, key.id, key.quality)
                        video = local
                        local
                    } else {
                        if (localOnly()) throw IOException("该集尚未离线，请先下载")
                        val local = cachedEpisode(key.id, key.quality)?.takeIf {
                            synchronized(this) { Key(it.id, it.quality) !in rejectedDiskEntries }
                        }
                        val restored = local?.let(::restoreLocal)
                        if (restored != null) {
                            video = restored.video
                            diskMeta = restored.diskMeta
                            restored.video
                        } else {
                            val recent = takeRecent(key.id, key.quality)
                            if (recent != null) {
                                video = recent.video
                                if (entry.warmStart) recent.video.preload()
                                recent.video
                            } else {
                                val info = resolveStream(key.id, key.quality, playback = playback, prefetch = speculative, ahead = ahead).copy(idHint = key.id, qualityNumber = key.quality)
                                if (info.expiresAt > 0 && info.expiresAt <= clock() + 60_000L) {
                                    throw IOException("播放地址即将过期，请重试")
                                }
                                val created = RemoteVideo(repository.http, info, chunkBytes = 256 * 1024)
                                video = created
                                created.setPlaybackActive(false)
                                created.prepare(32 * 1024)
                                // 详情页只为从头播放的目标多留一个媒体块，不开启持续预读。
                                if (entry.warmStart) created.preload()
                                created
                            }
                        }
                    }
                }
                val result = PreparedEpisode(prepared, clock(), diskMeta)
                if (diskMeta == null && !prepared.isLocal) rememberRecent(key, prepared)
                synchronized(this) {
                    if (entries[key] !== entry || entry.scope.isCancelled) throw InterruptedIOException("播放请求已取消")
                    entry.prepared = result
                    attempts.remove(key)
                }
                runCatching { onPrepared(key.id, key.quality) }
                result
            } catch (error: Throwable) {
                video?.close()
                throw error
            }
        }
        entries[key] = entry
        executor.execute(entry.task)
        return entry.task
    }

    /** 切集后只保留当前、下一集和上一集；离开窗口的在途请求一并取消。 */
    @Synchronized fun retain(ids: Set<String>, quality: Int) {
        retained = ids.mapTo(mutableSetOf()) { Key(it, quality) }
        entries.keys.filter { it.id !in ids || it.quality != quality }.forEach { key ->
            entries.remove(key)?.let(::cancel)
        }
        attempts.keys.retainAll { it.id in ids && it.quality == quality }
    }

    @Synchronized fun invalidate(id: String) {
        entries.keys.filter { it.id == id }.forEach { key ->
            entries.remove(key)?.let { entry ->
                entry.prepared?.diskMeta?.let { rejectedDiskEntries += Key(it.id, it.quality) }
                cancel(entry)
            }
        }
        attempts.keys.removeAll { it.id == id }
        recent.keys.removeAll { it.id == id }
        repository.invalidatePlayback(id)
    }

    /** 重试期间让数据源也绕过失败的本地文件，避免在线准备后再次读到同一份坏数据。 */
    @Synchronized fun canReadDisk(id: String, quality: Int): Boolean = Key(id, quality) !in rejectedDiskEntries

    /** 仅本地索引损坏时清理不可用的落盘缓存；换址和在线重试保留完整文件。 */
    var onInvalidate: (String) -> Unit = {}

    /** 播放中、正在切换和已挂入队列的资源由播放器持有，过期换址时延后关闭。 */
    @Synchronized fun protect(videos: Set<RemoteVideo>) {
        protectedVideos = videos.toSet()
        retiredVideos.filter { it !in protectedVideos }.forEach {
            retiredVideos.remove(it)
            it.close()
        }
    }

    private fun cancel(entry: Entry) {
        entry.scope.cancel()
        entry.task.cancel(true)
        entry.prepared?.video?.let { video ->
            if (video in protectedVideos) retiredVideos += video else video.close()
        }
    }

    @Synchronized fun clear() {
        retained = emptySet()
        entries.values.forEach(::cancel)
        entries.clear()
        attempts.clear()
        rejectedDiskEntries.clear()
    }

    override fun close() { clear(); protect(emptySet()); executor.shutdownNow() }
}
