// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 上新日历的游标只能用于其所属日期，偏移量和会话均由服务端下发。 */
data class NewReleaseCursor(val date: String, val offset: Int, val sessionId: String)
data class NewRelease(val series: Series, val online: Boolean, val note: String, val publishAt: Long) {
    /** 旧日历缓存也在读取时补齐排期，收藏后继续保留。 */
    fun withSchedule(date: String): NewRelease {
        val schedule = if (online) null else ReleaseSchedule(ReleaseSchedule.millis(publishAt), date, note)
        return copy(series = series.copy(badge = schedule?.label ?: series.badge, release = schedule))
    }
}
data class NewReleasePage(
    val date: String,
    val dates: List<String>,
    val items: List<NewRelease>,
    val cursor: NewReleaseCursor?,
)

/** 日期采用平台的中国时区；本地日期条只在日历尚未返回时兜底。 */
object NewReleaseCalendar {
    private fun formatter() = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }
    fun valid(date: String): Boolean = date.matches(Regex("[0-9]{8}")) &&
        runCatching { formatter().parse(date)?.let { formatter().format(it) == date } == true }.getOrDefault(false)
    fun today(): String = formatter().format(java.util.Date())
    fun nearbyDates(): List<String> {
        val today = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        return (-7..7).map { offset ->
            formatter().format((today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }.time)
        }
    }
}

/** 字段协议对照某 Apache-2.0 授权开源实现的 NewReleases。 */
internal object NewReleaseParser {
    fun parse(root: JSONObject, requestedDate: String, previous: NewReleaseCursor?): NewReleasePage {
        val data = root.optJSONObject("data") ?: throw IOException("新剧日历数据暂不可用")
        val calendar = data.optJSONObject("calendar_schema")
        val date = calendar?.str("default_date")?.takeIf(NewReleaseCalendar::valid)
            ?: requestedDate.ifBlank { NewReleaseCalendar.today() }
        val dateList = calendar?.optJSONArray("date_list")
        val dates = (0 until (dateList?.length() ?: 0)).map { dateList!!.optString(it) }
            .filter(NewReleaseCalendar::valid).distinct()
        val items = data.optJSONArray("subscribe_items")?.objects().orEmpty().mapNotNull { item ->
            val online = item.optBoolean("is_online", false)
            val row = item.optJSONObject("subscribe_data")
            val id = row?.str("series_id") ?: item.str("item_id")
            val title = seriesTitle(row?.str("title") ?: item.str("name"))
            if (id == "0" || !id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
            val note = item.str("sub_info")
            val heat = if (online) item.optJSONArray("rec_tags")?.optJSONObject(0)?.str("content").orEmpty()
                else item.str("expected_rec_text")
            val count = row?.optInt("episode_cnt", 0) ?: 0
            val badge = if (!online && note.isNotBlank()) note else heat.ifBlank {
                if (count > 0) "全 $count 集" else if (online) "已上线" else "待上线"
            }
            val categories = item.optJSONArray("categories")
            val tags = (0 until minOf(categories?.length() ?: 0, 5)).map { categories!!.optString(it) }
                .filter { it.isNotBlank() }.joinToString(" · ")
            val series = Series(id, title, cleanUrl(row?.str("cover") ?: item.str("cover")),
                row?.str("video_desc") ?: item.str("item_desc"), badge, tags)
            NewRelease(series, online, note, item.optLong("schedule_publish_time", 0).takeIf { it > 0 }
                ?: item.optLong("expected_publish_time", 0)).withSchedule(date)
        }.distinctBy { it.series.id }
        val offset = data.optInt("next_offset", 0)
        val cursor = if (data.optBoolean("has_more", false) && items.isNotEmpty() &&
            offset > (previous?.offset ?: 0)) {
            NewReleaseCursor(date, offset, data.str("session_id").ifBlank { previous?.takeIf { it.date == date }?.sessionId.orEmpty() })
        } else null
        return NewReleasePage(date, dates, items, cursor)
    }
}
