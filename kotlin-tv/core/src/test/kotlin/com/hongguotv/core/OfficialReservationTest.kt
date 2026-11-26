// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfficialReservationTest {
    private fun repository(answer: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(answer(chain.request()).toResponseBody()).build()
        }.build(), signer = FakeSigner())

    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun response(type: Int, online: Boolean = false) = """{"code":0,"data":{"video_data":{
        "series_id":"123","series_title":"测试剧三季","content_type":$type,"season_index":3,
        "online_time":1795708800,"video_list":${if (online) """[{"vid":"901","vid_index":1}]""" else "[]"}
    }}}"""

    @Test fun `预约请求与官方路径类型及来源一致且不串行搜索补季`() {
        for (type in listOf(1002, 1007)) {
            var calls = 0
            val repo = repository { request ->
                calls++
                assertEquals("/novel/player/video_detail/v1/", request.url.encodedPath)
                val data = body(request)
                assertEquals(type, data.getInt("content_type"))
                assertEquals("123", data.getString("series_id"))
                assertEquals(5, data.getJSONObject("biz_param").getInt("source"))
                assertEquals(1, data.getJSONObject("biz_param").getInt("detail_page_version"))
                response(type)
            }
            val series = Series("123", "测试剧三季", release = ReleaseSchedule(), contentType = type)
            val result = repo.reservationDetail(series)
            assertTrue(result.episodes.isEmpty())
            assertTrue(result.series.upcoming)
            assertEquals(3, result.series.seasonIndex)
            assertNotNull(result.series.release)
            assertEquals(1, calls)
            assertEquals(result, repo.reservationDetail(series))
            assertEquals(1, calls)
            repo.reservationDetail(series, refresh = true)
            assertEquals(2, calls)
        }
    }

    @Test fun `旧预约没有作品类型时不猜测而让官方按编号识别`() {
        val repo = repository { request ->
            assertFalse(body(request).has("content_type"))
            response(1002)
        }
        assertEquals(1002, repo.reservationDetail(Series("123", "测试剧三季", release = ReleaseSchedule())).series.contentType)
    }

    @Test fun `官方返回正式分集后清除预告类型和排期`() {
        val repo = repository { response(1004, online = true) }
        val result = repo.reservationDetail(Series("123", "测试剧三季", release = ReleaseSchedule(), contentType = 1007))
        assertEquals(listOf("901"), result.episodes)
        assertEquals(1004, result.series.contentType)
        assertFalse(result.series.upcoming)
        assertNull(result.series.release)
    }

    @Test fun `官方季序优先于标题并随本机快照保存`() {
        val third = Series("123", "系列预告", release = ReleaseSchedule(), contentType = 1007, seasonIndex = 3)
        val second = Series("456", "测试剧第九季", contentType = 1004, seasonIndex = 2)
        val restored = Series.fromJson(third.toJson())
        assertEquals(1007, restored.contentType)
        assertEquals(3, seriesSeasonNumber(restored))
        assertEquals(listOf("456", "123"), SeasonSnapshot.ordered(listOf(restored, second)).map { it.id })
    }
}
