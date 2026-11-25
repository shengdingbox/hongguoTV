// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** 固定向量来自 openhongguo 的 SDK 实测数据，防止误用旧版摘要与信封。 */
class OpenHongguoPlaybackSigningTest {
    @Test fun field13MatchesSdkVectorsAcrossUnsignedTimestampRange() {
        val vectors = listOf(
            "2f93e6a829558cb92d9bf1114ee7e87d0d6942e1ec3bb789a7a2b5bc1eb87b28bf139057eaa4b162aed4016e99d44b1c006f4a67" to "ea52b57a285f77fe0d88832dd05431040149a450",
            "113ead29810a18a113bd29d54667825fb0c4a318e18087cc03e1d33ffc580b27350f565551e00b0046260b46ab7433f3f0e8ed6d" to "1b06ab72a39593f2927cbef97701cae7c060bdba",
            "797ad5fcedd17ddc3ca34dd3fdd3726420ac074ac98f0cd0f1c653e5925e2d25490976c17e2e215b00a78edcab641f0e3191fa70" to "f44b67b54bb362be5663455a7db1732142657a81",
            "60d494246563cde9c23447dd901ae05dd172ec6c06db9ba4edaee069d723004e921b0c0076745c8e0da7fdfdbdc3333b3fc66c6e" to "346205d60371f41f912cb3518ffe87b590ec98a7",
            "ffeb01ca878d130c7e247e3b42cba5c7ef5606eed20ca237a8c06de5a78bd1c903194fb1e3c3d25a1ef364ef5a9cffb657244369" to "8e09302381ca897db5de727d0e53cc5dcae9de00",
        )
        for ((raw, expected) in vectors) {
            val input = hex(raw)
            assertEquals(expected, OpenHongguoPlaybackSigning.field13(
                input.copyOfRange(0, 32), input.copyOfRange(32, 48), input.i32Le(48),
            ).hex())
        }
    }

    @Test fun playbackKeepsV5MedusaAndRealEnvelopesBoundToFinalTimestampAndBody() {
        val profile = SignerProfile(
            VendorConstants.device + mapOf("device_id" to "1234567890123456", "iid" to "1234567890123457", "cdid" to "cdid"),
            RemoteSigner.DEFAULT_USER_AGENT,
        )
        val store = InMemorySignerProfileStore().apply { save(profile) }
        // 从秒边界附近开始，确保调整分支后所有签名头仍使用最终查询串的时间戳。
        val signer = RemoteSigner(store = store, clock = { 1_793_460_000_999L }, random = java.util.Random(7))
        val body = OpenHongguoPlaybackSigning.body("123")
        val signed = signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/", emptyMap(), body)
        val url = signed.url.toHttpUrl()
        val ts = url.queryParameter("ts")!!.toInt()
        val ticket = url.queryParameter("_rticket")!!.toLong()
        assertEquals(ticket / 1000L, ts.toLong())
        assertEquals(ts.toString(), signed.headers["x-khronos"])
        assertEquals(ticket.toString(), signed.headers["x-ss-req-ticket"])
        assertEquals(digest("MD5", body).hex().uppercase(), signed.headers["x-ss-stub"])
        assertTrue(signed.headers.getValue("x-argus").length > 64)
        assertTrue(signed.headers.getValue("x-ladon").length > 8)
        assertEquals("0405", signed.headers.getValue("x-gorgon").substring(8, 12))
        val prefix = decode64(signed.headers.getValue("x-medusa")).copyOfRange(0, 20)
        for (i in prefix.indices) prefix[i] = (prefix[i].u() xor le32(ts)[i and 3].u()).toByte()
        assertArrayEquals(le32(5) + PlaybackV5Constants.magic, prefix)
        assertArrayEquals(body, signed.body)
        assertEquals("iid", url.queryParameterName(0))
        assertEquals("device_id", url.queryParameterName(1))
        assertEquals("7.3.9.32", url.queryParameter("version_name"))
        assertEquals("23127PN0CC", url.queryParameter("device_type"))
    }
}
