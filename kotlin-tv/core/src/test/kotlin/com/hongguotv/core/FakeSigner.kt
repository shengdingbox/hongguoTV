// SPDX-License-Identifier: GPL-3.0-only.
package com.hongguotv.core

/**
 * 测试替身：不连接局域网签名服务，直接按固定设备档案生成假签名头。
 *
 * 生产代码里所有官方接口都必须经 [RemoteSigner] 取签名，测试用这个子类把
 * “签名服务”留在进程内，避免单测依赖外网和 192.168.3.77。
 */
class FakeSigner(
    private val identity: Map<String, String> =
        linkedMapOf("device_id" to "1905892595378490", "iid" to "1905892595382586"),
    private val agent: String = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC)",
) : RemoteSigner(baseUrl = "http://signer.test") {

    /** 记录被请求签名的完整地址，供断言检查参数拼装。 */
    val signedUrls = mutableListOf<String>()

    override fun profile(): SignerProfile = SignerProfile(identity + ("version_name" to "7.3.9.32"), agent)

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
