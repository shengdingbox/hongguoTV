// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** 展示数据快照；手机可保留多页卡片，分页会话和播放地址不落盘。 */
object CatalogSnapshot {
    private const val MAX_AGE=7*24*60*60*1000L
    fun encode(items: List<Series>,hasMore: Boolean,now: Long,ranking: ComicRanking?=null,maxItems: Int=30): String = JSONObject()
        .put("savedAt",now).put("hasMore",hasMore)
        .put("items",JSONArray(items.take(maxItems.coerceIn(1,120)).map { it.copy(description=it.description.take(1500)).toJson() }))
        // 榜单缓存附带名次/热度：首页缓存不传 ranking，落盘形状不变
        .apply { ranking?.let { r -> put("ranking",JSONObject()
            .put("totalPages",r.totalPages).put("updatedText",r.updatedText)
            .put("positions",JSONObject(r.positions.mapValues { (_,p) -> JSONObject().put("rank",p.rank ?: 0).put("heat",p.heat) }))) } }
        .toString()
    fun decode(value: String,now: Long,maxItems: Int=30): CatalogPage? = runCatching {
        val limit=maxItems.coerceIn(1,120)
        require(value.length<=200_000L*limit/30)
        val o=JSONObject(value); require(now-o.getLong("savedAt") in 0..MAX_AGE)
        val rows=o.getJSONArray("items"); require(rows.length() in 0..limit)
        val items=(0 until rows.length()).map { Series.fromJson(rows.getJSONObject(it)) }
        require(items.all { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() })
        // 旧首页缓存没有 ranking 字段则保持 null，按普通片库渲染
        val ranking=o.optJSONObject("ranking")?.let { r ->
            // positions 字段存在但类型不对说明结构被篡改，整体拒绝
            val positions=r.getJSONObject("positions")
            require(positions.length() in 0..maxOf(60,limit))
            val parsed=linkedMapOf<String,RankPosition>()
            val ids=positions.keys()
            while(ids.hasNext()) {
                val id=ids.next(); val p=positions.getJSONObject(id)
                require(id.matches(Regex("[0-9]{1,30}")))
                parsed[id]=RankPosition(p.optInt("rank").takeIf { it>0 },p.optString("heat"))
            }
            ComicRanking(parsed,r.optInt("totalPages",0),r.optString("updatedText"))
        }
        CatalogPage(items.distinctBy { it.id },o.getBoolean("hasMore"),ranking)
    }.getOrNull()
}
