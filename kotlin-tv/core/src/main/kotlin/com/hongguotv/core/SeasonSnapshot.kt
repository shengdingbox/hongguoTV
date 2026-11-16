// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** 一个系列共用一份完整分季列表，包含当前季及尚未上线作品的排期。 */
data class CachedSeasons(val sourceId: String, val items: List<Series>, val savedAt: Long)

object SeasonSnapshot {
    const val MAX_AGE = 30 * 24 * 60 * 60 * 1000L
    const val MAX_GROUPS = 40
    const val MAX_ITEMS = 60
    private const val MAX_LENGTH = 500_000
    private val idPattern = Regex("[0-9]{1,30}")

    fun validId(id: String) = idPattern.matches(id) && id != "0"

    fun retain(items: List<Series>) = items.filter { validId(it.id) && it.title.isNotBlank() }
        .distinctBy { it.id }.take(MAX_ITEMS).map { item ->
            item.copy(title = item.title.take(200), cover = item.cover.take(2048), description = item.description.take(500),
                badge = item.badge.take(200), tags = item.tags.take(200), release = item.release?.let { it.copy(note = it.note.take(500)) })
        }

    fun encode(cached: CachedSeasons): String = JSONObject().put("version", 1)
        .put("sourceId", cached.sourceId).put("savedAt", cached.savedAt)
        .put("items", JSONArray(retain(cached.items).map { it.toJson() })).toString()

    fun decode(value: String, now: Long): CachedSeasons? = runCatching {
        require(value.length <= MAX_LENGTH)
        val root = JSONObject(value)
        require(root.getInt("version") == 1)
        val sourceId = root.getString("sourceId")
        require(validId(sourceId))
        val savedAt = root.getLong("savedAt")
        require(savedAt > 0 && now - savedAt in 0..MAX_AGE)
        val rows = root.getJSONArray("items")
        require(rows.length() in 0..MAX_ITEMS)
        val items = (0 until rows.length()).map { Series.fromJson(rows.getJSONObject(it)) }
        require(items.all { validId(it.id) && it.title.isNotBlank() })
        require(items.distinctBy { it.id }.size == items.size)
        CachedSeasons(sourceId, retain(items), savedAt)
    }.getOrNull()
}
