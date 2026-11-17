// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 搜索统一使用无类型筛选的综合页，失败再回退剧集页签与网站；结果不再按内容类型裁剪。 */
internal enum class SearchRoute(val tab: Int) {
    COMMON(11),
    SERIES(29),
    WEBSITE(0),
}

internal data class SearchResponse(
    val items: List<Series>, val hasMore: Boolean, val offset: Int?, val searchId: String, val passback: String,
)

internal object SearchResults {
    private fun objects(value: Any?): List<JSONObject> = when(value) {
        is JSONObject -> listOf(value)
        is JSONArray -> value.objects()
        else -> emptyList()
    }

    fun parse(root: JSONObject, route: SearchRoute): SearchResponse {
        if(root.optInt("code", -1) != 0) throw IOException("搜索接口返回错误")
        val tabs = root.optJSONArray("search_tabs") ?: root.optJSONObject("data")?.optJSONArray("search_tabs")
            ?: throw IOException("搜索格式异常")
        val tab = tabs.objects().firstOrNull { it.optInt("tab_type") == route.tab }
            ?: throw IOException("搜索页签暂不可用")
        val rows = mutableListOf<JSONObject>()
        fun collect(row: JSONObject, depth: Int = 0) {
            if(depth > 4) return
            rows += row
            row.optJSONArray("cell_data")?.objects()?.forEach { collect(it, depth + 1) }
            val lynx = row.opt("lynx_data")
            val nested = when(lynx) {
                is String -> runCatching { org.json.JSONTokener(lynx).nextValue() }.getOrNull()
                else -> lynx
            }
            objects(nested).forEach { collect(it, depth + 1) }
        }
        tab.optJSONArray("data")?.objects()?.forEach { collect(it) }
        val items = rows.flatMap { row ->
            objects(row.opt("video_data")).flatMap { video ->
                val detail = video.optJSONObject("video_detail") ?: video
                val cards = objects(detail.opt("video_detail_list"))
                (if(cards.isEmpty()) listOf(detail) else cards).mapNotNull { card ->
                    val id = card.str("series_id_str").ifBlank { card.str("series_id") }
                        .ifBlank { video.str("series_id_str") }.ifBlank { video.str("series_id") }
                        .ifBlank { row.str("book_id") }.ifBlank { row.str("search_result_id") }
                    val title = card.str("series_title").ifBlank { card.str("title") }
                        .ifBlank { video.str("title") }
                        .ifBlank { row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty() }
                        .ifBlank { row.str("cell_name") }
                    if(!id.matches(Regex("[0-9]{1,30}")) || id == "0" || title.isBlank()) return@mapNotNull null
                    val count = card.optInt("episode_cnt", video.optInt("episode_cnt"))
                    Series(id, seriesTitle(title),
                        cleanUrl(card.str("series_cover").ifBlank { card.str("cover") }
                            .ifBlank { video.str("cover").ifBlank { video.str("cover_url") } }),
                        card.str("series_intro").ifBlank { video.str("video_desc") },
                        card.str("episode_right_text").ifBlank { if(count > 0) "全 $count 集" else video.str("rec_text") },
                        card.str("sub_title").ifBlank { video.str("sub_title") })
                }
            }
        }.distinctBy { it.id }
        return SearchResponse(items, tab.optBoolean("has_more"), tab.str("next_offset").toIntOrNull(),
            tab.str("search_id"), tab.str("passback"))
    }

    /** 只排列已有的季，不为排序发出额外的详情、联想或官网请求。 */
    fun orderSeasons(items: List<Series>): List<Series> {
        val groups = items.groupBy { base(it.title) }
        val placed = mutableSetOf<String>()
        return items.flatMap { item ->
            val key = base(item.title)
            if(!placed.add(key)) emptyList() else groups.getValue(key).sortedBy {
                seasonNumber(it.title).let { number -> if(number == Int.MAX_VALUE) 1 else number }
            }
        }
    }

    private fun base(title: String) = title.replace(SeasonPattern, "").trim().trim('：', ':', '-', '·', ' ')

    fun suggestions(root: JSONObject): List<String> {
        if(root.optInt("code", -1) != 0) return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()
        for(key in listOf("query_result_v2", "suggest_result", "query_result")) {
            val list = data.optJSONArray(key) ?: continue
            val words = (0 until list.length()).mapNotNull { index ->
                when(val row = list.opt(index)) {
                    is JSONObject -> row.str("name").ifBlank { row.optJSONObject("search_high_light")?.str("text").orEmpty() }
                    is String -> row.trim()
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }.distinct().take(20)
            if(words.isNotEmpty()) return words
        }
        return emptyList()
    }
}
