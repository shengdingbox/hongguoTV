// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 仅未上线作品携带排期；时间戳统一为毫秒，日期来自平台上新日历。 */
data class ReleaseSchedule(val publishAt: Long = 0, val date: String = "", val note: String = "") {
    val isSpecified get() = publishAt > 0 || NewReleaseCalendar.valid(date) || ABSOLUTE_DATE.containsMatchIn(note)

    val label: String get() {
        if (publishAt > 0) {
            val calendar = Calendar.getInstance(ZONE).apply { timeInMillis = publishAt }
            val hasTime = calendar.get(Calendar.HOUR_OF_DAY) != 0 || calendar.get(Calendar.MINUTE) != 0 ||
                calendar.get(Calendar.SECOND) != 0
            val time = if (hasTime) " ${format("HH:mm", Date(publishAt))}"
                else TIME.find(note)?.value?.let { " $it" }.orEmpty()
            return "预计 ${format("M月d日", Date(publishAt))}$time 上线"
        }
        if (ABSOLUTE_DATE.containsMatchIn(note)) {
            val expected = if (note.contains("预计")) note else "预计 $note"
            return if (note.contains("上线") || note.contains("开播") || note.contains("首播")) expected else "$expected 上线"
        }
        if (NewReleaseCalendar.valid(date)) {
            val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = ZONE }.parse(date)!!
            val time = TIME.find(note)?.value?.let { " $it" }.orEmpty()
            return "预计 ${format("M月d日", day)}$time 上线"
        }
        return "上线时间待定"
    }

    /** 到达预计时间后允许重新取详情；只有日期时不虚构当天的具体上线时刻。 */
    fun isPending(now: Long = System.currentTimeMillis()): Boolean = when {
        publishAt > 0 -> publishAt > now
        NewReleaseCalendar.valid(date) -> date > format("yyyyMMdd", Date(now))
        else -> false
    }

    fun toJson() = JSONObject().put("publishAt", publishAt).put("date", date).put("note", note.take(500))

    companion object {
        private val ZONE = TimeZone.getTimeZone("Asia/Shanghai")
        private val TIME = Regex("(?<!\\d)(?:(?:[01]?\\d|2[0-3])[:：][0-5]\\d|(?:[01]?\\d|2[0-3])[时点](?:[0-5]?\\d分?)?)(?!\\d)")
        private val ABSOLUTE_DATE = Regex("\\d{1,2}月\\d{1,2}日|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")
        private fun format(pattern: String, value: Date) = SimpleDateFormat(pattern, Locale.CHINA).apply {
            timeZone = ZONE
        }.format(value)

        internal fun millis(value: Long): Long = when {
            value <= 0 -> 0
            value <= 9_999_999_999L -> value * 1000
            else -> value
        }

        /** 系列预告把排期放在 subscribe_item.sub_title_list，预约人数不属于上线文案。 */
        internal fun fromItem(item: JSONObject, date: String = "", note: String = item.str("sub_info")): ReleaseSchedule {
            val sources = listOfNotNull(item, item.optJSONObject("subscribe_item"))
            val stamp = sources.asSequence().flatMap { source ->
                sequenceOf(source.optLong("schedule_publish_time"), source.optLong("expected_publish_time"), source.optLong("online_time"))
            }.firstOrNull { it > 0 } ?: 0
            val notes = listOf(note) + sources.flatMap { source ->
                listOf(source.str("sub_info")) + source.optJSONArray("sub_title_list")?.objects()
                    .orEmpty().map { it.str("content") }
            }
            val releaseNote = notes.firstOrNull { ABSOLUTE_DATE.containsMatchIn(it) || TIME.containsMatchIn(it) }
                ?: notes.firstOrNull { it.isNotBlank() }.orEmpty()
            return ReleaseSchedule(millis(stamp), date.takeIf(NewReleaseCalendar::valid).orEmpty(), releaseNote.take(500))
        }

        internal fun fromSeries(item: JSONObject): ReleaseSchedule? {
            val trailer = item.optInt("content_type") in listOf(1002, 1007)
            if (!trailer && item.optInt("episode_cnt") > 0) return null
            val schedule = fromItem(item)
            return schedule.takeIf { trailer || item.optJSONObject("subscribe_item") != null || it.publishAt > 0 ||
                (item.has("is_online") && !item.optBoolean("is_online", true)) }
        }

        fun fromJson(item: JSONObject) = ReleaseSchedule(millis(item.optLong("publishAt")),
            item.str("date").takeIf(NewReleaseCalendar::valid).orEmpty(), item.str("note").take(500))
    }
}
