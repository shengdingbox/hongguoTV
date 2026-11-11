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
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),seriesTitle(o.str("title")),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
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
data class Detail(val series: Series, val episodes: List<String>, val related: List<Series> = emptyList(), val sameName: List<Series> = emptyList(), val social: SeriesSocial = SeriesSocial()) {
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
    val idHint: String = "", val qualityNumber: Int = 0)
/** 一次窗口取址的结果：[requested] 请求集数、[cached] 取址后可播放的集数、[batch] 本次批量新增、[route] 命中的路由。 */
data class WarmReport(val requested: Int, val cached: Int, val batch: Int, val route: String?)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
/** 平台对该集只产出了 ByteVC2 等电视无法解码的编码，重试与换清晰度都不会成功。 */
class UnplayableVideoException(message: String, val diagnostic: String = ""): IOException(message)
/** 播放信息接口返回了 HTTP 200 空体；已按有界退避重读仍未成功。 */
class EmptyPlaybackResponseException: IOException("播放接口暂时返回空响应，请稍后重试")

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

open class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).cookieJar(MemoryCookies()).build(), private val clock: () -> Long = System::currentTimeMillis, val signer: RemoteSigner = RemoteSigner(), private val modelCache: PlaybackModelCache = PlaybackModelCache()) {
    /** 同一份取址缓存、另一套传输：预取需要更短的超时，但必须和正式播放看到同一个缓存。 */
    fun childTransport(http: OkHttpClient) = ContentRepository(http, clock, signer, modelCache)
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
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private val genreCache=mutableMapOf<ContentType,Pair<Long,List<Genre>>>()
    private val suggestCache=linkedMapOf<String,Pair<Long,List<String>>>()
    // 签名搜索接口被平台关停时会稳定返回空体，短期内不再重复尝试，避免每次搜索都白等两次往返
    @Volatile private var appSearchRetryAt=0L
    // 最近一次取播放模型成功的路由，下次优先复用，减少平台按请求量限流时的无效请求
    @Volatile private var goodRoute: String?=null
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
        request.header("User-Agent", if (signed != null) signer.profile().userAgent else VendorConstants.VIDEO_UA)
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
    open fun ranking(board: RankBoard, page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/${board.path}?page=$page"),page,board)
    }
    /** 公开上新日历，与开心果 iOS 的 NewReleases 使用同一签名接口。 */
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
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // AI 剧没有独立搜索口径，官网把它归在漫剧搜索结果里
        if(type==ContentType.AI) return search(keyword,page,ContentType.COMIC)
        if(type==ContentType.COMIC) {
            try { return fillSeasonsFromDetail(appSearch(keyword,page,type),page) }
            catch(e: Exception) { /* App search API rejected the request or the cursor expired; the website paginates independently. */ }
            return fillSeasons(webSearch(keyword,page,COMIC_WEB_GENRE),page,type,keyword)
        }
        var appError: Exception? = null
        try { val result=appSearch(keyword,page,type); if(result.items.isNotEmpty() || !result.hasMore) return fillSeasonsFromDetail(result,page) } catch (e: Exception) { if(e is SearchSessionExpiredException) throw e; appError=e }
        return try { fillSeasons(webSearch(keyword,page,SHORT_WEB_GENRE),page,type,keyword) }
        catch(e: Exception) { throw IOException("搜索暂不可用（App接口：${appError?.message ?: "无结果"}；网站：${e.message}）",e) }
    }
    /** 高级搜索：按服务端下发的条件筛选剧集；条件变化后必须重取第 1 页才能拿到新的选项面板。 */
    fun filteredDiscovery(selection: DiscoverySelection, cursor: DiscoveryCursor? = null): FilteredDiscoveryPage {
        // 客户端版本号由本机设备档案统一提供，这里只传业务参数。
        val body=selection.request(cursor)
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",emptyMap(),body.toByteArray(Charsets.UTF_8))
        // 该接口与搜索接口同源，偶发空体或 5xx 时重试一次再判定失败
        val raw=runCatching { text(signed.url, signed) }.getOrElse { first ->
            Thread.sleep(300)
            runCatching { text(signed.url, signed) }.getOrElse { throw first }
        }
        return DiscoveryParser.parse(raw,cursor==null)
    }
    // 站点搜索页已改为 SPA，数据由 /api/search/result/ 下发（返回 searchList/totalPages/hasMore，10 条一页）。
    private fun webSearch(keyword: String, page: Int, genre: String?): CatalogPage {
        val url="$site/api/search/result/?query=${encodePart(keyword)}&page=$page"
        // 该接口偶发 502，重试一次再判定失败，避免搜索页直接报错
        val body=runCatching { text(url) }.getOrElse { first ->
            Thread.sleep(300)
            runCatching { text(url) }.getOrElse { throw first }
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
    private fun fillSeasons(page: CatalogPage, pageNumber: Int, type: ContentType, keyword: String): CatalogPage {
        if(pageNumber!=1 || page.items.size<2) return page
        val groups=page.items.groupBy { baseName(it.title) }.filter { (base,rows) ->
            base.isNotBlank() && (rows.size>1 || rows.any { seasonNumber(it.title)!=Int.MAX_VALUE })
        }
        if(groups.isEmpty()) return page
        val additions=mutableMapOf<String,List<Series>>()
        var budget=10
        for((base,rows) in groups.entries.sortedByDescending { it.value.size }.take(2)) {
            if(budget<=0) break
            val genre=if(type==ContentType.COMIC) COMIC_WEB_GENRE else SHORT_WEB_GENRE
            // 基底词、用户输入、基底前缀各取一次联想，多个 query 的结果互补
            val extra=collectSeasons(base,page.items,genre,listOf(base,keyword,base.take(4)),"$type|$base",budget)
            budget-=extra.size
            additions[base]=extra
        }
        if(additions.values.all { it.isEmpty() }) return page
        return mergeSeasonGroups(page,additions)
    }
    /** 原位合并：同一基底按季号排列，其余条目保持原有相关性顺序。 */
    private fun mergeSeasonGroups(page: CatalogPage, additions: Map<String,List<Series>>): CatalogPage {
        val output=mutableListOf<Series>()
        val placed=mutableSetOf<String>()
        for(item in page.items) {
            val base=baseName(item.title)
            if(base in additions) {
                if(!placed.add(base)) continue
                val group=(page.items.filter { baseName(it.title)==base }+additions.getValue(base)).distinctBy { it.id }
                output+=group.sortedBy { seasonNumber(it.title).let { n -> if(n==Int.MAX_VALUE) 1 else n } }
            } else output+=item
        }
        return page.copy(items=output)
    }
    /**
     * App 搜索接口把同系列的各季分散在分页里并与无关剧混排，首页常缺季。
     * 详情页 related 自带整季聚合（与详情页"系列 N 季"同源），用第一条命中的详情把首页同名季补齐；
     * 只对已出现 2 条以上的基底补，避免泛词搜索为单条结果额外拉详情页。
     */
    private fun fillSeasonsFromDetail(page: CatalogPage, pageNumber: Int): CatalogPage {
        if(pageNumber!=1 || page.items.size<2) return page
        val groups=page.items.groupBy { baseName(it.title) }.filter { (base,rows) -> base.isNotBlank() && rows.size>1 }
        if(groups.isEmpty()) return page
        val additions=mutableMapOf<String,List<Series>>()
        for((base,rows) in groups.entries.sortedByDescending { it.value.size }.take(2)) {
            val extra=detailSeasons(base,rows.first().id).filter { s -> page.items.none { it.id==s.id } }
            additions[base]=extra
        }
        if(additions.values.all { it.isEmpty() }) return page
        return mergeSeasonGroups(page,additions)
    }
    private fun detailSeasons(base: String, id: String): List<Series> {
        val key="detail|$base"
        val cached=synchronized(seasonFills) { seasonFills[key]?.takeIf { clock()-it.at<86_400_000 }?.items }
        if(cached!=null) return cached
        val found=runCatching { val d=detail(id); (listOf(d.series)+d.sameName).filter { baseName(it.title)==base }.distinctBy { it.id } }.getOrElse { emptyList() }
        synchronized(seasonFills) {
            seasonFills[key]=SeasonFill(clock(),found)
            while(seasonFills.size>50) seasonFills.remove(seasonFills.keys.first())
        }
        return found
    }
    /** App 搜索/联想接口和播放接口一样按注册设备放行，身份与版本号统一由本机设备档案决定。 */
    private fun searchValues(): MutableMap<String,String> = linkedMapOf()
    private fun suggestRaw(query: String): List<String> {
        synchronized(suggestCache) { suggestCache[query]?.takeIf { clock()-it.first<300_000 }?.let { return it.second } }
        val result=runCatching {
            if(query.isBlank() || query.length>40) return emptyList()
            val values=searchValues()
            values["query"]=query
            val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/suggest/v",values)
            val data=JSONObject(text(signed.url, signed))
            if(data.optInt("code",-1)!=0) return emptyList()
            val rows=data.optJSONObject("data")?.optJSONArray("query_result") ?: return emptyList()
            (0 until rows.length()).map { rows.optString(it).trim() }.filter { it.isNotEmpty() }.distinct().take(20)
        }.getOrElse { emptyList() }
        if(result.isNotEmpty()) synchronized(suggestCache) {
            suggestCache[query]=clock() to result
            while(suggestCache.size>200) suggestCache.remove(suggestCache.keys.first())
        }
        return result
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        if(clock()<appSearchRetryAt) throw IOException("搜索接口暂不可用")
        val tabType=type.searchTab ?: throw IOException("${type.label}暂不支持搜索")
        val values=searchValues()
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to tabType.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        // 官方 APK 逆向确认主网关为 reading.snssdk.com；签名只覆盖参数与主机无关，被网关拦截时换主机兜底
        var data: JSONObject? = null
        for(host in listOf("https://api5-normal-sinfonlinea.fqnovel.com","https://reading.snssdk.com")) {
            val signed=signer.sign("$host/reading/bookapi/search/tab/v",values)
            val candidate=runCatching { JSONObject(text(signed.url, signed)) }.getOrNull() ?: continue
            if(candidate.optInt("code",-1)==0) { data=candidate; break }
        }
        if(data==null) {
            appSearchRetryAt=clock()+300_000
            throw IOException("搜索接口暂不可用")
        }
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val rows=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { rows.addAll(it) }
            for(row in rows) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,seriesTitle(title),cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    /** Pinyin-initial suggestions from the signed app API; every failure returns an empty list so the TV keyboard keeps working. */
    fun suggest(query: String): List<String> {
        val key=query.trim().lowercase()
        if(key.isEmpty() || key.length>20 || !key.matches(Regex("[a-z0-9]+"))) return emptyList()
        return suggestRaw(key)
    }
    /** 主详情与辅助信息分开缓存，首页点击只等待标题和完整分集。 */
    private data class DetailCache(val at: Long, val detail: Detail, val extrasLoaded: Boolean = false)
    private val detailCache=linkedMapOf<String,DetailCache>()

    /** 仅请求一次主接口；推荐、系列剧由页面展示后再补齐。 */
    fun seriesDetail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        cachedDetail(id)?.let { return it }
        val main=fetchSeriesDetail(id)
        val result=Detail(main.series,main.episodes,social=main.social)
        return synchronized(detailCache) {
            detailCache[id]?.takeIf { clock()-it.at<DETAIL_CACHE_TTL }?.detail ?: result.also {
                detailCache[id]=DetailCache(clock(),it)
                while(detailCache.size>30) detailCache.remove(detailCache.keys.first())
            }
        }
    }

    /** 完整详情保留给补季、收藏更新等需要系列信息的调用方。 */
    fun detail(id: String): Detail {
        val main=seriesDetail(id)
        if(!needsDetailExtras(id)) return cachedDetail(id) ?: main
        val scope=RequestScope.current()?.child() ?: RequestScope()
        // 两个辅助请求共用取消作用域与等待预算，页面离开后一起取消。
        val relatedTask=FutureTask(Callable { scope.run { runCatching { planRelated(id,main.series) }.getOrDefault(emptyList()) } })
        val seasonsTask=FutureTask(Callable { scope.run { runCatching { planSeasons(id) }.getOrDefault(emptyList()).filter { it.id!=id } } })
        val threads=listOf(relatedTask,seasonsTask).map { task -> Thread(task,"hongguotv-detail-extra").apply { isDaemon=true; start() } }
        try {
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(DETAIL_AUX_TIMEOUT)
            fun <T> getAux(task: Future<T>): T? = try { task.get((deadline-System.nanoTime()).coerceAtLeast(0),TimeUnit.NANOSECONDS) }
            catch(_: TimeoutException) { null } catch(_: ExecutionException) { null } catch(_: InterruptedException) { Thread.currentThread().interrupt(); null }
            val related=getAux(relatedTask).orEmpty()
            val sameName=getAux(seasonsTask).orEmpty().distinctBy { it.id }.sortedBy { seasonNumber(it.title).let { n -> if(n==Int.MAX_VALUE) 1 else n } }
            if(scope.isCancelled || Thread.currentThread().isInterrupted) throw IOException("请求已取消")
            val result=main.copy(related=related,sameName=sameName)
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
        val series = planCardSeries(v).copy(id = id)
        val episodes = v.optJSONArray("video_list")?.objects()
            ?.mapNotNull { row -> row.str("vid").takeIf { vid -> vid.matches(Regex("[0-9]{1,30}")) }?.let { row.optInt("vid_index", Int.MAX_VALUE) to it } }
            ?.sortedBy { it.first }?.map { it.second }
            ?: throw IOException("分集暂不可用")
        if (episodes.isEmpty() || episodes.distinct().size != episodes.size) throw IOException("分集数据异常")
        return SeriesPayload(series, episodes, detailSocial(v))
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
        val cell = cells.firstOrNull { it.optInt("show_type") == 547 } ?: return emptyList()
        return valid(cell.optJSONArray("video_data")?.objects()?.map(::planCardSeries).orEmpty())
    }

    /** 统一打 plan feed；按 `from` 场景取回 data 下的 cell 数组。 */
    private fun planCells(bookId: String, from: String, filterId: String? = null): List<JSONObject> {
        val values = linkedMapOf(
            "book_id" to bookId,
            "from" to from,
            "scene" to "10",
            "session_uuid" to UUID.randomUUID().toString(),
            "user_is_login" to "0",
        )
        if (filterId != null) values["filter_ids"] = filterId
        val data = signedJson("$API/reading/bookapi/plan/v1/", values, null)
        return data.optJSONArray("data")?.objects().orEmpty()
    }

    /** 签名 JSON 请求，统一处理 code != 0 与空响应。 */
    private fun signedJson(url: String, values: Map<String, String>, body: ByteArray?, transport: OkHttpClient = http): JSONObject {
        val signed = signer.sign(url, values, body)
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

    /** 详情/推荐/系列剧共用的卡片解析：尽量凑齐 Series 需要的六个字段。 */
    private fun planCardSeries(o: JSONObject): Series {
        val id = o.str("series_id_str").ifEmpty { o.str("series_id") }.ifEmpty { o.str("vid") }
        val title = o.str("series_title").ifEmpty { o.str("title") }
        val cover = o.str("series_cover").ifEmpty { o.str("cover") }
        val intro = o.str("series_intro").ifEmpty { o.str("description") }
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
        return Series(id, seriesTitle(title), cleanUrl(cover), intro, badge, tags)
    }

    /** 详情接口自带的热度/收藏，替换原 SSR 里的评分区；没有评分字段就隐藏。 */
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
    fun stream(id: String, maxQuality: Int = 1080, refreshModel: Boolean = false): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        if(refreshModel) modelCache.evict(id)
        try {
            return streamFromModel(videoModel(id),maxQuality)
        } catch (unplayable: UnplayableVideoException) {
            // 站方对同一集会间歇性下发降级模型（2026-11-08 路由标记诊断证实：来自 video_tab 主接口，
            // video_list 全部清晰度都是 bytevc2 加密 blob，旧 POST 路由已全空、无路由可跳，换身份也不影响下发）。
            // 降级按请求随机出现：剔除坏缓存，再赌几轮正常模型，轮间稍作退避；
            // 连续降级才把异常交给上层，由「自动跳过不兼容集数」接手。
            // 这里不再 recover：单集接口对同一身份可重复请求，换设备只会白白多消耗一次取址配额。
            var failure=unplayable
            for(round in 1 until PLAYBACK_RETRY_ROUNDS) {
                if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("播放请求已取消")
                modelCache.evict(id)
                if(round>1) Thread.sleep(RETRY_BACKOFF_MS*(round-1))
                try {
                    val (level,preferAlt)=RETRY_PLANS[round-1]
                    return streamFromModel(videoModel(id,level,preferAlt),maxQuality)
                } catch(next: UnplayableVideoException) {
                    failure=next
                }
            }
            throw failure
        }
    }
    private fun streamFromModel(model: JSONObject, maxQuality: Int): StreamInfo {
        // PR #7's player endpoint returns direct streams, with no fallback_api hop.
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
        return StreamInfo(url,key,quality,expires)
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
            val encryption=item.optJSONObject("encrypt_info") ?: JSONObject()
            if(meta.str("codec_type").lowercase() in setOf("bytevc2","bvc2")) return@mapNotNull null
            val encrypted=encryption.optBoolean("encrypt")
            if(encrypted && (encryption.str("encryption_method")!="cenc-aes-ctr" || encryption.str("spade_a").isEmpty())) return@mapNotNull null
            val url=listOf(item.str("main_url"),item.str("backup_url")).map(::cleanUrl)
                .firstOrNull { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
            val height=qualityNumber(meta.str("definition").ifEmpty { meta.str("vheight").ifEmpty { meta.str("height") } })
            Triple(height,url,if(encrypted) encryption.str("spade_a") else "")
        }.sortedByDescending { it.first }
        if(rows.isEmpty() && model.has("fallback_api")) return null
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull()
            ?: throw UnplayableVideoException("本集片源暂不兼容，请播放下一集", "route="+model.optString(ROUTE_KEY)+" direct video_list has no supported streams")
        val key=selected.third.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(selected.second,key,quality,playbackExpiry(model,selected.second))
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
     *  与正式播放共用 PR #7 的单集 POST 和模型缓存，最多预热 [WARM_LIMIT] 集。
     *  失败不上抛，播放时按需取址仍是兜底。 */
    fun warm(episodes: List<String>): WarmReport {
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
            val model=try { videoModel(id) } catch(problem: java.io.InterruptedIOException) { throw problem } catch(_: Exception) { null } ?: continue
            modelCache.put(id,model.put(LEVEL_KEY,1),clock())
            filled++; route=model.optString(ROUTE_KEY).ifEmpty { route }
        }
        return WarmReport(ids.size,cached+filled,filled,route)
    }
    /** 播放请求：签名头由本机播放算法生成，UA 与本机设备档案一致。 */
    private fun signedBuilder(signed: SignedRequest): Request.Builder =
        Request.Builder().url(signed.url).apply {
            header("User-Agent", signer.profile().userAgent)
            signed.headers.forEach { (key,value) -> header(key,value) }
        }
    private fun playerRequest(path: String,payload: ByteArray): Request {
        val signed=signer.sign("$API$path", linkedMapOf(), payload)
        return signedBuilder(signed)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
    /** 单集取址：`GET /reading/bookapi/video_tab/video_model/v`。
     *
     *  2026-11-09 实测：该接口对同一匿名设备可连续请求（12/12 全部返回模型）；
     *  而下面的 `multi_video_model` 对匿名设备基本是一次性配额（同一设备第 1 次成功后持续空响应，
     *  换主机只能再成功一次），也是「一直卡在正在获取播放地址」的根因。
     *  因此单集取址优先走这里，`multi_video_model` 只作为兜底。
     *  参数照抄葫芦娃后端已验证的组合：video_id / source / need_all_video_definition。 */
    private fun videoTabSingleModel(id: String, preferAlt: Boolean = false): JSONObject? = videoTabSingle(id,preferAlt).model
    /** 单集取址的完整结果：`model` 为空时由 [blank] 区分「空体」和「有明确错误码」，
     *  后者不该再触发换设备重扫。 */
    private data class SingleTab(val model: JSONObject?, val blank: Boolean)
    private fun videoTabSingle(id: String, preferAlt: Boolean = false): SingleTab {
        val extra=linkedMapOf(
            "video_id" to id, "source" to "0", "need_all_video_definition" to "true",
        )
        val hosts=listOf(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/video_model/v",
            "https://reading.snssdk.com/reading/bookapi/video_tab/video_model/v",
        )
        val ordered=if(preferAlt) hosts.reversed() else hosts
        var allBlank=true
        for(base in ordered) {
            val signed=signer.sign(base,extra,null)
            val request=signedBuilder(signed).get().build()
            val body=try {
                RequestScope.execute(http.newCall(request)) { response ->
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
            if(root.optInt("code",root.optInt("Code",-1))!=0) continue
            val data=root.optJSONObject("data") ?: continue
            val model=modelOf(data.opt("video_model")) ?: continue
            return SingleTab(model.put(ROUTE_KEY,"video_tab|"+base.removePrefix("https://").substringBefore('/')),false)
        }
        return SingleTab(null,allBlank)
    }
    /** 多集取址：`GET /reading/bookapi/video_tab/multi_video_model/v1/`。
     *  参数取自官方 `GetVideoModelRequest` 的 snake_case 字段，缺任一字段会回 `100103`。
     *  注意该接口对匿名设备近乎一次性配额，只能作为兜底，见 [videoTabSingleModel]。
     *  失败一律返回 null，由调用方回退旧接口。 */
    private fun videoTabModel(id: String, level: Int = 1, preferAlt: Boolean = false): JSONObject? {
        val extra=linkedMapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to level.toString(),
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "",
        )
        val hosts=listOf(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",
            "https://reading.snssdk.com/reading/bookapi/video_tab/multi_video_model/v1/",
        )
        // preferAlt：重试诊断用，优先探测备用主机（两个主机可能落在不同后端集群）。
        val ordered=if(preferAlt) hosts.reversed() else hosts
        for(base in ordered) {
            val signed=signer.sign(base,extra,null)
            val request=signedBuilder(signed).get().build()
            val body=try {
                RequestScope.execute(http.newCall(request)) { response ->
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
            val row=rows.optJSONObject(id) ?: rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.firstOrNull() ?: continue
            val model=modelOf(row.opt("video_model")) ?: continue
            return model.put(ROUTE_KEY,"video_tab|"+base.removePrefix("https://").substringBefore('/'))
        }
        return null
    }
    /** 播放模型优先走 PR #7 的单集 POST；video_tab 与其他播放器路由作为兼容回退。 */
    private fun videoModel(id: String, level: Int = 1, preferAlt: Boolean = false): JSONObject {
        modelCache.get(id,clock())?.let { return it }
        val model=fetchVideoModel(id,level,preferAlt).put(LEVEL_KEY,level)
        modelCache.put(id,model,clock())
        return model
    }
    private fun fetchVideoModel(id: String, level: Int = 1, preferAlt: Boolean = false): JSONObject {
        val primaryPayload="""{"video_id":"$id","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}""".toByteArray(Charsets.UTF_8)
        val primary=try {
            sweepRoutes(listOf(PLAYER_PATH to primaryPayload))
        } catch(problem: java.io.InterruptedIOException) {
            throw problem
        } catch(problem: IOException) {
            Sweep(null,null,false,problem)
        }
        primary.model?.let { return it }
        val singleTab=videoTabSingle(id,preferAlt)
        singleTab.model?.let { return it }
        val multiTab=videoTabModel(id,level,preferAlt)
        multiTab?.let { return it }
        // 与 video_tab 保持同一 device_level（可诊断轮换）：部分剧集在 3 下会把 360P–720P 全发成
        // bytevc2（MP4 提取器读不出视频轨），只剩超出电视解码上限的 1080P bytevc1，降档无路可走。
        val biz="""{"detail_page_version":0,"device_level":$level,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}"""
        val map="""{"biz_param":$biz,"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        val routes=linkedMapOf(
            "/novel/player/multi_video_model/v1/" to map,
            "/novel/player/multi_video_model/preload/v1/" to map,
            "/novel/player/multi_video_model/default/v1/" to map,
            "/novel/player/multi_video_model/detail/v1/" to map,
        )
        val good=goodRoute
        val ordered=buildList {
            if(good!=null && routes.containsKey(good)) add(good to routes.getValue(good))
            for((path,payload) in routes) if(path!=good) add(path to payload)
        }
        val first=sweepRoutes(ordered)
        first.model?.let { model -> goodRoute=first.path; return model }
        // 只有两个 GET 接口（单集/多集）都空、且旧 POST 路由也全空，才算「身份被用尽」。
        val allBlank=primary.allBlank && first.allBlank && singleTab.blank && multiTab==null
        // 空体基本都发生在「当前匿名身份已经被站方用尽」之后：只在这种情况下换一台匿名设备再扫一轮。
        // 非空体（有明确错误码）重扫没有意义，直接快速失败，避免界面上长时间停在「正在获取播放地址」。
        if(!allBlank) throw first.failure ?: primary.failure ?: IOException("播放信息为空")
        signer.recover()
        val healed=sweepRoutes(listOf(PLAYER_PATH to primaryPayload)+ordered)
        healed.model?.let { model -> goodRoute=healed.path; return model }
        if(healed.allBlank) throw EmptyPlaybackResponseException()
        throw healed.failure ?: IOException("播放信息为空")
    }
    /** 一轮路由扫描：首条路由原地重发一次，其余路由各发一次，尽量少的请求量覆盖所有候选。
     *  返回后 [Sweep.allBlank] 才是 true 才代表「所有路由都空体」；有非空错误码时会立即返回，不再空转。 */
    private fun sweepRoutes(routes: List<Pair<String,ByteArray>>): Sweep {
        var allBlank=true
        for((index,pair) in routes.withIndex()) {
            val (path,payload)=pair
            // 首条路由既是上次成功的那条、也最可能是当前唯一可用的，值得原地重发一次。
            val attempts=if(index==0) BLANK_RETRIES else 1
            for(attempt in 0 until attempts) {
                if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("播放请求已取消")
                val body=RequestScope.execute(http.newCall(playerRequest(path,payload))) { response ->
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
                    videoModelOf(data)?.let { model -> return Sweep(model.put(ROUTE_KEY,path),path,false,null) }
                    // 结构异常属于「有明确失败」，不能和空体（配额用尽）混为一谈。
                    return Sweep(null,null,false,IOException("播放信息为空"))
                }
                return Sweep(null,null,false,IOException("播放接口暂不可用（错误码 $code）"))
            }
        }
        return Sweep(null,null,allBlank,null)
    }
    private data class Sweep(val model: JSONObject?, val path: String?, val allBlank: Boolean, val failure: IOException?)
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
        // 推荐与系列剧在主详情显示后并行补齐，共享两秒预算。
        private const val DETAIL_AUX_TIMEOUT=2_000L
        private const val DETAIL_CACHE_TTL=600_000L
        // 网站搜索混合两类内容：video_data.genre 205 是漫剧，203 是短剧
        // （2026-10-13 用站点自带的漫剧热播榜与短剧分类列表逐条核对确认）。
        private const val COMIC_WEB_GENRE="205"
        private const val SHORT_WEB_GENRE="203"
        // 首条路由的空响应重发次数与退避：网关抖动时先原地重试，再换下一条路由。
        private const val BLANK_RETRIES=2
        private const val BLANK_BACKOFF_MS=400L
        // 预热上限：单集接口一次一集，取 8 集足够覆盖相邻窗口，又不会把请求量放大。
        private const val WARM_LIMIT=8
        // 诊断专用：随模型进缓存的路由标记，streamFromModel 抛不兼容异常时写进 diagnostic。
        private const val ROUTE_KEY="__route"
        // 降级模型按请求间歇出现（实测约 1/3），一次取址失败后最多再赌两轮正常模型；仍降级才报不兼容。
        private const val PLAYBACK_RETRY_ROUNDS=3
        private const val RETRY_BACKOFF_MS=400L
        // 重试轮次轮换参数：诊断降级窗口下 level=3 与备用主机是否下发电视可解码的转码集
        // （1=现行为，3=官方档位；preferAlt=优先 reading.snssdk.com 备用主机）。
        private val RETRY_PLANS=listOf(1 to true, 3 to false)
        private const val LEVEL_KEY="__level"
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
    @Synchronized fun evict(id: String) {
        entries.remove(id)
    }
}

private val SeasonPattern=Regex("第([0-9零〇一二三四五六七八九十百千]+)(?:季|部)")
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
