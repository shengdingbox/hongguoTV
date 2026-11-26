// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.Locale

/** 预约只关联同名同季的正式作品，优先使用相同海报和原始季名，歧义时保留预约。 */
internal object ReservationMatch {
    private val season = Regex("(?:第)?([0-9零〇一二三四五六七八九十百千]+)(季|部)$")
    private val punctuation = "：:·-—_（）()《》〈〉【】[]，,。.!！?？"
    private data class Key(val name: String, val number: Int?, val kind: String = "", val ordinal: Boolean = false)

    private fun compact(title: String) = seriesTitle(title).lowercase(Locale.ROOT)
        .filterNot { it.isWhitespace() || it in punctuation }

    private fun key(title: String): Key {
        val value = compact(title)
        val suffix = season.find(value) ?: return Key(value, null)
        val number = seasonNumber("第${suffix.groupValues[1]}${suffix.groupValues[2]}")
        if (number == Int.MAX_VALUE) return Key(value, null)
        return Key(value.substring(0, suffix.range.first), number, suffix.groupValues[2], suffix.value.startsWith("第"))
    }

    private fun cover(value: String) = value.substringBefore('?').substringAfterLast('/').substringBefore('~')

    fun matches(source: Series, candidate: Series): Boolean {
        if (source.title.isBlank() || candidate.title.isBlank()) return false
        val a = key(source.title)
        val b = key(candidate.title)
        if (a.copy(ordinal = false) != b.copy(ordinal = false)) return false
        // 平台可能同时存在「三季」与「第三季」两个版本，跨这种命名必须有同海报佐证。
        return a.ordinal == b.ordinal || cover(source.cover).let { it.isNotBlank() && it == cover(candidate.cover) }
    }

    fun select(source: Series, candidates: List<Series>): Series? {
        val matching = candidates.distinctBy { it.id }.filter { matches(source, it) }
        val sourceTitle = compact(source.title)
        val sourceCover = cover(source.cover)
        fun score(candidate: Series) = (if (compact(candidate.title) == sourceTitle) 4 else 0) +
            (if (sourceCover.isNotBlank() && cover(candidate.cover) == sourceCover) 8 else 0)
        val best = matching.maxOfOrNull(::score) ?: return null
        return matching.filter { score(it) == best }.singleOrNull()
    }
}
