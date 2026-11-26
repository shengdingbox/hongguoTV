// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

private const val SEASON_LABEL_DELIMITERS = "：:·・-—_（）()《》〈〉【】[]"

private fun trimSeasonLabel(value: String): String = value.trim {
    it.isWhitespace() || it in SEASON_LABEL_DELIMITERS
}

/** 季标签只保留季号或独立季名，剧名前缀从同系列的普通季标题中提取。 */
fun seasonLabels(items: Collection<Series>): Map<String, String> {
    val titles = items.associate { it.id to trimSeasonLabel(seriesTitle(it.title)) }
    val bases = titles.values.mapNotNull { title ->
        SeasonPattern.find(title)?.let { trimSeasonLabel(title.substring(0, it.range.first)) }
            ?.takeIf { it.isNotEmpty() }
    }.distinct()
    val numberedBase = trimSeasonLabel(bases.reduceOrNull { prefix, title ->
        prefix.commonPrefixWith(title)
    }.orEmpty())
    val commonBase = trimSeasonLabel(titles.values.reduceOrNull { prefix, title ->
        prefix.commonPrefixWith(title)
    }.orEmpty())

    return items.associate { series ->
        val title = titles.getValue(series.id)
        val number = seriesSeasonNumber(series)
        val numberedLabel = "第${if (number == Int.MAX_VALUE) 1 else number}季"
        val base = bases.filter { title.startsWith(it) }.maxByOrNull { it.length }
            ?: numberedBase.takeIf { it.isNotEmpty() }
            ?: commonBase.takeIf { it.isNotEmpty() }
        val name = base?.let { trimSeasonLabel(title.removePrefix(it)) }.orEmpty()
        val label = when {
            seasonNumber(title) != Int.MAX_VALUE -> numberedLabel
            base != null && title.startsWith(base) -> name.ifEmpty { numberedLabel }
            // 没有剧名前缀的条目已是独立季名；带分隔符时只保留最后的季名。
            base != null -> trimSeasonLabel(title.substringAfterLast('：').substringAfterLast(':'))
            number != Int.MAX_VALUE -> numberedLabel
            else -> trimSeasonLabel(title.substringAfterLast('：').substringAfterLast(':'))
        }
        series.id to label.ifEmpty { numberedLabel }
    }
}
