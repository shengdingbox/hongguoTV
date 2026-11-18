// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 远程计算-unidbg 的签名器：只校验「怎么和签名服务对话」。
 *
 * 真实算法在服务端（unidbg + libmetasec_ml.so），这里用 MockWebServer 冒充它，
 * 断言送签的 URL、请求体字节、token 与返回头回写都正确。
 */
class UnidbgSignerTest {
    private lateinit var server: MockWebServer

    /** 固定档案，避免测试触发真实的匿名设备注册。 */
    private class FixtureSigner(endpoint: String, token: String = "") :
        UnidbgSigner(endpoint = endpoint, token = token, store = InMemorySignerProfileStore()) {
        override fun profile(): SignerProfile = SignerProfile(
            linkedMapOf(
                "device_id" to "1905892595378490",
                "iid" to "1905892595382586",
                "cdid" to "fixture-cdid",
                "aid" to "8662",
                "version_name" to "7.3.9.32",
                "version_code" to "73932",
            ),
            "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC)",
        )
    }

    private fun signatureResponse() = MockResponse().setResponseCode(200).setBody(
        """{"headers":{"X-Gorgon":"g","X-Khronos":"1700000000","X-Argus":"a","X-Ladon":"l","X-Medusa":"m","X-Helios":"h"}}"""
    )

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `sends the final url and raw body bytes then applies returned headers`() {
        server.enqueue(signatureResponse())
        val signer = FixtureSigner(server.url("/").toString().trimEnd('/'))
        val body = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00)

        val signed = signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/x/v?foo=bar", emptyMap(), body)

        val recorded = server.takeRequest()
        assertEquals("/feature-hash", recorded.path)
        assertEquals("POST", recorded.method)
        val sent = recorded.body.readUtf8()
        // 送签的必须是线上最终地址：业务参数保留、身份字段来自档案、_rticket 已定。
        assertTrue(sent, sent.contains("\"url\":\"https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/x/v?foo=bar"))
        assertTrue(sent, sent.contains("device_id=1905892595378490"))
        assertTrue(sent, sent.contains("iid=1905892595382586"))
        assertTrue(sent, sent.contains("_rticket="))
        // 请求体按原始字节（含 gzip 魔数）提交，签名覆盖的就是实际发出的字节。
        assertTrue(sent, sent.contains("1f8b0800"))

        // 六个签名头原样回写，URL 用签名时的那一份。
        assertEquals(signed.url, sent.substringAfter("\"url\":\"").substringBefore("\""))
        for (name in listOf("x-gorgon", "x-khronos", "x-argus", "x-ladon", "x-medusa", "x-helios")) {
            assertTrue(name, signed.headers.containsKey(name))
        }
        assertEquals("1700000000", signed.headers["x-khronos"])
    }

    @Test fun `sends the token only when configured`() {
        server.enqueue(signatureResponse())
        FixtureSigner(server.url("/").toString().trimEnd('/')).sign("https://a.example/x", emptyMap(), null)
        assertEquals("", server.takeRequest().getHeader("X-Signer-Token"))

        server.enqueue(signatureResponse())
        FixtureSigner(server.url("/").toString().trimEnd('/'), token = "s3cret").sign("https://a.example/x", emptyMap(), null)
        assertEquals("s3cret", server.takeRequest().getHeader("X-Signer-Token"))
    }

    @Test fun `reports an incomplete signature instead of sending unsigned`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"headers":{"X-Medusa":"m"}}"""))
        val signer = FixtureSigner(server.url("/").toString().trimEnd('/'))
        val problem = runCatching { signer.sign("https://a.example/x", emptyMap(), null) }.exceptionOrNull()
        assertTrue(problem is SignatureException)
        assertTrue(problem?.message.orEmpty().contains("完整签名"))
    }

    @Test fun `surfaces the signer refusal code`() {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"error":"openhongguo-signer: signer refused the request","neptune":"-7"}""")
        )
        val signer = FixtureSigner(server.url("/").toString().trimEnd('/'))
        val problem = runCatching { signer.sign("https://a.example/x", emptyMap(), null) }.exceptionOrNull()
        assertTrue(problem is SignatureException)
        assertEquals(true, problem?.message.orEmpty().contains("-7"))
    }

    /** 远程模式必须跳过本机 V5 播放取址路由。 */
    @Test fun `remote modes do not use the local playback route`() {
        val remote = FixtureSigner(server.url("/").toString().trimEnd('/'))
        assertTrue(remote.usesLocalPlaybackAlgorithm.not())
        assertTrue(remote.prefersVideoTab)
        // 本地算法保持原行为。
        assertTrue(RemoteSigner().usesLocalPlaybackAlgorithm)
        assertTrue(RemoteSigner().prefersVideoTab.not())
    }
}
