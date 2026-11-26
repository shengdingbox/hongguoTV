// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** Main-thread-owned library. JSON is parsed once, and getters never parse or sort. */
class LibraryIndex(data: LibraryData, updates: Map<String, FavoriteUpdate> = emptyMap()) {
    private var favoriteRows = data.favorites.distinctBy { it.id }.take(500)
    private var favoriteIds = favoriteRows.map { it.id }.toSet()
    // 旧收藏按排期迁入预约；上线状态与是否已查看分集分别保存。
    private var reservationIds = (data.reservations + favoriteRows.filter { it.release != null }.map { it.id }).intersect(favoriteIds)
    private var laterRows = data.later.distinctBy { it.id }.take(100)
    private var laterIds = laterRows.map { it.id }.toSet()
    private var historyRows = data.history.sortedByDescending { it.updatedAt }.distinctBy { it.series.id }.take(200)
        .map { it.withWatchedEpisodes() }
    private val progressById = historyRows.associateBy { it.series.id }.toMutableMap()
    private var watchedIds = data.watched.toSet()
    private var hiddenIds = data.hidden.take(500).toSet()
    private var searchRows = data.searches.take(20)
    private var updateRows = updates.filterKeys { it in favoriteIds }
    private var updateCount = updateRows.values.count { it.added > 0 }
    private var groupById = data.favoriteGroups.filterKeys { it in favoriteIds }
    var settings = data.settings

    fun favorites() = favoriteRows
    fun favorite(id: String) = id in favoriteIds
    fun reserved(id: String) = id in reservationIds
    fun reservations() = reservationIds
    fun availableFavorites() = favoriteRows.filterNot { reserved(it.id) }
    fun reservedFavorites() = favoriteRows.filter { reserved(it.id) }
    fun later() = laterRows
    fun queued(id: String) = id in laterIds
    fun history() = historyRows
    fun progress(id: String) = progressById[id]
    fun watchedEpisodes(id: String): Set<String> = progressById[id]?.watchedEpisodeIds.orEmpty()
    fun watched(id: String) = id in watchedIds
    fun watched() = watchedIds
    fun hidden() = hiddenIds
    fun searches() = searchRows
    fun updates() = updateRows
    fun favoriteUpdate(id: String) = updateRows[id]
    fun updatedFavorites() = updateCount
    fun groups() = favoriteRows.mapNotNull { groupById[it.id] }.distinct()
    fun groupOf(id: String) = groupById[id]
    fun groupsSnapshot() = groupById.toMap()
    fun setGroup(id: String, group: String?): Boolean {
        if (!favorite(id)) return false
        val name = group?.trim()?.filterNot { it.isISOControl() }?.take(12).orEmpty()
        if (name.isEmpty()) {
            if (id !in groupById) return false
            groupById = groupById - id
            return true
        }
        if (groupById[id] == name) return false
        if (name !in groupById.values && groupById.values.toSet().size >= 20) return false
        groupById = groupById + (id to name)
        return true
    }

    fun toggle(series: Series): Boolean {
        val added = !favorite(series.id)
        favoriteRows = (if (added) listOf(series) + favoriteRows else favoriteRows.filterNot { it.id == series.id }).take(500)
        favoriteIds = favoriteRows.map { it.id }.toSet()
        reservationIds = (if (added && series.release != null) reservationIds + series.id else reservationIds - series.id).intersect(favoriteIds)
        updateRows = updateRows.filterKeys { it in favoriteIds }
        updateCount = updateRows.values.count { it.added > 0 }
        if (!added) groupById = groupById - series.id
        return added
    }
    fun toggleLater(series: Series): Boolean {
        val added = !queued(series.id)
        laterRows = (if (added) listOf(series) + laterRows else laterRows.filterNot { it.id == series.id }).take(100)
        laterIds = laterRows.map { it.id }.toSet()
        return added
    }
    /** A paused player's identical checkpoint does not refresh its timestamp or trigger a write. */
    fun save(progress: LibraryProgress): Boolean {
        val previous = progressById[progress.series.id]
        val next = progress.withWatchedEpisodes(previous?.watchedEpisodeIds.orEmpty())
        if (previous != null && previous.copy(updatedAt = next.updatedAt) == next) return false
        historyRows = (listOf(next) + historyRows.filterNot { it.series.id == next.series.id }).take(200).sortedByDescending { it.updatedAt }
        progressById.clear()
        historyRows.forEach { progressById[it.series.id] = it }
        return true
    }
    fun setWatched(id: String, value: Boolean): Boolean {
        if (watched(id) == value) return false
        watchedIds = if (value) watchedIds + id else watchedIds - id
        return true
    }
    fun hide(id: String) { hiddenIds = (hiddenIds + id).toList().takeLast(500).toSet() }
    fun unhideAll() { hiddenIds = emptySet() }
    fun rememberSearch(query: String, type: ContentType) { searchRows = SearchHistory.remember(searchRows, query, type) }
    fun clearSearches() { searchRows = emptyList() }
    fun removeHistory(id: String) {
        historyRows = historyRows.filterNot { it.series.id == id }
        progressById.remove(id)
        watchedIds = watchedIds - id
    }
    fun clearHistory() { historyRows = emptyList(); progressById.clear(); watchedIds = emptySet() }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean, newSeason: Series? = null): Boolean {
        // 未上线或暂不可取的分集不能写入零集计数，也不能覆盖已有的收藏更新状态。
        if (count <= 0 || !favorite(id)) return false
        val previous = updateRows[id]
        var next = previous?.observe(count, now, acknowledge) ?: FavoriteUpdate.first(count, now)
        if (!acknowledge) next = next.observeNewSeason(newSeason?.id, newSeason?.title, now)
        if (previous == next) return false
        updateRows = updateRows + (id to next)
        updateCount += (if (next.hasUpdate) 1 else 0) - (if (previous?.hasUpdate == true) 1 else 0)
        return true
    }
    /** 同步最新元数据；只有详情实际展示了分集，才将预约转成收藏。 */
    fun observeFavoriteDetail(detail: Detail, now: Long, acknowledge: Boolean = false): Boolean {
        val id = detail.series.id
        val previous = favoriteRows.firstOrNull { it.id == id } ?: return false
        if (now < (updateRows[id]?.checkedAt ?: 0L)) return false
        val count = detail.episodes.size
        // 已确认上线的作品，不能因接口临时缺分集或旧排期兜底而退回待上线。
        if (count == 0 && previous.release == null && (updateRows[id]?.total ?: 0) > 0) return false
        val schedule = if (count > 0) null else detail.series.release ?: previous.release
        val series = detail.series.copy(
            title = detail.series.title.ifBlank { previous.title },
            cover = detail.series.cover.ifBlank { previous.cover },
            description = detail.series.description.ifBlank { previous.description },
            tags = detail.series.tags.ifBlank { previous.tags },
            badge = if (count > 0) "共 $count 集" else schedule?.label ?: detail.series.badge.ifBlank { previous.badge },
            release = schedule,
        )
        val nextReservations = when {
            count > 0 && acknowledge -> reservationIds - id
            series.release != null -> reservationIds + id
            else -> reservationIds
        }
        val metadataChanged = previous != series || nextReservations != reservationIds
        if (previous != series) favoriteRows = favoriteRows.map { if (it.id == id) series else it }
        reservationIds = nextReservations
        val updateChanged = observeFavorite(id, count, now, acknowledge,
            detail.newerSeasons.lastOrNull { it.release == null })
        return metadataChanged || updateChanged
    }
    fun snapshot() = LibraryData(favoriteRows, historyRows, watchedIds, searchRows, settings, laterRows, hiddenIds, groupById, reservationIds)
}

/** Keeps the native-library-v1 disk keys and rich local metadata compatible with older APKs. */
object LibraryDisk {
    fun decode(values: Map<String, *>): LibraryIndex {
        fun rows(key: String) = runCatching { JSONArray(values[key] as? String ?: "[]") }.getOrDefault(JSONArray())
        fun series(key: String): List<Series> {
            val data = rows(key)
            return (0 until data.length()).mapNotNull { runCatching { Series.fromJson(data.getJSONObject(it)) }.getOrNull() }
        }
        fun ids(key: String) = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val history = rows("progress").let { rows ->
            (0 until rows.length()).mapNotNull { i -> runCatching {
                val o = rows.getJSONObject(i)
                val seen = o.optJSONArray("watchedEpisodeIds") ?: JSONArray()
                val watchedEpisodeIds = (0 until minOf(seen.length(), LibraryProgress.MAX_WATCHED_EPISODES)).mapNotNull { index ->
                    (seen.opt(index) as? String)?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
                }.toSet()
                LibraryProgress(Series.fromJson(o.getJSONObject("series")), o.getString("episodeId"), o.getInt("episodeIndex"),
                    o.getLong("position"), o.getLong("duration"), o.optBoolean("completed"), o.getLong("updatedAt"), watchedEpisodeIds)
            }.getOrNull() }
        }
        val rawUpdates = runCatching { JSONObject(values["favoriteUpdates"] as? String ?: "{}") }.getOrDefault(JSONObject())
        val updates = rawUpdates.keys().asSequence().mapNotNull { id -> FavoriteUpdate.decode(rawUpdates.optJSONObject(id))?.let { id to it } }.toMap()
        val settings = LibrarySettings(PlaybackQuality.normalize((values["quality"] as? Number)?.toInt() ?: 1080),
            PlaybackSpeed.normalize((values["playbackSpeed"] as? Number)?.toFloat() ?: 1f), values["autoNext"] as? Boolean ?: true,
            ContentType.fromStored(values["contentType"] as? String), VideoFrameMode.fromStored(values["frameMode"] as? String), values["autoSeason"] as? Boolean ?: true,
            values["danmaku"] as? Boolean ?: true,
            DanmakuPrefs.opacity((values["danmakuOpacity"] as? Number)?.toInt() ?: DanmakuPrefs.OPACITY_DEFAULT),
            DanmakuPrefs.font((values["danmakuFont"] as? Number)?.toInt() ?: DanmakuPrefs.FONT_DEFAULT),
            DanmakuPrefs.speed((values["danmakuSpeed"] as? Number)?.toInt() ?: DanmakuPrefs.SPEED_DEFAULT),
            DanmakuPrefs.rows((values["danmakuRows"] as? Number)?.toInt() ?: DanmakuPrefs.ROWS_DEFAULT),
            values["showClock"] as? Boolean ?: true,
            values["episodeCache"] as? Boolean ?: true)
        val groupMap = runCatching { JSONObject(values["favoriteGroups"] as? String ?: "{}") }.getOrDefault(JSONObject())
            .let { o -> o.keys().asSequence().mapNotNull { k -> (o.opt(k) as? String)?.let { k to it } }.toMap() }
        return LibraryIndex(LibraryData(series("favorites"), history, ids("watched"),
            SearchHistory.decode(values["searches"] as? String ?: "[]"), settings, series("later"), ids("hidden"), groupMap, ids("reservations")), updates)
    }
    fun series(rows: List<Series>) = JSONArray(rows.map { it.toJson() }).toString()
    fun history(rows: List<LibraryProgress>) = JSONArray(rows.map { p ->
        JSONObject().put("series", p.series.toJson()).put("episodeId", p.episodeId).put("episodeIndex", p.episodeIndex)
            .put("position", p.position).put("duration", p.duration).put("completed", p.completed).put("updatedAt", p.updatedAt)
            .put("watchedEpisodeIds", JSONArray(p.watchedEpisodeIds.sorted()))
    }).toString()
    fun updates(rows: Map<String, FavoriteUpdate>) = JSONObject().also { result -> rows.forEach { (id, update) -> result.put(id, update.json()) } }.toString()
    fun groups(rows: Map<String, String>) = JSONObject().also { result -> rows.forEach { (id, name) -> result.put(id, name) } }.toString()
}
