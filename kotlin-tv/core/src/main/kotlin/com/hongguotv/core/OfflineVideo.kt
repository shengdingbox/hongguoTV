// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** 手动离线下载使用 4 MiB 有界 Range，512 KiB 流式解密落盘，不经过播放器的小块缓存。 */
class OfflineVideo(
    http: OkHttpClient,
    val info: StreamInfo,
    private val onLog: (String) -> Unit = {},
) : Closeable {
    // 媒体传输沿用连接池与读超时，不继承接口请求的整次调用时限。
    private val transport = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    private val urls = (listOf(info.url) + info.backupUrls).distinct()
    private val calls = mutableSetOf<Call>()
    @Volatile private var closed = false
    private var preferred = 0
    var total: Long = 0; private set
    private lateinit var prepared: MediaCrypto.Prepared

    private fun ensureActive() {
        RequestScope.current()?.ensureActive()
        if (closed) throw IOException("离线下载已取消")
    }

    private fun <T> range(url: String, start: Long, end: Long, consume: (Response, Long) -> T): T {
        ensureActive()
        val request = Request.Builder().url(url)
            .header("User-Agent", VendorConstants.VIDEO_UA)
            .header("Referer", "https://novel.snssdk.com/")
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity").build()
        val call = transport.newCall(request)
        synchronized(calls) {
            if (closed) { call.cancel(); throw IOException("离线下载已取消") }
            calls += call
        }
        try {
            return RequestScope.execute(call) { response ->
                if (response.code != 206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
                val match = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("视频分段响应异常")
                val begin = match.groupValues[1].toLongOrNull()
                val finish = match.groupValues[2].toLongOrNull()
                val size = match.groupValues[3].toLongOrNull() ?: throw IOException("视频大小无效")
                val last = minOf(end, size - 1)
                if (size <= 0 || start >= size || begin != start || finish != last)
                    throw IOException("视频分段范围不匹配")
                if (total != 0L && size != total) throw IOException("视频资源已变化，请重新获取地址")
                total = size
                consume(response, last - start + 1)
            }
        } finally {
            synchronized(calls) { calls -= call }
        }
    }

    private fun readRange(start: Long, end: Long): ByteArray {
        var failure: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            ensureActive()
            val candidate = (preferred + attempt) % urls.size
            try {
                return range(urls[candidate], start, end) { response, length ->
                    (response.body ?: throw IOException("视频分段为空")).source().readByteArray(length)
                }.also { preferred = candidate }
            } catch (problem: IOException) {
                ensureActive()
                failure = problem
                onLog("offline header retry=${attempt + 1} cdn=${candidate + 1}: ${problem.message}")
            }
        }
        throw failure ?: IOException("视频索引读取失败")
    }

    fun prepare(): OfflineVideo {
        if (::prepared.isInitialized) return this
        val first = readRange(0, PROBE_BYTES - 1L)
        val headerEnd = MediaCrypto.headerEnd(first, total)
        val header = if (headerEnd > first.size) first + readRange(first.size.toLong(), headerEnd - 1L)
            else first.copyOf(headerEnd)
        prepared = MediaCrypto.prepare(header, info.key)
        if (prepared.samples.any { it.end > total }) throw IOException("视频样本超出资源大小")
        return this
    }

    fun preparedSnapshot(): MediaCrypto.Prepared {
        check(::prepared.isInitialized) { "离线视频尚未准备" }
        return prepared
    }

    /** 文件长度是已解密的连续断点；块失败后从最后完整写入位置续传，保留同一资源的进度。 */
    fun writeTo(output: RandomAccessFile, onWritten: (Int) -> Unit, onProgress: (Long, Long) -> Unit) {
        check(::prepared.isInitialized) { "离线视频尚未准备" }
        require(output.length() in 0..total) { "离线断点超出文件长度" }
        var position = output.length()
        output.seek(position)
        var publishedAt = 0L
        onProgress(position, total)
        while (position < total) {
            val blockEnd = minOf(total - 1, position + BLOCK_BYTES - 1)
            var complete = false
            var failure: IOException? = null
            val firstCandidate = preferred
            for (attempt in 0 until MAX_ATTEMPTS) {
                ensureActive()
                val candidate = (firstCandidate + attempt) % urls.size
                try {
                    range(urls[candidate], position, blockEnd) { response, _ ->
                        val source = (response.body ?: throw IOException("视频分段为空")).source()
                        while (position <= blockEnd) {
                            ensureActive()
                            val count = minOf(BUFFER_BYTES.toLong(), blockEnd - position + 1).toInt()
                            // 一次累积满 512 KiB 再解密，避免按网络的零碎读取反复创建加密运算。
                            val raw = source.readByteArray(count.toLong())
                            if (raw.size != count) throw EOFException("视频分段提前结束")
                            val clear = MediaCrypto.decrypt(raw, position, info.key, prepared)
                            output.write(clear)
                            position += clear.size
                            onWritten(clear.size)
                            val now = System.nanoTime() / 1_000_000
                            if (now - publishedAt >= 250 || position == total) {
                                publishedAt = now
                                onProgress(position, total)
                            }
                        }
                    }
                    preferred = candidate
                    complete = true
                    break
                } catch (problem: IOException) {
                    ensureActive()
                    failure = problem
                    onLog("offline block retry=${attempt + 1} cdn=${candidate + 1} offset=$position: ${problem.message}")
                    // 字节已完整写入时，不因响应关闭失败重新请求空区间。
                    if (position > blockEnd) { complete = true; break }
                    output.setLength(position)
                    output.seek(position)
                }
            }
            if (!complete) throw IOException("分块下载失败（$position/$total）：${failure?.message ?: "未知错误"}", failure)
        }
        onProgress(position, total)
    }

    override fun close() {
        synchronized(calls) {
            closed = true
            calls.toList().forEach { it.cancel() }
            calls.clear()
        }
    }

    private companion object {
        const val BLOCK_BYTES = 4L * 1024 * 1024
        const val BUFFER_BYTES = 512 * 1024
        const val PROBE_BYTES = 256 * 1024
        const val MAX_ATTEMPTS = 6
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}
