// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 随心看游标：服务端会话和 offset 都由接口下发，不使用本地页码。 */
data class SuixinCursor(
    val sessionId: String = "",
    val offset: Int = 0,
    val seen: Set<String> = emptySet(),
    val cellId: String = "",
    val planId: String = "",
    val versionTag: String = "",
    val readyToContinue: Boolean = false,
)

data class SuixinTrialHint(val episodeId: String, val episodeCount: Int)

data class SuixinCategoryCursor(val sessionId: String = "", val offset: Int = 0)

data class SuixinPage(
    val items: List<Series>,
    val cursor: SuixinCursor?,
    val trialHints: Map<String, SuixinTrialHint> = emptyMap(),
    val diagnostic: String = "",
    val categoryCursor: SuixinCategoryCursor? = null,
)

/** 随心看接口的纯解析部分，便于单测和另端复用。 */
object SuixinFeedParser {
    private val id = Regex("[0-9]{1,30}")

    private fun rows(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull(value::optJSONObject)
        is JSONObject -> listOf(value)
        else -> emptyList()
    }

    private fun str(row: JSONObject, key: String) = if (row.isNull(key)) "" else row.optString(key).trim()

    private fun series(row: JSONObject, fallback: JSONObject = row): Series? {
        val detail = row.optJSONObject("video_detail")
        val value = if (detail != null) detail else row
        val seriesId = str(value, "series_id").ifBlank { str(value, "book_id") }
            .ifBlank { str(fallback, "series_id").ifBlank { str(fallback, "book_id") } }
        val title = str(value, "series_title").ifBlank { str(value, "book_name") }
            .ifBlank { str(value, "title") }.ifBlank { str(fallback, "series_title") }
            .ifBlank { str(fallback, "book_name") }.ifBlank { str(fallback, "title") }
        if (!id.matches(seriesId) || title.isBlank()) return null
        val count = value.optInt("episode_cnt", fallback.optInt("episode_cnt", 0))
        return Series(
            seriesId,
            title,
            cleanUrl(str(value, "series_cover").ifBlank { str(value, "cover") }
                .ifBlank { str(fallback, "series_cover") }),
            str(value, "series_intro").ifBlank { str(value, "video_desc") },
            if (count > 0) "共 $count 集" else "",
        )
    }

    private fun collectCell(cell: JSONObject, out: MutableList<Series>, depth: Int = 0) {
        if (depth > 12) return
        for (video in rows(cell.opt("video_data"))) {
            val detail = video.optJSONObject("video_detail") ?: video
            series(detail, video)?.let(out::add)
            rows(detail.opt("video_detail_list")).mapNotNullTo(out) { series(it) }
        }
        rows(cell.optJSONObject("video_detail")?.opt("video_detail_list")).mapNotNullTo(out) { series(it) }
        for (book in rows(cell.opt("book_data"))) {
            val seriesId = str(book, "book_id")
            val title = str(book, "book_name")
            if (!id.matches(seriesId) || title.isBlank()) continue
            val count = book.optInt("serial_count", 0)
            out += Series(seriesId, title, cleanUrl(str(book, "thumb_url")), str(book, "abstract"),
                if (count > 0) "共 $count 集" else "")
        }
        rows(cell.opt("cell_data")).forEach { collectCell(it, out, depth + 1) }
    }

    private fun hints(rows: List<JSONObject>, items: List<Series>): Map<String, SuixinTrialHint> {
        val ids = items.mapTo(hashSetOf()) { it.id }
        return rows.mapNotNull { row ->
            val value = row.optJSONObject("video_detail") ?: row
            val seriesId = str(value, "series_id").ifBlank { str(value, "book_id") }
            if (seriesId !in ids) return@mapNotNull null
            val first = str(value, "first_vid").ifBlank {
                rows(value.opt("video_list")).firstOrNull { it.optInt("vid_index") == 1 }?.let { str(it, "vid") }.orEmpty()
            }
            val count = value.optInt("episode_cnt", 0)
            if (id.matches(first) && count > 0) seriesId to SuixinTrialHint(first, count) else null
        }.toMap()
    }

    private fun tab(root: JSONObject): JSONObject {
        if (root.optInt("code", -1) != 0) throw IOException("随心看暂不可用（错误码 ${root.optInt("code", -1)}）")
        return root.optJSONObject("data")?.optJSONArray("tab_item")?.let { list ->
            (0 until list.length()).mapNotNull(list::optJSONObject).firstOrNull { it.optInt("tab_type") == 16 }
        } ?: throw IOException("随心看数据格式已变化")
    }

    /** 解析 bookmall/tab/v1/ 的首屏。 */
    fun initial(root: JSONObject): SuixinPage {
        val tab = tab(root)
        val cells = rows(tab.opt("cell_data"))
        val items = mutableListOf<Series>()
        cells.forEach { collectCell(it, items) }
        val unique = items.distinctBy { it.id }
        if (unique.isEmpty()) throw IOException("随心看暂未返回可播放剧集")
        val session = str(tab, "session_id")
        val cell = cells.firstOrNull { str(it, "cell_id_str").ifBlank { str(it, "cell_id") }.isNotBlank() }
        val cellId = cell?.let { str(it, "cell_id_str").ifBlank { str(it, "cell_id") } }.orEmpty()
        val planId = str(tab, "bookstore_id").ifBlank { str(tab, "plan_id") }
        val version = str(tab, "version_tag")
        val offset = tab.optInt("next_offset", tab.optInt("offset", 0)).coerceAtLeast(0)
        val cursor = SuixinCursor(
            sessionId = session,
            offset = offset,
            cellId = cellId,
            planId = planId,
            versionTag = version,
            // 官方单列续刷只有完整 cell/plan/version 才启用；否则继续 tab 按条 offset。
            readyToContinue = session.isNotBlank() && cellId.isNotBlank() &&
                planId.isNotBlank() && version.length <= 4096,
        )
        val rows = cells.flatMap { it.optJSONArray("cell_data")?.let(::rows).orEmpty() }
            .flatMap { it.optJSONArray("video_data")?.let(::rows).orEmpty() }
        return SuixinPage(unique, cursor, hints(rows, unique), "tab=16 cells=${cells.size} items=${unique.size} continue=${cursor.readyToContinue}")
    }

    /** 解析 bookmall/cell/change/v1/ 的续刷响应。 */
    fun next(root: JSONObject, previous: SuixinCursor): SuixinPage {
        if (root.optInt("code", -1) != 0) throw IOException("随心看续刷暂不可用（错误码 ${root.optInt("code", -1)}）")
        val data = root.optJSONObject("data") ?: throw IOException("随心看续刷数据格式已变化")
        val view = data.optJSONObject("cell_view") ?: throw IOException("随心看续刷单元缺失")
        val items = mutableListOf<Series>()
        collectCell(view, items)
        val unique = items.distinctBy { it.id }
        val nextOffset = data.optInt("next_offset", -1)
        val nextSession = str(data, "session_id").ifBlank { previous.sessionId }
        val cursor = if (nextOffset > previous.offset) {
            previous.copy(offset = nextOffset, sessionId = nextSession)
        } else null
        return SuixinPage(unique, cursor, diagnostic = "cell=1 items=${unique.size} offset=${previous.offset} next=$nextOffset hasMore=${data.optBoolean("has_more", false)}")
    }

    /** 解析分类随心看 landpage 响应为同一卡片结构。 */
    fun category(root: JSONObject, previous: SuixinCategoryCursor? = null): SuixinPage {
        if (root.optInt("code", -1) != 0) throw IOException("分类随心看暂不可用（错误码 ${root.optInt("code", -1)}）")
        val data = root.optJSONObject("data") ?: throw IOException("分类随心看数据格式已变化")
        val values = rows(data.opt("video_data"))
        val items = values.mapNotNull { series(it) }.distinctBy { it.id }
        val nextOffset = data.optInt("next_offset", -1)
        val session = str(data, "session_id").ifBlank { previous?.sessionId.orEmpty() }
        val offset = nextOffset.takeIf { it >= 0 } ?: (previous?.offset ?: 0)
        val categoryCursor = if (data.optBoolean("has_more", true)) SuixinCategoryCursor(session, offset) else null
        // 分类接口与推荐接口一样带 first_vid / video_list，试播提示继续沿用同一套解析。
        // 分类与书城流共用卡片字段；分类游标单独保存，不能混用 tab 会话。
        val pageCursor = SuixinCursor(sessionId = session, offset = offset, planId = "category")
        return SuixinPage(items, pageCursor, hints(values, items), "category items=${items.size}", categoryCursor)
    }
}

/** 旧版目录接口暂不可用时的随心看请求通道：书城推荐使用设备档案签名和游客票据。 */
class SuixinFeedClient(
    private val http: OkHttpClient,
    private val signer: RemoteSigner,
) {
    private companion object {
        val HOSTS = listOf(
            "https://api5-normal-sinfonlinea.fqnovel.com",
            "https://api5-normal-hl.fqnovel.com",
            "https://api5-normal-lf.fqnovel.com",
        )
        const val LIMIT = 16 * 1024 * 1024
    }

    private val attempt = AtomicInteger(0)

    private fun text(signed: SignedRequest): String {
        val request = Request.Builder().url(signed.url)
            .header("Accept", "application/json")
            .header("X-XS-From-Web", "0")
            .header("Sdk-Version", "2")
            .apply {
                signed.headers.forEach { (key, value) -> header(key, value) }
                val payload = signed.body
                if (payload == null) get() else post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }
            .build()
        return RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("随心看请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("随心看接口返回空响应")
            if (body.contentLength() > LIMIT) throw IOException("随心看响应过大")
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= LIMIT) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (bytes.size > LIMIT) throw IOException("随心看响应过大")
            val result = String(bytes, Charsets.UTF_8)
            if (result.isBlank()) throw IOException("随心看接口返回空响应")
            result
        }
    }

    private fun host() = HOSTS[attempt.getAndIncrement().mod(HOSTS.size)]

    private fun request(url: String, values: Map<String, String>, body: ByteArray? = null): JSONObject {
        // 分类接口是 POST；书城 tab/cell 接口继续使用游客票据签名。
        val signed = if (body == null) signer.signCatalog(url, values) else signer.sign(url, values, body)
        return JSONObject(text(signed))
    }

    fun initial(offset: Int = 0): SuixinPage {
        val values = linkedMapOf(
            "tab_type" to "16",
            "bottom_tab_type" to "7",
            "landing_bottom_tab_type" to "7",
            "offset" to offset.toString(),
            "client_req_type" to if (offset == 0) "1" else "2",
        )
        return SuixinFeedParser.initial(request("${host()}/reading/bookapi/bookmall/tab/v1/", values))
    }

    fun next(cursor: SuixinCursor): SuixinPage {
        val values = linkedMapOf(
            "tab_type" to "16",
            "bottom_tab_type" to "7",
            "landing_bottom_tab_type" to "7",
            "cell_id" to cursor.cellId,
            "plan_id" to cursor.planId,
            "version_tag" to cursor.versionTag,
            "client_template" to "7",
            "change_type" to "0",
            "offset" to cursor.offset.toString(),
            "client_req_type" to "2",
            "filter_ids" to cursor.seen.joinToString(","),
        )
        if (cursor.sessionId.isNotBlank()) values["session_id"] = cursor.sessionId
        // 每个参数都交给 signCatalog 组装，避免二次编码和签名查询串不一致。
        return SuixinFeedParser.next(
            request("${host()}/reading/bookapi/bookmall/cell/change/v1/", values),
            cursor,
        )
    }

    fun category(comic: Boolean, cursor: SuixinCategoryCursor? = null): SuixinPage {
        val genre = if (comic) "comic_series" else "short_play"
        val scene = if (comic) "comic_series" else "default"
        val selector = JSONObject().put("genre", JSONArray().put(genre))
        listOf("sort", "gender", "category_dim_theme", "category_dim_role", "category_dim_epoch", "online_time", "creation_status")
            .forEach { selector.put(it, JSONArray()) }
        val body = JSONObject().put("req_scene", scene).put("offset", cursor?.offset ?: 0).put("limit", 24)
            .put("req_type", "only_content").put("need_selector_panel", false)
            .put("client_req_type", if (cursor == null) 3 else 2).put("session_id", cursor?.sessionId.orEmpty()).put("filter_ids", "")
            .put("select_items", selector).toString().toByteArray(Charsets.UTF_8)
        return SuixinFeedParser.category(
            request("${host()}/reading/distribution/category/landpage/v/", emptyMap(), body),
            cursor,
        )
    }
}
