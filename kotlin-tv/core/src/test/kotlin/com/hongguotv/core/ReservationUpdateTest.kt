// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class ReservationUpdateTest {
    private val title = "全球死考：刚下刑场你让我去考试三季"
    private val source = Series("100", title, "https://p3.example.com/poster.jpg", release = ReleaseSchedule(), contentType = 1007)
    private val detailPath = "/novel/player/multi_video_detail/v1/"
    private val planPath = "/reading/bookapi/plan/v1/"
    private val searchPath = "/reading/bookapi/search/tab/v"

    private fun repository(answer: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            // 全部请求都必须是内容读取，不允许借查新提交预约、取消或账号同步。
            assertTrue(request.url.encodedPath in listOf(detailPath, planPath, searchPath))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(answer(request).toResponseBody()).build()
        }.build(), signer = FakeSigner())

    private fun id(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getString("series_id")
    private fun video(id: String, episodes: List<String> = emptyList(), name: String = title): String {
        val row = JSONObject().put("series_id", id).put("series_title", name)
            .put("cover", source.cover).put("content_type", if (episodes.isEmpty()) 1007 else 1004)
            .put("video_list", JSONArray(episodes.mapIndexed { index, vid -> JSONObject().put("vid", vid).put("vid_index", index) }))
        return JSONObject().put("code", 0).put("data", JSONObject().put(id, JSONObject().put("video_data", row))).toString()
    }
    private fun card(id: String = "200", name: String = title) = JSONObject().put("series_id", id)
        .put("title", name).put("cover", source.cover).put("content_type", 1004).put("episode_cnt", 2)
    private fun plan(vararg rows: JSONObject) = JSONObject().put("code", 0).put("data", JSONArray().put(
        JSONObject().put("show_type", 547).put("video_data", JSONArray(rows.toList())))).toString()
    private fun search(vararg rows: JSONObject) = JSONObject().put("code", 0).put("search_tabs", JSONArray().put(
        JSONObject().put("tab_type", 11).put("has_more", false).put("data", JSONArray().put(
            JSONObject().put("video_data", JSONArray(rows.toList())))))).toString()

    @Test fun `原编号上线时直接返回实际分集且不指定预告类型`() {
        var requests = 0
        val repo = repository { request ->
            requests++
            assertEquals(detailPath, request.url.encodedPath)
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertFalse(body.has("content_type"))
            video("100", listOf("901", "902"))
        }
        val result = repo.reservationUpdate(source)
        assertEquals(listOf("901", "902"), result.episodes)
        assertEquals(1, requests)
    }

    @Test fun `旧编号失效时从公开系列定位正式作品并保留本机预约`() {
        val repo = repository { request -> when (request.url.encodedPath) {
            detailPath -> if (id(request) == "100") throw IOException("旧预告已失效") else video("200", listOf("901", "902"))
            planPath -> plan(card())
            else -> throw AssertionError("系列匹配成功后不应继续搜索")
        } }
        val result = repo.reservationUpdate(source)
        assertEquals("200", result.series.id)
        val state = LibraryIndex(LibraryData(listOf(source), emptyList(), emptySet(), emptyList(), LibrarySettings()))
        assertTrue(state.observeFavoriteDetail(result, 1, originalId = source.id))
        assertTrue(state.reserved("200"))
        assertTrue(state.availableFavorites().isEmpty())
    }

    @Test fun `预告仍无分集时后台搜索正式编号并确认分集`() {
        val repo = repository { request -> when (request.url.encodedPath) {
            detailPath -> if (id(request) == "100") video("100") else video("200", listOf("901"))
            planPath -> plan()
            searchPath -> search(card())
            else -> throw AssertionError()
        } }
        assertEquals("200", repo.reservationUpdate(source).series.id)
    }

    @Test fun `搜索标题和热度不能代替正式分集确认`() {
        val repo = repository { request -> when (request.url.encodedPath) {
            detailPath -> video(id(request))
            planPath -> plan()
            searchPath -> search(card())
            else -> throw AssertionError()
        } }
        val result = repo.reservationUpdate(source)
        assertEquals("100", result.series.id)
        assertTrue(result.episodes.isEmpty())
    }

    @Test fun `不同季作品不能替换预约`() {
        val repo = repository { request -> when (request.url.encodedPath) {
            detailPath -> { assertEquals("100", id(request)); video("100") }
            planPath -> plan(card(name = title.replace("三季", "四季")))
            searchPath -> search(card(name = title.replace("三季", "四季")))
            else -> throw AssertionError()
        } }
        assertEquals("100", repo.reservationUpdate(source).series.id)
    }

    @Test fun `读取失败不能当作没有更新并进入检查冷却`() {
        val repo = repository { request -> when (request.url.encodedPath) {
            detailPath -> video("100")
            planPath -> throw IOException("读取系列失败")
            searchPath -> search()
            else -> throw AssertionError()
        } }
        assertThrows(IOException::class.java) { repo.reservationUpdate(source) }
    }
}
