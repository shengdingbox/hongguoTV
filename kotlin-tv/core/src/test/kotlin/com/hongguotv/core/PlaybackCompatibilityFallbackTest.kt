// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远程只有 ByteVC2 时，复用已有本地 V5 为同一分集寻找兼容源，不接入额外解码框架。 */
class PlaybackCompatibilityFallbackTest {
    private val singlePath = "/reading/bookapi/video_tab/video_model/v"
    private val v5GetPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
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

    private fun singleResponse(model: JSONObject) = JSONObject().put("code", 0)
        .put("data", JSONObject().put("video_model", model)).toString()

    private fun multiResponse(model: JSONObject, id: String = episodeId) = JSONObject().put("code", 0)
        .put("data", JSONObject().put(id, JSONObject().put("video_model", model))).toString()

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
        localProbeFactory = { FakeSigner() },
    )

    @Test fun remoteByteVc2UsesLocalV5AndCachesCompatibleEpisode() {
        val requests = CopyOnWriteArrayList<Request>()
        val policy = policy()
        val repo = repository(policy) { request ->
            requests += request
            when (request.url.encodedPath) {
                singlePath -> singleResponse(model("bytevc2"))
                v5GetPath -> multiResponse(model("h264"))
                else -> throw AssertionError("不应重复扫描取址路由")
            }
        }
        assertEquals("https://example.com/h264.mp4", repo.stream(episodeId).url)
        val count = requests.size
        assertEquals("https://example.com/h264.mp4", repo.stream(episodeId).url)
        assertEquals(count, requests.size)
        assertEquals(1, requests.count { it.url.encodedPath == singlePath })
        // 本地 V5 使用本地设备，不得复用 Frida 的远端设备资料。
        val local = requests.single { it.url.encodedPath == v5GetPath }
        assertEquals("1905892595378490", local.url.queryParameter("device_id"))
        assertEquals("1", local.url.queryParameter("device_level"))
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
    }

    @Test fun incompatibleLocalGetStillTriesExistingV5Post() {
        val posts = AtomicInteger()
        val repo = repository(policy()) { request ->
            when (request.url.encodedPath) {
                singlePath -> singleResponse(model("bytevc2"))
                v5GetPath -> multiResponse(model("bytevc2"))
                MobilePlayback.MODEL_PATH -> {
                    posts.incrementAndGet()
                    multiResponse(model("bytevc1"))
                }
                else -> throw AssertionError("出现未知取址路由")
            }
        }
        assertEquals("https://example.com/bytevc1.mp4", repo.stream(episodeId).url)
        assertEquals(1, posts.get())
    }

    @Test fun localV5NeverSubstitutesAnotherEpisode() {
        val posts = AtomicInteger()
        val repo = repository(policy()) { request ->
            when (request.url.encodedPath) {
                singlePath -> singleResponse(model("bytevc2"))
                v5GetPath -> multiResponse(model("h264", "https://example.com/wrong-episode.mp4"), "999")
                MobilePlayback.MODEL_PATH -> {
                    posts.incrementAndGet()
                    multiResponse(model("h264", "https://example.com/correct-episode.mp4"))
                }
                else -> throw AssertionError("出现未知取址路由")
            }
        }
        assertEquals("https://example.com/correct-episode.mp4", repo.stream(episodeId).url)
        assertEquals(1, posts.get())
    }

    @Test fun cancellingPlaybackAlsoInterruptsLocalProbe() {
        val scope = RequestScope()
        val localStarted = CountDownLatch(1)
        val localCancelled = CountDownLatch(1)
        val pending = CountDownLatch(1)
        val repo = repository(policy()) { request ->
            when (request.url.encodedPath) {
                v5GetPath -> {
                    localStarted.countDown()
                    try {
                        pending.await()
                        throw AssertionError("取消后不应继续返回片源")
                    } catch (_: InterruptedException) {
                        localCancelled.countDown()
                        throw InterruptedIOException("本地探测已取消")
                    }
                }
                singlePath -> {
                    assertTrue(localStarted.await(1, TimeUnit.SECONDS))
                    scope.cancel()
                    throw InterruptedIOException("播放请求已取消")
                }
                else -> throw AssertionError("取消后不应继续扫描路由")
            }
        }
        try {
            assertThrows(InterruptedIOException::class.java) { scope.run { repo.stream(episodeId) } }
            assertTrue(localCancelled.await(1, TimeUnit.SECONDS))
        } finally {
            pending.countDown()
        }
    }

    @Test fun allByteVc2SourcesRemainIncompatibleAndDoNotSwitchAlgorithm() {
        val policy = policy()
        val requests = CopyOnWriteArrayList<Request>()
        val repo = repository(policy) { request ->
            requests += request
            when (request.url.encodedPath) {
                singlePath -> singleResponse(model("bytevc2"))
                v5GetPath, MobilePlayback.MODEL_PATH -> multiResponse(model("bytevc2"))
                else -> throw AssertionError("出现未知取址路由")
            }
        }
        repeat(2) {
            assertThrows(UnplayableVideoException::class.java) { repo.stream(episodeId) }
        }
        assertEquals(PlaybackAlgorithm.FRIDA, policy.algorithm)
        assertEquals(2, requests.count { it.url.encodedPath == v5GetPath })
        assertEquals(2, requests.count { it.url.encodedPath == MobilePlayback.MODEL_PATH })
        assertEquals(6, requests.count { it.url.encodedPath == singlePath })
        assertTrue(requests.filter { it.method == "GET" }.all { it.url.queryParameter("device_level") == "1" })
    }
}
