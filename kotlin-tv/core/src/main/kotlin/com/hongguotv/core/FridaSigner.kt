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

/** 使用 Android 中红果 App 的设备身份和签名；不注册本机设备，也不混用本机算法。 */
class FridaSigner(
    endpoint: String = DEFAULT_ENDPOINT,
    private val transport: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = System::currentTimeMillis,
) : RemoteSigner(http = transport) {
    companion object {
        private const val DEFAULT_ENDPOINT = "http://taihe.678120.cn:8056"
        private val SIGNATURE_HEADERS = setOf(
            "x-gorgon", "x-khronos", "x-argus", "x-ladon", "x-medusa", "x-helios",
        )
    }

    private val base = endpoint.trimEnd('/').toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Android 签名服务地址无效")
    private var cachedProfile: SignerProfile? = null
    private var profileAt = 0L
    @Volatile private var supportsBodySigning = false
    override val prefersVideoTab get() = true
    override val usesLocalPlaybackAlgorithm get() = false

    @Synchronized
    override fun profile(): SignerProfile {
        cachedProfile?.takeIf { clock() - profileAt in 0L until 60_000L }?.let { return it }
        val info = exchange("info")
        val source = info.optJSONObject("deviceProfile")
            ?: throw SignatureException("Android 签名服务未提供设备资料，请更新服务")
        val params = source.optJSONObject("params")
            ?: throw SignatureException("Android 签名服务的设备资料不完整")
        val values = linkedMapOf<String, String>()
        for (key in params.keys()) {
            if (key in IDENTITY_KEYS && !params.isNull(key)) {
                params.optString(key).takeIf { it.isNotBlank() }?.let { values[key] = it }
            }
        }
        val agent = source.optString("user_agent").ifBlank { source.optString("userAgent") }
        val profile = SignerProfile(values, agent)
        if (!profile.available || values["cdid"].isNullOrBlank() || agent.isBlank()) {
            throw SignatureException("Android 中的红果设备尚未就绪")
        }
        supportsBodySigning = info.optBoolean("supportsBodySigning")
        cachedProfile = profile
        profileAt = clock()
        return profile
    }

    override fun sign(url: String, values: Map<String, String>, body: ByteArray?): SignedRequest {
        val profile = profile()
        if (body != null && !supportsBodySigning) {
            throw SignatureException("Android 签名服务尚不支持请求体签名，请更新服务")
        }
        val target = url.toHttpUrlOrNull() ?: throw SignatureException("请求地址无效")
        val params = linkedMapOf<String, String>()
        for (key in target.queryParameterNames) {
            if (key !in IDENTITY_KEYS) params[key] = target.queryParameter(key).orEmpty()
        }
        for ((key, value) in values) if (key !in IDENTITY_KEYS) params[key] = value
        params.putAll(profile.params)
        val ticket = clock()
        params["ts"] = (ticket / 1000).toString()
        params["_rticket"] = ticket.toString()
        val signedUrl = target.newBuilder().query(null).fragment(null).build().toString() + "?" + encodeQuery(params)
        val payload = JSONObject().put("url", signedUrl)
        // 账号同步的请求体可能已经 gzip，使用 Base64 保留线上原始字节。
        if (body != null) payload.put("body_base64", body.toByteString().base64())
        val result = exchange("sign", payload)
        val signatures = linkedMapOf<String, String>()
        for (key in result.keys()) {
            val name = key.lowercase(Locale.ROOT)
            if (name in SIGNATURE_HEADERS && !result.isNull(key)) {
                result.optString(key).takeIf { it.isNotBlank() }?.let { signatures[name] = it }
            }
        }
        if (!signatures.keys.containsAll(SIGNATURE_HEADERS)) {
            throw SignatureException("Android 签名服务未返回完整签名，请稍后重试")
        }
        val headers = linkedMapOf(
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "User-Agent" to profile.userAgent,
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

    override fun signCatalog(url: String, values: Map<String, String>, body: ByteArray?): SignedRequest {
        val signed = sign(url, values, body)
        return signed.copy(headers = signed.headers + mapOf(
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "${signed.headers["x-ss-req-ticket"]}-0",
        ))
    }

    @Synchronized
    override fun recover() {
        cachedProfile = null
        profileAt = 0
        supportsBodySigning = false
    }

    private fun exchange(path: String, payload: JSONObject? = null): JSONObject {
        val url = base.newBuilder().addPathSegment(path).build()
        val request = Request.Builder().url(url).apply {
            if (payload != null) post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
        try {
            return RequestScope.execute(transport.newCall(request)) { response ->
                if (!response.isSuccessful) throw SignatureException("Android 签名服务不可用（HTTP ${response.code}）")
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) throw SignatureException("Android 签名服务返回空响应")
                try { JSONObject(text) }
                catch (problem: org.json.JSONException) { throw SignatureException("Android 签名服务返回格式异常", problem) }
            }
        } catch (problem: InterruptedIOException) {
            if (Thread.currentThread().isInterrupted || RequestScope.current()?.isCancelled == true) throw problem
            throw SignatureException("远程计算-frida 连接超时，可在设置中改用其他算法", problem)
        } catch (problem: SignatureException) {
            throw problem
        } catch (problem: IOException) {
            throw SignatureException("无法连接远程计算-frida 服务，可在设置中改用其他算法", problem)
        }
    }
}
