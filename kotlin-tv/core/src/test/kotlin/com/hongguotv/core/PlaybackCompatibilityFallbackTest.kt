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
    private val playerPath = "/novel/player/video_model/v1/"
    private val episodeId = "123"

    private class FridaFakeSigner : FakeSigner(mapOf(
        "device_id" to "remote-device", "iid" to "remote-install", "cdid" to "remote-cdid",
    )) {
        override val prefersVideoTab get() = true
        override val usesLocalPlaybackAlgorithm get() = false
    }

    private fun model(codec: String, url: String = "https://example.com/$codec.mp4") = JSONObject()
        .put("video_list", JSONArray().put(JSONObject()
            .put("main_url", url)
            .put("video_meta", JSONObject().put("codec_type", codec).put("definition", "720p"))))

    /** 当前主路径是多集 GET，响应按分集 ID 给出模型。 */
    private fun multiResponse(model: JSONObject, id: String = episodeId) = JSONObject().put("code", 0)
        .put("data", JSONObject().put("video_model_data",
            JSONObject().put(id, JSONObject().put("video_model", model)))).toString()

    private fun playerResponse() = JSONObject().put("code", 0)
        .put("data", JSONObject().put("video_model", model("h264"))).toString()

    private fun repository(policy: PlaybackAlgorithmPolicy, clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),
        playbackAlgorithms = policy,
        clock = clock,
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

    @Test fun preferredGetFailureIsPreservedWithoutRescanningAfterPostFallback() {
        val requests = mutableListOf<Request>()
        val repo = repository(policy()) { request ->
            requests += request
            if (request.url.encodedPath == singlePath) """{"code":110}""" else ""
        }
        val failure = assertThrows(ServiceException::class.java) { repo.stream(episodeId) }
        assertEquals(110, failure.code)
        val getRequests = requests.filter { it.method == "GET" }
        // 两条 GET 路径各探测两个主机；POST 全部空响应后仍保留首次 GET 的明确错误码。
        assertEquals(listOf(multiPath, multiPath, singlePath, singlePath), getRequests.map { it.url.encodedPath })
        assertEquals(4, getRequests.map { it.url.host to it.url.encodedPath }.distinct().size)
        assertTrue(requests.any { it.method == "POST" })
    }

    @Test fun missingTargetEpisodeFallsBackToPostInsteadOfCachingAnotherEpisode() {
        val requests = mutableListOf<Request>()
        val repo = repository(policy()) { request ->
            requests += request
            when (request.url.encodedPath) {
                multiPath -> multiResponse(model("h264", "https://example.com/wrong.mp4"), "456")
                "/novel/player/video_model/v1/" -> JSONObject().put("code", 0)
                    .put("data", JSONObject().put("video_model", model("h264"))).toString()
                else -> ""
            }
        }
        repeat(2) { assertEquals("https://example.com/h264.mp4", repo.stream(episodeId).url) }
        // 错集响应不能终止回退；第二次播放复用真正目标集的缓存。
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun warmPostSuccessIsSharedWithPlaybackTransportsAndReboundRepositories() {
        val requests = mutableListOf<Request>()
        val policy = policy()
        val repo = repository(policy) { request ->
            requests += request
            if (request.url.encodedPath == playerPath) playerResponse() else ""
        }
        assertEquals(1, repo.warm(listOf(episodeId)).cached)
        assertEquals(listOf(multiPath, multiPath, singlePath, singlePath, playerPath), requests.map { it.url.encodedPath })
        val children = listOf(repo.childTransport(repo.http), repo.rebind(policy.signer, policy))
        for ((index, child) in children.withIndex()) {
            requests.clear()
            assertEquals("https://example.com/h264.mp4", child.stream((124 + index).toString()).url)
            // 使用未缓存的新分集，确认省掉的是探路请求，而非恰好命中模型缓存。
            assertEquals(listOf(playerPath), requests.map { it.url.encodedPath })
        }
    }

    @Test fun rememberedBackupGetHostIsTriedFirstForAnotherEpisode() {
        val requests = mutableListOf<Request>()
        val repo = repository(policy()) { request ->
            requests += request
            if (request.url.encodedPath == multiPath && request.url.host == "reading.snssdk.com") {
                multiResponse(model("h264"), request.url.queryParameter("video_id")!!)
            } else ""
        }
        repo.stream(episodeId)
        assertEquals(2, requests.size)
        requests.clear()
        assertEquals("https://example.com/h264.mp4", repo.stream("124").url)
        assertEquals(1, requests.size)
        assertEquals("reading.snssdk.com", requests.single().url.host)
        assertEquals(multiPath, requests.single().url.encodedPath)
    }

    @Test fun failedRememberedPostFallsBackAndLearnsNewSuccessfulRoute() {
        for (failure in listOf("", """{"code":100103}""", "invalid-json", "socket-timeout")) {
            var postAvailable = true
            val requests = mutableListOf<Request>()
            val repo = repository(policy()) { request ->
                requests += request
                when {
                    request.url.encodedPath == playerPath -> when {
                        postAvailable -> playerResponse()
                        failure == "socket-timeout" -> throw java.net.SocketTimeoutException("接口超时")
                        else -> failure
                    }
                    request.url.encodedPath == multiPath && !postAvailable ->
                        multiResponse(model("h264"), request.url.queryParameter("video_id")!!)
                    else -> ""
                }
            }
            repo.stream(episodeId)
            postAvailable = false
            requests.clear()
            assertEquals("https://example.com/h264.mp4", repo.stream("124").url)
            assertEquals(playerPath, requests.first().url.encodedPath)
            assertEquals(multiPath, requests.last().url.encodedPath)
            requests.clear()
            repo.stream("125")
            assertEquals(listOf(multiPath), requests.map { it.url.encodedPath })
        }
    }

    @Test fun expiredSuccessUsesDefaultRouteOrderAgain() {
        var now = 1_000L
        val requests = mutableListOf<Request>()
        val repo = repository(policy(), clock = { now }) { request ->
            requests += request
            if (request.url.encodedPath == playerPath) playerResponse() else ""
        }
        repo.stream(episodeId)
        now += 10 * 60 * 1000L
        requests.clear()
        repo.stream("124")
        assertEquals(multiPath, requests.first().url.encodedPath)
        assertEquals(playerPath, requests.last().url.encodedPath)
    }

    @Test fun switchingAlgorithmDoesNotReuseAnotherSignersPreferredPost() {
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.FRIDA,
            fridaFactory = { FridaFakeSigner() },
            unidbgFactory = { FridaFakeSigner() },
        )
        val requests = mutableListOf<Request>()
        val repo = repository(policy) { request ->
            requests += request
            when {
                request.url.encodedPath == playerPath -> playerResponse()
                request.url.encodedPath == multiPath && policy.algorithm == PlaybackAlgorithm.UNIDBG ->
                    multiResponse(model("h264"), request.url.queryParameter("video_id")!!)
                else -> ""
            }
        }
        repo.stream(episodeId)
        policy.select(PlaybackAlgorithm.UNIDBG)
        requests.clear()
        repo.stream("124")
        assertEquals(listOf(multiPath), requests.map { it.url.encodedPath })
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
