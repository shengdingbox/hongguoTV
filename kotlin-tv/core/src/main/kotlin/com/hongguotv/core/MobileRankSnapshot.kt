// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** 动态榜单仅缓存展示数据；服务端分页会话每次启动后重新建立。 */
data class CachedMobileRank(val page: MobileRankPage, val savedAt: Long)

object MobileRankSnapshot {
    const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
    const val MAX_ITEMS = 120
    const val MAX_BOARDS = 20
    private const val MAX_CONTENTS = 20
    private const val MAX_SUB_BOARDS = 40
    private const val MAX_LENGTH = 800_000
    private val selectorId = Regex("[A-Za-z0-9_-]{1,100}")
    private val seriesId = Regex("[0-9]{1,30}")

    /** 选择器 ID 不包含分隔符，SP 键能唯一隔离每个内容类型及其子榜。 */
    fun key(contentId: String, boardId: String): String? =
        if (selectorId.matches(contentId) && selectorId.matches(boardId)) "$contentId|$boardId" else null

    fun retain(page: MobileRankPage): MobileRankPage = page.copy(
        contents = page.contents.take(MAX_CONTENTS).map { content ->
            content.copy(name = cleanText(content.name, 100), boards = content.boards.take(MAX_SUB_BOARDS).map { board -> board.copy(name = cleanText(board.name, 100)) })
        },
        items = page.items.distinctBy { it.id }.take(MAX_ITEMS).map { series ->
            series.copy(title = cleanText(series.title, 200), cover = cleanText(series.cover, 2048), description = cleanText(series.description, 500),
                badge = cleanText(series.badge, 200), tags = cleanText(series.tags, 200))
        },
        cursor = null,
    )

    fun encode(page: MobileRankPage, now: Long): String {
        val retained = retain(page)
        require(key(retained.contentId, retained.boardId) != null)
        require(validDirectory(retained))
        require(retained.items.all { seriesId.matches(it.id) && it.title.isNotBlank() })
        return JSONObject().put("version", 1).put("savedAt", now)
            .put("contentId", retained.contentId).put("boardId", retained.boardId)
            .put("contents", JSONArray(retained.contents.map { content ->
                JSONObject().put("id", content.id).put("name", content.name).put("selected", content.selected)
                    .put("boards", JSONArray(content.boards.map { board ->
                        JSONObject().put("id", board.id).put("name", board.name).put("selected", board.selected)
                    }))
            })).put("items", JSONArray(retained.items.map { it.toJson() })).toString().also { require(it.length <= MAX_LENGTH) }
    }

    fun decode(value: String, now: Long): CachedMobileRank? = runCatching {
        require(value.length <= MAX_LENGTH)
        val root = JSONObject(value)
        require(root.getInt("version") == 1)
        val savedAt = root.getLong("savedAt")
        require(savedAt > 0 && savedAt <= now && now - savedAt <= MAX_AGE)
        val contentId = root.text("contentId", 100)
        val boardId = root.text("boardId", 100)
        require(key(contentId, boardId) != null)
        val contents = root.getJSONArray("contents")
        require(contents.length() in 1..MAX_CONTENTS)
        val directory = (0 until contents.length()).map { index ->
            val content = contents.getJSONObject(index)
            val boards = content.getJSONArray("boards")
            require(boards.length() in 1..MAX_SUB_BOARDS)
            MobileRankContent(content.text("id", 100), content.text("name", 100), content.getBoolean("selected"),
                (0 until boards.length()).map { boardIndex ->
                    val board = boards.getJSONObject(boardIndex)
                    MobileRankBoard(board.text("id", 100), board.text("name", 100), board.getBoolean("selected"))
                })
        }
        val rows = root.getJSONArray("items")
        require(rows.length() in 0..MAX_ITEMS)
        val items = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Series(row.text("id", 30).also { require(seriesId.matches(it)) }, row.text("title", 200).also { require(it.isNotBlank()) },
                row.text("cover", 2048), row.text("description", 500), row.text("badge", 200), row.text("tags", 200))
        }
        require(items.distinctBy { it.id }.size == items.size)
        val page = MobileRankPage(directory, contentId, boardId, items, null)
        require(validDirectory(page))
        CachedMobileRank(page, savedAt)
    }.getOrNull()

    private fun validDirectory(page: MobileRankPage): Boolean =
        page.contents.isNotEmpty() && page.contents.distinctBy { it.id }.size == page.contents.size &&
            page.contents.all { content ->
                selectorId.matches(content.id) && content.name.isNotBlank() && content.name.none(Char::isISOControl) &&
                    content.boards.isNotEmpty() && content.boards.distinctBy { it.id }.size == content.boards.size &&
                    content.boards.all { selectorId.matches(it.id) && it.name.isNotBlank() && it.name.none(Char::isISOControl) }
            } && page.contents.any { it.id == page.contentId && it.boards.any { board -> board.id == page.boardId } }

    private fun cleanText(value: String, limit: Int) = value.take(limit).map { if (it.isISOControl()) ' ' else it }.joinToString("")

    private fun JSONObject.text(key: String, limit: Int): String {
        val value = get(key)
        require(value is String && value.length <= limit && value.none(Char::isISOControl))
        return value
    }
}
