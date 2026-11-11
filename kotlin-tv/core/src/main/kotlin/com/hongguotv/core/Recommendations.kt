// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 推荐频道的分页信息来自 bookmall/tab 和 cell/change，不使用本地页码。 */
data class RecommendationCursor(
    val cellId: String,
    val sessionId: String,
    val showType: String,
    val clientTemplate: String = "",
    val offset: Int = 0,
)
data class RecommendationPage(val items: List<Series>, val cursor: RecommendationCursor?)

/** 对照开心果 iOS 所用 openhongguo 的 ParseDiscoverTab / ParseCellChange。 */
internal object RecommendationParser {
    private fun rows(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> value.objects()
        is JSONObject -> listOf(value)
        else -> emptyList()
    }

    private fun series(row: JSONObject, fallback: JSONObject = row): Series? {
        val id = row.str("series_id_str").ifBlank { row.str("series_id") }
            .ifBlank { fallback.str("series_id_str").ifBlank { fallback.str("series_id") } }
        val title = seriesTitle(row.str("series_title").ifBlank { row.str("title") }.ifBlank { fallback.str("title") })
        if (id == "0" || !id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return null
        val count = row.optInt("episode_cnt", 0).takeIf { it > 0 } ?: (row.optJSONArray("video_list")?.length() ?: 0)
        return Series(id, title, cleanUrl(row.str("series_cover")), row.str("series_intro"),
            if (count > 0) "全 $count 集" else "")
    }

    private fun collect(cell: JSONObject, out: MutableList<Series>, depth: Int = 0) {
        if (depth > 12) return
        for (video in rows(cell.opt("video_data"))) {
            val detail = video.optJSONObject("video_detail") ?: video
            series(detail, video)?.let(out::add)
            rows(detail.opt("video_detail_list")).mapNotNullTo(out) { series(it) }
        }
        rows(cell.optJSONObject("video_detail")?.opt("video_detail_list")).mapNotNullTo(out) { series(it) }
        for (book in rows(cell.opt("book_data"))) {
            val id = book.str("book_id")
            val title = seriesTitle(book.str("book_name"))
            if (id == "0" || !id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) continue
            val count = book.optInt("serial_count", 0)
            out += Series(id, title, cleanUrl(book.str("thumb_url")), book.str("abstract"),
                if (count > 0) "全 $count 集" else "")
        }
        rows(cell.opt("cell_data")).forEach { collect(it, out, depth + 1) }
    }

    fun initial(root: JSONObject): RecommendationPage {
        val data = root.optJSONObject("data") ?: throw IOException("推荐频道数据暂不可用")
        val tab = data.optJSONArray("tab_item")?.objects()?.firstOrNull { it.optInt("tab_type") == 16 }
            ?: throw IOException("接口未返回推荐频道，请稍后重试")
        val cells = rows(tab.opt("cell_data"))
        val items = mutableListOf<Series>()
        cells.forEach { collect(it, items) }
        val session = tab.str("session_id")
        val candidates = cells.filter { it.str("cell_id_str").isNotBlank() && it.optInt("show_type") > 0 }
        val cell = candidates.firstOrNull { rows(it.opt("cell_data")).isNotEmpty() } ?: candidates.firstOrNull()
        val cursor = if (cell != null && session.isNotBlank()) RecommendationCursor(
            cell.str("cell_id_str"), session, cell.str("show_type"),
            tab.optInt("client_template", 0).takeIf { it > 0 }?.toString().orEmpty(),
        ) else null
        return RecommendationPage(items.distinctBy { it.id }, cursor)
    }

    fun next(root: JSONObject, previous: RecommendationCursor): RecommendationPage {
        val data = root.optJSONObject("data") ?: throw IOException("推荐分页数据暂不可用")
        val items = mutableListOf<Series>()
        data.optJSONObject("cell_view")?.let { collect(it, items) }
        val offset = data.optInt("next_offset", 0)
        val cursor = if (data.optBoolean("has_more", false) && offset > previous.offset) {
            previous.copy(offset = offset, sessionId = data.str("session_id").ifBlank { previous.sessionId })
        } else null
        return RecommendationPage(items.distinctBy { it.id }, cursor)
    }
}
