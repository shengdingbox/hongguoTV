// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/** 播放页持有的相邻集缓存；最终地址、解密索引和开头分块随同一个 RemoteVideo 一起复用。 */
class EpisodePlaybackCache(
    private val repository: ContentRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPrepared: (String, Int) -> Unit = { _, _ -> },
) : Closeable {
    // 包装结果，资源始终由缓存持有；取消某个页面的等待不能关闭其他页面即将复用的视频。
    data class PreparedEpisode(val video: RemoteVideo, val createdAt: Long)
    private data class Key(val id: String, val quality: Int)
    private class Entry {
        val scope = RequestScope()
        lateinit var task: FutureTask<PreparedEpisode>
        var prepared: PreparedEpisode? = null
    }
    private data class Attempts(val count: Int, val lastAt: Long)
    private val entries = mutableMapOf<Key, Entry>()
    private val attempts = mutableMapOf<Key, Attempts>()
    private var retained = emptySet<Key>()
    private var protectedVideos = emptySet<RemoteVideo>()
    private val retiredVideos = mutableSetOf<RemoteVideo>()
    private val executor = Executors.newFixedThreadPool(2) { action ->
        Thread(action, "episode-playback").apply { isDaemon = true }
    }

    /** 整集落盘缓存的地址解析：与相邻集预取走同一条链路（模型缓存、降级重试、即将过期重取）。 */
    fun resolveStream(id: String, quality: Int): StreamInfo =
        repository.stream(id, quality).let { info ->
            if (info.expiresAt > 0 && info.expiresAt <= clock() + 60_000L)
                repository.stream(id, quality, refreshModel = true) else info
        }

    private fun fresh(prepared: PreparedEpisode): Boolean {
        val now = clock()
        val expires = prepared.video.info.expiresAt
        return now - prepared.createdAt in 0..600_000L && (expires <= 0 || expires > now + 60_000L)
    }

    @Synchronized fun peek(id: String, quality: Int): PreparedEpisode? =
        entries[Key(id, quality)]?.prepared?.takeIf(::fresh)

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

    fun prefetch(id: String, quality: Int) { request(Key(id, quality), speculative = true) }

    @Synchronized fun isLoading(id: String, quality: Int): Boolean =
        entries[Key(id, quality)]?.task?.isDone == false

    @Synchronized private fun request(key: Key, speculative: Boolean): FutureTask<PreparedEpisode>? {
        if (key !in retained) {
            if (speculative) return null
            throw InterruptedIOException("播放请求已取消")
        }
        val previous = entries[key]
        if (previous != null) {
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
        val entry = Entry()
        entry.task = FutureTask {
            var video: RemoteVideo? = null
            try {
                val prepared = entry.scope.run {
                    // 优先复用 modelCache 里的模型：详情页/相邻集预取已经取好地址时，这里应当零网络。
                    // 只有解析出的地址即将过期才强制重取，避免把「预取」变成白做（每次多两跳网络）。
                    var info = repository.stream(key.id, key.quality)
                    if (info.expiresAt > 0 && info.expiresAt <= clock() + 60_000L) {
                        info = repository.stream(key.id, key.quality, refreshModel = true)
                    }
                    info = info.copy(idHint = key.id, qualityNumber = key.quality)
                    if (info.expiresAt > 0 && info.expiresAt <= clock() + 60_000L) {
                        throw IOException("播放地址即将过期，请重试")
                    }
                    // 地址与索引请求共用；相邻集的媒体字节交给 Media3 按实际续播位置预加载，
                    // 不在这里额外下载开头分块或启动持续预读。
                    RemoteVideo(repository.http, info, chunkBytes = 256 * 1024).also {
                        video = it
                        it.setPlaybackActive(false)
                        it.prepare()
                    }
                }
                val result = PreparedEpisode(prepared, clock())
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
        entries.keys.filter { it.id == id }.forEach { entries.remove(it)?.let(::cancel) }
        attempts.keys.removeAll { it.id == id }
        repository.invalidatePlayback(id)
        onInvalidate(id)
    }

    /** 地址失效时级联废弃整集落盘缓存（旧地址下载出的密文已不可用）。由 MainActivity 接线。 */
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
    }

    override fun close() { clear(); protect(emptySet()); executor.shutdownNow() }
}
