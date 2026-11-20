// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAlgorithmRecoveryTest {
    @Test fun secondConsecutiveLocalFailureSwitchesAndRetriesWithUnidbg() {
        var localCalls = 0
        var unidbgCalls = 0
        val local = object : RemoteSigner() {
            override fun profile() = SignerProfile(
                linkedMapOf("device_id" to "1", "iid" to "2", "version_name" to "7.3.9.32"),
                "local",
            )
            override fun sign(url: String, values: Map<String, String>, body: ByteArray?) = SignedRequest(url, emptyMap(), body)
        }
        val remote = object : RemoteSigner() {
            override fun profile() = SignerProfile(
                linkedMapOf("device_id" to "1", "iid" to "2", "version_name" to "7.3.9.32"),
                "remote",
            )
            override fun sign(url: String, values: Map<String, String>, body: ByteArray?) = SignedRequest(url, emptyMap(), body)
            override val prefersVideoTab get() = true
            override val usesLocalPlaybackAlgorithm get() = false
        }
        val policy = PlaybackAlgorithmPolicy(
            PlaybackAlgorithm.LOCAL,
            localFactory = { local },
            unidbgFactory = { remote },
        )
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val body = when {
                url.encodedPath.startsWith("/reading/bookapi/video_tab/") -> {
                    unidbgCalls++
                    playbackModel()
                }
                url.host.contains("fqnovel.com") || url.host == "reading.snssdk.com" -> {
                    localCalls++
                    ""
                }
                else -> playbackModel()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val repository = ContentRepository(http, signer = local, playbackAlgorithms = policy)
        // 第一次和第二次本地取址各失败一次；第二次失败后切到 unidbg 并立即重试。
        assertTrue(runCatching { repository.stream("123") }.isFailure)
        val result = repository.stream("123")
        assertEquals(PlaybackAlgorithm.UNIDBG, policy.algorithm)
        assertTrue(unidbgCalls > 0)
        assertEquals("https://example.com/a.mp4", result.url)
    }

    private fun playbackModel() =
        """{"code":0,"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.com/a.mp4","video_meta":{"definition":"1080p","codec_type":"h264"}}]}}}}"""
}
