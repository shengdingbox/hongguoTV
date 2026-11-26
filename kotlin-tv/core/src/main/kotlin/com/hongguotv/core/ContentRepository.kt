// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "", val release: ReleaseSchedule? = null) {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags).put("release",release?.toJson())
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),seriesTitle(o.str("title")),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags"),o.optJSONObject("release")?.let { ReleaseSchedule.fromJson(it) }) }
}
/** 详情页附带的社交数据，缺失时各字段保持默认空值，调用方按需隐藏。 */
data class SeriesSocial(val rating: Double = 0.0, val ratingCount: String = "", val favoriteText: String = "", val heatText: String = "") {
    val isEmpty get() = rating<=0.0 && favoriteText.isBlank() && heatText.isBlank()
}
/**
 * 剧名规整。
 *
 * 详情接口偶尔把简介拼进剧名字段（实测拿到过 `《黄石第五季》\n蒙大拿荒野深处…`），而标题栏只有
 * 一行高，第二行就被裁成半截字——看起来像被什么东西盖住了。这里只留第一行，压掉多余空白。
 * 读缓存的路径也要过一遍：脏数据可能已经存进去了。
 */
fun seriesTitle(raw: String): String = raw.lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotEmpty() }
    .orEmpty()
    .replace(Regex("[\\s\\u3000]+"), " ")
    .take(48)
data class Detail(val series: Series, val episodes: List<String>, val related: List<Series> = emptyList(), val sameName: List<Series> = emptyList(), val social: SeriesSocial = SeriesSocial(), val seasonsLoaded: Boolean = false) {
    /** Same-name entries with a finite season number beyond the current one (untitled season counts as 1). */
    val newerSeasons: List<Series> get() {
        val current=seasonNumber(series.title).let { if(it==Int.MAX_VALUE) 1 else it }
        return sameName.filter { val n=seasonNumber(it.title); n!=Int.MAX_VALUE && n>current }
    }
    /** The immediately following season, so auto-play continues in order instead of jumping to the latest. */
    val nextSeason: Series? get() = newerSeasons.minByOrNull { seasonNumber(it.title) }
}
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
/** One website genre selector entry; the slug selects the category path, ids echo back for validation. */
data class Genre(val slug: String, val name: String, val ids: List<String>)
/** [idHint]/[qualityNumber] 由播放缓存回填，供整集落盘缓存按键查找元数据。 */
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String, val expiresAt: Long = 0L,
    val idHint: String = "", val qualityNumber: Int = 0, val backupUrls: List<String> = emptyList())
/** 一次窗口取址的结果：[requested] 请求集数、[cached] 取址后可播放的集数、[batch] 本次批量新增、[route] 命中的路由。 */
data class WarmReport(val requested: Int, val cached: Int, val batch: Int, val route: String?)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
/** 已尝试的取址路由没有返回当前播放链路可用的视频，保留诊断信息供兼容源回退使用。 */
class UnplayableVideoException(message: String, val diagnostic: String = ""): IOException(message)
/** 播放信息接口返回了 HTTP 200 空体；已按有界退避重读仍未成功。 */
class EmptyPlaybackResponseException: IOException("播放接口暂时返回空响应，请稍后重试")
/** 播放接口返回了非空错误码；合集分集在普通语义下正是回 101000。 */
class ServiceException(val code: Int): IOException("播放接口暂不可用（错误码 $code）")

/** 站点 CDN 在重定向时下发 ttwid 并拦截无 Cookie 的后续请求，内存 Cookie 罐使其自动流转。 */
class MemoryCookies : okhttp3.CookieJar {
    private val store = java.util.concurrent.ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()
    // RFC 语义：同名 + 同域 + 同路径的新 Cookie 替换旧值，而不是叠加。
    private fun List<okhttp3.Cookie>.replacedBy(cookies: List<okhttp3.Cookie>) =
        filter { kept -> cookies.none { it.name==kept.name && it.domain==kept.domain && it.path==kept.path } }
            .toMutableList().also { it += cookies }
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        if (cookies.isEmpty()) return
        store.compute(url.host) { _, kept -> kept.orEmpty().replacedBy(cookies) }
    }
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host].orEmpty().filter { it.expiresAt > now }
    }
}

open class ContentRepository(
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).cookieJar(MemoryCookies()).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    val signer: RemoteSigner = RemoteSigner(),
    private val modelCache: PlaybackModelCache = PlaybackModelCache(),
    /** 播放算法热切换策略；为空时只使用传入的 [signer]，兼容纯内容请求和旧测试。 */
    private val playbackAlgorithms: PlaybackAlgorithmPolicy? = null,
    /** 播放恢复状态；预取与正式播放的子仓库必须共享同一份，避免各自重复撞域名。 */
    private val playbackRecovery: PlaybackRecovery = PlaybackRecovery(),
) {
    /** 用户选择的当前签名器；普通内容接口与播放取址共用。 */
    val activeSigner: RemoteSigner get() = playbackAlgorithms?.signer ?: signer
    /** 同一份取址缓存、另一套传输：预取需要更短的超时，但必须和正式播放看到同一个缓存。 */
    fun childTransport(http: OkHttpClient) = ContentRepository(http, clock, signer, modelCache, playbackAlgorithms, playbackRecovery)
    /** 设置页在当前 Activity 内显式切换，不必等待 Activity 重建。 */
    fun selectPlaybackAlgorithm(algorithm: PlaybackAlgorithm) { playbackAlgorithms?.select(algorithm) }
    /** 播放缓存重建时替换 repository；新缓存会自动绑定最新算法。 */
    fun rebind(signer: RemoteSigner, algorithms: PlaybackAlgorithmPolicy?): ContentRepository =
        ContentRepository(http, clock, signer, modelCache, algorithms, playbackRecovery)
    /** 当前播放算法；纯内容请求为空。 */
    val playbackAlgorithm: PlaybackAlgorithm? get() = playbackAlgorithms?.algorithm
    private val site = "https://hongguoduanju.com"
    /** 红果官方 App 的 JSON 网关；签名与设备身份由 [RemoteSigner] 统一补齐。 */
    private val API = "https://api5-normal-sinfonlinea.fqnovel.com"
    // 点击卡片后的关键请求独立限时，复用连接池；不继承普通内容请求最长 45 秒的等待。
    private val detailHttp by lazy {
        http.newBuilder()
            .connectTimeout(minOf(http.connectTimeoutMillis.toLong(), 4_000L), TimeUnit.MILLISECONDS)
            .readTimeout(minOf(http.readTimeoutMillis.toLong(), 6_000L), TimeUnit.MILLISECONDS)
            .callTimeout(if(http.callTimeoutMillis>0) minOf(http.callTimeoutMillis.toLong(), 8_000L) else 8_000L, TimeUnit.MILLISECONDS)
            .build()
    }
    // 搜索独立短超时，所有回退和首次匿名注册另外受 RequestScope 的总预算约束。
    private val searchHttp by lazy { http.newBuilder().connectTimeout(2,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(3_500,TimeUnit.MILLISECONDS).build() }
    private val suggestHttp by lazy { searchHttp.newBuilder().callTimeout(1_500,TimeUnit.MILLISECONDS).build() }
    private data class SearchCursor(val route: SearchRoute, val searchId: String = "", val passback: String = "", val offset: Int = 0, val seen: Set<String> = emptySet())
    private data class SearchPage(val at: Long, val page: CatalogPage, val cursor: SearchCursor)
    private val searchPages=linkedMapOf<Triple<ContentType,String,Int>,SearchPage>()
    private val searchRetryAt=mutableMapOf<SearchRoute,Long>()
    private val genreCache=mutableMapOf<ContentType,Pair<Long,List<Genre>>>()
    private val suggestCache=linkedMapOf<String,Pair<Long,List<String>>>()
    private fun categoryPath(type: ContentType)=when(type) {
        ContentType.COMIC -> "comic-drama"
        ContentType.SHORT -> "real-drama"
        ContentType.AI -> "ai-drama"
    }
    /** 官方接口统一走这里：签名头与设备身份都由本机 [RemoteSigner] 生成。 */
    private fun text(url: String): String = text(url, null)

    /** [signed] 非空时用本机签名给出的地址与请求头；为空时按普通网页请求处理。 */
    private fun text(url: String, signed: SignedRequest?, transport: OkHttpClient = http): String {
        val request = Request.Builder().url(signed?.url ?: url).header("Accept-Language","zh-CN,zh;q=0.9")
        request.header("User-Agent", if (signed != null) activeSigner.profile().userAgent else VendorConstants.VIDEO_UA)
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            // 受保护接口对「平台不认的设备身份」只回 HTTP 200 + 空体，这里给出可行动的提示。
            if(bytes.isEmpty()) throw IOException(
                if (signed != null) "接口返回空响应，本机匿名设备可能已被限制，请稍后重试"
                else "接口响应为空，请稍后重试")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),seriesTitle(o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } }),cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    /**
     * 电视排行榜的分页会话。
     *
     * 同一个榜单可能被首页、排行榜页签和随机荐剧同时消费，各自的分页进度不能互相覆盖，
     * 因此游标按会话隔离：调用方自己持有 [RankSession]，翻页时传回来即可。
     */
    class RankSession internal constructor(var board: RankBoard) {
        internal var cursor: MobileRankCursor? = null
        /** 已加载条数：名次按累计顺序编号，跨页连续。 */
        internal var offset: Int = 0
        /** 丢弃当前游标，下次请求从第 1 页重新建立会话。 */
        fun reset() { cursor = null; offset = 0 }
    }

    /**
     * 为某榜单开一份独立的分页会话。会话只服务构造时指定的榜单：
     * 同一个会话对象被换成别的榜单时自动重开，避免把 A 榜的游标用到 B 榜上。
     */
    fun rankSession(board: RankBoard): RankSession = RankSession(board)

    /**
     * 电视排行榜的一页。
     *
     * 官方接口不按页码翻页，进度由 [session] 上的游标决定：
     * - 传 `page == 1` 或首次使用该会话，从第 1 页重新建立；
     * - 传 `page > 1` 沿会话游标续页；会话没有游标时同样从第 1 页起。
     */
    @Synchronized
    open fun ranking(board: RankBoard, page: Int = 1, session: RankSession? = null): CatalogPage {
        require(page in 1..100)
        val state = session ?: rankDefaultSessions.getOrPut(board) { RankSession(board) }
        // 会话绑定的榜单与本次请求不一致说明调用方换榜了，旧游标不能沿用。
        if (state.board != board || page == 1) { state.reset(); state.board = board }
        val cursor = state.cursor
        val values = linkedMapOf("cell_id" to RANK_CELL_ID, "tab_type" to "26",
            "selected_items" to board.contentId, "offset" to (cursor?.offset ?: 0).toString(),
            "limit" to "0", "client_req_type" to "2", "client_template" to "2",
            "unlimited_selector_change_type" to if (cursor == null) "1" else "2",
            "user_is_login" to "0")
        // 首屏主动指定默认子榜，否则服务端会按自己的默认项返回（通常是短剧热播）。
        values["sub_selected_items"] = cursor?.boardId?.takeIf { it.isNotBlank() }
            ?: ComicRank.defaultBoardId(board)
        if (cursor != null) {
            if (cursor.seen.isNotEmpty()) values["filter_ids"] = cursor.seen.joinToString(",")
            if (cursor.sessionId.isNotBlank()) values["session_id"] = cursor.sessionId
        }
        val root = catalogJson("$API/reading/bookapi/bookmall/cell/change/v1/", values)
        val slice = ComicRank.parse(root, board, cursor, state.offset)
        state.offset += slice.page.items.size
        state.cursor = slice.next
        return slice.page
    }

    /** 未显式传会话时的默认会话，供只取单页的调用方使用。 */
    private val rankDefaultSessions = mutableMapOf<RankBoard, RankSession>()

    /** 登录账号凭据；推荐请求用它走个性化口径，为空保持游客。由界面层在账号变化时注入。 */
    @Volatile var feedAccount: AccountCredentials? = null

    /** 随心看首屏：书城推荐接口，返回卡片、试播集提示和服务端游标；offset>0 时按 tab 翻页口径续拉。 */
    fun suixinInitial(offset: Int = 0, unsignedFallback: Boolean = false): SuixinPage {
        val page = SuixinFeedClient(http, activeSigner, feedAccount).initial(offset, unsignedFallback)
        val cursor = page.cursor?.copy(seen = page.items.mapTo(hashSetOf()) { it.id })
        return page.copy(cursor = cursor, items = page.items, trialHints = page.trialHints)
    }

    /**
     * 随心看续刷：优先走单列 cell/change，服务端没有单列上下文时回退 tab/v1 的按条 offset。
     * 110 先降级游客口径；其余非零错误码且已有服务端会话时丢弃旧会话从头拉（对齐参考 restartServer）。
     */
    fun suixinNext(cursor: SuixinCursor): SuixinPage {
        var current = cursor
        var last: Exception? = null
        repeat(3) {
            try {
                return suixinNextOnce(current)
            } catch (feed: SuixinFeedException) {
                last = feed
                if (feed.code == 110 && !current.unsignedFallback) {
                    current = current.copy(unsignedFallback = true)
                    return@repeat
                }
                if (feed.code != 0 && current.sessionId.isNotEmpty()) {
                    current = current.restarted()
                    return@repeat
                }
                throw feed
            }
        }
        throw last ?: IOException("随心看续刷失败")
    }

    private fun suixinNextOnce(cursor: SuixinCursor): SuixinPage {
        if (cursor.readyToContinue && cursor.cellId.isNotBlank()) {
            val page = SuixinFeedClient(http, activeSigner, feedAccount).next(cursor)
            val seen = (cursor.seen + page.items.map { it.id }).toList().takeLast(540).toSet()
            val next = page.cursor?.copy(seen = seen)
            return page.copy(cursor = next, items = page.items.filter { it.id !in cursor.seen })
        }
        // tab/v1 回退与 cell 通道统一走同一客户端：同一签名口径与主机轮换策略。
        val page = SuixinFeedClient(http, activeSigner, feedAccount).initial(cursor.offset, cursor.unsignedFallback)
        val seen = (cursor.seen + page.items.map { it.id }).toList().takeLast(540).toSet()
        val pageCursor = page.cursor?.copy(offset = cursor.offset + maxOf(1, page.items.size), seen = seen)
        return page.copy(cursor = pageCursor, items = page.items.filter { it.id !in cursor.seen })
    }

    /** 分类随心看；漫剧与真人短剧使用同一分类接口的不同 req_scene。 */
    fun suixinCategory(comic: Boolean, cursor: SuixinCategoryCursor? = null): SuixinPage =
        SuixinFeedClient(http, activeSigner).category(comic, cursor)

    /** 应用按服务端试播集提示直接打开单集，失败时由上层回退详情首集。 */
    fun suixinTrial(hint: SuixinTrialHint, maxQuality: Int = 1080): Pair<Detail, StreamInfo> {
        // 播放模型只接受集 ID，详情仅用于首个正式播放页的描述，不能把集 ID 当成作品 ID。
        return Detail(Series(hint.episodeId, "随心看"), listOf(hint.episodeId)) to
            stream(hint.episodeId, maxQuality, playback = true)
    }


    /** 与第三方参考 iOS 客户端的 Discover 对齐：首屏合并频道预加载和首批猜你喜欢，之后沿游标加载。 */
    open fun recommendations(cursor: RecommendationCursor? = null): RecommendationPage {
        require(cursor == null || (cursor.cellId.isNotBlank() && cursor.sessionId.isNotBlank() &&
            cursor.showType.isNotBlank() && cursor.offset >= 0)) { "推荐分页信息已过期，请下拉刷新" }
        fun change(next: RecommendationCursor): RecommendationPage {
            val values = linkedMapOf(
                "cell_id" to next.cellId,
                "offset" to next.offset.toString(),
                "limit" to "20",
                "change_type" to "0",
                "tab_type" to "16",
                "show_type" to next.showType,
                "client_req_type" to "2",
                "session_id" to next.sessionId,
            )
            if (next.clientTemplate.isNotBlank()) values["client_template"] = next.clientTemplate
            return RecommendationParser.next(catalogJson("$API/reading/bookapi/bookmall/cell/change/v", values), next)
        }
        var result = if (cursor != null) change(cursor) else {
            val values = linkedMapOf(
                "tab_type" to "-1",
                "offset" to "0",
                "client_req_type" to "3",
                "bottom_tab_type" to "7",
                "bottom_tab_type_list" to "7,0,1,2,3,4",
            )
            val initial = RecommendationParser.initial(catalogJson("$API/reading/bookapi/bookmall/tab/v", values))
            initial.cursor?.let { next ->
                val more = change(next)
                RecommendationPage((initial.items + more.items).distinctBy { it.id }, more.cursor,
                    "${initial.diagnostic}; ${more.diagnostic}")
            } ?: initial
        }
        // 服务端偶尔夹有空页：最多越过两页，单次加载不超过三页，避免空列表卡住或无限请求。
        repeat(2) {
            val next = result.cursor
            if (result.items.isNotEmpty() || next == null) {
                if (cursor == null && result.items.isEmpty()) throw EmptyRecommendationException(result.diagnostic)
                return result
            }
            val more = change(next)
            result = more.copy(diagnostic = "${result.diagnostic}; ${more.diagnostic}")
        }
        if (result.items.isEmpty() && (cursor == null || result.cursor != null)) {
            throw EmptyRecommendationException(result.diagnostic)
        }
        return result
    }

    /** 一次补充约 24 部，沿真实游标最多连续取 4 页；每页立即交给界面展示。 */
    fun recommendationBatch(
        cursor: RecommendationCursor? = null,
        onPage: (RecommendationPage) -> Unit = {},
    ): RecommendationPage {
        var next = cursor
        var batch: RecommendationPage? = null
        repeat(4) {
            val page = try { recommendations(next) } catch (problem: Exception) {
                if (Thread.currentThread().isInterrupted || RequestScope.current()?.isCancelled == true) throw problem
                // 已成功的页面照常交付，后续失败从最后成功的游标继续，不丢弃已拿到的内容。
                return batch?.let { it.copy(diagnostic = "${it.diagnostic}; batch stopped=${problem.javaClass.simpleName}") }
                    ?: throw problem
            }
            val previous = batch
            val items = (previous?.items.orEmpty() + page.items).distinctBy { it.id }
            val repeated = previous != null && page.items.isNotEmpty() && items.size == previous.items.size
            val result = RecommendationPage(items, if (repeated) null else page.cursor,
                listOfNotNull(previous?.diagnostic, page.diagnostic).joinToString("; "))
            batch = result
            next = result.cursor
            onPage(result)
            if (items.size >= 24 || next == null) return result
        }
        return checkNotNull(batch)
    }

    private fun catalogJson(url: String, values: Map<String, String>): JSONObject {
        val signed = activeSigner.signCatalog(url, values)
        val raw = text(signed.url, signed)
        val root = runCatching { JSONObject(raw) }.getOrElse { throw IOException("内容接口数据格式异常", it) }
        val code = root.str("code")
        if (code.isNotBlank() && code != "0") throw IOException("内容接口返回错误（code=$code）")
        return root
    }

    /** 公开上新日历，与第三方参考 iOS 客户端的 NewReleases 使用同一签名接口。 */
    open fun newReleases(date: String = "", cursor: NewReleaseCursor? = null): NewReleasePage {
        require(date.isEmpty() || NewReleaseCalendar.valid(date)) { "新剧日期必须为 YYYYMMDD" }
        require(cursor == null || (NewReleaseCalendar.valid(cursor.date) && cursor.offset > 0 &&
            (date.isEmpty() || date == cursor.date))) { "新剧分页游标不属于当前日期" }
        val selectedDate = cursor?.date ?: date
        val values = linkedMapOf(
            "order_experiment" to "descend",
            "offset" to (cursor?.offset ?: 0).toString(),
            "active_panel" to "5",
            "session_id" to cursor?.sessionId.orEmpty(),
            "version" to "0",
            "gender_type" to "2",
            "tab_type" to "5",
            "filter_type" to "gender",
            "subscribe_list_opt" to "0",
            "tab_style" to "2",
            "need_calendar_schema" to "true",
            "user_is_login" to "0",
        )
        if (selectedDate.isNotEmpty()) values["target_date"] = selectedDate
        return NewReleaseParser.parse(signedJson("$API/reading/user/subscribe/list/v1/", values, null), selectedDate, cursor)
    }
    /** 搜索首页真实热搜，包含短剧、漫剧、新剧与热点话题等服务端分组。 */
    open fun hotSearch(): HotSearchPage {
        val values = linkedMapOf("user_is_login" to "0", "bookstore_tab" to "16", "bookstore_tab_type" to "16",
            "scene" to "10", "limit" to "0", "tab_name" to "feed", "from" to "search_input_page",
            "query_history_removed" to "false", "offset" to "0", "search_middle_page_version" to "4",
            "search_source" to "1", "new_search_middle_page" to "true", "hot_word_exchange" to "false")
        return MobileDiscoveryParser.hot(catalogJson("$API/reading/bookapi/plan/v1/", values))
    }

    /** 手机榜单使用官方动态目录，可选内容类型与子榜由服务端返回。 */
    open fun mobileRanking(contentId: String = "", boardId: String = "", cursor: MobileRankCursor? = null): MobileRankPage {
        require(cursor == null || ((contentId.isBlank() || cursor.contentId == contentId) &&
            (boardId.isBlank() || cursor.boardId == boardId) && cursor.offset > 0 && cursor.seen.size <= 2000)) { "榜单游标已过期，请下拉刷新" }
        val content = cursor?.contentId ?: contentId.ifBlank { "all" }
        val board = cursor?.boardId ?: boardId
        val values = linkedMapOf("cell_id" to RANK_CELL_ID, "tab_type" to "26", "selected_items" to content,
            "offset" to (cursor?.offset ?: 0).toString(), "limit" to "0", "client_req_type" to "2",
            "client_template" to "2", "unlimited_selector_change_type" to if (cursor == null) "1" else "2",
            "user_is_login" to "0")
        if (board.isNotBlank()) values["sub_selected_items"] = board
        if (cursor != null) {
            values["filter_ids"] = cursor.seen.joinToString(",")
            if (cursor.sessionId.isNotBlank()) values["session_id"] = cursor.sessionId
        }
        return MobileDiscoveryParser.rank(catalogJson("$API/reading/bookapi/bookmall/cell/change/v1/", values), content, board, cursor)
    }
    /** 官方榜单每页固定约 10 部；每批沿游标最多取 2 页，逐页展示，目标约 20 部。 */
    fun mobileRankingBatch(
        contentId: String = "",
        boardId: String = "",
        cursor: MobileRankCursor? = null,
        onPage: (MobileRankPage) -> Unit = {},
    ): MobileRankPage {
        var next = cursor
        var batch: MobileRankPage? = null
        repeat(2) {
            val page = try {
                mobileRanking(next?.contentId ?: contentId, next?.boardId ?: boardId, next)
            } catch (problem: Exception) {
                if (Thread.currentThread().isInterrupted || RequestScope.current()?.isCancelled == true) throw problem
                // 后续页失败仍保留已展示的数据和最后成功的游标，下次继续续页。
                return batch ?: throw problem
            }
            val items = (batch?.items.orEmpty() + page.items).distinctBy { it.id }
            val result = page.copy(items = items)
            batch = result
            next = result.cursor
            onPage(result)
            if (items.size >= 20 || next == null) return result
        }
        return checkNotNull(batch)
    }
    fun comicRanking(page: Int = 1)=ranking(RankBoard.COMIC,page)
    fun realRanking(page: Int = 1)=ranking(RankBoard.REAL,page)
    /** Genre selectors from the website's category page; cached briefly because they rarely change. */
    open fun genres(type: ContentType): List<Genre> {
        synchronized(genreCache) { genreCache[type]?.let { if(clock()-it.first<600_000) return it.second } }
        val data=router("$site/category/${categoryPath(type)}")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("题材列表暂不可用")
        val rows=section.optJSONArray("selectorList") ?: throw IOException("题材列表暂不可用")
        val genres=linkedMapOf<String,Genre>()
        for(row in rows.objects()) for(item in row.optJSONArray("items")?.objects() ?: emptyList()) {
            val ids=item.optJSONArray("category_json_ids") ?: continue
            val list=(0 until ids.length()).map { ids.optString(it) }.filter { it.isNotBlank() }
            val slug=item.str("selector_item_id"); val name=item.str("show_name")
            if(slug.isNotBlank() && name.isNotBlank() && list.isNotEmpty()) genres.putIfAbsent(slug,Genre(slug,name,list))
        }
        if(genres.isEmpty()) throw IOException("题材列表暂不可用")
        return genres.values.toList().also { list -> synchronized(genreCache) { genreCache[type]=clock() to list } }
    }
    open fun home(page: Int = 1, type: ContentType = ContentType.SHORT, genre: Genre? = null): CatalogPage {
        require(page in 1..100)
        if(genre!=null) return categoryListing("$site/category/${categoryPath(type)}/${genre.slug}"+if(page>1) "?page=$page" else "",page,"题材",genre)
        if(type==ContentType.COMIC) return comicRanking(page)
        if(type==ContentType.AI) return categoryListing("$site/category/ai-drama"+if(page>1) "?page=$page" else "",page,"AI剧")
        return categoryListing("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "",page,"首页")
    }
    private fun categoryListing(url: String,page: Int,label: String,genre: Genre? = null): CatalogPage {
        val data=router(url)
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("${label}数据结构已变化")
        if(genre!=null) {
            // The site silently ignores filters it does not recognize; only the echoed ids prove the genre applied.
            val applied=section.optJSONObject("apiQuery")?.optJSONArray("category_json_ids")
            val matched=applied!=null && genre.ids.any { id -> (0 until applied.length()).any { applied.optString(it)==id } }
            if(!matched) throw IOException("题材筛选暂不可用，请稍后重试")
        }
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("${label}数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.COMIC, refresh: Boolean = false): CatalogPage {
        val query=keyword.trim()
        require(query.isNotBlank() && query.length<=80 && page in 1..100)
        if(type==ContentType.AI) return search(query,page,ContentType.COMIC,refresh)
        val key=Triple(type,query,page)
        val previous=synchronized(searchPages) {
            val now=clock()
            searchPages.entries.removeAll { now-it.value.at !in 0..300_000L }
            searchPages[key]?.takeIf { page>1 || !refresh && now-it.at<60_000 }?.let { return it.page }
            if(page==1) {
                searchPages.keys.removeAll { it.first==type && it.second==query }
                null
            } else searchPages[Triple(type,query,page-1)] ?: throw SearchSessionExpiredException()
        }
        if(previous!=null && !previous.page.hasMore) return CatalogPage(emptyList(),false)
        return searchBudget(10_000) {
            val cursor=previous?.cursor
            // 翻页只能续接原数据源；失败时保留当前结果供重试，不拿网站页码冒充 App 游标。
            val routes=cursor?.let { listOf(it.route) }
                ?: listOf(SearchRoute.COMMON,SearchRoute.SERIES,SearchRoute.WEBSITE)
            var failure: Exception?=null
            for(route in routes) {
                ensureSearchActive()
                if(!refresh && cursor==null && route!=SearchRoute.WEBSITE && synchronized(searchRetryAt) { clock()<(searchRetryAt[route] ?: 0L) }) continue
                try {
                    val result=if(route==SearchRoute.WEBSITE) {
                        val result=webSearch(query,page,null,retry=false,transport=searchHttp)
                        SearchResponse(result.items,result.hasMore,null,"","")
                    } else appSearch(query,route,cursor)
                    val seen=cursor?.seen.orEmpty()
                    val fresh=result.items.filter { it.id !in seen }
                    val nextOffset=result.offset ?: ((cursor?.offset ?: 0)+20)
                    val more=result.hasMore && fresh.isNotEmpty() && page<100 && seen.size+fresh.size<2000 &&
                        (route==SearchRoute.WEBSITE || nextOffset>(cursor?.offset ?: 0))
                    val output=CatalogPage(SearchResults.orderSeasons(fresh),more)
                    val next=SearchCursor(route,result.searchId,result.passback,nextOffset,seen+fresh.map { it.id })
                    ensureSearchActive()
                    synchronized(searchPages) {
                        searchPages[key]=SearchPage(clock(),output,next)
                        while(searchPages.size>100) searchPages.remove(searchPages.keys.first())
                    }
                    return@searchBudget output
                } catch(problem: Exception) {
                    ensureSearchActive()
                    failure=problem
                    // 短期避开失败的具体页签，其他搜索来源仍可继续尝试。
                    if(cursor==null && route!=SearchRoute.WEBSITE) synchronized(searchRetryAt) { searchRetryAt[route]=clock()+30_000 }
                }
            }
            throw IOException("搜索暂不可用，请稍后重试",failure)
        }
    }

    private fun ensureSearchActive() {
        RequestScope.current()?.ensureActive()
        if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("搜索请求已取消")
    }

    private fun <T> searchBudget(timeoutMillis: Long, action: () -> T): T {
        val scope=RequestScope.current()?.child(timeoutMillis) ?: RequestScope(timeoutMillis)
        try { return scope.run(action) } finally { scope.cancel() }
    }
    /** 高级搜索：按服务端下发的条件筛选剧集；条件变化后必须重取第 1 页才能拿到新的选项面板。 */
    fun filteredDiscovery(selection: DiscoverySelection, cursor: DiscoveryCursor? = null): FilteredDiscoveryPage {
        // 客户端版本号由本机设备档案统一提供，这里只传业务参数。
        val body=selection.request(cursor)
        val signed=activeSigner.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",emptyMap(),body.toByteArray(Charsets.UTF_8))
        // 该接口与搜索接口同源，偶发空体或 5xx 时重试一次再判定失败
        val raw=runCatching { text(signed.url, signed) }.getOrElse { first ->
            Thread.sleep(300)
            runCatching { text(signed.url, signed) }.getOrElse { throw first }
        }
        return DiscoveryParser.parse(raw,cursor==null)
    }
    // 站点搜索页已改为 SPA，数据由 /api/search/result/ 下发（返回 searchList/totalPages/hasMore，10 条一页）。
    private fun webSearch(keyword: String, page: Int, genre: String?, retry: Boolean = true, transport: OkHttpClient = http): CatalogPage {
        val url="$site/api/search/result/?query=${encodePart(keyword)}&page=$page"
        // 该接口偶发 502，重试一次再判定失败，避免搜索页直接报错
        val body=try { text(url,null,transport) } catch(first: IOException) {
            ensureSearchActive()
            if(!retry) throw first
            Thread.sleep(300)
            try { text(url,null,transport) } catch(_: IOException) { throw first }
        }
        val data=runCatching { JSONObject(body) }.getOrElse { throw IOException("搜索数据暂不可用") }
        val rows=data.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val mapped=rows.objects().let { list -> if(genre==null) list else list.filter { (it.optJSONObject("video_data") ?: it).optString("genre")==genre } }.map(::card)
        // 站点把漫剧/短剧混在同一结果里，按 genre 过滤后单页条数会减少；分页仍以站点页数为准。
        val lastPage=data.optInt("totalPages",page)
        return CatalogPage(valid(mapped),data.optBoolean("hasMore",false) && page<minOf(lastPage,100))
    }
    private data class SeasonFill(val at: Long, val items: List<Series>)
    private val seasonFills=mutableMapOf<String, SeasonFill>()
    /** 网站搜索固定只返回前 10 条，多季剧必然缺季；联想接口未被拦截，用同名季的精确标题回查网站补齐。 */
    private fun collectSeasons(base: String, known: List<Series>, genre: String?, hints: List<String>, cacheKey: String, budget: Int = 10, timeoutMillis: Long = 8_000): List<Series> {
        val knownIds=known.map { it.id }.toSet()
        // 缓存存的是「该剧名已知季并集」（写缓存时的 known + 本次新发现），不是差集。
        // 各季详情页的 related 聚合范围不一致（第 1 季页常带全季、第 5 季页只带前四季）：
        // 存差集时第 1 季页算出「无缺季」的空结果会污染第 5 季页，第 6 季直接消失（2026-11-08 用户实测）。
        // 存并集后，第 5 季页只需从缓存里减掉自己已有的季，缺的季原样补回，无需重新全网搜索。
        val cached=synchronized(seasonFills) { seasonFills[cacheKey]?.takeIf { clock()-it.at<86_400_000 }?.items }
        if(cached!=null) {
            val missing=cached.filter { it.id !in knownIds }.take(budget)
            // 缓存知识面覆盖本次已知（或确实还有缺季）才直接复用；缓存比本次知道的还少，则重新补一次。
            // 比较基数只数同基底的季：搜索页传进来的 known 混着多个剧，用总数会误判成「缓存太窄」而白跑一轮网络。
            if(missing.isNotEmpty() || cached.size>=known.count { baseName(it.title)==base }) return missing
        }
        val found=mutableListOf<Series>()
        fun absorb(page: CatalogPage?) {
            page?.items?.filter { it.id !in knownIds && baseName(it.title)==base && found.none { s -> s.id==it.id } }?.let { found+=it }
        }
        // 补季是辅助信息，所有搜索、联想与逐季回查共用一个总时限，不能拖住已取到的分集。
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val scope=RequestScope()
        var complete=true
        fun <T> await(task: Future<T>): T? {
            val remaining=(deadline-System.nanoTime()).coerceAtLeast(0L)
            return try { task.get(remaining,TimeUnit.NANOSECONDS) }
            catch(_: TimeoutException) { complete=false; null }
            catch(_: ExecutionException) { complete=false; null }
        }
        val queries=hints.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val pool=Executors.newFixedThreadPool(4)
        try {
            // RequestScope 是 ThreadLocal，必须显式带进工作线程，才能取消真实的 HTTP 请求。
            val pages=listOf(base,base.take(4)).distinct().map { query -> pool.submit(Callable { scope.run { webSearch(query,1,genre) } }) }
            val suggestions=queries.map { query -> pool.submit(Callable { scope.run { suggestRaw(query) } }) }
            pages.forEach { absorb(await(it)) }
            val knownTitles=(known.map { it.title }+found.map { it.title }).map { it.trim() }.toSet()
            val names=suggestions.flatMap { await(it).orEmpty() }.map { it.trim() }
                .filter { it.isNotBlank() && baseName(it)==base && it !in knownTitles }.distinct()
            if(System.nanoTime()<deadline) {
                val lookups=names.map { name ->
                    val season=seasonNumber(name)
                    pool.submit(Callable { scope.run {
                        webSearch(name,1,genre).items.firstOrNull { it.id !in knownIds && baseName(it.title)==base && seasonNumber(it.title)==season }
                    } })
                }
                for(task in lookups) {
                    if(found.size>=budget) break
                    val match=await(task)
                    if(match!=null && found.none { it.id==match.id }) found+=match
                }
            } else if(names.isNotEmpty()) complete=false
        } catch(e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("补季请求已取消").apply { initCause(e) }
        } finally {
            scope.cancel()
            pool.shutdownNow()
        }
        val final=found.take(budget)
        // 写并集；超时或失败的部分结果只用于本次展示，不能把缺季状态缓存一整天。
        if(complete) {
            val union=(known.filter { baseName(it.title)==base }+final).distinctBy { it.id }.take(40)
            synchronized(seasonFills) {
                seasonFills[cacheKey]=SeasonFill(clock(),union)
                while(seasonFills.size>50) seasonFills.remove(seasonFills.keys.first())
            }
        }
        return final
    }
    /** 搜索、联想复用书城的匿名游客票据和设备签名，与第三方参考实现 SDK 的请求头一致。 */
    private fun suggestRaw(query: String): List<String> {
        if(query.isBlank() || query.length>40 || query.any { it.isISOControl() }) return emptyList()
        synchronized(suggestCache) { suggestCache[query]?.takeIf { clock()-it.first in 0..(if(it.second.isEmpty()) 10_000L else 300_000L) }?.let { return it.second } }
        val result=runCatching { searchBudget(2_000) {
            val values=linkedMapOf("query" to query,"count" to "10","search_source" to "1","user_is_login" to "0")
            val signed=activeSigner.signCatalog("$API/reading/bookapi/search/suggest/v1/",values)
            SearchResults.suggestions(JSONObject(text(signed.url,signed,suggestHttp)))
        } }.getOrElse { emptyList() }
        if(Thread.currentThread().isInterrupted || RequestScope.current()?.isCancelled==true) return emptyList()
        // 空结果也短暂缓存，避免同一个没有联想的词反复触发请求。
        synchronized(suggestCache) {
            suggestCache[query]=clock() to result
            while(suggestCache.size>200) suggestCache.remove(suggestCache.keys.first())
        }
        return result
    }

    private fun appSearch(keyword: String, route: SearchRoute, cursor: SearchCursor?): SearchResponse {
        val values=linkedMapOf("query" to keyword,"tab_type" to route.tab.toString(),"count" to "20",
            "offset" to (cursor?.offset ?: 0).toString(),"use_correct" to "true","bookshelf_search_plan" to "4",
            "search_source" to "1","tab_name" to "feed","bookstore_tab" to "16","user_is_login" to "0")
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
        }
        val signed=activeSigner.signCatalog("$API/reading/bookapi/search/tab/v",values)
        return SearchResults.parse(JSONObject(text(signed.url,signed,searchHttp)),route)
    }

    /** 支持中文、完整剧名和电视键盘首拼；失败时保持输入框可用。 */
    fun suggest(query: String): List<String> = suggestRaw(query.trim().lowercase(Locale.ROOT))

    /** 首屏显示后再批量补封面，保留搜索标题与顺序，不串行请求每部剧的详情。 */
    fun searchArtwork(items: List<Series>): List<Series> = searchBudget(6_000) {
        val found=items.associateBy { it.id }.toMutableMap()
        for(item in items.filter { it.cover.isBlank() }) {
            cachedDetail(item.id)?.series?.takeIf { it.cover.isNotBlank() }?.let { found[item.id]=item.copy(cover=it.cover,badge=item.badge.ifBlank { it.badge }) }
        }
        val missing=found.values.filter { it.cover.isBlank() }.map { it.id }.distinct().take(40)
        for(ids in missing.chunked(10)) {
            val data=try {
                ensureSearchActive()
                val body=JSONObject().put("series_id",ids.joinToString(",")).put("dr_scene","player")
                    .put("biz_param",JSONObject().put("detail_page_version",0).put("need_all_video_definition",false)
                        .put("need_mp4_align",false).put("source",7).put("use_os_player",false).put("use_server_dns",false))
                    .toString().toByteArray(Charsets.UTF_8)
                val signed=activeSigner.signCatalog("$API/novel/player/multi_video_detail/player/v1",emptyMap(),body)
                JSONObject(text(signed.url,signed,searchHttp)).optJSONObject("data")
            } catch(_: IOException) { break } ?: continue
            for(id in ids) {
                val video=data.optJSONObject(id)?.optJSONObject("video_data") ?: continue
                val card=planCardSeries(video)
                if(card.cover.isNotBlank()) found[id]=found.getValue(id).let { it.copy(cover=card.cover,badge=it.badge.ifBlank { card.badge }) }
            }
        }
        val result=items.map { found.getValue(it.id) }
        synchronized(searchPages) {
            for((key,saved) in searchPages.toMap()) {
                val updated=saved.page.items.map { item -> found[item.id]?.takeIf { it.cover.isNotBlank() }?.let { item.copy(cover=it.cover,badge=item.badge.ifBlank { it.badge }) } ?: item }
                if(updated!=saved.page.items) searchPages[key]=saved.copy(page=saved.page.copy(items=updated))
            }
        }
        result
    }
    /** 主详情与辅助信息分开缓存，首页点击只等待标题和完整分集。 */
    private data class DetailCache(val at: Long, val detail: Detail, val extrasLoaded: Boolean = false)
    private val detailCache=linkedMapOf<String,DetailCache>()
    /** 合集分集的 vid；取址语义与普通短剧不同，播放链路按此集合自动选择。 */
    private val albumEpisodes=linkedSetOf<String>()
    private fun isAlbumEpisode(id: String): Boolean = synchronized(albumEpisodes) { id in albumEpisodes }

    /** 仅请求一次主接口；推荐、系列剧由页面展示后再补齐。 */
    fun seriesDetail(id: String, refresh: Boolean = false): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        if (!refresh) cachedDetail(id)?.let { return it }
        val main=fetchSeriesDetail(id)
        val result=Detail(main.series,main.episodes,social=main.social)
        return synchronized(detailCache) {
            detailCache[id]?.takeIf { !refresh && clock()-it.at<DETAIL_CACHE_TTL }?.detail ?: result.also {
                detailCache[id]=DetailCache(clock(),it)
                while(detailCache.size>30) detailCache.remove(detailCache.keys.first())
            }
        }
    }

    /** 预告 ID 上线后可能失效或继续返回空目录，需关联同名同季的正式剧集。 */
    fun reservationDetail(series: Series): Detail {
        val original = runCatching { detail(series.id, refreshMain = true) }
        original.getOrNull()?.takeIf { it.episodes.isNotEmpty() }?.let { return it }
        RequestScope.current()?.ensureActive()
        if (Thread.currentThread().isInterrupted) throw IOException("请求已取消")
        val scope = RequestScope.current()?.child(15_000) ?: RequestScope(15_000)
        try {
            return scope.run {
                val attempted = mutableSetOf(series.id)
                var resolutionFailure: Throwable? = null
                fun resolve(candidates: List<Series>): Detail? {
                    val candidate = ReservationMatch.select(series, candidates.filter { it.id !in attempted }) ?: return null
                    attempted += candidate.id
                    val found = runCatching { seriesDetail(candidate.id, refresh = true) }
                        .onFailure { resolutionFailure = it }.getOrNull()
                    scope.ensureActive()
                    return found?.takeIf { it.episodes.isNotEmpty() && ReservationMatch.matches(series, it.series) }
                }
                val seasons = original.getOrNull()?.sameName.orEmpty()
                resolve(seasons)?.let { return@run it }
                val results = search(series.title, refresh = true).items
                resolve(seasons + results)?.let { return@run it }
                resolutionFailure?.let { throw IOException("预约剧集详情暂不可用，请稍后重试", it) }
                original.getOrThrow()
            }
        } finally {
            scope.cancel()
        }
    }

    /** 完整详情保留给补季、收藏更新等需要系列信息的调用方。 */
    fun detail(id: String, refreshSeasons: Boolean = false, onSeasons: ((List<Series>) -> Unit)? = null, refreshMain: Boolean = false): Detail {
        val main=seriesDetail(id, refresh=refreshMain)
        val needsExtras=needsDetailExtras(id)
        if(!needsExtras && !refreshSeasons) return cachedDetail(id) ?: main
        val fetchExtras=needsExtras && main.episodes.isNotEmpty()
        val scope=RequestScope.current()?.child() ?: RequestScope()
        // 辅助请求共用取消作用域与等待预算，页面离开后一起取消。
        val relatedTask=FutureTask(Callable { scope.run {
            if(fetchExtras) runCatching { planRelated(id,main.series) }.getOrDefault(main.related) else main.related
        } })
        val seasonsTask=FutureTask(Callable { scope.run {
            runCatching { planSeasons(id) }.getOrNull()?.also { rows ->
                scope.ensureActive()
                onSeasons?.invoke(rows)
            }
        } })
        val ratingTask=FutureTask(Callable { scope.run {
            if(fetchExtras) runCatching { websiteRating(id) }.getOrNull() else null
        } })
        val threads=listOf(relatedTask,seasonsTask,ratingTask).map { task -> Thread(task,"hongguotv-detail-extra").apply { isDaemon=true; start() } }
        try {
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(DETAIL_AUX_TIMEOUT)
            fun <T> getAux(task: Future<T>): T? = try { task.get((deadline-System.nanoTime()).coerceAtLeast(0),TimeUnit.NANOSECONDS) }
            catch(_: TimeoutException) { null } catch(_: ExecutionException) { null } catch(_: InterruptedException) { Thread.currentThread().interrupt(); null }
            val related=getAux(relatedTask) ?: main.related
            val latestSeasons=getAux(seasonsTask)
            val sameName=(latestSeasons?.filter { it.id!=id } ?: main.sameName).distinctBy { it.id }
                .sortedBy { seasonNumber(it.title).let { n -> if(n==Int.MAX_VALUE) 1 else n } }
            val rating=getAux(ratingTask)
            if(scope.isCancelled || Thread.currentThread().isInterrupted) throw IOException("请求已取消")
            val social=rating?.let { main.social.copy(rating=it.rating,ratingCount=it.ratingCount) } ?: main.social
            val result=main.copy(related=related,sameName=sameName,social=social,
                seasonsLoaded=latestSeasons!=null || main.seasonsLoaded)
            synchronized(detailCache) {
                val cached=detailCache[id]
                if(cached!=null && cached.detail==main) detailCache[id]=cached.copy(detail=result,extrasLoaded=true)
            }
            return result
        } finally {
            scope.cancel()
            threads.forEach { it.interrupt() }
        }
    }

    fun needsDetailExtras(id: String): Boolean = synchronized(detailCache) {
        detailCache[id]?.takeIf { clock()-it.at<DETAIL_CACHE_TTL }?.extrasLoaded!=true
    }

    /** 已打开的主详情立即复用，辅助请求未完成也不重新拉分集。 */
    fun cachedDetail(id: String): Detail? = synchronized(detailCache) {
        detailCache[id]?.takeIf { clock()-it.at<DETAIL_CACHE_TTL }?.detail
    }

    private data class SeriesPayload(val series: Series, val episodes: List<String>, val social: SeriesSocial)

    /** `POST /novel/player/multi_video_detail/v1/`：详情与整份分集列表。 */
    private fun fetchSeriesDetail(id: String): SeriesPayload {
        val body = JSONObject()
            .put("series_id", id)
            .put("biz_param", JSONObject()
                .put("caller_scene", "single_col")
                .put("detail_page_version", 0)
                .put("from_video_id", "")
                .put("need_all_video_definition", false)
                .put("need_mp4_align", false)
                .put("source", 4)
                .put("video_id_type", 1))
            .toString().toByteArray(Charsets.UTF_8)
        val data = signedJson("$API/novel/player/multi_video_detail/v1/", emptyMap(), body, detailHttp)
        // multi_video_detail 按 series_id 包装每部剧，真正的卡片与分集在其 video_data 内。
        val v = data.optJSONObject("data")?.optJSONObject(id)?.optJSONObject("video_data")
            ?: throw IOException("剧集详情暂不可用")
        // 合集卡片的普通详情没有分集，必须读专用目录；搜索、收藏与续播直达共用此入口。
        if (v.optInt("content_type") == ALBUM_TYPE) return fetchAlbumDetail(id)
        val series = planCardSeries(v)
        if (series.id != id || series.title.isBlank()) throw IOException("剧集详情数据异常")
        // 与第三方参考实现一致：详情有效但尚无分集时仍可展示、收藏；缺失或 null 列表同样视为空。
        // 非空列表必须逐项合法，避免把格式损坏或无效 ID 误当作暂无分集。
        val rows = if (v.isNull("video_list")) JSONArray()
            else v.optJSONArray("video_list") ?: throw IOException("分集数据异常")
        val episodes = (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: throw IOException("分集数据异常")
            val vid = row.str("vid")
            if (!vid.matches(Regex("[0-9]{1,30}"))) throw IOException("分集数据异常")
            row.optInt("vid_index", Int.MAX_VALUE) to vid
        }.sortedBy { it.first }.map { it.second }
        if (episodes.distinct().size != episodes.size) throw IOException("分集数据异常")
        var schedule = series.release.takeIf { episodes.isEmpty() }
        if (schedule != null && !schedule.isSpecified) {
            // 预告详情只给 content_type=1007 和空分集；排期需从同系列的预约条目补取。
            // 使用当前剧集 ID 查询，收藏、搜索直达也不依赖先打开新剧日历。
            val scope = RequestScope.current()?.child(2_000) ?: RequestScope(2_000)
            val reserved = try {
                scope.run {
                    planCells(id, "single_col_same_ip_series", id, detailHttp)
                        .firstOrNull { it.optInt("show_type") == 547 }
                        ?.optJSONArray("video_data")?.objects()?.map(::planCardSeries)
                        ?.firstOrNull { it.id == id }?.release
                }
            } catch (problem: IOException) {
                RequestScope.current()?.ensureActive()
                if (Thread.currentThread().isInterrupted) throw problem
                null
            } finally {
                scope.cancel()
            }
            if (reserved?.isSpecified == true) schedule = reserved
        }
        val current = when {
            episodes.isNotEmpty() && series.release != null -> series.copy(badge = "共 ${episodes.size} 集", release = null)
            schedule != null -> series.copy(badge = schedule.label, release = schedule)
            else -> series
        }
        return SeriesPayload(current, episodes, detailSocial(v))
    }

    /**
     * `GET /reading/bookapi/plan/v1/` 的相关推荐 cell（show_type 160）。
     * 匿名身份下服务端有时改发通用「更多好剧」cell（401），同样可用。
     */
    private fun planRelated(id: String, current: Series): List<Series> {
        val data = planCells(id, "select_panel_recommend")
        val cell = data.firstOrNull { it.optInt("show_type") == 160 }
            ?: data.firstOrNull { planSeries(it).isNotEmpty() }
            ?: return emptyList()
        val items = valid(planSeries(cell)).filter { it.id != id }
        val key = current.title.trim()
        if (key.isEmpty()) return items
        return items.sortedBy { item ->
            when {
                item.title.equals(key, ignoreCase = true) -> 0
                item.title.startsWith(key) || key.startsWith(item.title) -> 1
                else -> 2
            }
        }
    }

    /** `GET /reading/bookapi/plan/v1/` 的「系列剧」cell（show_type 547）：整季列表。 */
    private fun planSeasons(id: String): List<Series> {
        val cells = planCells(id, "single_col_same_ip_series", id)
        val cell = cells.firstOrNull { it.optInt("show_type") == 547 }
            ?: if(cells.isEmpty()) return emptyList() else throw IOException("分季列表暂不可用")
        if(!cell.has("video_data")) throw IOException("分季数据格式异常")
        val rows=if(cell.isNull("video_data")) JSONArray() else cell.optJSONArray("video_data")
            ?: throw IOException("分季数据格式异常")
        val items=rows.objects().map(::planCardSeries)
        if(items.size!=rows.length() || items.any { !SeasonSnapshot.validId(it.id) || it.title.isBlank() })
            throw IOException("分季条目格式异常")
        return valid(items)
    }

    /** 统一打 plan feed；按 `from` 场景取回 data 下的 cell 数组。 */
    private fun planCells(bookId: String, from: String, filterId: String? = null, transport: OkHttpClient = http): List<JSONObject> {
        val values = linkedMapOf(
            "book_id" to bookId,
            "from" to from,
            "scene" to "10",
            "session_uuid" to UUID.randomUUID().toString(),
            "user_is_login" to "0",
        )
        if (filterId != null) values["filter_ids"] = filterId
        val data = signedJson("$API/reading/bookapi/plan/v1/", values, null, transport)
        val rows=data.optJSONArray("data") ?: throw IOException("详情辅助信息格式异常")
        val items=rows.objects()
        if(items.size!=rows.length()) throw IOException("详情辅助条目格式异常")
        return items
    }

    /** 签名 JSON 请求，统一处理 code != 0 与空响应。 */
    private fun signedJson(url: String, values: Map<String, String>, body: ByteArray?, transport: OkHttpClient = http): JSONObject {
        val signed = activeSigner.sign(url, values, body)
        val raw = text(signed.url, signed, transport)
        val data = runCatching { JSONObject(raw) }.getOrElse { throw IOException("接口数据格式异常") }
        val code = data.optString("code", "0")
        if (code.isNotEmpty() && code != "0") throw IOException("接口返回错误（code=$code）")
        return data
    }

    /** plan feed 卡片字段与详情页同构，统一从 video_data/series 字段还原。 */
    private fun planSeries(cell: JSONObject): List<Series> {
        val rows = cell.optJSONArray("video_data") ?: cell.optJSONArray("cell_data") ?: return emptyList()
        return rows.objects().map(::planCardSeries)
    }

    /** 合集目录的子作品才是真实分集；单个子作品的 vid 需另发一次批量请求补齐。 */
    private fun fetchAlbumDetail(id: String): SeriesPayload {
        val body = JSONObject().put("album_id", id).put("need_video_detail_info", true)
            .put("biz_param", JSONObject().put("caller_scene", "album").put("detail_page_version", 0)
                .put("source", 22).put("video_id_type", 1).put("need_all_video_definition", false))
        val data = signedJson("$API/novel/player/album_detail/v1/", emptyMap(),
            body.toString().toByteArray(Charsets.UTF_8), detailHttp).optJSONObject("data")
            ?: throw IOException("合集详情暂不可用")
        val album = data.optJSONObject("album_data") ?: throw IOException("合集详情数据异常")
        val albumId = album.str("album_id_str").ifBlank { album.str("album_id") }
        val title = seriesTitle(album.str("title"))
        if (albumId != id || title.isBlank()) throw IOException("合集详情数据异常")
        val directory = if (album.isNull("video_detail_list")) JSONArray()
            else album.optJSONArray("video_detail_list") ?: throw IOException("合集分集数据异常")
        val seriesIds = (0 until directory.length()).map { index ->
            val row = directory.optJSONObject(index) ?: throw IOException("合集分集数据异常")
            row.str("series_id_str").ifBlank { row.str("series_id") }
                .takeIf { it.matches(Regex("[0-9]{1,19}")) && it != "0" }
                ?: throw IOException("合集分集 ID 异常")
        }
        if (seriesIds.distinct().size != seriesIds.size) throw IOException("合集分集数据异常")
        val details = data.optJSONObject("video_detail_data") ?: JSONObject()
        // 不传 series_ids 时接口只回第一个子作品的详情，其余目录项的 vid 为空。
        val missing = seriesIds.filter { details.optJSONObject(it)?.optJSONObject("video_data") == null }
        // 子作品过多时接口仍返回成功，却不附带详情；按每批 20 个补取，分集顺序仍以目录为准。
        for (batch in missing.chunked(20)) {
            body.put("series_ids", JSONArray(batch.map { it.toLong() }))
            val extra = signedJson("$API/novel/player/album_detail/v1/", emptyMap(),
                body.toString().toByteArray(Charsets.UTF_8), detailHttp)
                .optJSONObject("data")?.optJSONObject("video_detail_data")
                ?: throw IOException("合集分集暂不可用")
            for (seriesId in batch) details.put(seriesId, extra.optJSONObject(seriesId))
        }
        val episodes = seriesIds.flatMap { seriesId ->
            val video = details.optJSONObject(seriesId)?.optJSONObject("video_data")
                ?: throw IOException("合集分集暂不可用")
            val rows = video.optJSONArray("video_list") ?: throw IOException("合集分集数据异常")
            (0 until rows.length()).map { index ->
                val vid = rows.optJSONObject(index)?.str("vid").orEmpty()
                if (!vid.matches(Regex("[0-9]{1,30}")) || vid == "0") throw IOException("合集分集 ID 异常")
                index to vid
            }
        }.map { it.second }
        if (episodes.isEmpty()) throw IOException("合集暂无剧集")
        if (episodes.distinct().size != episodes.size) throw IOException("合集分集数据异常")
        synchronized(albumEpisodes) {
            albumEpisodes += episodes
            while (albumEpisodes.size > ALBUM_EPISODE_LIMIT) albumEpisodes.remove(albumEpisodes.first())
        }
        val badge = album.optJSONArray("sub_title_list")?.optString(0).orEmpty().ifBlank { "更新至 ${episodes.size} 集" }
        return SeriesPayload(Series(id, title, cleanUrl(album.str("cover")), album.str("intro"), badge, "合集"),
            episodes, detailSocial(album))
    }

    /** 详情/推荐/系列剧共用的卡片解析，同时保留预告作品的预约排期。 */
    private fun planCardSeries(o: JSONObject): Series {
        val id = o.str("series_id_str").ifEmpty { o.str("series_id") }.ifEmpty { o.str("vid") }
        val title = o.str("series_title").ifEmpty { o.str("title") }
        val cover = o.str("series_cover").ifEmpty { o.str("cover") }
        val intro = o.str("series_intro").ifEmpty { o.str("description") }.ifEmpty { o.str("video_desc") }
            .ifEmpty { o.optJSONObject("subscribe_item")?.str("item_desc").orEmpty() }
        val count = o.optInt("episode_cnt", 0)
        val badge = o.str("episode_right_text").ifEmpty { if (count > 0) "全 $count 集" else "" }
        // 题材标签取 secondary_infos 里 data_type=3 的可搜索标签；
        // 没有时退回 sub_title，但滤掉与 badge/热度行重复的「全N集」「红果热度值…」。
        val infoTags = o.optJSONArray("secondary_infos")?.objects()
            ?.filter { it.optInt("data_type") == 3 }
            ?.map { it.str("content") }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val fallbackTags = o.optJSONArray("series_sub_title_list")
            ?.let { list -> (0 until list.length()).map { list.optString(it).trim() } }
            ?.filter { it.isNotEmpty() && !it.startsWith("全") && !it.contains("热度") }
            .orEmpty()
        val tags = (infoTags.ifEmpty { fallbackTags }).distinct().take(3).joinToString(" · ")
        val release = ReleaseSchedule.fromSeries(o)
        return Series(id, seriesTitle(title), cleanUrl(cover), intro, release?.label ?: badge, tags, release)
    }

    /** 网页详情提供十分制评分；只接收与当前剧集匹配的有效分数。 */
    private fun websiteRating(id: String): SeriesSocial? {
        val page=extractRouter(text("$site/detail?series_id=$id",null,detailHttp))
            .optJSONObject("loaderData")?.optJSONObject("detail_page") ?: return null
        if(!page.optBoolean("isSuccess") || page.optJSONObject("seriesDetail")?.str("series_id")!=id) return null
        val social=page.optJSONObject("seriesSocialInfo") ?: return null
        val rating=social.optDouble("rating",0.0)
        if(!rating.isFinite() || rating<=0.0 || rating>10.0) return null
        val count=social.str("rating_count").toLongOrNull()?.takeIf { it>0 }?.toString().orEmpty()
        return SeriesSocial(rating=rating,ratingCount=count)
    }

    /** App 详情自带热度与收藏，评分由主内容展示后的网页辅助请求补齐。 */
    private fun detailSocial(v: JSONObject): SeriesSocial {
        val favorite = compactCount(v.str("followed_cnt"))
        val heat = v.optLong("hot_score", 0)
        return SeriesSocial(
            rating = 0.0,
            ratingCount = "",
            favoriteText = if (favorite.isBlank()) "" else "${favorite}收藏",
            heatText = if (heat > 0) "红果热度值${compactCount(heat.toString())}" else "",
        )
    }
    private fun compactCount(raw: String): String {
        val value=raw.trim().toLongOrNull() ?: return ""
        if(value<1) return ""
        fun trim(number: Double, unit: String)=String.format(Locale.CHINA,"%.1f",number).removeSuffix(".0")+unit
        return when {
            value>=100_000_000 -> trim(value/100_000_000.0,"亿")
            value>=10_000 -> trim(value/10_000.0,"万")
            else -> value.toString()
        }
    }
    // 普通短剧不额外搜季；有明确季号或已知其它季时，才在有限时间内补齐系列。
    private fun sameName(current: Series, related: List<Series>): List<Series> {
        val base=baseName(current.title); if(base.isBlank()) return emptyList()
        val cacheKey="detail|$base"
        val cached=synchronized(seasonFills) { seasonFills[cacheKey]?.takeIf { clock()-it.at<86_400_000 }?.items }.orEmpty()
        val siblings=(related+cached).filter { it.id!=current.id && baseName(it.title)==base }.distinctBy { it.id }
        val hasSeasons=seasonNumber(current.title)!=Int.MAX_VALUE || siblings.any { seasonNumber(it.title)!=Int.MAX_VALUE }
        val extra=if(hasSeasons) collectSeasons(base,listOf(current)+siblings,null,listOf(base,base.take(4)),cacheKey,20,timeoutMillis=5_000) else emptyList()
        return (siblings+extra).distinctBy { it.id }.filter { it.id!=current.id }
            .sortedBy { seasonNumber(it.title).let { n -> if(n==Int.MAX_VALUE) 1 else n } }.take(20)
    }
    private fun baseName(title: String)=title.replace(SeasonPattern,"").trim().trim('：',':','-','·',' ')
    /** 重试、缓存过期或播放会话重建时，移除可能含有失效地址的旧模型。 */
    fun invalidatePlayback(id: String) { modelCache.evict(id) }

    /** Fetch a direct MP4 URL for one episode.
     *  播放接口只答注册过的匿名设备：设备档案与签名头统一取自 [RemoteSigner]，本机不再注册身份。
     *  [refreshModel] 为 true 时先丢弃缓存的播放模型再重取（地址即将过期时用）；
     *  默认复用缓存，否则详情页/相邻集的预热会全部白做。 */
    fun stream(
        id: String,
        maxQuality: Int = 1080,
        refreshModel: Boolean = false,
        playback: Boolean = false,
        prefetch: Boolean = false,
        ahead: List<String> = emptyList(),
    ): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val policy = playbackAlgorithms
        if (policy == null) {
            return withStreamBudget(if (playback) 8_000 else if (prefetch) 2_000 else 0) {
                streamUnbounded(id, maxQuality, refreshModel, ahead)
            }
        }
        recoverPlaybackAlgorithm(id)
        // 本地最多三轮、两个远程各一轮，共用总预算，避免失效算法无限拖延。
        val foreground = playback && !prefetch
        val budget = if (foreground && policy.isAutomatic) 25_000L else if (playback) 8_000L else if (prefetch) 2_000L else 0L
        return withStreamBudget(budget) {
            val tried = mutableSetOf<PlaybackAlgorithm?>()
            var lastFailure: Exception? = null
            var attempts = 0
            while (true) {
                RequestScope.ensureActive()
                RequestScope.current()?.ensureActive()
                if (RequestScope.remainingMillis()?.let { it <= 0 } == true) {
                    throw lastFailure ?: java.io.InterruptedIOException("播放取址超时，请稍后重试")
                }
                val attempt = if (foreground) policy.nextAddress(tried) else policy.beginAddress()
                if (attempt == null) throw lastFailure ?: IOException("播放算法正在冷却，请稍后重试")
                attempts++
                // 每轮绑定独立算法的缓存和路由健康度；并发切换不会混用设备、签名与 UA。
                val bound = boundPlayback(attempt.signer)
                val cached = !refreshModel && bound.modelCache.get(id, clock()) != null
                try {
                    val stream = withStreamBudget(if (foreground && policy.isAutomatic) 5_000 else 0) {
                        bound.streamUnbounded(id, maxQuality, refreshModel, ahead)
                    }
                    if (!cached || !policy.isAutomatic) policy.addressSucceeded(attempt)
                    return@withStreamBudget stream
                } catch (problem: Exception) {
                    if (problem is InterruptedException) Thread.currentThread().interrupt()
                    RequestScope.ensureActive()
                    RequestScope.current()?.ensureActive()
                    // 外层页面超时不代表算法失效；单个算法的独立预算耗尽则允许降级。
                    if (policy.isAutomatic && RequestScope.remainingMillis()?.let { it <= 0 } == true) throw problem
                    if (!foreground) throw problem
                    bound.modelCache.evict(id)
                    policy.addressFailed(attempt)
                    // 本地未达到三次阈值时继续本地；已经切换或冷却后不重复尝试旧算法。
                    if (policy.algorithm != attempt.algorithm || attempt.algorithm != PlaybackAlgorithm.LOCAL) {
                        tried += attempt.algorithm
                    }
                    lastFailure = problem
                    if (!policy.isAutomatic || attempts >= 5) throw problem
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("播放取址失败，请稍后重试")
        }
    }

    private fun <T> withStreamBudget(timeoutMillis: Long, action: () -> T): T =
        if (timeoutMillis <= 0) action() else RequestScope.withDeadline(
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis), action,
        )

    private fun boundPlayback(signer: RemoteSigner) = ContentRepository(
        http, clock, signer, modelCache.forSigner(signer), null, playbackRecovery.forSigner(signer),
    ).also { bound ->
        synchronized(albumEpisodes) { bound.albumEpisodes.addAll(albumEpisodes) }
    }

    /** 播放期间也可由界面定时调用；缓存命中不参与恢复判断，只有真实兼容取址成功才恢复。 */
    fun recoverPlaybackAlgorithm(id: String) {
        val policy = playbackAlgorithms ?: return
        if (!id.matches(Regex("[0-9]{1,30}"))) return
        val album = isAlbumEpisode(id)
        policy.recover { attempt ->
            val probe = ContentRepository(http, clock, attempt.signer)
            if (album) synchronized(probe.albumEpisodes) { probe.albumEpisodes += id }
            try {
                RequestScope(5_000).run {
                    probe.stream(id, maxQuality = Int.MAX_VALUE, refreshModel = true, playback = true)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 检测当前算法的最高兼容清晰度，不套用用户的播放清晰度上限。
     *
     * 优先沿用正式取址流程；本地首个模型不足 1080P 时，另用最多 8 秒补查多集 GET，
     * 避免首轮取址耗尽预算后，把某个接口的低清模型误报为本地算法的能力上限。
     */
    fun probePlayback(id: String): StreamInfo {
        var best = stream(id, maxQuality = Int.MAX_VALUE, refreshModel = true, playback = true)
        if (!activeSigner.usesLocalPlaybackAlgorithm || isAlbumEpisode(id) || qualityNumber(best.quality) >= 1080) {
            return best
        }
        // 首轮继续使用正式播放的时限，补查另计预算；外层检测作用域仍统一管理取消和总时限。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        return RequestScope.withDeadline(deadline) {
            val alternatives = listOf<() -> JSONObject?>(
                { videoTabModelV5(id) },
                { videoTabModel(id) },
            )
            for (resolve in alternatives) {
                RequestScope.ensureActive()
                if (RequestScope.remainingMillis()?.let { it <= 0L } == true) break
                try {
                    val model = resolve() ?: continue
                    val candidate = streamFromModel(model, Int.MAX_VALUE)
                    if (qualityNumber(candidate.quality) > qualityNumber(best.quality)) best = candidate
                    if (qualityNumber(best.quality) >= 1080) break
                } catch (problem: Exception) {
                    // 补查失败保留已取得的兼容资源；页面取消和检测超时仍须立即终止。
                    if (problem is InterruptedException) Thread.currentThread().interrupt()
                    RequestScope.ensureActive()
                    RequestScope.current()?.ensureActive()
                }
            }
            best
        }
    }

    /**
     * 执行一次真实播放请求的签名并返回耗时。
     *
     * 本地算法使用与正式播放相同的 PR #25 签名；远程签名器会完成各自的服务往返。
     * 这里只负责签名本身，可用性由调用方另行检查，避免本地取址失败连带隐藏本地签名延迟。
     */
    fun measurePlaybackSignature(id: String): PlaybackSignatureProbe {
        require(id.matches(Regex("[0-9]{1,30}")))
        val signer = activeSigner
        val started = System.nanoTime()
        val profile = signer.profile()
        val host = "api5-normal-sinfonlineb.fqnovel.com"
        // 本地检测与正式播放使用同一路径、请求体和完整签名头。
        if (signer.usesLocalPlaybackAlgorithm) {
            signer.sign("$API$PLAYER_PATH", emptyMap(), OpenHongguoPlaybackSigning.body(id))
        } else {
            val url = "https://$host${MobilePlayback.VIDEO_MODEL_PATH}"
            val values = PlaybackProbe.signatureValues(id, profile)
            signer.sign(url, values, null)
        }
        return PlaybackSignatureProbe(signatureElapsedMillis(started))
    }

    private fun streamUnbounded(id: String, maxQuality: Int, refreshModel: Boolean, ahead: List<String> = emptyList()): StreamInfo {
        if(refreshModel) modelCache.evict(id)
        val albumEpisode=isAlbumEpisode(id)
        try {
            return streamFromModel(addressModel(id, albumEpisode = albumEpisode, ahead = ahead),maxQuality)
        } catch (unplayable: UnplayableVideoException) {
            // 平台可能间歇下发不兼容模型：先在绑定的算法内有界重取，再由外层决定降级。
            // 始终使用电视兼容档位，不切到 level=3，也不反复更换设备。
            var failure=unplayable
            for(round in 1 until PLAYBACK_RETRY_ROUNDS) {
                if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("播放请求已取消")
                RequestScope.ensureActive()
                modelCache.evict(id)
                if(round>1) Thread.sleep(RETRY_BACKOFF_MS*(round-1))
                try {
                    val (level,preferAlt)=RETRY_PLANS[round-1]
                    return streamFromModel(addressModel(id,level,preferAlt,albumEpisode,ahead),maxQuality)
                } catch(next: UnplayableVideoException) {
                    failure=next
                }
            }
            // 最后一轮的不兼容模型也要移除，避免下一次播放直接命中坏缓存。
            modelCache.evict(id)
            throw failure
        }
    }

    private fun streamFromModel(model: JSONObject, maxQuality: Int): StreamInfo {
        // V5 与旧播放模型共用地址、密钥解析和既有 CENC 播放链路。
        directStreamFromModel(model,maxQuality)?.let { return it }
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw UnplayableVideoException(
            "本集片源暂不兼容，请播放下一集",
            // 诊断：不整段截断（截断会漏掉后面的清晰度条目），逐条给紧凑摘要，
            // 并带上该模型来源路由，定位降级模型由哪条接口下发。
            "route="+model.optString(ROUTE_KEY).ifEmpty { "unknown" }+" lvl="+model.optString(LEVEL_KEY).ifEmpty { "?" }+" video_list="+compactVideoList(videoList))
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        // 时间戳兼容秒/毫秒；同时采用模型与 CDN 签名中最早的截止时间。
        val parsed=url.toHttpUrlOrNull()
        val expires=(listOf(model.optLong("expire_time"),model.optLong("url_expire"),info.optLong("expire_time"),info.optLong("url_expire"))+
            listOf("x-expires","expires","expire","expire_time").mapNotNull { parsed?.queryParameter(it)?.toLongOrNull() })
            .filter { it>0 }.map { if(it<1_000_000_000_000L) it*1000 else it }.minOrNull() ?: 0L
        val backups = listOf("backup_url", "backup_url_1", "backup_url_2").mapNotNull { field ->
            runCatching {
                val rawUrl = cleanUrl(item.str(field))
                val candidate = if (rawUrl.isNotEmpty() && !rawUrl.startsWith("http") && seed.isNotEmpty())
                    MediaCrypto.decryptUrl(rawUrl, decode64(seed)) else rawUrl
                candidate.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            }.getOrNull()
        }.distinct().filterNot { it == url }
        return StreamInfo(url,key,quality,expires,backupUrls=backups)
    }
    /** 新播放模型的 video_list 可为数组或对象，元数据和 CENC 密钥材料分开存放。 */
    private fun directStreamFromModel(model: JSONObject, maxQuality: Int): StreamInfo? {
        val list=model.opt("video_list") ?: return null
        val items=when(list) {
            is JSONArray -> list.objects()
            is JSONObject -> list.keys().asSequence().mapNotNull { list.optJSONObject(it) }.toList()
            else -> return null
        }
        val rows=items.mapNotNull { item ->
            val meta=item.optJSONObject("video_meta") ?: item
            val encryption=item.optJSONObject("encrypt_info")
            if(meta.str("codec_type").lowercase(Locale.ROOT) in setOf("bytevc2","bvc2")) return@mapNotNull null
            val spade=encryption?.str("spade_a").orEmpty().ifEmpty { item.str("spade_a") }
            val encrypted=encryption?.optBoolean("encrypt",spade.isNotEmpty()) ?: spade.isNotEmpty()
            val method=encryption?.str("encryption_method").orEmpty()
            if(encrypted && (spade.isEmpty() || (method.isNotEmpty() && method!="cenc-aes-ctr"))) return@mapNotNull null
            val urls=listOf("main_url", "backup_url", "backup_url_1", "backup_url_2")
                .mapNotNull { playbackUrl(item.str(it),model.str("key_seed")) }.distinct()
            if (urls.isEmpty()) return@mapNotNull null
            val height=qualityNumber(meta.str("definition").ifEmpty { meta.str("vheight").ifEmpty { meta.str("height") } })
            Triple(height,urls,if(encrypted) spade else "")
        }.sortedByDescending { it.first }
        if(rows.isEmpty() && model.has("fallback_api")) return null
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull()
            ?: throw UnplayableVideoException("本集片源暂不兼容，请播放下一集", "route="+model.optString(ROUTE_KEY)+" direct video_list has no supported streams")
        val key=selected.third.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        val url=selected.second.first()
        return StreamInfo(url,key,quality,playbackExpiry(model,url),backupUrls=selected.second.drop(1))
    }
    /** 播放地址可以是明文、Base64 文本，或由 key_seed 保护的 Spade 地址。 */
    private fun playbackUrl(value: String, seed: String): String? {
        val raw=cleanUrl(value)
        if(raw.startsWith("https://") || raw.startsWith("http://")) return raw
        if(raw.isEmpty()) return null
        return runCatching {
            val bytes=decode64(raw)
            val decoded=if(bytes.size>=5 && bytes[0].u()==0xa8 && seed.isNotEmpty())
                MediaCrypto.decryptUrl(raw,decode64(seed)) else String(bytes,Charsets.UTF_8).trim()
            cleanUrl(decoded).takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }.getOrNull()
    }
    private fun playbackExpiry(model: JSONObject, url: String): Long {
        val parsed=url.toHttpUrlOrNull()
        return (listOf(model.optLong("expire_time"),model.optLong("url_expire"))+
            listOf("x-expires","expires","expire","expire_time").mapNotNull { parsed?.queryParameter(it)?.toLongOrNull() })
            .filter { it>0 }.map { if(it<1_000_000_000_000L) it*1000 else it }.minOrNull() ?: 0L
    }
    /** 诊断用：每条清晰度给 `key:codec:高度:main_url形态/backup形态` 的紧凑摘要，条目间用分号，不再整段截断。 */
    private fun compactVideoList(videoList: JSONObject): String = videoList.keys().asSequence().mapNotNull { k ->
        val o=videoList.optJSONObject(k) ?: return@mapNotNull null
        val codec=o.str("codec_type").ifEmpty { "?" }
        val height=o.str("vheight").ifEmpty { o.str("height").ifEmpty { "?" } }
        "$k:$codec:$height:"+urlShape(o.str("main_url"))+"/"+urlShape(o.str("backup_url_1"))
    }.joinToString(";")
    /** 地址归类：http=直接可播 URL；enc=base64 加密 blob（非 http 开头）；empty=缺字段。 */
    private fun urlShape(value: String) = when {
        value.isEmpty() -> "empty"
        value.startsWith("http") -> "http"
        else -> "enc"
    }
    /** 预热一整窗剧集的播放模型：命中缓存的不再请求。
     *
     *  与正式播放共用 V5 单集取址和模型缓存，最多预热 [WARM_LIMIT] 集。
     *  失败不上抛，播放时按需取址仍是兜底。 */
    fun warm(episodes: List<String>): WarmReport {
        if (playbackAlgorithms != null) return boundPlayback(playbackAlgorithms.beginAddress().signer).warm(episodes)
        val ids=episodes.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct()
        if(ids.isEmpty()) return WarmReport(0,0,0,null)
        val now=clock()
        val cached=ids.count { modelCache.get(it,now)!=null }
        val missing=ids.filter { modelCache.get(it,now)==null }.take(WARM_LIMIT)
        var filled=0
        var route: String?=null
        for(id in missing) {
            if(Thread.currentThread().isInterrupted) break
            // 取消信号必须原样上抛：被 runCatching 吞掉后，预取线程会继续把剩余集数全拉一遍。
            val model=try { videoModel(id, albumEpisode = isAlbumEpisode(id)) } catch(problem: java.io.InterruptedIOException) { throw problem } catch(_: Exception) { null } ?: continue
            modelCache.put(id,model.put(LEVEL_KEY,1),clock())
            filled++; route=model.optString(ROUTE_KEY).ifEmpty { route }
        }
        return WarmReport(ids.size,cached+filled,filled,route)
    }
    /** 播放请求：签名头由本机播放算法生成，UA 与本机设备档案一致。 */
    private fun playbackTransport(): OkHttpClient {
        val remaining = RequestScope.remainingMillis() ?: return http
        val budget = remaining.coerceAtLeast(1L)
        return http.newBuilder()
            .connectTimeout(minOf(budget, 4_000L), TimeUnit.MILLISECONDS)
            .readTimeout(minOf(budget, 4_000L), TimeUnit.MILLISECONDS)
            .callTimeout(budget, TimeUnit.MILLISECONDS)
            .build()
    }
    private fun signedBuilder(signed: SignedRequest): Request.Builder =
        Request.Builder().url(signed.url).apply {
            header("User-Agent", activeSigner.profile().userAgent)
            signed.headers.forEach { (key,value) -> header(key,value) }
        }
    private fun playerRequest(path: String,payload: ByteArray): Request {
        val signed=activeSigner.sign("$API$path", linkedMapOf(), payload)
        // 保留签名器生成的完整头部，包括 PR #25 要求的 x-medusa。
        return signedBuilder(signed)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
    /** 单集取址的完整结果：`model` 为空时由 [blank] 区分「空体」和「有明确错误码」，
     *  后者不该再触发换设备重扫。 */
    private data class SingleTab(val model: JSONObject?, val blank: Boolean, val code: Int = 0)
    /** 单集兼容取址：`GET /reading/bookapi/video_tab/video_model/v`。
     *  远程签名优先尝试多集 GET；该接口保留给多集不可用、但单集仍可用的环境。
     *  电视端固定 device_level=1，避免服务端整集下发当前播放链路不支持的 bytevc2。 */
    private fun videoTabSingle(id: String, preferAlt: Boolean = false, preferredEndpoint: String? = null): SingleTab {
        val extra=linkedMapOf(
            "video_id" to id, "source" to "0", "need_all_video_definition" to "true",
            // 电视端的兼容档：服务端在 3 下可能整集改成 VVC，Media3 的 MP4 提取器读不出视频轨。
            "device_level" to "1",
            // 与多集接口保持同一请求口径：缺 video_platform 会回 PARAM_INVALID(100103)，
            // 其余几项在部分环境下也会导致空体，统一补齐避免换环境后再次失败。
            // use_server_dns / detail_page_version / disable_digg_stat 对齐 App 的
            // GetVideoBizParam 口径（官方默认 false/0/false）。
            "video_id_type" to "0", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024",
            "from_video_id" to "", "use_server_dns" to "0",
            "detail_page_version" to "0", "disable_digg_stat" to "0",
        )
        val hosts=listOf(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/video_model/v",
            "https://reading.snssdk.com/reading/bookapi/video_tab/video_model/v",
        )
        val ordered=(if(preferAlt) hosts.reversed() else hosts).sortedByDescending { it == preferredEndpoint }
        var allBlank=true
        var failure=0
        for(base in ordered) {
            val signed=activeSigner.sign(base,extra,null)
            val request=signedBuilder(signed).get().build()
            val body=try {
                RequestScope.execute(playbackTransport().newCall(request)) { response ->
                    if(!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
                    response.body?.string().orEmpty()
                }
            } catch(problem: java.io.InterruptedIOException) {
                throw problem
            } catch(_: IOException) {
                continue
            } catch(_: RuntimeException) {
                continue
            }
            if(body.isBlank()) continue
            allBlank=false
            val root=runCatching { JSONObject(body) }.getOrNull() ?: continue
            failure=root.optInt("code",root.optInt("Code",-1))
            if(failure!=0) continue
            val data=root.optJSONObject("data") ?: continue
            val model=modelOf(data.opt("video_model")) ?: continue
            return SingleTab(model.put(ROUTE_KEY,"video_tab|"+base.removePrefix("https://").substringBefore('/')).put(ENDPOINT_KEY,base),false)
        }
        return SingleTab(null,allBlank,failure)
    }
    /** 多集取址：`GET /reading/bookapi/video_tab/multi_video_model/v1/`。
     *  参数取自官方 `GetVideoModelRequest` 的 snake_case 字段，缺任一字段会回 `100103`。
     *  远程签名优先使用该接口，失败后再尝试单集 GET 和 POST 兼容路由。
     *  失败一律返回 null，由调用方继续回退。 */
    private fun videoTabModel(id: String, level: Int = 1, preferAlt: Boolean = false, preferredEndpoint: String? = null): JSONObject? {
        val extra=linkedMapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to level.toString(),
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "",
            // 对齐 App 的 GetVideoBizParam 口径（官方默认 false/0/false）。
            "use_server_dns" to "0", "detail_page_version" to "0",
            "disable_digg_stat" to "0",
        )
        val hosts=listOf(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",
            "https://reading.snssdk.com/reading/bookapi/video_tab/multi_video_model/v1/",
        )
        // preferAlt：重试诊断用，优先探测备用主机（两个主机可能落在不同后端集群）。
        val ordered=(if(preferAlt) hosts.reversed() else hosts).sortedByDescending { it == preferredEndpoint }
        for(base in ordered) {
            val signed=activeSigner.sign(base,extra,null)
            val request=signedBuilder(signed).get().build()
            val body=try {
                RequestScope.execute(playbackTransport().newCall(request)) { response ->
                    if(!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
                    response.body?.string().orEmpty()
                }
            } catch (problem: java.io.InterruptedIOException) {
                throw problem
            } catch (problem: IOException) {
                continue
            } catch (problem: RuntimeException) {
                continue
            }
            val root=runCatching { JSONObject(body) }.getOrNull() ?: continue
            if(root.optInt("code",root.optInt("Code",-1))!=0) continue
            val data=root.optJSONObject("data") ?: continue
            val rows=data.optJSONObject("video_model_data") ?: data
            // 必须匹配目标分集，缺失时继续回退，避免播放其它集并把错误模型写入当前集缓存。
            val row=rows.optJSONObject(id) ?: continue
            val model=modelOf(row.opt("video_model")) ?: continue
            return model.put(ROUTE_KEY,"video_tab|"+base.removePrefix("https://").substringBefore('/')).put(ENDPOINT_KEY,base)
        }
        return null
    }
    /**
     * 本地算法的 74532 兼容形态，与 PR #25 共用 `POST /novel/player/video_model/v1/`。
     *
     * 由 [MobilePlayback.videoModel] 完成 74532 画像、gzip 体、field21=312 与签名；
     * 响应兼容 `data.video_model` 及 `data[id].video_model`。
     * 空体/业务错误返回 null，交给 GET 和旧 POST 兼容路由。
     */
    private fun videoModelV5(id: String, preferredHost: String? = null): JSONObject? {
        val profile = activeSigner.profile()
        val hosts = if (preferredHost != null) listOf(preferredHost) + PlayRetryPlan.hosts.filter { it != preferredHost }
        else PlayRetryPlan.hosts
        for (host in hosts) {
            val route = PlaybackRoutes.Route(host)
            try {
                val model = MobilePlayback.videoModel(id, profile, http, clock(), host)
                playbackRecovery.routes.success(route, clock())
                return model.put(ROUTE_KEY, "video-model|${PlayRetryPlan.tag(host)}").put(ENDPOINT_KEY, host)
            } catch (problem: java.io.InterruptedIOException) {
                throw problem
            } catch (problem: EmptyPlaybackResponseException) {
                // 新端点是独立形态，空体只表示该形态不可用；不能把共享域名打进冷却，
                // 否则紧随其后的 GET / 旧 POST 兼容兜底会被直接跳过。
            } catch (problem: ServiceException) {
                playbackRecovery.routes.failed(route, clock(), empty = false)
            } catch (problem: IOException) {
                playbackRecovery.routes.failed(route, clock(), empty = false)
            } catch (problem: RuntimeException) {
                playbackRecovery.routes.failed(route, clock(), empty = false)
            }
        }
        return null
    }

    /** 本地 V5 多集 GET 首选入口，沿用参考项目 0.16.13 的签名口径。
     *  与 [videoTabModel] 的老签名口径不同，这里用本机 V5 三头签 MD5(空字节)；
     *  失败一律返回 null，由调用方继续后续兼容路由。 */
    private fun videoTabModelV5(id: String): JSONObject? {
        // 参考项目只打族内首选域一次：该路由按签名口径放行，与域名轮换无关。
        val host = "api5-normal-sinfonlineb.fqnovel.com"
        val request = try {
            MobilePlayback.videoTabRequest(id, activeSigner.profile(), clock(), host)
        } catch (cancel: java.io.InterruptedIOException) {
            throw cancel
        } catch (_: Exception) {
            return null
        }
        val body = try {
            RequestScope.execute(playbackTransport().newCall(request)) { response ->
                if (!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
                response.body?.string().orEmpty()
            }
        } catch (problem: java.io.InterruptedIOException) {
            throw problem
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        if (body.isBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optInt("code", root.optInt("Code", -1)) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val rows = data.optJSONObject("video_model_data") ?: data
        // 只能使用目标分集，不能把响应里的其它分集误当成兼容源。
        val row = rows.optJSONObject(id) ?: return null
        val model = modelOf(row.opt("video_model")) ?: return null
        return model.put(ROUTE_KEY, "v5get|$host").put(ENDPOINT_KEY,host)
    }
    /** 当前算法的播放模型入口；失败计数由完整取流入口统一处理，内部重试不重复累计。
     *  [ahead] 是顺带预取的相邻集：V5 批量请求一次带回，切集时零请求命中模型缓存。 */
    private fun addressModel(id: String, level: Int = 1, preferAlt: Boolean = false, albumEpisode: Boolean = false, ahead: List<String> = emptyList()): JSONObject {
        modelCache.get(id,clock())?.let { return it }
        try {
            val model = fetchVideoModel(id,level,preferAlt,albumEpisode,ahead)
            // 真实取址成功：连续空轮清零，匿名身份不需要恢复。
            playbackRecovery.markNonEmptyRound()
            modelCache.put(id,model.put(LEVEL_KEY,level),clock())
            return model
        } catch (problem: EmptyPlaybackResponseException) {
            // 整次取址以空体收场才累计连续空轮；网络异常由域名健康度另行记录。
            RequestScope.ensureActive()
            playbackRecovery.markEmptyRound()
            throw problem
        }
    }
    /** 本地算法优先走 PR #25 的完整模型接口；所有取址路由共用模型缓存。 */
    private fun videoModel(id: String, level: Int = 1, preferAlt: Boolean = false, albumEpisode: Boolean = false): JSONObject {
        modelCache.get(id,clock())?.let { return it }
        val model=fetchVideoModel(id,level,preferAlt,albumEpisode).put(LEVEL_KEY,level)
        modelCache.put(id,model,clock())
        return model
    }
    private fun fetchVideoModel(id: String, level: Int = 1, preferAlt: Boolean = false, albumEpisode: Boolean = false, ahead: List<String> = emptyList()): JSONObject {
        try {
            return resolveVideoModel(id, level, preferAlt, albumEpisode, ahead)
        } catch (problem: IOException) {
            // 自检、离线下载与首页预览可能没先打开详情，合集分集此时还没被标记。
            // 普通语义对合集回 101000（明确错误码，不是空体），据此确定性地按合集语义重取一次。
            if (albumEpisode || problem !is ServiceException || problem.code != ALBUM_SERVICE_CODE) throw problem
            return resolveVideoModel(id, level, preferAlt, true, ahead)
        }
    }
    private fun resolveVideoModel(id: String, level: Int, preferAlt: Boolean, albumEpisode: Boolean, ahead: List<String> = emptyList()): JSONObject {
        // 合集分集使用 PUGC 分组和 video_id_type=1，不尝试固定回 ILLEGAL_ACCESS 的 video_tab。
        val idType = if (albumEpisode) 1 else 0
        val group = if (albumEpisode) MobilePlayback.GROUP_PUGC else MobilePlayback.GROUP_SHORT
        val routeSigner = activeSigner
        val prefersVideoTab = routeSigner.prefersVideoTab && !albumEpisode
        val usesLocal = routeSigner.usesLocalPlaybackAlgorithm
        val memory = playbackRecovery.successfulRoutes
        // 显式探测备用主机的兼容性重试仍按备用顺序执行，不被成功记忆覆盖。
        val preferred = if (preferAlt) null else memory.last(routeSigner, group, clock())
        val defaults = buildList {
            if (prefersVideoTab) {
                add(PlaybackRouteKind.VIDEO_TAB_MULTI)
                add(PlaybackRouteKind.VIDEO_TAB_SINGLE)
            }
            if (usesLocal) {
                if (!albumEpisode) {
                    // 先走官方 74532 新端点，再用 GET 作兼容兜底。
                    add(PlaybackRouteKind.VIDEO_MODEL_V5)
                    add(PlaybackRouteKind.VIDEO_TAB_V5)
                }
                // 旧 multi_video_model POST 仅保留兜底，不覆盖新端点成功记忆。
                add(PlaybackRouteKind.MOBILE)
            }
            add(PlaybackRouteKind.PLAYER)
            if (!albumEpisode && !prefersVideoTab) {
                add(PlaybackRouteKind.VIDEO_TAB_SINGLE)
                if (!usesLocal) add(PlaybackRouteKind.VIDEO_TAB_MULTI)
            }
            add(PlaybackRouteKind.LEGACY)
        }
        // 成功接口整个提前，POST 曾成功时无需先消耗 GET 配额；每个接口族仍只扫描一次。
        val remembered = if (preferred != null && preferred.kind in defaults) {
            listOf(preferred.kind) + defaults.filter { it != preferred.kind }
        } else defaults
        // 本地普通短剧先用 PR #25 的完整模型接口，避免新接口的 720P 结果提前结束取址。
        // 旧的低清成功记忆也不能把它挤到后面；合集仍使用原有的 PUGC 请求。
        val planned = if (usesLocal && !albumEpisode) {
            listOf(PlaybackRouteKind.PLAYER) + remembered.filter { it != PlaybackRouteKind.PLAYER }
        } else remembered
        // 本地普通短剧精确对齐 SDK PlaybackBody；其余请求保留原有电视兼容参数。
        val primaryPayload = if (usesLocal && !albumEpisode) {
            OpenHongguoPlaybackSigning.body(id)
        } else {
            """{"video_id":"$id","biz_param":{"detail_page_version":0,"device_level":$level,"disable_digg_stat":false,"disable_video_relate_book":false,"from_video_id":"","need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_id_type":$idType,"video_platform":3,"source":0}}""".toByteArray(Charsets.UTF_8)
        }
        val biz = """{"detail_page_version":0,"device_level":$level,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}"""
        val map = """{"biz_param":$biz,"mixed_video_id_map":{"$group":["$id"]}}""".toByteArray(Charsets.UTF_8)
        val legacyPaths = listOf(
            "/novel/player/multi_video_model/v1/",
            "/novel/player/multi_video_model/preload/v1/",
            "/novel/player/multi_video_model/default/v1/",
            "/novel/player/multi_video_model/detail/v1/",
        )
        val preferredLegacyPath = preferred?.takeIf { it.kind == PlaybackRouteKind.LEGACY }?.endpoint
        val ordered = legacyPaths.sortedByDescending { it == preferredLegacyPath }.map { it to map }
        var singleTab = SingleTab(null, true)
        var videoTabFailure: IOException? = null
        var mobile = Sweep(null, null, false, null)
        var primary = Sweep(null, null, false, null)
        var first = Sweep(null, null, false, null)
        var routeTimeout: IOException? = null

        fun sweep(routes: List<Pair<String, ByteArray>>): Sweep = try {
            sweepRoutes(routes, retryFirstBlank = !usesLocal)
        } catch (problem: java.io.InterruptedIOException) {
            throw problem
        } catch (problem: IOException) {
            Sweep(null, null, false, problem)
        } catch (problem: org.json.JSONException) {
            Sweep(null, null, false, IOException("播放信息格式异常", problem))
        }

        fun remember(kind: PlaybackRouteKind, model: JSONObject): JSONObject {
            // 手动切换算法后，旧在途请求不能替新算法写入优先路由。
            if (activeSigner === routeSigner) {
                memory.remember(routeSigner, group, kind, model.getString(ENDPOINT_KEY), clock())
            }
            return model
        }

        for (kind in planned) {
            RequestScope.ensureActive()
            val endpoint = preferred?.takeIf { it.kind == kind }?.endpoint
            val model = try {
                when (kind) {
                    PlaybackRouteKind.VIDEO_TAB_MULTI -> videoTabModel(id, level, preferAlt, endpoint)
                    PlaybackRouteKind.VIDEO_TAB_SINGLE -> {
                        singleTab = videoTabSingle(id, preferAlt, endpoint)
                        // 保留远程 GET 的明确错误码，最终失败时继续支持原有的合集识别。
                        if (prefersVideoTab && !singleTab.blank) videoTabFailure = ServiceException(singleTab.code)
                        singleTab.model
                    }
                    PlaybackRouteKind.VIDEO_MODEL_V5 -> videoModelV5(id, endpoint)
                    PlaybackRouteKind.MOBILE -> {
                        // 旧 POST 保留多域名容错；但新端点已失败时不再顺带预取相邻集，
                        // 避免用批量响应拖大播放预算。
                        mobile = mobilePlaybackModel(
                            id,
                            group,
                            if (usesLocal) emptyList() else ahead,
                            endpoint,
                        )
                        mobile.model
                    }
                    PlaybackRouteKind.VIDEO_TAB_V5 -> videoTabModelV5(id)
                    PlaybackRouteKind.PLAYER -> {
                        primary = sweep(listOf(PLAYER_PATH to primaryPayload))
                        primary.model
                    }
                    PlaybackRouteKind.LEGACY -> {
                        first = sweep(ordered)
                        first.model
                    }
                }
            } catch (problem: java.net.SocketTimeoutException) {
                // 单个接口超时仍允许回退；页面取消或整次取址预算耗尽时立即停止。
                RequestScope.ensureActive()
                RequestScope.current()?.ensureActive()
                if (RequestScope.remainingMillis()?.let { it <= 0L } == true) throw problem
                routeTimeout = problem
                null
            }
            if (model != null) return remember(kind, model)
            if (preferred != null && kind == preferred.kind) memory.failed(routeSigner, group, preferred)
        }
        // 只有所有相关路由均为空体时才重扫旧 POST；接口记忆不改变空轮与身份恢复的判断。
        val allBlank = routeTimeout == null && mobile.allBlank && primary.allBlank && first.allBlank && singleTab.blank
        if (!allBlank) throw mobile.failure ?: first.failure ?: primary.failure
            ?: videoTabFailure ?: routeTimeout ?: IOException("播放信息为空")
        val healed = sweepRoutes(listOf(PLAYER_PATH to primaryPayload) + ordered, retryFirstBlank = !usesLocal)
        healed.model?.let { model ->
            val kind = if (healed.path == PLAYER_PATH) PlaybackRouteKind.PLAYER else PlaybackRouteKind.LEGACY
            return remember(kind, model)
        }
        if (healed.allBlank) throw EmptyPlaybackResponseException()
        throw healed.failure ?: mobile.failure ?: videoTabFailure
            ?: IOException("播放信息为空")
    }
    /** V5 单集请求；先按域名健康度轮换，只有全部域名都空时才轮换匿名身份并重扫一次。
     *  [ahead] 里的相邻集随同一次 POST 顺带取回并写入模型缓存，切集时不再取址。 */
    private fun mobilePlaybackModel(
        id: String,
        group: String = MobilePlayback.GROUP_SHORT,
        ahead: List<String> = emptyList(),
        preferredHost: String? = null,
    ): Sweep {
        val wanted = (listOf(id) + ahead)
            .filter { it.matches(Regex("[0-9]{1,30}")) }
            .distinct()
            .take(3)
        // 与参考项目一致：开始取址前，若上一轮已耗尽全部 V5 域名且连续空响应达到阈值，
        // 先替换匿名身份，再用新身份重新规划域名；不把恢复动作全部堆到失败返回之后。
        if (playbackRecovery.emptyRoundsAtLeast(IDENTITY_ROTATE_ROUNDS) &&
            playbackRecovery.lastEmptyRound() && playbackRecovery.routes.exhausted(clock()) &&
            rotatePlaybackIdentity()
        ) {
            playbackRecovery.resetEmptyRounds()
            playbackRecovery.resetRotationCooldown()
        }
        // 空轮计数由 addressModel 按整次取址的最终结果统一维护：V5 域名全冷却时
        // 本轮没有真正尝试 V5，这里既不累加也不清零，避免冷却窗口内的取址把
        // 「连续空轮」计数意外清掉，导致身份轮换阈值永远达不到。
        return sweepMobilePlayback(wanted, group, preferredHost)
    }

    /** 按域名健康度扫描 V5 模型；失败只更新路由状态，不直接更换匿名身份。 */
    private fun sweepMobilePlayback(
        wanted: List<String>,
        group: String,
        preferredHost: String? = null,
    ): Sweep {
        var allBlank = true
        var sawResponse = false
        var lastFailure: IOException? = null
        var tried = 0
        val now = clock()
        // 所有域名都在冷却时不要假装“刚刚又空了一轮”，直接交给调用方走兼容路由；
        // 兼容路由也失败后，上层再按冷却窗口恢复。
        if (playbackRecovery.routes.exhausted(now) &&
            !playbackRecovery.emptyRoundsAtLeast(IDENTITY_ROTATE_ROUNDS)
        ) {
            return Sweep(null, null, true, EmptyPlaybackResponseException(), false, false)
        }
        // 域名健康度先过滤冷却主机，成功记忆仅调整剩余候选的顺序。
        val pending = java.util.ArrayDeque(playbackRecovery.routes.plan(clock(), speculative = false)
            .sortedByDescending { it.host == preferredHost })
        while (pending.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("播放请求已取消")
            val route = pending.removeFirst()
            tried++
            try {
                val results = MobilePlayback.models(
                    wanted, activeSigner.profile(), http, clock(), group, route.host,
                )
                playbackRecovery.routes.success(route, clock())
                playbackRecovery.resetEmptyRounds()
                val routeTag = "v5|${PlayRetryPlan.tag(route.host)}"
                // 顺带集先进缓存；当前集由上层 addressModel 统一入缓存（带 LEVEL_KEY）。
                for ((episodeId, model) in results) {
                    if (episodeId != wanted.first()) {
                        modelCache.put(episodeId, model.put(LEVEL_KEY, 1).put(ROUTE_KEY, routeTag).put(ENDPOINT_KEY,route.host), clock())
                    }
                }
                return Sweep(
                    results.getValue(wanted.first()).put(ROUTE_KEY, routeTag).put(ENDPOINT_KEY,route.host),
                    MobilePlayback.MODEL_PATH,
                    allBlank = false,
                    failure = null,
                    sawResponse = true,
                    attempted = true,
                )
            } catch (problem: java.net.SocketTimeoutException) {
                // 单个域名超时先冷却并换下一域名，整轮预算耗尽和主动取消立即停止。
                RequestScope.ensureActive()
                RequestScope.current()?.ensureActive()
                if (RequestScope.remainingMillis()?.let { it <= 0L } == true) throw problem
                allBlank = false
                lastFailure = problem
                playbackRecovery.routes.failed(route, clock(), empty = false)
            } catch (problem: java.io.InterruptedIOException) {
                throw problem
            } catch (problem: EmptyPlaybackResponseException) {
                playbackRecovery.routes.failed(route, clock(), empty = true)
                lastFailure = problem
            } catch (problem: IOException) {
                // 有响应但模型无效属于明确失败；只有真正的空体才累计“全部域名空”。
                sawResponse = true
                allBlank = false
                lastFailure = problem
                playbackRecovery.routes.failed(route, clock(), empty = false)
            }
        }
        return Sweep(null, null, allBlank, lastFailure, sawResponse, tried > 0)
    }

    /** 匿名身份轮换只在前台取址且路由全部为空时触发，并限制失败/成功后的冷却。 */
    private fun rotatePlaybackIdentity(): Boolean {
        if (!playbackRecovery.allowsRotation(clock())) return false
        val succeeded = runCatching { activeSigner.renew() }.isSuccess
        playbackRecovery.recordRotation(succeeded)
        return succeeded
    }
    /** 一轮路由扫描：本地算法每条路由只发一次；远程算法保留首条空体时的原地重发。
     *  返回后 [Sweep.allBlank] 才是 true 才代表「所有路由都空体」；有非空错误码时会立即返回，不再空转。 */
    private fun sweepRoutes(routes: List<Pair<String,ByteArray>>, retryFirstBlank: Boolean = true): Sweep {
        var allBlank=true
        for((index,pair) in routes.withIndex()) {
            val (path,payload)=pair
            // 本地扫描空体后直接换候选，避免每个接口族的首条都被额外请求一次。
            val attempts=if(index==0 && retryFirstBlank) BLANK_RETRIES else 1
            for(attempt in 0 until attempts) {
                if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("播放请求已取消")
                val body=RequestScope.execute(playbackTransport().newCall(playerRequest(path,payload))) { response ->
                    if(!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
                    response.body?.string().orEmpty()
                }
                if(body.isBlank()) {
                    if(attempt+1<attempts) Thread.sleep(BLANK_BACKOFF_MS)
                    continue
                }
                allBlank=false
                val data=JSONObject(body)
                val code=data.optInt("Code",data.optInt("code",0))
                if(code==0) {
                    videoModelOf(data)?.let { model -> return Sweep(model.put(ROUTE_KEY,path).put(ENDPOINT_KEY,path),path,false,null) }
                    // 结构异常属于「有明确失败」，不能和空体（配额用尽）混为一谈。
                    return Sweep(null,null,false,IOException("播放信息为空"))
                }
                return Sweep(null,null,false,ServiceException(code))
            }
        }
        return Sweep(null,null,allBlank,null)
    }
    private data class Sweep(
        val model: JSONObject?,
        val path: String?,
        val allBlank: Boolean,
        val failure: IOException?,
        val sawResponse: Boolean = false,
        val attempted: Boolean = false,
    )
    /** 新路由的播放模型在 `data.video_model`，旧路由在 `data[<vid>].video_model`；两种都可能被序列化成字符串。 */
    private fun videoModelOf(data: JSONObject): JSONObject? {
        val root=data.optJSONObject("data") ?: return null
        modelOf(root.opt("video_model"))?.let {
            if(!it.has("expire_time") && root.has("expire_time")) it.put("expire_time",root.optLong("expire_time"))
            return it
        }
        val entry=root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull() ?: return null
        return modelOf(entry.opt("video_model"))
    }
    private fun modelOf(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    companion object {
        private const val PLAYER_PATH="/novel/player/video_model/v1/"
        // 推荐、系列剧与评分在主详情显示后并行补齐，共享两秒预算。
        private const val DETAIL_AUX_TIMEOUT=2_000L
        private const val DETAIL_CACHE_TTL=600_000L
        // 网站搜索混合两类内容：video_data.genre 205 是漫剧，203 是短剧
        // （2026-10-13 用站点自带的漫剧热播榜与短剧分类列表逐条核对确认）。
        // 首条路由的空响应重发次数与退避：网关抖动时先原地重试，再换下一条路由。
        private const val BLANK_RETRIES=2
        /** 连续整轮空响应达到此值才替换匿名身份；避免一次网关抖动就丢身份。 */
        private const val IDENTITY_ROTATE_ROUNDS=2
        /** 空体原地重试不再等待：参考项目同场景零等待快扫，400ms 退避会在失败链上累计出约 2 秒纯等待。 */
        private const val BLANK_BACKOFF_MS=0L
        // 预热上限：单集接口一次一集，取 8 集足够覆盖相邻窗口，又不会把请求量放大。
        private const val WARM_LIMIT=8
        // 诊断专用：随模型进缓存的路由标记，streamFromModel 抛不兼容异常时写进 diagnostic。
        private const val ROUTE_KEY="__route"
        // 成功接口的准确地址或主机，仅用于本次运行内的路由优先级，不含签名和设备凭据。
        private const val ENDPOINT_KEY="__route_endpoint"
        // 合集卡片的 content_type；其分集是 PUGC 子作品，取址分组与 type 都与普通短剧不同。
        private const val ALBUM_TYPE=1003
        // 合集分集标记只用于取址语义；上限避免长会话泄漏。
        private const val ALBUM_EPISODE_LIMIT=4_000
        // 合集分集用普通 ID 语义取址时，服务端固定回 101000 服务异常。
        private const val ALBUM_SERVICE_CODE=101000
        // 降级模型按请求间歇出现（实测约 1/3），一次取址失败后最多再赌两轮正常模型；仍降级才报不兼容。
        private const val PLAYBACK_RETRY_ROUNDS=3
        private const val RETRY_BACKOFF_MS=400L
        // 电视兼容重试：固定 device_level=1，只轮换主机，所有轮次都不升到 3。
        // 平台在 level=3 下会把整集下发成 bytevc2，电视无法解码，不能作为首轮参数。
        private val RETRY_PLANS=listOf(1 to true, 1 to false)
        private const val LEVEL_KEY="__level"
        // 短剧榜单页面的书城 cell：电视与手机排行榜共用同一份官方目录。
        private const val RANK_CELL_ID="7470092475068071998"
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}

/** 播放模型的小缓存：一窗剧集一次批量取址，切集直接命中，不再向站方重复要地址。 */
class PlaybackModelCache(private val limit: Int = 120, private val ttlMillis: Long = 2*60*60*1000L) {
    private class Entry(val at: Long, val model: JSONObject)
    // accessOrder=true：get/put 命中都把条目移到末尾，淘汰时删最久未用的（LRU），
    // 而不是最早插入的——反复回看的前几集不该被后面批量插入的集挤掉。
    private val entries=object : LinkedHashMap<String,Entry>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: Map.Entry<String,Entry>?) = size > limit
    }
    @Synchronized fun get(id: String, now: Long): JSONObject? {
        val entry=entries[id] ?: return null
        if(now-entry.at>ttlMillis) { entries.remove(id); return null }
        return entry.model
    }
    @Synchronized fun put(id: String, model: JSONObject, now: Long) {
        entries[id]=Entry(now,model)
    }
    // 算法缓存分区，防止旧请求晚到后覆盖新算法的模型；子仓库仍复用同一分区。
    private val partitions = java.util.IdentityHashMap<RemoteSigner, PlaybackModelCache>()
    @Synchronized internal fun forSigner(signer: RemoteSigner): PlaybackModelCache =
        partitions.getOrPut(signer) { PlaybackModelCache(limit, ttlMillis) }
    @Synchronized fun evict(id: String) {
        entries.remove(id)
        partitions.values.forEach { it.evict(id) }
    }
}

internal val SeasonPattern=Regex("第([0-9零〇一二三四五六七八九十百千]+)(?:季|部)")
fun seasonNumber(title: String): Int {
    val matched=SeasonPattern.find(title)?.groupValues?.get(1) ?: return Int.MAX_VALUE
    return chineseNumber(matched)
}
private fun chineseNumber(text: String): Int {
    text.toIntOrNull()?.let { return it }
    val digits=mapOf('一' to 1,'二' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
    val ten=text.indexOf('十')
    if(ten<0) return text.singleOrNull()?.let { digits[it] } ?: Int.MAX_VALUE
    val high=if(ten==0) 1 else digits[text.first()] ?: 1
    val low=if(ten==text.lastIndex) 0 else digits[text.last()] ?: 0
    return high*10+low
}
