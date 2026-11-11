// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

data class CachedNewReleases(val page: NewReleasePage, val savedAt: Long)

/** 新剧按日期保存展示数据，空日期也缓存；分页会话在联网后重新建立。 */
object NewReleaseSnapshot {
    const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
    const val MAX_ITEMS = 120

    fun encode(page: NewReleasePage, now: Long): String = JSONObject()
        .put("savedAt", now).put("date", page.date)
        .put("dates", JSONArray(page.dates.filter(NewReleaseCalendar::valid).distinct().take(31)))
        .put("items", JSONArray(page.items.distinctBy { it.series.id }.take(MAX_ITEMS).map { item ->
            JSONObject().put("series", item.series.copy(description = item.series.description.take(500)).toJson())
                .put("online", item.online).put("note", item.note.take(500)).put("publishAt", item.publishAt)
        })).toString()

    fun decode(value: String, now: Long): CachedNewReleases? = runCatching {
        require(value.length <= 800_000)
        val root = JSONObject(value)
        val savedAt = root.getLong("savedAt")
        require(now - savedAt in 0..MAX_AGE)
        val date = root.getString("date")
        require(NewReleaseCalendar.valid(date))
        val calendar = root.getJSONArray("dates")
        require(calendar.length() in 0..31)
        val dates = (0 until calendar.length()).map { calendar.getString(it) }
        require(dates.all(NewReleaseCalendar::valid))
        val rows = root.getJSONArray("items")
        require(rows.length() in 0..MAX_ITEMS)
        val items = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val series = Series.fromJson(row.getJSONObject("series"))
            require(series.id.matches(Regex("[0-9]{1,30}")) && series.title.isNotBlank())
            NewRelease(series, row.getBoolean("online"), row.getString("note"), row.getLong("publishAt"))
        }.distinctBy { it.series.id }
        CachedNewReleases(NewReleasePage(date, dates.distinct(), items, null), savedAt)
    }.getOrNull()
}
