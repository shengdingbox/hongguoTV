// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.io.IOException

/**
 * 电视排行榜：官方 App 的短剧榜单接口（`cell/change/v1`，`tab_type=26`）。
 *
 * 三类榜单与官网热播榜一一对应，字段口径沿用 [ComicRanking]：
 * 名次取页内顺序、热度取 `rec_text_item.RecommendText`。
 *
 * 名次为什么不用 `recommend_info.rank`：那是服务端自己的编号，推荐榜上按个人兴趣
 * 排序会出现重复，推广位还会照抄下一行的名次。只有「已加载条数 + 页内序号」才是
 * 稳定的名次，与官网榜单的口径一致。
 */
enum class RankBoard(val contentId: String, val rankKey: String, val label: String) {
    COMIC("comic_series_rank", "comic", "漫剧"),
    REAL("human", "real", "短剧"),
    AI("ai_playlet", "ai", "AI剧");
}

internal object ComicRank {
    /** 一页官方榜单：条目、名次与「下一页从哪里开始」的游标。 */
    data class Slice(val page: CatalogPage, val next: MobileRankCursor?)

    /** 该内容类型没出现在返回目录里时，用服务端对它的默认子榜。 */
    private val defaultBoards = mapOf(
        RankBoard.COMIC to "comic_series_hot_play",
        RankBoard.REAL to "human_hot_play",
        RankBoard.AI to "ai_playlet_hot_play",
    )

    /**
     * 首屏请求要主动指定子榜：不传时服务端按自己的默认项返回（通常是短剧热播），
     * 电视端就点不到对应榜单。续页沿用游标里的子榜即可。
     */
    fun defaultBoardId(board: RankBoard): String = defaultBoards.getValue(board)

    fun parse(root: JSONObject, board: RankBoard, cursor: MobileRankCursor?, offsetBefore: Int = 0): Slice {
        val data = root.optJSONObject("data") ?: throw IOException("${board.label}榜单暂不可用")
        val cell = data.optJSONObject("cell_view") ?: throw IOException("${board.label}榜单暂不可用")

        // 目录用于校验内容类型；接口没给就退回本类默认子榜，不让整页失败。
        val contents = MobileDiscoveryParser.rankContents(cell)
        val content = contents.firstOrNull { it.id == board.contentId }
        if (contents.isNotEmpty() && content == null) throw IOException("${board.label}榜单暂不可用")
        val boardId = cursor?.boardId
            ?: content?.boards?.firstOrNull { it.id == defaultBoards[board] }?.id
            ?: content?.boards?.firstOrNull { it.selected }?.id
            ?: content?.boards?.firstOrNull()?.id
            ?: defaultBoards.getValue(board)

        val rows = cell.optJSONArray("cell_data")?.objects().orEmpty()
            .flatMap { it.optJSONArray("video_data")?.objects().orEmpty() }
        val positions = linkedMapOf<String, RankPosition>()
        val items = rows.mapNotNull { row ->
            val id = row.str("series_id")
            val title = seriesTitle(row.str("title"))
            if (id == "0" || !id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
            Series(id, title, cleanUrl(row.str("cover")), badge = episodeBadge(row))
        }.distinctBy { it.id }
        // 第 1 页空结果说明该榜单不可用；续页空则只是到头了，让 hasMore 正常收尾。
        if (items.isEmpty() && cursor == null) throw IOException("${board.label}榜单暂不可用")
        // 热度按系列 ID 建索引，避免逐条回扫整页。
        val heats = rows.associate { it.str("series_id") to it.optJSONObject("rec_text_item")?.str("RecommendText").orEmpty() }
        items.forEachIndexed { index, series ->
            positions[series.id] = RankPosition(
                rank = offsetBefore + index + 1,
                // 推广位显示「平台推荐」，既不是热度也不是名次依据，按无热度处理。
                heat = heats[series.id].orEmpty().takeUnless { it == PROMOTED } ?: "",
            )
        }

        val offset = data.optInt("next_offset")
        val seen = (cursor?.seen.orEmpty() + items.map { it.id }).distinct()
        val next = if (data.optBoolean("has_more") && items.isNotEmpty() &&
            offset > (cursor?.offset ?: 0) && seen.size > cursor?.seen.orEmpty().size && seen.size < 2000) {
            MobileRankCursor(board.contentId, boardId, offset,
                data.str("session_id").ifBlank { cursor?.sessionId.orEmpty() }, seen)
        } else null
        return Slice(CatalogPage(items, next != null, ComicRanking(positions, 0, "")), next)
    }

    private fun episodeBadge(row: JSONObject): String {
        val count = row.optInt("episode_cnt")
        return if (count > 0) "全 $count 集" else ""
    }

    private const val PROMOTED = "平台推荐"
}
