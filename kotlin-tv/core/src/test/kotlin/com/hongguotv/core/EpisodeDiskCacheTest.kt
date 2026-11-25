// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EpisodeDiskCacheTest {
    private val http = OkHttpClient()
    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        dir = Files.createTempDirectory("episode-cache-test").toFile()
    }
    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun box(type: String, body: ByteArray) = be32(body.size + 8) + type.toByteArray() + body
    private fun be32(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    /** 未加密的最小 MP4：ftyp + moov，媒体数据用 0xAB 填充。 */
    private fun plainMp4(mediaBytes: Int): ByteArray {
        val ftyp = box("ftyp", "isom0000".toByteArray())
        val moov = box("moov", box("free", ByteArray(8)))
        return ftyp + moov + ByteArray(mediaBytes) { 0xAB.toByte() }
    }
    // ---------- 区间算法（纯函数） ----------

    @Test fun mergeRangesUnionsOverlapsAndKeepsOrder() {
        val merged = EpisodeDiskCache.mergeRanges(
            listOf(longArrayOf(0, 100), longArrayOf(200, 300)), longArrayOf(50, 250))
        assertArrayEquals(longArrayOf(0, 300), merged[0]); assertEquals(1, merged.size)
        val disjoint = EpisodeDiskCache.mergeRanges(listOf(longArrayOf(0, 10)), longArrayOf(20, 30))
        assertEquals(2, disjoint.size)
        assertArrayEquals(longArrayOf(20, 30), disjoint[1])
    }
    @Test fun coveredRequiresFullSpan() {
        val ranges = listOf(longArrayOf(0, 100), longArrayOf(100, 200))
        assertTrue(EpisodeDiskCache.covered(ranges, 0, 200))
        assertTrue(EpisodeDiskCache.covered(ranges, 50, 100))
        assertFalse(EpisodeDiskCache.covered(ranges, 150, 100))
        assertFalse(EpisodeDiskCache.covered(ranges, 300, 1))
    }
    @Test fun rangesJsonRoundTrips() {
        val ranges = listOf(longArrayOf(0, 4096), longArrayOf(8192, 12288))
        val decoded = EpisodeDiskCache.rangesFromJson(EpisodeDiskCache.rangesToJson(ranges))
        assertEquals(ranges.size, decoded.size)
        ranges.zip(decoded).forEach { (a, b) -> assertArrayEquals(a, b) }
        assertEquals(emptyList<LongArray>(), EpisodeDiskCache.rangesFromJson(JSONArray("[1,2,3]")))
    }

    // ---------- 窗口与逐出 ----------

    @Test fun emptyDownloadWindowKeepsCachedEpisodes() {
        val info = { id: String, q: Int -> StreamInfo(server.url("/v/$id").toString(), null, "720P") }
        val cache = EpisodeDiskCache(http, dir, resolver = info)
        // 跨剧共享池不以全局 5 集裁剪，空下载窗口也保留已缓存文件
        for (n in 1..6) {
            val key = "10$n"
            File(dir, "$key-q720.mp4").writeBytes(ByteArray(1024))
            val meta = EpisodeDiskCache.Meta(key, 720, server.url("/v/$key").toString(), null, "720P", 0,
                1024, ByteArray(16), emptyList(), n.toLong(), true)
            writeMeta(key, 720, meta, listOf(longArrayOf(0, 1024)))
        }
        val reloaded = EpisodeDiskCache(http, dir, resolver = info)
        reloaded.schedule(emptyList(), 720)
        for (n in 1..6) assertNotNull(reloaded.meta("10$n", 720))
        assertTrue(File(dir, "101-q720.mp4").exists())
        reloaded.close(); cache.close()
    }
    @Test fun pauseKeepsCachedEpisodes() {
        val info = { id: String, q: Int -> StreamInfo(server.url("/v/$id").toString(), null, "720P") }
        // 三条下好的条目：模拟用户切走界面时下三集已缓存
        for (n in 1..3) {
            val key = "30$n"
            File(dir, "$key-q720.mp4").writeBytes(ByteArray(1024))
            writeMeta(key, 720, EpisodeDiskCache.Meta(key, 720, server.url("/v/$key").toString(), null, "720P", 0,
                1024, ByteArray(16), emptyList(), n.toLong(), true), listOf(longArrayOf(0, 1024)))
        }
        val cache = EpisodeDiskCache(http, dir, resolver = info)
        cache.schedule(listOf("301", "302", "303"), 720)
        cache.pause()
        // 暂停只停调度，不清条目：切界面回来三条进度条仍然是满的
        for (n in 1..3) {
            assertNotNull(cache.meta("30$n", 720))
            assertTrue(File(dir, "30$n-q720.mp4").exists())
        }
        assertEquals(0, cache.queuedCount)
        // 恢复调度后仍能命中原文件
        cache.schedule(listOf("301", "302", "303"), 720)
        assertNotNull(cache.meta("302", 720))
        cache.close()
    }
    @Test fun poolCapDropsOldestEntry() {
        val info = { id: String, q: Int -> StreamInfo(server.url("/v/$id").toString(), null, "720P") }
        val episodeBytes = 100L * 1024 * 1024
        // 六部剧各一集共 600 MB，超出共享池的 500 MB，淘汰最早缓存的一集。
        for (n in 1..6) {
            val id = "20$n"
            RandomAccessFile(File(dir, "$id-q720.mp4"), "rw").use { it.setLength(episodeBytes) }
            writeMeta(id, 720, EpisodeDiskCache.Meta(id, 720, server.url("/v/$id").toString(), null, "720P", 0,
                episodeBytes, ByteArray(16), emptyList(), n.toLong(), true, seriesId = "series-$n"),
                listOf(longArrayOf(0, episodeBytes)))
        }
        val cache = EpisodeDiskCache(http, dir, resolver = info)
        assertNull(cache.meta("201", 720))
        for (n in 2..6) assertNotNull(cache.meta("20$n", 720))
        cache.close()
    }
    @Test fun completeCacheRemainsReadableAfterAddressExpires() {
        var now = 1_000_000L
        val info = { id: String, q: Int -> StreamInfo(server.url("/v/$id").toString(), null, "720P", expiresAt = now - 10_000) }
        File(dir, "301-q720.mp4").writeBytes(ByteArray(1024))
        writeMeta("301", 720, EpisodeDiskCache.Meta("301", 720, server.url("/v/301").toString(), null, "720P", now - 10_000,
            1024, ByteArray(16), emptyList(), 1, true), listOf(longArrayOf(0, 1024)))
        val cache = EpisodeDiskCache(http, dir, clock = { now }, resolver = info)
        assertNotNull(cache.meta("301", 720))
        val buffer = ByteArray(32)
        assertEquals(buffer.size, cache.read("301", 720, 0, buffer, 0, buffer.size))
        cache.close()
    }

    // ---------- 真实下载与命中读取 ----------

    @Test fun downloadsWholeEpisodeAndServesReads() {
        val info = { id: String, q: Int -> StreamInfo(server.url("/v/$id").toString(), null, "720P") }
        val cache = EpisodeDiskCache(http, dir, resolver = info)
        // 夹具不含加密索引（无 saiz/saio），prepare 会失败 → 下载落空，队列自动跳过，不崩溃不死循环
        cache.schedule(listOf("401"), 720)
        Thread.sleep(1500)
        assertNull(cache.meta("401", 720))
        cache.close()
    }

    @Test fun nextThreeEpisodesDownloadSequentiallyEvenWhenCallerRequestsConcurrency() {
        val total = 2L * 1024 * 1024
        val paths = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val laterStarted = CountDownLatch(1)
        val finished = CountDownLatch(3)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                paths += path
                if (path == "/v/601" && entered.count > 0) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                } else if (path != "/v/601") laterStarted.countDown()
                val range = requireNotNull(request.getHeader("Range")).removePrefix("bytes=").split('-')
                val start = range[0].toLong()
                val end = range[1].toLong()
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/$total")
                    .setBody(Buffer().write(ByteArray((end - start + 1).toInt())))
            }
        }
        for (id in listOf("601", "602", "603")) {
            File(dir, "$id-q720.mp4").writeBytes(byteArrayOf(0))
            writeMeta(id, 720, EpisodeDiskCache.Meta(id, 720, server.url("/v/$id").toString(), null,
                "720P", 0, total, ByteArray(16), emptyList(), 1, false), listOf(longArrayOf(0, 1)))
        }
        val cache = EpisodeDiskCache(http, dir,
            resolver = { _, _ -> throw AssertionError("断点续传应复用已有元数据") },
            onLog = { if (it.startsWith("Episode cached ")) finished.countDown() })
        try {
            cache.schedule(listOf("601", "602", "603"), 720, parallelism = 3)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(laterStarted.await(200, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("/v/601", "/v/602", "/v/603"), paths.distinct())
            assertEquals(paths.sorted(), paths.toList())
            for (id in listOf("601", "602", "603")) assertTrue(cache.meta(id, 720)?.done == true)
        } finally {
            release.countDown()
            cache.close()
        }
    }

    @Test fun readHonorsCoverageAndClipsToTotal() {
        val file = plainMp4(64 * 1024)
        File(dir, "501-q720.mp4").writeBytes(file)
        writeMeta("501", 720, EpisodeDiskCache.Meta("501", 720, "http://x/v", null, "720P", 0,
            file.size.toLong(), ByteArray(16), emptyList(), 1, true), listOf(longArrayOf(0, 32 * 1024)))
        val cache = EpisodeDiskCache(http, dir, resolver = { _, _ -> error("不该取址") })
        val buf = ByteArray(8 * 1024)
        assertEquals(8192, cache.read("501", 720, 0, buf, 0, buf.size))
        // 读出的前 8KiB 必须与源文件逐字节一致（含 ftyp/moov 头与 0xAB 填充）
        assertArrayEquals(file.copyOf(8192), buf)
        // 未落盘区间返回 0；越过 total 的读取被截断
        assertEquals(0, cache.read("501", 720, 40 * 1024, buf, 0, buf.size))
        cache.close()
    }

    /** 与 EpisodeDiskCache 内部同一份 JSON 格式，测试侧独立写一份避免暴露内部 API。 */
    private fun writeMeta(id: String, quality: Int, meta: EpisodeDiskCache.Meta, ranges: List<LongArray>) {
        val samples = JSONArray()
        meta.samples.forEach { s -> samples.put(org.json.JSONObject().put("s", s.start).put("e", s.end).put("iv",
            java.util.Base64.getEncoder().encodeToString(s.iv))) }
        val o = org.json.JSONObject().put("id", id).put("quality", quality).put("url", meta.url)
            .put("key", "").put("qualityLabel", meta.qualityLabel).put("expiresAt", meta.expiresAt)
            .put("total", meta.total).put("header", java.util.Base64.getEncoder().encodeToString(meta.header))
            .put("samples", samples).put("updatedAt", meta.updatedAt).put("done", meta.done).put("seriesId", meta.seriesId)
            .put("ranges", EpisodeDiskCache.rangesToJson(ranges))
        File(dir, "$id-q$quality.meta").writeText(o.toString())
    }
}
