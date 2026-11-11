// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import com.hongguotv.core.Series
import org.json.JSONObject

/** 手机导航现场独立于观看记录保存，不将临时页面状态混进收藏备份。 */
data class PhoneSessionSnapshot(
    val screen: String = "catalog",
    val tab: String = "home",
    val originTab: String = "home",
    val query: String = "",
    val searchText: String = "",
    val rankContent: String = "",
    val rankBoard: String = "",
    val hotBoard: String = "",
    val newDate: String = "",
    val series: Series? = null,
    val episodeId: String = "",
    val episodeIndex: Int = 0,
    val position: Long = 0L,
    val scrollPositions: Map<String, Int> = emptyMap(),
    val detailScroll: Int = 0,
)

class PhoneSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phone-session-v1", Context.MODE_PRIVATE)
    private var lastSaved: String? = null

    fun load(): PhoneSessionSnapshot? = runCatching {
        val raw = prefs.getString("session", null) ?: return null
        if (raw.length > MAX_LENGTH) return null
        val json = JSONObject(raw)
        if (json.optInt("version") != 1) return null
        val tab = json.optString("tab").takeIf { it in TABS } ?: "home"
        val origin = json.optString("originTab").takeIf { it in TABS } ?: tab
        val screen = json.optString("screen").takeIf { it in SCREENS } ?: "catalog"
        val series = json.optJSONObject("series")?.let { Series.fromJson(it) }
            ?.takeIf { it.id.matches(ID) && it.title.isNotBlank() }
        val positions = json.optJSONObject("scrollPositions")
        PhoneSessionSnapshot(
            screen = if (screen in listOf("detail", "player") && series == null) "catalog" else screen,
            tab = tab,
            originTab = origin,
            query = json.optString("query").take(80),
            searchText = json.optString("searchText").take(80),
            rankContent = json.optString("rankContent").take(100),
            rankBoard = json.optString("rankBoard").take(100),
            hotBoard = json.optString("hotBoard").take(100),
            newDate = json.optString("newDate").take(10),
            series = series,
            episodeId = json.optString("episodeId").takeIf { it.matches(ID) }.orEmpty(),
            episodeIndex = json.optInt("episodeIndex").coerceIn(0, 100_000),
            position = json.optLong("position").coerceIn(0L, 604_800_000L),
            scrollPositions = TABS.associateWith { positions?.optInt(it)?.coerceIn(0, MAX_SCROLL) ?: 0 },
            detailScroll = json.optInt("detailScroll").coerceIn(0, MAX_SCROLL),
        ).also { lastSaved = raw }
    }.getOrNull()

    fun save(snapshot: PhoneSessionSnapshot) {
        val scroll = JSONObject()
        snapshot.scrollPositions.filterKeys { it in TABS }.forEach { (key, value) ->
            scroll.put(key, value.coerceIn(0, MAX_SCROLL))
        }
        val series = snapshot.series?.let {
            it.copy(title = it.title.take(200), cover = it.cover.take(2048), description = "", badge = it.badge.take(100), tags = it.tags.take(200))
        }
        val json = JSONObject().put("version", 1)
            .put("screen", snapshot.screen).put("tab", snapshot.tab).put("originTab", snapshot.originTab)
            .put("query", snapshot.query.take(80)).put("searchText", snapshot.searchText.take(80))
            .put("rankContent", snapshot.rankContent.take(100)).put("rankBoard", snapshot.rankBoard.take(100))
            .put("hotBoard", snapshot.hotBoard.take(100)).put("newDate", snapshot.newDate.take(10))
            .put("series", series?.toJson() ?: JSONObject.NULL)
            .put("episodeId", snapshot.episodeId).put("episodeIndex", snapshot.episodeIndex)
            .put("position", snapshot.position.coerceIn(0L, 604_800_000L))
            .put("scrollPositions", scroll).put("detailScroll", snapshot.detailScroll.coerceIn(0, MAX_SCROLL))
            .toString()
        if (json == lastSaved || json.length > MAX_LENGTH) return
        lastSaved = json
        // apply 在内存中立即可见，磁盘写入由系统队列处理；生命周期会等待待完成的写入。
        prefs.edit().putString("session", json).apply()
    }

    companion object {
        val TABS = setOf("home", "rank", "new", "mine", "fav", "search")
        private val SCREENS = setOf("catalog", "detail", "player", "settings")
        private val ID = Regex("[0-9]{1,30}")
        private const val MAX_SCROLL = 10_000_000
        private const val MAX_LENGTH = 32_768
    }
}
