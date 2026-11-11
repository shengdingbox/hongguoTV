// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

data class CachedHotSearch(val page: HotSearchPage, val savedAt: Long)

/** 热搜只缓存展示数据；重启后仍刷新，失败时保留上次的分榜和话题。 */
object HotSearchSnapshot {
    const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
    const val MAX_GUESSES = 30
    const val MAX_BOARDS = 20
    const val MAX_ITEMS = 100
    const val MAX_TOPICS = 100
    private const val MAX_LENGTH = 8_000_000
    private val idPattern = Regex("[0-9]{1,30}")

    private fun clean(value: String, limit: Int) = value.filterNot(Char::isISOControl).trim().take(limit)
    private fun items(rows: List<HotSearchItem>, limit: Int) = rows.asSequence()
        .filter { it.series.id != "0" && idPattern.matches(it.series.id) && it.series.title.isNotBlank() && it.position in 1..100_000 }
        .distinctBy { it.series.id }.take(limit).map { item ->
            item.copy(series = item.series.copy(title = clean(item.series.title, 200), cover = clean(item.series.cover, 2048),
                description = "", badge = clean(item.series.badge, 200), tags = ""))
        }.toList()

    /** 与磁盘使用相同上限，避免内存页面或异常响应无限增大。 */
    fun retain(page: HotSearchPage): HotSearchPage {
        val boards = page.boards.asSequence().map { board ->
            HotSearchBoard(clean(board.name, 100), items(board.items, MAX_ITEMS),
                board.topics.asSequence().filter { it.title.isNotBlank() }.distinctBy { it.query.ifBlank { it.title } }
                    .take(MAX_TOPICS).map { topic ->
                        HotSearchTopic(clean(topic.title, 200), clean(topic.query.ifBlank { topic.title }, 200), clean(topic.heat, 100))
                    }.toList())
        }.filter { it.name.isNotBlank() && (it.items.isNotEmpty() || it.topics.isNotEmpty()) }
            .distinctBy { it.name }.take(MAX_BOARDS).toList()
        return HotSearchPage(items(page.guesses, MAX_GUESSES), boards)
    }

    fun encode(page: HotSearchPage, now: Long): String {
        val retained = retain(page)
        fun rows(items: List<HotSearchItem>) = JSONArray(items.map { item ->
            JSONObject().put("series", item.series.toJson()).put("position", item.position)
        })
        return JSONObject().put("version", 1).put("savedAt", now)
            .put("guesses", rows(retained.guesses))
            .put("boards", JSONArray(retained.boards.map { board ->
                JSONObject().put("name", board.name).put("items", rows(board.items))
                    .put("topics", JSONArray(board.topics.map { topic ->
                        JSONObject().put("title", topic.title).put("query", topic.query).put("heat", topic.heat)
                    }))
            })).toString().also { require(it.length <= MAX_LENGTH) { "热搜缓存超过大小限制" } }
    }

    fun decode(value: String, now: Long): CachedHotSearch? = runCatching {
        require(value.length <= MAX_LENGTH)
        val root = JSONObject(value)
        require(root.getInt("version") == 1)
        val savedAt = root.getLong("savedAt")
        require(savedAt > 0 && savedAt <= now && now - savedAt <= MAX_AGE)
        fun text(row: JSONObject, key: String, limit: Int): String = row.getString(key).also {
            require(it.length <= limit && it.none(Char::isISOControl))
        }
        fun rows(array: JSONArray, limit: Int): List<HotSearchItem> {
            require(array.length() <= limit)
            return (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                val raw = row.getJSONObject("series")
                val id = text(raw, "id", 30)
                require(id != "0" && idPattern.matches(id))
                val title = text(raw, "title", 200)
                require(title.isNotBlank())
                val series = Series(id, title, text(raw, "cover", 2048), badge = text(raw, "badge", 200))
                val position = row.getInt("position")
                require(position in 1..100_000)
                HotSearchItem(series, position)
            }.distinctBy { it.series.id }
        }
        val guesses = rows(root.getJSONArray("guesses"), MAX_GUESSES)
        val boardRows = root.getJSONArray("boards")
        require(boardRows.length() <= MAX_BOARDS)
        val boards = (0 until boardRows.length()).map { index ->
            val row = boardRows.getJSONObject(index)
            val name = text(row, "name", 100)
            require(name.isNotBlank())
            val topics = row.getJSONArray("topics")
            require(topics.length() <= MAX_TOPICS)
            HotSearchBoard(name, rows(row.getJSONArray("items"), MAX_ITEMS), (0 until topics.length()).map { topicIndex ->
                val topic = topics.getJSONObject(topicIndex)
                val title = text(topic, "title", 200)
                val query = text(topic, "query", 200)
                require(title.isNotBlank() && query.isNotBlank())
                HotSearchTopic(title, query, text(topic, "heat", 100))
            })
        }.filter { it.items.isNotEmpty() || it.topics.isNotEmpty() }.distinctBy { it.name }
        require(guesses.isNotEmpty() || boards.isNotEmpty())
        CachedHotSearch(HotSearchPage(guesses, boards), savedAt)
    }.getOrNull()
}
