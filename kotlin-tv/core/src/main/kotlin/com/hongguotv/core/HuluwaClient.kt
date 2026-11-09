// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 葫芦娃接口返回的业务错误，带后端给的 code 与中文提示。 */
class HuluwaException(val code: Int, message: String) : IOException(message)

/** 榜单条目：剧集信息、后端给的热度文案与名次。 */
data class RankedSeries(val series: Series, val heat: String, val rank: Int)

/** 榜单结果：内容分类与游标由后端给出，电视只透传。 */
data class RankingPage(
    val items: List<RankedSeries>, val content: String, val board: String, val cursor: String,
    val hasMore: Boolean,
)

/**
 * 葫芦娃服务客户端：电视端不再自己生成红果签名，而是调用葫芦娃后端的只读接口。
 *
 * <p>访问密钥内置于 APK（Authorization: Bearer）。后端负责设备、签名与 110 自动恢复，
 * 电视只拿剧集列表、分集与播放地址；视频解密仍在本机完成。</p>
 */
class HuluwaClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build(),
    /** 服务地址与密钥内置于 APK；保留参数只为测试替身使用。 */
    private val serverUrl: String = HuluwaConfig.SERVER_URL,
    private val accessKey: String = HuluwaConfig.ACCESS_KEY,
) {
    private fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        val joined = query.entries.filter { it.value.isNotBlank() }
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val url = serverUrl + path + if (joined.isEmpty()) "" else "?$joined"
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $accessKey")
            .header("Accept", "application/json")
            .get().build()
        return RequestScope.execute(http.newCall(request)) { response ->
            val body = response.body ?: throw IOException("葫芦娃服务返回空内容")
            val text = readLimited(body.byteStream(), MAX_BODY)
            // 拦截器在鉴权失败时直接写 HTTP 401。
            if (response.code == 401) throw HuluwaException(401, "葫芦娃服务拒绝了访问密钥，请确认前后端内置密钥一致")
            val envelope = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IOException("葫芦娃服务返回的数据格式不正确")
            val code = envelope.optInt("code", -1)
            if (code != 200) throw HuluwaException(code, envelope.str("message").ifBlank { "葫芦娃服务请求失败（$code）" })
            envelope.optJSONObject("data") ?: JSONObject()
        }
    }

    /** 逐块读取并限制总长度：Android 8 上不能依赖 Java 9 的 readNBytes。 */
    private fun readLimited(input: java.io.InputStream, limit: Int): String {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (out.size() <= limit) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        if (out.size() > limit) throw IOException("葫芦娃服务返回内容过大")
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 首页推荐短剧。 */
    fun discover(): List<Series> = seriesList(get("/api/tv/discover").optJSONArray("items"))

    /** 搜索短剧，offset 由调用方按 20 递增。 */
    fun search(keyword: String, offset: Int): CatalogPage {
        require(keyword.isNotBlank() && keyword.length <= 100) { "请输入 1–100 字的剧名" }
        require(offset in 0..2000) { "搜索页码超出范围" }
        val data = get("/api/tv/search", mapOf("query" to keyword, "offset" to offset.toString()))
        return CatalogPage(seriesList(data.optJSONArray("items")), data.optBoolean("hasMore", false))
    }

    /** 榜单：content/board/cursor 为空时由后端使用默认榜单。 */
    fun ranking(content: String = "", board: String = "", cursor: String = ""): RankingPage {
        val data = get("/api/tv/ranking", mapOf("content" to content, "board" to board, "cursor" to cursor))
        val rows = data.optJSONArray("items") ?: JSONArray()
        val ranked = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            parseSeries(row)?.let { RankedSeries(it, row.str("score"), index + 1) }
        }.distinctBy { it.series.id }
        return RankingPage(ranked, data.str("content"), data.str("board"), data.str("cursor"),
            data.optBoolean("hasMore", false))
    }

    /** 剧集详情：后端返回的每集 id 就是播放时使用的 video_id。 */
    fun detail(id: String): Detail {
        require(id.matches(ID)) { "剧集编号无效" }
        val data = get("/api/tv/series/$id")
        val rows = data.optJSONArray("episodes") ?: JSONArray()
        val ids = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.str("id") }
            .filter { it.matches(ID) }.distinct()
        if (ids.isEmpty()) throw IOException("该剧暂未返回可用分集")
        return Detail(
            Series(id, data.str("title").ifBlank { "未命名短剧" }, cleanUrl(data.str("cover")),
                data.str("intro"), "全 ${ids.size} 集", ""),
            ids,
        )
    }

    /** 播放信息：挑选电视可解码的清晰度，并保留 spade_a 用于本地解密。 */
    fun stream(videoId: String, maxQuality: Int = 1080): StreamInfo {
        require(videoId.matches(ID)) { "分集编号无效" }
        val rows = get("/api/tv/episodes/$videoId/playback").optJSONArray("streams") ?: JSONArray()
        val candidates = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { row ->
            val url = cleanUrl(row.str("url"))
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            // ByteVC2 / BVC2 在 Android 的 MP4 解析器里可能只有音频轨，直接排除。
            if (row.str("codec").lowercase() in setOf("bytevc2", "bvc2")) return@mapNotNull null
            val encrypted = row.optBoolean("encrypted", false)
            val spade = row.str("spadeA")
            if (encrypted && (row.str("encryptionMethod") != "cenc-aes-ctr" || spade.isBlank())) return@mapNotNull null
            val definition = row.str("definition")
            // 竖屏短剧的 height 是长边（720p 会是 1280），不能拿它当清晰度；
            // definition 标注的短边才是用户理解的 360P/720P/1080P。
            val quality = ContentRepository.qualityNumber(definition)
                .takeIf { it > 0 } ?: row.optInt("height", 0)
            PlaybackRow(url, quality, spade, definition)
        }.sortedByDescending { it.quality }.toList()
        val selected = candidates.firstOrNull { it.quality <= maxQuality } ?: candidates.lastOrNull()
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val key = selected.spade.takeIf { it.isNotEmpty() }?.let { MediaCrypto.deriveKey(it) }
        val quality = if (selected.quality > 0) "${selected.quality}P" + (if (selected.quality > maxQuality) "（兼容资源）" else "")
            else selected.definition.ifBlank { "自动" }
        return StreamInfo(selected.url, key, quality)
    }

    private data class PlaybackRow(val url: String, val quality: Int, val spade: String, val definition: String)

    private fun parseSeries(row: JSONObject): Series? {
        val id = row.str("id")
        val title = row.str("title")
        if (!id.matches(ID) || title.isBlank()) return null
        val count = row.optInt("episodeCount", 0)
        return Series(id, title, cleanUrl(row.str("cover")), row.str("intro"),
            if (count > 0) "全 $count 集" else "", row.str("tags"))
    }

    private fun seriesList(rows: JSONArray?): List<Series> {
        if (rows == null) return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::parseSeries) }.distinctBy { it.id }
    }

    private companion object {
        val ID = Regex("[0-9]{1,24}")
        const val MAX_BODY = 4 * 1024 * 1024
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
