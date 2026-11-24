// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * 使用 hongguosign（unidbg 跑真实 libmetasec_ml.so）完成签名。
 *
 * 与 [FridaSigner] 的区别在服务和协议，不在设备身份：
 * - 设备档案仍由本机 [RemoteSigner] 注册并持久化，[sign] 会先补全身份字段再送去签名；
 * - 签名服务只负责把完整的 URL（含查询串）与请求体字节换成六个签名头。
 *
 * 服务接口兼容 openhongguo-signer：`POST /feature-hash`
 * 请求 `{"url": "...", "body_hex": "..."}`，返回 `{"headers": {...}}`。
 * 服务在 `SIGNER_TOKEN` 非空时要求 `X-Signer-Token` 头。
 */
open class UnidbgSigner(
    endpoint: String = DEFAULT_ENDPOINT,
    /** 与服务端 `SIGNER_TOKEN` 一致；留空表示服务未开启鉴权。 */
    private val token: String = DEFAULT_TOKEN,
    private val transport: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
    /** 匿名设备档案持久化；与本地算法共用同一份档案。 */
    store: SignerProfileStore = InMemorySignerProfileStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) : RemoteSigner(http = transport, store = store, clock = clock) {
    companion object {
        /**
         * 菜鸡公网签名服务地址。
         *
         * 链路：公网 8028 → 飞牛 frpc-caiji → 192.168.3.77:8006（hongguosign）。
         * 与 frida 模式共用同一台转发服务器，只是端口不同。
         */
        private const val DEFAULT_ENDPOINT = "http://taihe.678120.cn:8028"
        private const val DEFAULT_TOKEN = ""

        /** 签名服务返回的签名头，全部小写比较。 */
        private val SIGNATURE_HEADERS = setOf(
            "x-gorgon", "x-khronos", "x-argus", "x-ladon", "x-medusa", "x-helios",
        )
    }

    private val base = endpoint.trimEnd('/').toHttpUrlOrNull()
        ?: throw IllegalArgumentException("unidbg 签名服务地址无效")

    /**
     * 播放取址优先走 video_tab GET：
     * unidbg 返回的是通用六头签名，本机 V5 的 Helios/Medusa 专用口径不适用。
     * 这条路由与「远程计算-frida」一致，也是平台对匿名设备更宽容的一条。
     */
    override val prefersVideoTab get() = true

    /** 与远程计算一致：V5 单集路由的签名口径只适用于本机算法。 */
    override val usesLocalPlaybackAlgorithm get() = false

    /**
     * 签名前先按 [RemoteSigner] 的规则补齐设备身份，再把最终 URL 和原始请求体交给签名服务。
     *
     * 签名服务只产出签名头，不改 URL：`_rticket` / `ts` 必须在送签前定好，
     * 服务端签的就是线上实际发出的字节。
     */
    override fun sign(url: String, values: Map<String, String>, body: ByteArray?): SignedRequest {
        val profile = profile()
        if (!profile.available) throw SignatureException("本机匿名设备尚未就绪")
        val target = url.toHttpUrlOrNull() ?: throw SignatureException("请求地址无效")

        // 与 RemoteSigner 一致：业务参数本机优先，身份字段一律以设备档案为准。
        val params = linkedMapOf<String, String>()
        for (key in target.queryParameterNames) {
            if (key !in IDENTITY_KEYS) params[key] = target.queryParameter(key).orEmpty()
        }
        for ((key, value) in values) if (key !in IDENTITY_KEYS) params[key] = value
        for ((key, value) in profile.params) {
            if (value.isBlank() || key in params) continue
            params[key] = value
        }
        val ticket = clock()
        params["ts"] = (ticket / 1000).toString()
        params["_rticket"] = ticket.toString()
        val signedUrl = target.newBuilder().query(null).fragment(null).build().toString() + "?" + encodeQuery(params)

        val signatures = featureHash(signedUrl, body)
        val headers = linkedMapOf(
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-xs-from-web" to "0",
            "x-ss-req-ticket" to ticket.toString(),
            "x-tt-request-tag" to "t=0;n=0",
            "sdk-version" to "2",
            "passport-sdk-version" to "50561",
            "x-vc-bdturing-sdk-version" to "3.7.2.cn",
        )
        headers.putAll(signatures)
        if (body != null) {
            headers["Content-Type"] = "application/json; charset=UTF-8"
            headers["x-ss-stub"] = digest("MD5", body).hex().uppercase(Locale.ROOT)
        }
        return SignedRequest(signedUrl, headers, body)
    }

    /** 书城推荐：透明六头签名之外，还要按 iOS SDK 的约定补上游客票据与统计头。 */
    override fun signCatalog(url: String, values: Map<String, String>, body: ByteArray?): SignedRequest {
        val signed = sign(url, values, body)
        val profile = profile()
        val ticket = signed.headers["x-ss-req-ticket"].orEmpty().ifBlank { clock().toString() }
        val headers = signed.headers.toMutableMap()
        headers["lc"] = "101"
        headers["x-ss-dp"] = profile.params["aid"].orEmpty().ifBlank { "8662" }
        headers["x-vc-bdturing-sdk-version"] = "4.0.3.cn"
        headers["x-reading-request"] = "$ticket-0"
        if (profile.cookies["ttreq"].isNullOrBlank()) return signed.copy(headers = headers)
        headers["Cookie"] = "passport_csrf_token=; passport_csrf_token_default=; " +
            "store-region=${profile.cookies["store-region"].orEmpty()}; " +
            "store-region-src=${profile.cookies["store-region-src"].orEmpty()}; " +
            "install_id=${profile.installId}; ttreq=${profile.cookies["ttreq"]}"
        return signed.copy(headers = headers)
    }

    /**
     * 调用 `/feature-hash`。
     *
     * 服务端串行计算并限制排队时间；传递当前请求剩余预算，
     * 避免取址轮次已经超时后，服务端仍继续处理排队请求。
     */
    private fun featureHash(url: String, body: ByteArray?): Map<String, String> {
        val payload = JSONObject().put("url", url)
        // 签名必须覆盖线上实际发送的字节（例如 gzip 之后），所以用十六进制原样提交。
        if (body != null && body.isNotEmpty()) payload.put("body_hex", body.toByteString().hex())
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegment("feature-hash").build())
            .header("X-Signer-Timeout-Ms", (RequestScope.remainingMillis() ?: 30_000L)
                .coerceIn(1L, 30_000L).toString())
            .apply { if (token.isNotBlank()) header("X-Signer-Token", token) }
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val result = try {
            RequestScope.execute(transport.newCall(request)) { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                    val neptune = runCatching { JSONObject(text).optString("neptune") }.getOrDefault("")
                    throw SignatureException(
                        when {
                            response.code == 429 || response.code == 503 -> "unidbg 签名服务繁忙，请稍后重试"
                            response.code == 504 -> "unidbg 签名计算超时，请稍后重试"
                            reason.isBlank() -> "unidbg 签名服务不可用（HTTP ${response.code}）"
                            neptune.isNotBlank() -> "unidbg 签名服务拒绝了请求（X-Neptune $neptune）"
                            else -> "unidbg 签名服务返回错误：$reason"
                        }
                    )
                }
                if (text.isBlank()) throw SignatureException("unidbg 签名服务返回空响应")
                try { JSONObject(text) }
                catch (problem: org.json.JSONException) { throw SignatureException("unidbg 签名服务返回格式异常", problem) }
            }
        } catch (problem: InterruptedIOException) {
            if (Thread.currentThread().isInterrupted || RequestScope.current()?.isCancelled == true) throw problem
            throw SignatureException("unidbg 签名服务连接超时，可在设置中改用其他算法", problem)
        } catch (problem: SignatureException) {
            throw problem
        } catch (problem: IOException) {
            throw SignatureException("无法连接 unidbg 签名服务，可在设置中改用其他算法", problem)
        }

        val returned = result.optJSONObject("headers")
            ?: throw SignatureException("unidbg 签名服务未返回签名头")
        val signatures = linkedMapOf<String, String>()
        for (key in returned.keys()) {
            val name = key.lowercase(Locale.ROOT)
            if (name !in SIGNATURE_HEADERS) continue
            returned.optString(key).takeIf { it.isNotBlank() }?.let { signatures[name] = it }
        }
        if (!signatures.keys.containsAll(SIGNATURE_HEADERS)) {
            throw SignatureException("unidbg 签名服务未返回完整签名，请稍后重试")
        }
        return signatures
    }
}
