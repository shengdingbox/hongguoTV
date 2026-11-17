// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 测试替身：不连外网、不跑真实注册，直接按固定设备档案生成假签名头。
 *
 * 真实签名算法与设备注册在 [LocalSignerTest] 里用官方测试向量单独校验；
 * 其他单测只关心「参数怎么拼、URL 怎么发」，用这个替身即可。
 */
class FakeSigner(
    private val identity: Map<String, String> =
        linkedMapOf("device_id" to "1905892595378490", "iid" to "1905892595382586"),
    private val agent: String = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC)",
) : RemoteSigner() {

    /** 记录被请求签名的完整地址，供断言检查参数拼装。 */
    val signedUrls = mutableListOf<String>()

    override fun profile(): SignerProfile = SignerProfile(identity + ("version_name" to "7.3.9.32"), agent, mapOf("ttreq" to "fixture-guest"))

    override fun recover() = Unit

    override fun sign(url: String, values: Map<String, String>, body: ByteArray?): SignedRequest {
        val merged = linkedMapOf<String, String>()
        for ((key, value) in values) if (key !in IDENTITY_KEYS) merged[key] = value
        merged.putAll(identity)
        merged["_rticket"] = "1700000000000"
        val signedUrl = url.substringBefore('?') + "?" + encodeQuery(merged)
        signedUrls += signedUrl
        return SignedRequest(
            signedUrl,
            linkedMapOf(
                "X-Gorgon" to "8404fake",
                "X-Khronos" to "1700000000",
                "X-Argus" to "Argus",
                "X-Ladon" to "Ladon",
                "X-Medusa" to "Medusa",
                "X-Helios" to "Helios",
            ),
            body,
        )
    }
}
