// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(UnplayableVideoException::class.java) { repo.stream("123",720) }
    }

    @Test fun incompatibleSourceDiagnosticCarriesRouteAndEveryVideoListEntry() {
        // 模拟站方降级模型：全部条目 main_url 都是加密 blob（非 http 开头），应逐条出现在诊断里且带路由。
        // 模拟优先播放 POST 返回带 fallback_api 的兼容模型。
        val repo=repository { request ->
            if(request.url.host=="example.com") {
                val rows=JSONObject()
                    .put("video_1",JSONObject().put("vheight",1080).put("codec_type","bytevc2").put("main_url","qAABAGXlCAY==").put("backup_url_1","qAABAFoQKf1K=="))
                    .put("video_5",JSONObject().put("vheight",720).put("codec_type","bytevc2").put("main_url","qAABAAAA=="))
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        val error=assertThrows(UnplayableVideoException::class.java) { repo.stream("123",1080) }
        // 优先 POST 路由随模型进入诊断；最终重试轮的 level=3 也应保留。
        assertEquals("route=$playerPath lvl=3 video_list=video_1:bytevc2:1080:enc/enc;video_5:bytevc2:720:enc/empty",error.diagnostic)
    }

    @Test fun tabRouteTagRidesAlongInIncompatibleDiagnostic() {
        val repo=repository { request ->
            if(request.url.host=="example.com") {
                val rows=JSONObject().put("video_0",JSONObject().put("vheight",720).put("codec_type","bytevc2").put("main_url","qAABAAAA=="))
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            } else if(request.url.encodedPath==singlePath)
                """{"code":0,"data":{"video_model":{"fallback_api":"https://example.com/stream"}}}"""
            else ""
        }
        val error=assertThrows(UnplayableVideoException::class.java) { repo.stream("123",1080) }
        assertTrue(error.diagnostic.startsWith("route=video_tab|"))
    }

    @Test fun degradedModelIsEvictedAndRefetchedWithRenewedIdentity() {
        var streamFetches=0
        val repo=repository { request ->
            if(request.url.host=="example.com") {
                streamFetches++
                val rows=JSONObject()
                if(streamFetches==1) rows.put("video_0",JSONObject().put("vheight",720).put("codec_type","bytevc2").put("main_url","https://example.com/unsupported"))
                else rows.put("video_0",JSONObject().put("vheight",1080).put("codec_type","h264").put("main_url","https://example.com/recovered"))
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        // 第一次取到降级模型，剔缓存换身份重取后拿到正常片源，而不是直接报不兼容。
        assertEquals("https://example.com/recovered",repo.stream("123",1080).url)
        assertEquals(2,streamFetches)
    }

    @Test fun repeatedlyDegradedModelsKeepRetryingBeforeReportingIncompatible() {
        // 站方对同一集间歇性下发降级模型（全清晰度 bytevc2），按请求随机出现：
        // 连续两轮降级后第三轮拿到正常模型，应播放成功而不是提前报不兼容。
        var streamFetches=0
        val repo=repository { request ->
            if(request.url.host=="example.com") {
                streamFetches++
                val rows=JSONObject()
                if(streamFetches<3) rows.put("video_0",JSONObject().put("vheight",1080).put("codec_type","bytevc2").put("main_url","qAABAAAA=="))
                else rows.put("video_0",JSONObject().put("vheight",1080).put("codec_type","h264").put("main_url","https://example.com/recovered"))
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        assertEquals("https://example.com/recovered",repo.stream("123",1080).url)
        assertEquals(3,streamFetches)
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun videoInfo() =
        """{"video_info":{"data":{"video_list":{"video_0":{"vheight":1080,"codec_type":"h264","main_url":"https://example.com/a.mp4"}}}}}"""
    private val playerPath="/novel/player/video_model/v1/"
    // 旧单集 GET：优先播放 POST 不可用时的第一条兼容路由。
    private val singlePath="/reading/bookapi/video_tab/video_model/v"
    // 多集取址接口：对匿名设备近乎一次性配额，只作兜底。
    private val tabPath="/reading/bookapi/video_tab/multi_video_model/v1/"

    private fun playerModelResponse() =
        """{"code":0,"data":{"expire_time":1893456000,"video_model":{"video_list":[{"main_url":"https://example.com/a.mp4","video_meta":{"definition":"1080p","codec_type":"h264"}}]}}}"""

    @Test fun playerPostIsPreferredAndReturnsDirectStreamWithoutFallbackRequest() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests += request
            assertEquals(playerPath,request.url.encodedPath)
            assertEquals("api5-normal-sinfonlinea.fqnovel.com",request.url.host)
            assertEquals("POST",request.method)
            val body=okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val payload=JSONObject(body)
            assertEquals("123",payload.getString("video_id"))
            val business=payload.getJSONObject("biz_param")
            assertEquals(0,business.getInt("video_id_type"))
            assertTrue(business.getBoolean("need_all_video_definition"))
            assertEquals(3,business.getInt("video_platform"))
            playerModelResponse()
        }
        val stream=repo.stream("123")
        assertEquals("https://example.com/a.mp4",stream.url)
        assertEquals("1080P",stream.quality)
        assertEquals(1893456000000L,stream.expiresAt)
        assertNull(stream.key)
        assertEquals(1,requests.size)
    }

    @Test fun legacySingleEpisodeRouteIsPreferredOverMultiWhenPlayerIsBlank() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths += request.url.encodedPath
            when {
                request.url.host=="example.com" -> videoInfo()
                request.url.encodedPath==singlePath ->
                    """{"code":0,"data":{"video_model":{"fallback_api":"https://example.com/stream"}}}"""
                request.url.encodedPath==tabPath ->
                    """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}}"""
                else -> ""
            }
        }
        assertEquals("1080P",repo.stream("123").quality)
        assertEquals(listOf(playerPath,playerPath,singlePath,"/stream"),paths)
        // 旧单集 GET 成功后，不再触碰多集接口。
        assertFalse(paths.contains(tabPath))
    }

    @Test fun multiRouteStillWorksWhenSingleRouteIsBlank() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths += request.url.encodedPath
            when {
                request.url.host=="example.com" -> videoInfo()
                request.url.encodedPath==singlePath -> ""
                request.url.encodedPath==tabPath ->
                    """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}}"""
                else -> ""
            }
        }
        assertEquals("1080P",repo.stream("123").quality)
        assertTrue(paths.contains(singlePath))
        assertTrue(paths.contains(tabPath))
    }

    @Test fun nonBlankErrorWithoutRecoveryFailsFast() {
        // 非空体表示站方明确拒绝（有错误码），换设备重扫没有意义：
        // 优先 POST 拒绝后仍允许兼容回退；首条旧 POST 也明确拒绝时立即失败，不再换设备重扫。
        val postRoutes=mutableListOf<String>()
        val repo=repository { request ->
            when {
                request.url.host=="example.com" -> videoInfo()
                request.url.encodedPath.startsWith("/novel/player/") -> {
                    postRoutes += request.url.encodedPath
                    """{"code":100103,"message":"参数错误"}"""
                }
                else -> """{"code":100103,"message":"参数错误"}"""
            }
        }
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(listOf(playerPath,"/novel/player/multi_video_model/v1/"),postRoutes)
    }

    @Test fun videoTabRouteSuppliesPlaybackModel() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths += request.url.encodedPath
            when {
                request.url.host=="example.com" -> videoInfo()
                request.url.encodedPath==singlePath -> ""
                request.url.encodedPath==tabPath ->
                    """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}}"""
                else -> ""
            }
        }
        assertEquals("1080P",repo.stream("123").quality)
        assertTrue(paths.contains(singlePath))
        assertTrue(paths.contains(tabPath))
    }

    @Test fun newRouteVideoModelCanBeStringEncodedJson() {
        val repo=repository { request ->
            if(request.url.host=="example.com") videoInfo()
            else if(request.url.encodedPath=="/novel/player/video_model/v1/") """{"data":{"video_model":"{\"fallback_api\":\"https://example.com/stream\"}"}}"""
            else ""
        }
        assertEquals("1080P",repo.stream("123").quality)
    }

    @Test fun warmAndPlaybackSharePlayerPostModelsWithoutRefetching() {
        val modelRequests=mutableListOf<String>()
        val repo=repository { request ->
            assertEquals(playerPath,request.url.encodedPath)
            assertEquals("POST",request.method)
            val payload=okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            modelRequests += JSONObject(payload).getString("video_id")
            playerModelResponse()
        }
        val report=repo.warm(listOf("1","2","3"))
        assertEquals(3,report.cached)
        assertEquals(playerPath,report.route)
        assertEquals(listOf("1","2","3"),modelRequests)
        assertEquals("1080P",repo.stream("3").quality)
        // 正式播放复用同一份直连模型，不重取模型或请求 fallback_api。
        assertEquals(listOf("1","2","3"),modelRequests)
    }

    @Test fun blankRoutesFallBackToNextLegacyPostRoute() {
        val postRoutes=mutableListOf<String>()
        val preloadPath="/novel/player/multi_video_model/preload/v1/"
        val repo=repository { request ->
            if(request.method=="POST") postRoutes += request.url.encodedPath
            when {
                request.url.host=="example.com" -> videoInfo()
                request.url.encodedPath==preloadPath ->
                    """{"data":{"video_model":{"fallback_api":"https://example.com/stream"}}}"""
                else -> ""
            }
        }
        assertEquals("1080P",repo.stream("123").quality)
        assertEquals(listOf(playerPath,playerPath,
            "/novel/player/multi_video_model/v1/","/novel/player/multi_video_model/v1/",preloadPath),postRoutes)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock,
        // 签名服务用进程内替身，测试不连局域网服务，身份保持固定。
        FakeSigner()
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsDerivedFromOrderAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(5,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            if(request.url.host.endsWith("fqnovel.com")) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
            else """{"searchList":[{"video_data":{"series_id":"333","series_name":"漫画网站结果","genre":"205"}}]}"""
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("重生",2,ContentType.COMIC) }
        assertEquals(2,requests.size)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) { request ->
            requests++
            if(request.url.host.endsWith("fqnovel.com")) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
            else """{"searchList":[{"video_data":{"series_id":"444","series_name":"漫画兜底","genre":"205"}}]}"""
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
        assertEquals(1,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(3,requests)
    }

    @Test fun websiteSearchKeepsEveryGenreWhenAppSearchFails() {
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else """{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}},{"video_data":{"series_id":"222","series_name":"漫画测试","genre":"205"}}]}"""
        }
        val result=repo.search("修仙",1,ContentType.COMIC)
        // 搜索不再按内容类型裁剪，短剧与漫剧都保留服务端顺序。
        assertEquals(listOf("111","222"),result.items.map { it.id })
        assertFalse(result.hasMore)
    }

    @Test fun multiSeasonSearchReturnsImmediatelyWithoutSuggestionOrDetailRequests() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            val url=request.url
            paths+=url.encodedPath
            val keyword=url.queryParameter("query").orEmpty()
            when {
                url.host.endsWith("fqnovel.com") && url.encodedPath.contains("suggest") ->
                    JSONObject().put("code",0).put("data",JSONObject().put("query_result",JSONArray().put("测试剧第二季"))).toString()
                url.host.endsWith("fqnovel.com") -> ""
                keyword.contains("第二季") ->
                    """{"searchList":[{"video_data":{"series_id":"222","series_name":"测试剧第二季","genre":"205"}}]}"""
                else ->
                    """{"searchList":[{"video_data":{"series_id":"111","series_name":"测试剧第一季","genre":"205"}},{"video_data":{"series_id":"113","series_name":"测试剧第三季","genre":"205"}}]}"""
            }
        }
        val result=repo.search("测试剧",1,ContentType.COMIC)
        assertEquals(listOf("111","113"),result.items.map { it.id })
        assertEquals(listOf("/reading/bookapi/search/tab/v","/reading/bookapi/search/tab/v","/api/search/result/"),paths)
    }

    @Test fun shortSearchWebsiteFallbackKeepsEveryGenre() {
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else """{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}},{"video_data":{"series_id":"222","series_name":"漫画测试","genre":"205"}}]}"""
        }
        val result=repo.search("总裁",1,ContentType.SHORT)
        assertEquals(listOf("111","222"),result.items.map { it.id })
    }

    @Test fun websiteSearchPaginatesWithSitePageAndHasMore() {
        val pages=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else {
                assertEquals("/api/search/result/",request.url.encodedPath)
                assertEquals("修仙",request.url.queryParameter("query"))
                val page=request.url.queryParameter("page").orEmpty()
                pages+=page
                """{"searchList":[{"video_data":{"series_id":"90$page","series_name":"网站结果第${page}页","genre":"205"}}],"totalPages":2,"hasMore":${page=="1"}}"""
            }
        }
        val first=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("901"),first.items.map { it.id })
        assertTrue(first.hasMore)
        val second=repo.search("修仙",2,ContentType.COMIC)
        assertEquals(listOf("902"),second.items.map { it.id })
        assertFalse(second.hasMore)
        assertEquals(listOf("1","2"),pages)
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun searchUsesUnfilteredCommonTabAndKeepsServerOrder() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("11",request.url.queryParameter("tab_type"))
            // 综合页不带类型筛选，否则合集（content_type=1003）会被服务端筛掉。
            assertNull(request.url.queryParameter("selected_items"))
            assertEquals("1",request.url.queryParameter("search_source"))
            assertEquals("feed",request.url.queryParameter("tab_name"))
            assertEquals("16",request.url.queryParameter("bookstore_tab"))
            assertEquals("101",request.header("lc"))
            assertTrue(request.header("Cookie").orEmpty().contains("ttreq=fixture-guest"))
            assertNotNull(request.header("x-reading-request"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(29,"111")).put(searchTab(11,if(requests==1) "998" else "999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyCommonSearchDoesNotFallBackToOtherSources() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":11,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    @Test fun albumResultsAppearInSearchWithoutTypeFiltering() {
        val repo=repository { request ->
            assertEquals("11",request.url.queryParameter("tab_type"))
            assertNull(request.url.queryParameter("selected_items"))
            """{"code":0,"search_tabs":[{"tab_type":11,"has_more":false,"data":[
                {"video_data":[{"series_id":"888","content_type":1003,"video_detail":{"series_title":"我的妹妹不可爱","series_cover":"https://example.com/a.jpg"}}]},
                {"video_data":[{"series_id":"999","content_type":1004,"video_detail":{"series_title":"困兽之战","episode_cnt":17}}]}]}]}"""
        }
        // 合集 1003 以前被客户端类型过滤丢弃，现在与短剧一起按服务端顺序展示。
        val items=repo.search("我的妹妹不可爱",1,ContentType.COMIC).items
        assertEquals(listOf("888","999"),items.map { it.id })
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size<=2) throw IOException("offline")
            """{"searchList":[{"video_data":{"series_id":"456","series_name":"测试短剧","genre":"203"}}]}"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(3,paths.size)
    }

    @Test fun repeatedSearchUsesCachedPageAndNeverFetchesDetailsForTheFirstPaint() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("/reading/bookapi/search/tab/v",request.url.encodedPath)
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"999"))).toString()
        }
        val first=repo.search("修仙")
        assertEquals(first,repo.search(" 修仙 "))
        assertEquals(1,requests)
    }

    @Test fun searchReadsNestedObjectCardsFromTheCommonTab() {
        val repo=repository { request ->
            assertEquals("11",request.url.queryParameter("tab_type"))
            """{"code":0,"search_tabs":[{"tab_type":11,"has_more":false,"data":[{"video_data":{"series_id":"999","content_type":1,"video_detail":{"series_title":"测试短剧","series_intro":"介绍","series_cover":"https://example.com/poster.jpg","episode_cnt":12}}}]}]}"""
        }
        val item=repo.search("测试",type=ContentType.SHORT).items.single()
        assertEquals("999",item.id)
        assertEquals("测试短剧",item.title)
        assertEquals("https://example.com/poster.jpg",item.cover)
        assertEquals("全 12 集",item.badge)
    }

    @Test fun searchKeepsEveryCardTypeAndStillOrdersSeasons() {
        val repo=repository {
            """{"code":0,"search_tabs":[{"tab_type":11,"has_more":false,"data":[{"video_data":[{"series_id":"111","content_type":1004,"video_detail":{"series_title":"测试漫剧第三季"}},{"series_id":"222","content_type":1,"title":"真人剧"},{"series_id":"333","content_type":1004,"video_detail":{"series_title":"测试漫剧第一季"}}]}]}]}"""
        }
        // 短剧、漫剧、合集混排时都保留，只对同名多季做排序。
        assertEquals(listOf("333","111","222"),repo.search("测试").items.map { it.id })
    }

    @Test fun duplicateOnlyPageEndsSearchInsteadOfRequestingTheSameItemsForever() {
        val repo=repository {
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"999"))).toString()
        }
        repo.search("修仙")
        val second=repo.search("修仙",2)
        assertTrue(second.items.isEmpty())
        assertFalse(second.hasMore)
    }

    @Test fun failedAppContinuationDoesNotSwitchToWebsitePages() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"999"))).toString()
            else throw IOException("offline")
        }
        repo.search("修仙")
        assertThrows(IOException::class.java) { repo.search("修仙",2) }
        assertEquals(listOf("/reading/bookapi/search/tab/v","/reading/bookapi/search/tab/v"),paths)
    }

    @Test fun searchArtworkFetchesMissingCoversInOneBatchAfterResultsAreAvailable() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) """{"code":0,"search_tabs":[{"tab_type":11,"has_more":false,"data":[{"video_data":[{"series_id":"111","title":"第一部"},{"series_id":"222","title":"第二部"}]}]}]}"""
            else {
                assertEquals("/novel/player/multi_video_detail/player/v1",request.url.encodedPath)
                val body=JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                assertEquals("111,222",body.getString("series_id"))
                """{"code":0,"data":{"111":{"video_data":{"series_id":"111","series_cover":"https://example.com/1.jpg"}},"222":{"video_data":{"series_id":"222","series_cover":"https://example.com/2.jpg"}}}}"""
            }
        }
        val first=repo.search("测试",type=ContentType.SHORT)
        assertEquals(1,paths.size)
        assertTrue(first.items.all { it.cover.isEmpty() })
        val enriched=repo.searchArtwork(first.items)
        assertEquals(listOf("https://example.com/1.jpg","https://example.com/2.jpg"),enriched.map { it.cover })
        assertEquals(enriched,repo.search("测试",type=ContentType.SHORT).items)
        assertEquals(2,paths.size)
    }

    // 详情接口为红果官方 JSON（POST /novel/player/multi_video_detail/v1/），
    // 分集在 data[series_id].video_data.video_list[].vid；相关推荐与系列剧走 plan feed，用 from 参数区分。
    private val detailPath="/novel/player/multi_video_detail/v1/"
    private val planPath="/reading/bookapi/plan/v1/"
    private fun relatedItem(id: String,name: String)=JSONObject().put("series_id_str",id).put("series_title",name)
        .put("series_cover","https://example.com/$id.jpg").put("episode_cnt",10).put("episode_right_text","全 10 集")
    // 默认按传入顺序编 vid_index；需要验证乱序时显式传 indexes。
    private fun detailVideo(id: String,name: String,vids: List<String>,indexes: List<Int>?=null)=JSONObject().put("code",0)
        .put("data",JSONObject().put(id,JSONObject().put("video_data",
            JSONObject().put("series_id_str",id).put("series_title",name)
                .put("video_list",JSONArray().apply {
                    vids.forEachIndexed { i,vid -> put(JSONObject().put("vid",vid).put("vid_index",indexes?.get(i) ?: i)) }
                }))))
    private fun planPayload(showType: Int,items: List<JSONObject>)=JSONObject().put("code",0)
        .put("data",JSONArray().put(JSONObject().put("show_type",showType)
            .put("video_data",JSONArray().apply { items.forEach { put(it) } })))
    private fun emptyPlan()="""{"code":0,"data":[]}"""
    // 详情改走 POST 后 series_id 在请求体里，按 body 区分是哪一季的详情请求。
    private fun detailBodyId(request: Request): String {
        val body=request.body ?: return ""
        val buffer=okio.Buffer(); body.writeTo(buffer)
        return runCatching { JSONObject(buffer.readUtf8()).optString("series_id") }.getOrDefault("")
    }
    private fun isPlanFrom(request: Request,from: String)=
        request.url.encodedPath==planPath && request.url.queryParameter("from")==from

    @Test fun detailUsesMultiVideoEndpointAndUnwrapsSeriesPayload() {
        var detailRequests=0
        val repo=repository { request ->
            when {
                request.url.encodedPath==detailPath -> {
                    detailRequests++
                    assertEquals("POST",request.method)
                    assertEquals("123",detailBodyId(request))
                    detailVideo("123","测试剧",listOf("900","901")).toString()
                }
                request.url.encodedPath==planPath -> emptyPlan()
                else -> throw IOException("不应再请求旧详情页或补季接口：${request.url}")
            }
        }
        assertEquals(listOf("900","901"),repo.detail("123").episodes)
        assertEquals(1,detailRequests)
    }

    @Test fun detailSortsEpisodesByOfficialIndex() {
        val raw=detailVideo("123","测试剧",listOf("901","900"),listOf(1,0)).toString()
        val repo=repository { request ->
            if(request.url.encodedPath==detailPath) raw else emptyPlan()
        }
        // 官方按 vid_index 给出播放顺序，不能沿用响应数组的偶然顺序。
        assertEquals(listOf("900","901"),repo.detail("123").episodes)
    }

    @Test fun detailRelatedHoistsSameNameSeasonsAndSkipsCurrentSeries() {
        val related=arrayListOf(
            relatedItem("111","别人的剧"),relatedItem("222","测试剧"),
            relatedItem("333","测试剧 第二季"),relatedItem("123","测试剧"))
        val repo=repository { request ->
            when {
                request.url.encodedPath==detailPath -> detailVideo("123","测试剧",listOf("900","901")).toString()
                isPlanFrom(request,"select_panel_recommend") -> planPayload(160,related).toString()
                else -> emptyPlan()
            }
        }
        val detail=repo.detail("123")
        assertEquals(listOf("900","901"),detail.episodes)
        assertEquals(listOf("222","333","111"),detail.related.map { it.id })
    }

    @Test fun detailWithoutRecommendationPayloadStillLoadsEpisodes() {
        val repo=repository { request ->
            when {
                request.url.encodedPath==detailPath -> detailVideo("123","测试剧",listOf("900","901")).toString()
                else -> emptyPlan()
            }
        }
        val detail=repo.detail("123")
        assertEquals(listOf("900","901"),detail.episodes)
        assertTrue(detail.related.isEmpty())
    }

    @Test fun detailCollectsSameNameSeasonsFromWebsiteSearchOrderedBySeason() {
        val repo=repository { request ->
            when {
                request.url.encodedPath==detailPath -> detailVideo("123","测试剧 第二季",listOf("900")).toString()
                isPlanFrom(request,"single_col_same_ip_series") -> planPayload(547,listOf(
                    relatedItem("444","测试剧 第十季"),relatedItem("222","测试剧 第三季"),
                    relatedItem("123","测试剧 第二季"),relatedItem("333","测试剧 第一季"))).toString()
                request.url.encodedPath==planPath -> emptyPlan()
                else -> throw IOException("详情不再回退网页补季：${request.url}")
            }
        }
        val detail=repo.detail("123")
        assertEquals(listOf("333","222","444"),detail.sameName.map { it.id })
    }

    @Test fun detailFillsMissingSeasonsFromSuggestionsLikeSearch() {
        val repo=repository { request ->
            when {
                request.url.encodedPath==detailPath -> detailVideo("123","测试剧 第一季",listOf("900")).toString()
                isPlanFrom(request,"single_col_same_ip_series") -> planPayload(547,listOf(
                    relatedItem("123","测试剧 第一季"),relatedItem("222","测试剧 第二季"),relatedItem("333","测试剧 第三季"))).toString()
                request.url.encodedPath==planPath -> emptyPlan()
                else -> throw IOException("详情不再请求联想或网页补季：${request.url}")
            }
        }
        assertEquals(listOf("222","333"),repo.detail("123").sameName.map { it.id })
    }

    @Test fun detailUsesPlanSeasonsAndCachesEachSeriesIndependently() {
        val repo=repository { request ->
            when {
                request.url.encodedPath==detailPath ->
                    if(detailBodyId(request)=="1") detailVideo("1","测试剧 第一季",listOf("900")).toString()
                    else detailVideo("2","测试剧 第二季",listOf("901")).toString()
                isPlanFrom(request,"single_col_same_ip_series") ->
                    if(request.url.queryParameter("book_id")=="1") planPayload(547,listOf(
                        relatedItem("1","测试剧 第一季"),relatedItem("2","测试剧 第二季"),relatedItem("3","测试剧 第三季"))).toString()
                    else planPayload(547,listOf(relatedItem("2","测试剧 第二季"),relatedItem("1","测试剧 第一季"))).toString()
                request.url.encodedPath==planPath -> emptyPlan()
                else -> throw IOException("详情不再请求网页补季：${request.url}")
            }
        }
        assertEquals(listOf("2","3"),repo.detail("1").sameName.map { it.id })
        assertEquals(listOf("1"),repo.detail("2").sameName.map { it.id })
        assertEquals(repo.detail("1"),repo.cachedDetail("1"))
    }

    @Test fun detailReusesCachedEpisodesWithoutAnotherRequest() {
        var requests=0
        val repo=repository { request ->
            if(request.url.encodedPath==detailPath) {
                requests++; detailVideo("123","测试剧",listOf("900","901")).toString()
            } else emptyPlan()
        }
        val first=repo.detail("123")
        assertEquals(first,repo.detail("123"))
        assertEquals(1,requests)
    }

    private fun currentRealRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-real-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun realRankingParsesTheShortDramaBoard() {
        val payload=rankPayload(1,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",1).put("heatText","9999万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-real-drama",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":1,"updatedText":"今日已更新"}}};</script>"""+currentRealRank(payload)
        }
        val result=repo.realRanking(1)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(1,"9999万热度"),result.ranking!!.positions["123"])
        assertEquals("今日已更新",result.ranking!!.updatedText)
        assertTrue(result.hasMore)
    }

    @Test fun realRankingRejectsTheComicBoardPayload() {
        val repo=repository { currentRank(rankPayload()) }
        assertThrows(IOException::class.java) { repo.realRanking(1) }
    }

    @Test fun renderedRealRankingWorksWithoutStreamedPayload() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-456"><span class="pc-badge-number-example">22</span>
                <h2 id="rank-title-456">短剧</h2><p class="pc-metrics-example"><span>100万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-real-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-real-drama?page=5">5</a></nav>"""
        }
        val result=repo.realRanking(2)
        assertEquals(RankPosition(22,"100万热度"),result.ranking!!.positions["456"])
        assertEquals(5,result.ranking!!.totalPages)
        assertEquals("今日已更新",result.ranking!!.updatedText)
    }

    private fun albumBody(request: Request)=JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    @Test fun albumDetailResolvesEverySubWorkIntoPlayableEpisodes() {
        val albumId="888"
        val first="900"
        val second="901"
        val bodies=mutableListOf<JSONObject>()
        val repo=repository { request ->
            when(request.url.encodedPath) {
                detailPath -> """{"code":0,"data":{"$albumId":{"video_data":{"series_id":"$albumId","content_type":1003}}}}"""
                "/novel/player/album_detail/v1/" -> {
                    val body=albumBody(request); bodies+=body
                    if(body.has("series_ids"))
                        """{"code":0,"data":{"album_data":{},"video_detail_data":{"$second":{"video_data":{"series_id":"$second","video_list":[{"vid":"$second","vid_index":1}]}}}}}"""
                    else
                        """{"code":0,"data":{"album_data":{"album_id_str":"$albumId","title":"我的妹妹不可爱","intro":"介绍","sub_title_list":["更新至2集"],
                            "video_detail_list":[{"series_id_str":"$first"},{"series_id_str":"$second"}]},
                            "video_detail_data":{"$first":{"video_data":{"series_id":"$first","video_list":[{"vid":"$first","vid_index":1}]}}}}}"""
                }
                else -> emptyPlan()
            }
        }
        // 合集详情没有分集，目录项必须逐个补齐 vid 后才算可用分集。
        val detail=repo.detail(albumId)
        assertEquals(listOf(first,second),detail.episodes)
        assertEquals("我的妹妹不可爱",detail.series.title)
        assertEquals("更新至2集",detail.series.badge)
        // 第二次请求显式带上缺失的目录项。
        assertEquals(listOf(second.toLong()),bodies[1].getJSONArray("series_ids").let { list -> (0 until list.length()).map { list.getLong(it) } })
        assertEquals(albumId,bodies[0].getString("album_id"))
    }

    private fun selector(slug: String,name: String,vararg ids: String)=JSONObject().put("selector_item_id",slug).put("show_name",name)
        .put("category_json_ids",JSONArray().apply { ids.forEach { put(it) } })

    private fun categoryHtml(recommendList: JSONArray = JSONArray(),selectorList: JSONArray? = null,apiIds: JSONArray? = null,totalPages: Int = 1): String {
        val section=JSONObject().put("recommendList",recommendList).put("pagination",JSONObject().put("totalPages",totalPages))
        if(selectorList!=null) section.put("selectorList",selectorList)
        if(apiIds!=null) section.put("apiQuery",JSONObject().put("category_json_ids",apiIds))
        return """<script>_ROUTER_DATA={"loaderData":{"category_$":${section}}};</script>"""
    }

    @Test fun genresComeFromTheCategorySelectorAndAreCached() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("/category/real-drama",request.url.encodedPath)
            categoryHtml(selectorList=JSONArray().put(JSONObject().put("row_name","全部分类").put("items",JSONArray()
                .put(selector("romance","爱情","5000")).put(selector("costume","古装","5007")))))
        }
        val genres=repo.genres(ContentType.SHORT)
        assertEquals(listOf("romance","costume"),genres.map { it.slug })
        assertEquals(listOf("爱情","古装"),genres.map { it.name })
        assertEquals(listOf("5000"),genres.first().ids)
        assertEquals(genres,repo.genres(ContentType.SHORT))
        assertEquals(1,requests)
    }

    @Test fun genreHomeUsesTheSlugPathAndValidatesAppliedIds() {
        val rows=JSONArray().put(JSONObject().put("series_id","456").put("series_name","爱情短剧"))
        val repo=repository { request ->
            assertEquals("/category/real-drama/romance",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            categoryHtml(rows,apiIds=JSONArray().put("5000"),totalPages=9)
        }
        val page=repo.home(2,ContentType.SHORT,Genre("romance","爱情",listOf("5000")))
        assertEquals("456",page.items.single().id)
        assertTrue(page.hasMore)
    }

    @Test fun genreHomeFailsWhenTheSiteIgnoresTheFilter() {
        val repo=repository { categoryHtml(apiIds=JSONArray()) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.SHORT,Genre("romance","爱情",listOf("5000"))) }
    }

    @Test fun comicGenresUseTheComicCategoryPath() {
        val rows=JSONArray().put(JSONObject().put("series_id","789").put("series_name","脑洞漫剧"))
        val repo=repository { request ->
            assertEquals("/category/comic-drama/creative",request.url.encodedPath)
            categoryHtml(rows,apiIds=JSONArray().put("755"))
        }
        assertEquals("789",repo.home(1,ContentType.COMIC,Genre("creative","脑洞",listOf("755"))).items.single().id)
    }

    @Test fun suggestParsesQueryResultWordsAndDeduplicates() {
        val repo=repository { request ->
            assertEquals("/reading/bookapi/search/suggest/v1/",request.url.encodedPath)
            assertEquals("jywn",request.url.queryParameter("query"))
            """{"code":0,"data":{"query_key":"jywn","query_result":["家有五女"," 今夜为你无眠 ","家有五女",""]}}"""
        }
        assertEquals(listOf("家有五女","今夜为你无眠"),repo.suggest(" JYWN "))
    }

    @Test fun suggestFallsBackToEmptyOnBlankBodyErrorCodeOrNetworkFailure() {
        var requests=0
        val blank=repository { requests++; "" }
        assertTrue(blank.suggest("jywn").isEmpty())
        val rejected=repository { requests++; """{"code":-1}""" }
        assertTrue(rejected.suggest("jywn").isEmpty())
        val offline=repository { requests++; throw IOException("offline") }
        assertTrue(offline.suggest("jywn").isEmpty())
        assertEquals(3,requests)
    }

    @Test fun suggestAcceptsChineseAndPrefersRichNamesOverNumericKeywords() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("家有",request.url.queryParameter("query"))
            assertEquals("10",request.url.queryParameter("count"))
            assertEquals("1",request.url.queryParameter("search_source"))
            """{"code":0,"data":{"query_result":["旧联想"],"query_result_v2":[{"name":"家有五女","keyword":"1234567890"},{"name":" 家有五女 "},{"name":"家有喜事"}]}}"""
        }
        assertEquals(listOf("家有五女","家有喜事"),repo.suggest(" 家有 "))
        assertEquals(listOf("家有五女","家有喜事"),repo.suggest("家有"))
        assertTrue(repo.suggest("").isEmpty())
        assertTrue(repo.suggest("a".repeat(41)).isEmpty())
        assertTrue(repo.suggest("家\n有").isEmpty())
        assertEquals(1,requests)
    }

    @Test fun seriesTitleKeepsOnlyTheName() {
        // 实测：详情接口把简介拼进了 series_name，标题栏只有一行高，第二行被裁成半截字
        assertEquals("《黄石第五季》",seriesTitle("《黄石第五季》\n蒙大拿荒野深处，达顿家族世代守护的黄石牧场迎来终极考验"))
        assertEquals("狂飙",seriesTitle("狂飙"))
        assertEquals("狂飙",seriesTitle("  狂飙  "))
        // 首行是空的时候往下一行找，不能整个丢空
        assertEquals("狂飙",seriesTitle("\n\n狂飙\n第二行"))
        // 行内多余空白压成一个空格，免得标题栏里出现大段空隙
        assertEquals("The Good Doctor",seriesTitle("The  Good\tDoctor"))
        assertEquals("",seriesTitle(""))
        // 超长标题截断，避免撑出标题栏
        assertEquals(48,seriesTitle("剧".repeat(120)).length)
    }
}
