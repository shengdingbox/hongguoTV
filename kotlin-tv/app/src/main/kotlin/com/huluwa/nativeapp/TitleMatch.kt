// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import com.hongguotv.core.Series

/**
 * 搜索词 → 结果里"确实是这部"的那一条。
 *
 * 红果主站对不认识的词不返回空，而是给一批热门推荐。所以「一句话开播」如果照旧拿首条，
 * 喊「黄石第五季」会播出《1000集让我留下来了》——宁可不播，也不能乱播。
 *
 * 只有标题真能对上才认：
 * 1. 归一后完全相同；
 * 2. 标题包含关键词（喊「文盲修仙」，库里叫「谁让这文盲修仙的第六季」）；
 * 3. 关键词包含标题（喊「黄石第五季」而库里只有「黄石」，标题至少两个字）。
 *
 * 都对不上就返回 null，由调用方提示「没搜到」。
 */
object TitleMatch {

    private fun nameKey(name: String): String =
        name.replace("《", "").replace("》", "").replace(Regex("\\s+"), "").lowercase()

    /** 季标记：兼容「第二季」「第2季」「第 2 部」，数字与「季/部」之间允许空格。 */
    private val SeasonMark = Regex("第\\s*([0-9零〇一二三四五六七八九十百千]+)\\s*(?:季|部)")

    /** 系列主名：去掉季标记和两端的括号/分隔符，「某剧（第二季）」与「某剧」算同一个系列。 */
    fun seriesBase(name: String): String = name.replace(SeasonMark, "")
        .trim().trim(' ', '\u3000', '：', ':', '-', '—', '_', '·', '（', '）', '(', ')',
            '【', '】', '[', ']', '《', '》')

    /** 命中结果的下标；没把握时返回 -1。 */
    fun best(query: String, items: List<Series>): Int = bestIn(query, items.map { it.title })

    fun bestIn(query: String, titles: List<String>): Int {
        val key = nameKey(query)
        if(key.length < 2) return -1
        val keys = titles.map { nameKey(it) }
        val exact = keys.indexOfFirst { it == key }
        if(exact >= 0) return exact
        val loose = keys.indexOfFirst { it.length >= 2 && (it.contains(key) || key.contains(it)) }
        return loose
    }
}
