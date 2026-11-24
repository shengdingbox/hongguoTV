// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 取址只使用用户选择的算法，兼容性重试不跨算法，完整失败统一累计一次。 */
class PlaybackCompatibilityFallbackTest {
    private val multiPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val singlePath = "/reading/bookapi/video_tab/video_model/v"
    private val episodeId = "123"

    private class FridaFakeSigner : FakeSigner(mapOf(
        "device_id" to "remote-device", "iid" to "remote-install", "cdid" to "remote-cdid",
    )) {
        override val prefersVideoTab get() = true
        override val usesLocalPlaybackAlgorithm get() = false
    }

    private fun model(codec: String) = JSONObject()
        .put("video_list", JSONArray().put(JSONObject()
            .put("main_url", "https://example.com/$codec.mp4")
            .put("video_meta", JSONObject().put("codec_type", codec).put("definition", "720p"))))

    /** 当前主路径是多集 GET，响应按分集 ID 给出模型。 */
    private fun multiResponse(model: JSONObject) = JSONObject().put("code", 0)
        .put("data", JSONObject().put("video_model_data",
            JSONObject().put(episodeId, JSONObject().put("video_model", model)))).toString()

    private fun repository(policy: PlaybackAlgorithmPolicy, respond: (Request) -> String) = ContentRepository(
        http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),
        playbackAlgorithms = policy,
    )

    private fun policy() = PlaybackAlgorithmPolicy(
        PlaybackAlgorithm.FRIDA,
        fridaFactory = { FridaFakeSigner() },
        localFactory = { throw AssertionError("不应自动使用本地算法") },
        unidbgFactory = { throw AssertionError("不应自动使用 unidbg 算法") },
    )

    @Test fun compatibleRemotePlaybackUsesOnlySelectedAlgorithmAndCachesEpisode() {
        val requests = mutableListOf<Request>()
        val policy = policy()
        val repo = repository(policy) { request ->
            requests += request
            assertEquals(multiPath, request.url.encodedPath)
            multiResponse(model("h264"))
        }
        repeat(2) { assertEquals("https://example.com/h264.mp4", repo.stream(episodeId).url) }
        assertEquals(1, requests.size)
        assertEquals("remote-device", requests.single().url.queryParameter("device_id"))
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
    }

    @Test fun incompatibleSourcesCountOneFailurePerStreamAndNeverUseOtherAlgorithms() {
        val policy = policy()
        val requests = mutableListOf<Request>()
        val repo = repository(policy) { request ->
            requests += request
            assertEquals(multiPath, request.url.encodedPath)
            multiResponse(model("bytevc2"))
        }
        repeat(2) {
            assertThrows(UnplayableVideoException::class.java) { repo.stream(episodeId) }
            assertNull(policy.unavailable)
        }
        assertThrows(UnplayableVideoException::class.java) { repo.stream(episodeId) }
        assertEquals(PlaybackAlgorithm.FRIDA, policy.unavailable?.algorithm)
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
        // 每次完整取址内部有 3 轮不兼容重试，三次 stream 共 9 个请求。
        assertEquals(9, requests.size)
        assertTrue(requests.all { it.url.queryParameter("device_level") == "1" })
        // 主路径已命中多集模型，不应再消耗单集 GET 或旧 POST 路由的配额。
        assertTrue(requests.none { it.url.encodedPath == singlePath })
    }

    @Test fun networkFailuresAlsoRequestManualSelectionAfterThreeAttempts() {
        val policy = policy()
        val repo = repository(policy) { throw IOException("网络连接失败") }
        repeat(2) {
            assertThrows(IOException::class.java) { repo.stream(episodeId) }
            assertNull(policy.unavailable)
        }
        assertThrows(IOException::class.java) { repo.stream(episodeId) }
        assertEquals(PlaybackAlgorithm.FRIDA, policy.unavailable?.algorithm)
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
    }

    @Test fun cancellingPlaybackDoesNotCountAsAddressFailure() {
        val policy = policy()
        repeat(3) {
            val scope = RequestScope()
            val repo = repository(policy) {
                scope.cancel()
                throw InterruptedIOException("播放请求已取消")
            }
            assertThrows(InterruptedIOException::class.java) { scope.run { repo.stream(episodeId) } }
        }
        assertNull(policy.unavailable)
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
    }

    @Test fun localFailureNeverUsesUnidbgForRequestFallback() {
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.LOCAL,
            localFactory = { FridaFakeSigner() },
            unidbgFactory = { throw AssertionError("不应使用其他算法兜底") },
        )
        val repo = repository(policy) { "" }
        repeat(3) { assertThrows(IOException::class.java) { repo.stream(episodeId, playback = true) } }
        assertEquals(PlaybackAlgorithm.LOCAL, policy.algorithm)
        assertEquals(PlaybackAlgorithm.LOCAL, policy.unavailable?.algorithm)
    }
}
