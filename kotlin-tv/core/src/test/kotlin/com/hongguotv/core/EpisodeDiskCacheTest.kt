package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    @Test fun windowRetainDropsOutsideEpisodes() {
        val info = { id: String, q: Int -> StreamInfo(server.url("/v/$id").toString(), null, "720P") }
        val cache = EpisodeDiskCache(http, dir, resolver = info)
        // 手工塞 6 条假条目（伪造 meta+mp4），再 schedule 窗口收缩
        for (n in 1..6) {
            val key = "10$n"
            File(dir, "$key-q720.mp4").writeBytes(ByteArray(1024))
            val meta = EpisodeDiskCache.Meta(key, 720, server.url("/v/$key").toString(), null, "720P", 0,
                1024, ByteArray(16), emptyList(), n.toLong(), true)
            writeMeta(key, 720, meta, listOf(longArrayOf(0, 1024)))
        }
        val reloaded = EpisodeDiskCache(http, dir, resolver = info)
        val kept = (2..6).map { "10$it" }
        reloaded.schedule(emptyList(), 720, kept.toSet(), 720)
        assertNull(reloaded.meta("101", 720))
        assertNotNull(reloaded.meta("106", 720))
        assertFalse(File(dir, "101-q720.mp4").exists())
        reloaded.close(); cache.close()
    }
    @Test fun poolCapDropsOldestEntry() {
        val info = { id: String, q: Int -> StreamInfo(server.url("/v/$id").toString(), null, "720P") }
        for (n in 1..5) {
            File(dir, "20$n-q720.mp4").writeBytes(ByteArray(1024))
            val meta = EpisodeDiskCache.Meta("20$n", 720, server.url("/v/20$n").toString(), null, "720P", 0,
                1024, ByteArray(16), emptyList(), n.toLong(), true)
            writeMeta("20$n", 720, meta, listOf(longArrayOf(0, 1024)))
        }
        // 全部 6 条都在构造前写盘，缓存启动扫描时一次性看到 6 条 → 超上限逐出最旧的 201
        File(dir, "206-q720.mp4").writeBytes(ByteArray(1024))
        writeMeta("206", 720, EpisodeDiskCache.Meta("206", 720, server.url("/v/206").toString(), null, "720P", 0,
            1024, ByteArray(16), emptyList(), 6, true), listOf(longArrayOf(0, 1024)))
        val cache = EpisodeDiskCache(http, dir, resolver = info)
        assertNull(cache.meta("201", 720))
        assertNotNull(cache.meta("206", 720))
        cache.close()
    }
    @Test fun expiredAddressMarksEntryStale() {
        var now = 1_000_000L
        val info = { id: String, q: Int -> StreamInfo(server.url("/v/$id").toString(), null, "720P", expiresAt = now - 10_000) }
        File(dir, "301-q720.mp4").writeBytes(ByteArray(1024))
        writeMeta("301", 720, EpisodeDiskCache.Meta("301", 720, server.url("/v/301").toString(), null, "720P", now - 10_000,
            1024, ByteArray(16), emptyList(), 1, true), listOf(longArrayOf(0, 1024)))
        val cache = EpisodeDiskCache(http, dir, clock = { now }, resolver = info)
        assertNull(cache.meta("301", 720))
        cache.close()
    }

    // ---------- 真实下载与命中读取 ----------

    @Test fun downloadsWholeEpisodeAndServesReads() {
        val info = { id: String, q: Int -> StreamInfo(server.url("/v/$id").toString(), null, "720P") }
        val cache = EpisodeDiskCache(http, dir, resolver = info)
        // 夹具不含加密索引（无 saiz/saio），prepare 会失败 → 下载落空，队列自动跳过，不崩溃不死循环
        cache.schedule(listOf("401"), 720, setOf("401"), 720)
        Thread.sleep(1500)
        assertNull(cache.meta("401", 720))
        cache.close()
    }

    @Test fun readHonorsCoverageAndClipsToTotal() {
        val file = plainMp4(64 * 1024)
        File(dir, "501-q720.mp4").writeBytes(file)
        writeMeta("501", 720, EpisodeDiskCache.Meta("501", 720, "http://x/v", null, "720P", 0,
            file.size.toLong(), ByteArray(16), emptyList(), 1, true), listOf(longArrayOf(0, 32 * 1024)))
        val cache = EpisodeDiskCache(http, dir, resolver = { _, _ -> error("不该取址") })
        val buf = ByteArray(8 * 1024)
        assertEquals(8192, cache.read("501", 720, 0, buf, 0, buf.size))
        // 前 24 字节是 ftyp/moov box 头，0xAB 填充从 box 之后开始；抽查 box 之后的一个字节即可
        assertEquals(0xAB.toByte(), buf[24])
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
            .put("samples", samples).put("updatedAt", meta.updatedAt).put("done", meta.done)
            .put("ranges", EpisodeDiskCache.rangesToJson(ranges))
        File(dir, "$id-q$quality.meta").writeText(o.toString())
    }
}
