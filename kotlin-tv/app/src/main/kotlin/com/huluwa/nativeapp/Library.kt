// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = LibraryProgress

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    private companion object {
        // A replacement Activity must not load before its predecessor's final checkpoint commits.
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
        // 独立版本键：旧 home-comic/home-short 存的是网站榜单或分类，不能当作推荐流。
        const val RECOMMENDATION_CACHE_KEY = "phone-recommendation-feed-v1"
        const val PHONE_CACHE_ITEMS = 120
        const val PHONE_RANK_CACHE_PREFIX = "phone-rank-v1-"
        const val MOBILE_RANK_CACHE_PREFIX = "phone-mobile-rank-v1-"
        const val HOT_SEARCH_CACHE_KEY = "phone-hot-search-v1"
        const val NEW_RELEASE_CACHE_PREFIX = "phone-new-releases-v1-"
        const val NEW_RELEASE_CACHE_DAYS = 15
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    private val writes = LatestWriteQueue<String, () -> Any?>("hongguotv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HongguoTV", "Local library commit failed; pending changes retained for retry") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HongguoTV", "Home cache commit failed") })
    private var index: LibraryIndex? = null
    private val homes = mutableMapOf<ContentType, CatalogPage>()
    private val homeSavedAt = mutableMapOf<ContentType, Long>()
    private var recommendationCache: CatalogPage? = null
    private var recommendationSavedAt = 0L
    private val phoneRanks = mutableMapOf<RankBoard, CatalogPage>()
    private val phoneRankSavedAt = mutableMapOf<RankBoard, Long>()
    private val newReleaseCaches = mutableMapOf<String, CachedNewReleases>()
    private val mobileRankCaches = mutableMapOf<String, CachedMobileRank>()
    private var hotSearchCache: CachedHotSearch? = null
    // 榜单缓存：漫剧/短剧/AI 三个榜各自独立一条，与首页缓存同存同有效期
    private val ranks = mutableMapOf<RankBoard, CatalogPage>()
    private val rankSavedAt = mutableMapOf<RankBoard, Long>()

    /** 启动时在后台读取首页、推荐、榜单和按日期保存的新剧快照。 */
    private data class DiskCaches(
        val homes: Map<ContentType, CatalogPage>, val homeStamps: Map<ContentType, Long>,
        val ranks: Map<RankBoard, CatalogPage>, val rankStamps: Map<RankBoard, Long>,
        val recommendations: CatalogPage?, val recommendationStamp: Long,
        val phoneRanks: Map<RankBoard, CatalogPage>, val phoneRankStamps: Map<RankBoard, Long>,
        val newReleases: Map<String, CachedNewReleases>,
        val mobileRanks: Map<String, CachedMobileRank>,
        val hotSearch: CachedHotSearch?,
    )
    private var loading = false
    private var closed = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { Log.e("HongguoTV", "Local library failed to load", it) }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            val result = runCatching {
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                val values = prefs.all
                val loaded = LibraryDisk.decode(values)
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<ContentType, CatalogPage>()
                val stamps = mutableMapOf<ContentType, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                ContentType.entries.forEach { type ->
                    val key = "home-${type.storedValue}"
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[type] = page
                        stamps[type] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // Only remove legacy cache after the separate cache has been committed.
                if (oldKeys.isNotEmpty() && migration.commit()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                // 榜单缓存是新 key，无旧库迁移：三个榜各读各的
                val rankCached = mutableMapOf<RankBoard, CatalogPage>()
                val rankStamps = mutableMapOf<RankBoard, Long>()
                RankBoard.entries.forEach { board ->
                    homePrefs.getString("rank-${board.rankKey}", null)?.let { raw ->
                        CatalogSnapshot.decode(raw, now)?.let { page ->
                            rankCached[board] = page
                            rankStamps[board] = org.json.JSONObject(raw).getLong("savedAt")
                        }
                    }
                }
                val recommendationRaw = homePrefs.getString(RECOMMENDATION_CACHE_KEY, null).orEmpty()
                val recommendations = CatalogSnapshot.decode(recommendationRaw, now, maxItems = PHONE_CACHE_ITEMS)
                val recommendationStamp = if (recommendations != null) org.json.JSONObject(recommendationRaw).getLong("savedAt") else 0L
                val phoneRankCached = mutableMapOf<RankBoard, CatalogPage>()
                val phoneRankStamps = mutableMapOf<RankBoard, Long>()
                RankBoard.entries.forEach { board ->
                    val raw = homePrefs.getString(PHONE_RANK_CACHE_PREFIX + board.rankKey, null).orEmpty()
                    CatalogSnapshot.decode(raw, now, maxItems = PHONE_CACHE_ITEMS)?.let { page ->
                        phoneRankCached[board] = page
                        phoneRankStamps[board] = org.json.JSONObject(raw).getLong("savedAt")
                    }
                }
                val releaseValues = homePrefs.all.filterKeys { it.startsWith(NEW_RELEASE_CACHE_PREFIX) }
                val newReleases = releaseValues.mapNotNull { (key, value) ->
                    NewReleaseSnapshot.decode(value as? String ?: return@mapNotNull null, now)
                        ?.takeIf { key == NEW_RELEASE_CACHE_PREFIX + it.page.date }
                }.sortedByDescending { it.savedAt }.take(NEW_RELEASE_CACHE_DAYS).associateBy { it.page.date }
                val staleReleaseKeys = releaseValues.keys - newReleases.keys.map { NEW_RELEASE_CACHE_PREFIX + it }.toSet()
                if (staleReleaseKeys.isNotEmpty()) {
                    homePrefs.edit().also { edit -> staleReleaseKeys.forEach(edit::remove) }.commit()
                }
                val mobileRankValues = homePrefs.all.filterKeys { it.startsWith(MOBILE_RANK_CACHE_PREFIX) }
                val mobileRanks = mobileRankValues.mapNotNull { (key, value) ->
                    MobileRankSnapshot.decode(value as? String ?: return@mapNotNull null, now)
                        ?.takeIf { key == MOBILE_RANK_CACHE_PREFIX + MobileRankSnapshot.key(it.page.contentId, it.page.boardId) }
                }.sortedByDescending { it.savedAt }.take(MobileRankSnapshot.MAX_BOARDS)
                    .associateBy { MobileRankSnapshot.key(it.page.contentId, it.page.boardId)!! }
                val staleMobileRankKeys = mobileRankValues.keys - mobileRanks.keys.map { MOBILE_RANK_CACHE_PREFIX + it }.toSet()
                if (staleMobileRankKeys.isNotEmpty()) {
                    homePrefs.edit().also { edit -> staleMobileRankKeys.forEach(edit::remove) }.commit()
                }
                val hotValue = homePrefs.all[HOT_SEARCH_CACHE_KEY]
                val hotSearch = (hotValue as? String)?.let { HotSearchSnapshot.decode(it, now) }
                if (hotValue != null && hotSearch == null) homePrefs.edit().remove(HOT_SEARCH_CACHE_KEY).commit()
                Pair(loaded, DiskCaches(cached, stamps, rankCached, rankStamps, recommendations, recommendationStamp,
                    phoneRankCached, phoneRankStamps, newReleases, mobileRanks, hotSearch))
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { (loaded, boot) ->
                    index = loaded
                    homes.putAll(boot.homes); homeSavedAt.putAll(boot.homeStamps)
                    ranks.putAll(boot.ranks); rankSavedAt.putAll(boot.rankStamps)
                    recommendationCache = boot.recommendations; recommendationSavedAt = boot.recommendationStamp
                    phoneRanks.putAll(boot.phoneRanks); phoneRankSavedAt.putAll(boot.phoneRankStamps)
                    newReleaseCaches.putAll(boot.newReleases)
                    mobileRankCaches.putAll(boot.mobileRanks)
                    hotSearchCache = boot.hotSearch
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
    }
    internal fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) { writes.submit(mapOf(key to { value })) }
    private fun persist(vararg keys: String) {
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { LibraryDisk.history(rows) } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "favoriteGroups" -> state.groupsSnapshot().let { rows -> { LibraryDisk.groups(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun watchedEpisodes(id: String): Set<String> = state().watchedEpisodes(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun updates() = state().updates()
    fun groups() = state().groups()
    fun groupOf(id: String) = state().groupOf(id)
    fun setGroup(id: String, group: String?): Boolean = state().setGroup(id, group).also { if (it) persist("favoriteGroups") }
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        val season = if (it.hasNewSeason) "《${it.newSeasonTitle ?: "新季"}》已上线" else ""
        val episodes = if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
        listOf(season, episodes).filter { it.isNotEmpty() }.joinToString(" · ")
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    fun save(progress: WatchProgress) { if (state().save(progress)) persist("progress") }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String, type: ContentType) { state().rememberSearch(query, type); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    fun removeHistory(id: String) { state().removeHistory(id); persist("progress", "watched") }
    fun clearHistory() { state().clearHistory(); persist("progress", "watched") }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false, newSeason: Series? = null) {
        if (state().observeFavorite(id, count, now, acknowledge, newSeason)) persist("favoriteUpdates")
    }
    fun cachedHome(type: ContentType): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[type] ?: return null)
        return homes[type].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(type: ContentType, items: List<Series>, hasMore: Boolean) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[type] = CatalogPage(retained, hasMore)
        homeSavedAt[type] = now
        homeWrites.submit(mapOf("home-${type.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }

    /** 手机推荐缓存只保存卡片；每次冷启动重新请求首屏获取当前设备的分页会话。 */
    fun cachedRecommendations(): List<Series> {
        state()
        val age = System.currentTimeMillis() - recommendationSavedAt
        return recommendationCache?.items?.takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }.orEmpty()
    }
    fun cacheRecommendations(items: List<Series>) {
        state()
        val now = System.currentTimeMillis()
        val retained = phoneCacheItems(items)
        recommendationCache = CatalogPage(retained, true)
        recommendationSavedAt = now
        homeWrites.submit(mapOf(RECOMMENDATION_CACHE_KEY to { CatalogSnapshot.encode(retained, true, now, maxItems = PHONE_CACHE_ITEMS) }))
    }

    private fun phoneCacheItems(items: List<Series>) = items.distinctBy { it.id }.take(PHONE_CACHE_ITEMS)
        .map { it.copy(description = it.description.take(500)) }

    /** 手机榜单独立保存多页快照；升级时仍可读取原有首屏缓存。 */
    fun cachedPhoneRank(board: RankBoard): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (phoneRankSavedAt[board] ?: return cachedRank(board))
        return phoneRanks[board].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L } ?: cachedRank(board)
    }
    fun cachePhoneRank(board: RankBoard, items: List<Series>, hasMore: Boolean, ranking: ComicRanking?) {
        state()
        val now = System.currentTimeMillis()
        val retained = phoneCacheItems(items)
        val retainedRanking = ranking?.copy(positions = ranking.positions.filterKeys { id -> retained.any { it.id == id } })
        phoneRanks[board] = CatalogPage(retained, hasMore, retainedRanking)
        phoneRankSavedAt[board] = now
        homeWrites.submit(mapOf(PHONE_RANK_CACHE_PREFIX + board.rankKey to {
            CatalogSnapshot.encode(retained, hasMore, now, retainedRanking, maxItems = PHONE_CACHE_ITEMS)
        }))
    }

    /** 完整目录和各内容/子榜的展示快照；不复用落盘前的分页会话。 */
    fun cachedMobileRanks(): List<MobileRankPage> {
        state()
        val now = System.currentTimeMillis()
        return mobileRankCaches.values.filter { now - it.savedAt in 0..MobileRankSnapshot.MAX_AGE }
            .sortedByDescending { it.savedAt }.map { it.page }
    }

    fun cacheMobileRank(page: MobileRankPage) {
        state()
        val key = MobileRankSnapshot.key(page.contentId, page.boardId) ?: return
        val now = System.currentTimeMillis()
        val directory = page.contents.ifEmpty { mobileRankCaches.values.maxByOrNull { it.savedAt }?.page?.contents.orEmpty() }
        val retained = MobileRankSnapshot.retain(page.copy(contents = directory))
        // 无目录的续页不替换可用快照；等首屏提供有效目录后再保存。
        if (directory.isEmpty() || directory.none { it.id == retained.contentId && it.boards.any { board -> board.id == retained.boardId } }) return
        mobileRankCaches[key] = CachedMobileRank(retained, now)
        val stale = mobileRankCaches.filterValues { now - it.savedAt !in 0..MobileRankSnapshot.MAX_AGE }.keys +
            mobileRankCaches.entries.filter { it.key != key }.sortedByDescending { it.value.savedAt }
                .drop(MobileRankSnapshot.MAX_BOARDS - 1).map { it.key }
        val batch = linkedMapOf<String, () -> Any?>(
            MOBILE_RANK_CACHE_PREFIX + key to { MobileRankSnapshot.encode(retained, now) })
        stale.forEach { old ->
            mobileRankCaches.remove(old)
            batch[MOBILE_RANK_CACHE_PREFIX + old] = { null }
        }
        homeWrites.submit(batch)
    }

    /** 热搜的分榜、名次与话题快照；调用方展示后仍发起联网刷新。 */
    fun cachedHotSearch(): HotSearchPage? {
        state()
        val cached = hotSearchCache ?: return null
        return cached.page.takeIf { System.currentTimeMillis() - cached.savedAt in 0..HotSearchSnapshot.MAX_AGE }
    }

    fun cacheHotSearch(page: HotSearchPage) {
        state()
        val retained = HotSearchSnapshot.retain(page)
        if (retained.guesses.isEmpty() && retained.boards.isEmpty()) return
        val now = System.currentTimeMillis()
        hotSearchCache = CachedHotSearch(retained, now)
        homeWrites.submit(mapOf(HOT_SEARCH_CACHE_KEY to { HotSearchSnapshot.encode(retained, now) }))
    }

    fun cachedNewReleases(date: String): NewReleasePage? {
        state()
        val cached = newReleaseCaches[date] ?: return null
        return cached.page.takeIf { System.currentTimeMillis() - cached.savedAt in 0..NewReleaseSnapshot.MAX_AGE }
    }
    fun cacheNewReleases(page: NewReleasePage) {
        state()
        if (!NewReleaseCalendar.valid(page.date)) return
        val now = System.currentTimeMillis()
        val retained = page.copy(items = page.items.distinctBy { it.series.id }.take(NewReleaseSnapshot.MAX_ITEMS).map {
            it.copy(series = it.series.copy(description = it.series.description.take(500)), note = it.note.take(500))
        }, cursor = null)
        newReleaseCaches[page.date] = CachedNewReleases(retained, now)
        val stale = newReleaseCaches.filterValues { now - it.savedAt !in 0..NewReleaseSnapshot.MAX_AGE }.keys +
            newReleaseCaches.entries.filter { it.key != page.date }.sortedByDescending { it.value.savedAt }
                .drop(NEW_RELEASE_CACHE_DAYS - 1).map { it.key }
        val batch = linkedMapOf<String, () -> Any?>(
            NEW_RELEASE_CACHE_PREFIX + page.date to { NewReleaseSnapshot.encode(retained, now) })
        stale.forEach { date ->
            newReleaseCaches.remove(date)
            batch[NEW_RELEASE_CACHE_PREFIX + date] = { null }
        }
        homeWrites.submit(batch)
    }

    /** 榜单缓存读取：与首页同款 7 天有效期，过期或从未缓存返回 null。 */
    fun cachedRank(board: RankBoard): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (rankSavedAt[board] ?: return null)
        return ranks[board].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }

    /** 榜单第 1 页落缓存：名次/热度一并存入，供冷启动直接带排名显示。 */
    fun cacheRank(board: RankBoard, items: List<Series>, hasMore: Boolean, ranking: ComicRanking?) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        ranks[board] = CatalogPage(retained, hasMore, ranking)
        rankSavedAt[board] = now
        homeWrites.submit(mapOf("rank-${board.rankKey}" to { CatalogSnapshot.encode(retained, hasMore, now, ranking) }))
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    var autoSeason: Boolean
        get() = state().settings.autoSeason
        set(value) { if (value != autoSeason) { state().settings = state().settings.copy(autoSeason = value); put("autoSeason", value) } }
    /** 整集落盘缓存开关：关闭走原有内存预载模式，切换时由 MainActivity 清空磁盘池。 */
    var episodeCache: Boolean
        get() = state().settings.episodeCache
        set(value) { if (value != episodeCache) { state().settings = state().settings.copy(episodeCache = value); put("episodeCache", value) } }
    var danmaku: Boolean
        get() = state().settings.danmaku
        set(value) { if (value != danmaku) { state().settings = state().settings.copy(danmaku = value); put("danmaku", value) } }
    var danmakuOpacity: Int
        get() = state().settings.danmakuOpacity
        set(value) { val next = DanmakuPrefs.opacity(value); if (next != danmakuOpacity) { state().settings = state().settings.copy(danmakuOpacity = next); put("danmakuOpacity", next) } }
    var danmakuFont: Int
        get() = state().settings.danmakuFont
        set(value) { val next = DanmakuPrefs.font(value); if (next != danmakuFont) { state().settings = state().settings.copy(danmakuFont = next); put("danmakuFont", next) } }
    var danmakuSpeed: Int
        get() = state().settings.danmakuSpeed
        set(value) { val next = DanmakuPrefs.speed(value); if (next != danmakuSpeed) { state().settings = state().settings.copy(danmakuSpeed = next); put("danmakuSpeed", next) } }
    var danmakuRows: Int
        get() = state().settings.danmakuRows
        set(value) { val next = DanmakuPrefs.rows(value); if (next != danmakuRows) { state().settings = state().settings.copy(danmakuRows = next); put("danmakuRows", next) } }
    var contentType: ContentType
        get() = state().settings.type
        set(value) { if (value != contentType) { state().settings = state().settings.copy(type = value); put("contentType", value.storedValue) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    var showClock: Boolean
        get() = state().settings.showClock
        set(value) { if (value != showClock) { state().settings = state().settings.copy(showClock = value); put("showClock", value) } }
    var theme: String
        get() = StartupSettings.string("theme", "NIGHT")
        set(value) { StartupSettings.set("theme", value) }
    /** 启动方向偏好：默认 auto 跟随系统，可选 landscape 强制横屏 / portrait 强制竖屏。
     *  落盘用大写枚举名（AUTO / LANDSCAPE / PORTRAIT），与 [OrientationMode.name] 一致。
     *  本机方向偏好。 */
    var orientationMode: String
        get() = OrientationMode.of(StartupSettings.string("orientationMode", "")).name
        set(value) { StartupSettings.set("orientationMode", OrientationMode.of(value).name) }
    /** 收藏页排序：update 最近更新 / watch 最近观看 / name 名称。本机视图偏好。 */
    var favoriteSort: String
        get() = StartupSettings.string("favoriteSort", "").takeIf { it in listOf("update", "watch", "name") } ?: "update"
        set(value) { StartupSettings.set("favoriteSort", value) }
    /** 排行榜「上新」视图上次用的时间窗：记住偏好，下次进区不用重新选。本机偏好。 */
    var newReleaseWindow: String
        get() = StartupSettings.string("newReleaseWindow", "")
        set(value) { StartupSettings.set("newReleaseWindow", value) }
    /** 手机新剧日历上次选中的日期；过期日期由页面回退到今天。本机偏好。 */
    var newReleaseDay: String
        get() = StartupSettings.string("newReleaseDay", "")
        set(value) { StartupSettings.set("newReleaseDay", value) }
    // Per-series intro/outro skips stay on this device, like the theme.
    private var marksCache: LinkedHashMap<String, SkipMark>? = null
    private fun marks(): LinkedHashMap<String, SkipMark> =
        marksCache ?: SkipMarks.decode(prefs.getString("skipMarks", null)).also { marksCache = it }
    fun skipMark(id: String): SkipMark? = marks()[id]
    fun setSkipMark(id: String, mark: SkipMark?) {
        val next = SkipMarks.set(marks(), id, mark)
        marksCache = next
        prefs.edit().putString("skipMarks", SkipMarks.encode(next)).apply()
    }
    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() {
        writes.flush(); homeWrites.flush()
    }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites
        }
    }
    private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
        val edit = target.edit()
        values.forEach { (key, encode) ->
            when (val value = encode()) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported library value")
            }
        }
        return edit.commit()
    }
}
