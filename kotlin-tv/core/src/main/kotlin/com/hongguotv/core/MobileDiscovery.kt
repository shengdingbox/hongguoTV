// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/** 搜索页的作品榜、热点话题榜和猜你想搜，分组以服务端目录为准。 */
data class HotSearchItem(val series: Series, val position: Int)
data class HotSearchTopic(val title: String, val query: String, val heat: String)
data class HotSearchBoard(val name: String, val items: List<HotSearchItem>, val topics: List<HotSearchTopic>)
data class HotSearchPage(val guesses: List<HotSearchItem>, val boards: List<HotSearchBoard>)
data class MobileRankBoard(val id: String, val name: String, val selected: Boolean)
data class MobileRankContent(val id: String, val name: String, val selected: Boolean, val boards: List<MobileRankBoard>)
/** 游标仅可续接所属内容和榜单，记录已展示 ID，避免平台分页重复返回。 */
data class MobileRankCursor(val contentId: String, val boardId: String, val offset: Int, val sessionId: String, val seen: List<String>)
data class MobileRankPage(val contents: List<MobileRankContent>, val contentId: String, val boardId: String,
    val items: List<Series>, val cursor: MobileRankCursor?)

internal object MobileDiscoveryParser {
    private val idPattern = Regex("[0-9]{1,30}")
    private fun hotItems(rows: JSONArray?): List<HotSearchItem> = rows?.objects().orEmpty().mapNotNull { row ->
        val id = row.str("series_id")
        val title = seriesTitle(row.str("title"))
        if (id == "0" || !idPattern.matches(id) || title.isBlank()) return@mapNotNull null
        val count = row.optInt("episode_cnt")
        val badge = row.str("card_tips").ifBlank { row.str("rec_text") }.ifBlank { if (count > 0) "全 $count 集" else "" }
        Series(id, title, cleanUrl(row.str("cover")), badge = badge)
    }.distinctBy { it.id }.mapIndexed { index, series -> HotSearchItem(series, index + 1) }

    fun hot(root: JSONObject): HotSearchPage {
        val guesses = mutableListOf<HotSearchItem>()
        val boards = mutableListOf<HotSearchBoard>()
        for (cell in root.optJSONArray("data")?.objects().orEmpty()) {
            val raw = cell.opt("lynx_data")
            val cells = when (raw) {
                is JSONArray -> raw
                is String -> if (raw.isBlank()) continue else JSONArray(raw)
                else -> continue
            }
            for (row in cells.objects()) {
                guesses += hotItems(row.optJSONArray("video_data"))
                for (board in row.optJSONArray("cell_data")?.objects().orEmpty()) {
                    val items = hotItems(board.optJSONArray("video_data"))
                    val topics = board.optJSONArray("video_topic")?.objects().orEmpty().mapNotNull { topic ->
                        val title = seriesTitle(topic.str("title"))
                        if (title.isBlank()) return@mapNotNull null
                        val query = runCatching { URI(topic.str("url")).rawQuery.orEmpty().split('&')
                            .firstOrNull { it.substringBefore('=') == "directQueryWord" }
                            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") } }.getOrNull().orEmpty()
                        HotSearchTopic(title, query.ifBlank { title }, topic.optJSONArray("secondary_info_list")?.optJSONObject(0)?.str("content").orEmpty())
                    }
                    if (items.isNotEmpty() || topics.isNotEmpty()) boards += HotSearchBoard(board.str("cell_name").ifBlank { "热搜榜" }, items, topics)
                }
            }
        }
        if (guesses.isEmpty() && boards.isEmpty()) throw IOException("暂时没有热搜数据，请稍后重试")
        return HotSearchPage(guesses.distinctBy { it.series.id }, boards.distinctBy { it.name })
    }

    /** 榜单目录：内容类型与各自的子榜。演员榜列的是人而不是剧，故不纳入。 */
    internal fun rankContents(cell: JSONObject): List<MobileRankContent> =
        cell.optJSONObject("cell_selector")?.optJSONObject("outer_row")?.optJSONArray("items")?.objects().orEmpty().mapNotNull { item ->
            val id = item.str("selector_item_id")
            val boards = item.optJSONObject("sub_cell_selector")?.optJSONObject("outer_row")?.optJSONArray("items")?.objects().orEmpty()
                .mapNotNull { b -> b.str("selector_item_id").takeIf { it.isNotBlank() }?.let { MobileRankBoard(it, b.str("show_name"), b.optBoolean("is_selected")) } }
            if (id.isBlank() || boards.isEmpty() || item.str("show_name").contains("演员")) null
            else MobileRankContent(id, item.str("show_name"), item.optBoolean("is_selected"), boards)
        }

    fun rank(root: JSONObject, requestedContent: String, requestedBoard: String, previous: MobileRankCursor?): MobileRankPage {
        val data = root.optJSONObject("data") ?: throw IOException("排行榜数据暂不可用")
        val cell = data.optJSONObject("cell_view") ?: throw IOException("排行榜目录暂不可用")
        val contents = rankContents(cell)
        val selectedContent = contents.firstOrNull { it.selected }
            ?: contents.firstOrNull { it.id == requestedContent } ?: contents.firstOrNull()
        val content = selectedContent?.id ?: requestedContent.ifBlank { "all" }
        val board = selectedContent?.boards?.firstOrNull { it.selected }?.id
            ?: selectedContent?.boards?.firstOrNull { it.id == requestedBoard }?.id
            ?: selectedContent?.boards?.firstOrNull()?.id ?: requestedBoard
        val items = cell.optJSONArray("cell_data")?.objects().orEmpty().flatMap { it.optJSONArray("video_data")?.objects().orEmpty() }.mapNotNull { row ->
            val id = row.str("series_id"); val title = seriesTitle(row.str("title"))
            if (id == "0" || !idPattern.matches(id) || title.isBlank()) return@mapNotNull null
            val count = row.optInt("episode_cnt")
            Series(id, title, cleanUrl(row.str("cover")), badge = row.optJSONObject("rec_text_item")?.str("RecommendText").orEmpty()
                .ifBlank { if (count > 0) "全 $count 集" else "" })
        }.distinctBy { it.id }
        val offset = data.optInt("next_offset")
        val seen = (previous?.seen.orEmpty() + items.map { it.id }).distinct()
        val next = if (data.optBoolean("has_more") && items.isNotEmpty() && offset > (previous?.offset ?: 0) &&
            seen.size > previous?.seen.orEmpty().size && seen.size < 2000) {
            MobileRankCursor(content, board, offset, data.str("session_id").ifBlank { previous?.sessionId.orEmpty() }, seen)
        } else null
        return MobileRankPage(contents, content, board, items, next)
    }
}
