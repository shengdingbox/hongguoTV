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
    private val entries = mutableMapOf<Key, Entry>()
    private val attempts = mutableMapOf<Key, Attempts>()
    // 本次播放失败的本地文件暂时绕过，保留文件供下次会话复用，重试可回落在线。
    private val rejectedDiskEntries = mutableSetOf<Key>()
    private var retained = emptySet<Key>()
    private var protectedVideos = emptySet<RemoteVideo>()
    private val retiredVideos = mutableSetOf<RemoteVideo>()
    private val executor = Executors.newFixedThreadPool(2) { action ->
        Thread(action, "episode-playback").apply { isDaemon = true }
    }

    /** 算法切换后只换取址仓库；已播放和已缓冲的 RemoteVideo 不受影响。 */
    @Synchronized fun rebind(next: ContentRepository) { repository = next }

    /** 整集落盘缓存的地址解析：与相邻集预取走同一条链路（模型缓存、降级重试、即将过期重取）。 */
    fun resolveStream(id: String, quality: Int): StreamInfo =
        repository.stream(id, quality).let { info ->
            if (info.expiresAt > 0 && info.expiresAt <= clock() + 60_000L)
                repository.stream(id, quality, refreshModel = true) else info
        }

    private fun fresh(prepared: PreparedEpisode): Boolean {
        if (prepared.video.isLocal) return true
        if (localOnly()) return false
        prepared.diskMeta?.let { meta ->
            val current = cachedEpisode(meta.id, meta.quality)
            return Key(meta.id, meta.quality) !in rejectedDiskEntries && current != null && current.header === meta.header
        }
        val now = clock()
        if (now - prepared.createdAt !in 0..600_000L) return false
        val expires = prepared.video.info.expiresAt
        return expires <= 0 || expires > now + 60_000L
    }

    /** 界面只获取已准备的内存结果；落盘索引的完整校验交给 load 的后台任务。 */
    @Synchronized fun peek(id: String, quality: Int): PreparedEpisode? =
        entries[Key(id, quality)]?.prepared?.takeIf(::fresh)

    private fun restoreLocal(meta: EpisodeDiskCache.Meta): PreparedEpisode? {
        val info = StreamInfo(meta.url, meta.key, meta.qualityLabel, meta.expiresAt, meta.id, meta.quality)
        return try {
            PreparedEpisode(RemoteVideo.fromPrepared(repository.http, info, meta.total, meta.prepared), clock(), meta)
        } catch (_: IOException) {
            onInvalidate(meta.id)
            null // 本地索引不可用时走在线准备。
        }
    }

    /** 预取和正式播放等待同一个任务；取消等待者不取消仍在窗口内的取址和索引请求。 */
    fun load(id: String, quality: Int): PreparedEpisode {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val task = checkNotNull(request(Key(id, quality), speculative = false))
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

    fun prefetch(id: String, quality: Int, warmStart: Boolean = false) {
        request(Key(id, quality), speculative = true, warmStart = warmStart)
    }

    @Synchronized fun isLoading(id: String, quality: Int): Boolean =
        entries[Key(id, quality)]?.task?.isDone == false

    @Synchronized private fun request(key: Key, speculative: Boolean, warmStart: Boolean = false): FutureTask<PreparedEpisode>? {
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
                val prepared = entry.scope.run {
                    val file = localEpisode(key.id)
                    if (file != null) {
                        return@run RemoteVideo.fromFile(repository.http, file, key.id, key.quality).also { video = it }
                    }
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
                        val info = resolveStream(key.id, key.quality).copy(idHint = key.id, qualityNumber = key.quality)
                        if (info.expiresAt > 0 && info.expiresAt <= clock() + 60_000L) {
                            throw IOException("播放地址即将过期，请重试")
                        }
                        RemoteVideo(repository.http, info, chunkBytes = 256 * 1024).also {
                            video = it
                            it.setPlaybackActive(false)
                            it.prepare()
                            // 详情页只为从头播放的目标多留一个媒体块，不开启持续预读。
                            if (entry.warmStart) it.preload()
                        }
                    }
                }
                val result = PreparedEpisode(prepared, clock(), diskMeta)
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
