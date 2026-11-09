// SPDX-License-Identifier: GPL-3.0-only.
package com.hongguotv.core

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 签名服务不可用或返回的数据不符合约定。 */
class SignatureException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 一次签名结果：完整请求地址（参数已按设备档案补全）与需要回写的请求头。 */
data class SignedRequest(val url: String, val headers: Map<String, String>, val body: ByteArray? = null)

/** 签名服务里的匿名设备档案。 */
data class SignerProfile(val params: Map<String, String>, val userAgent: String) {
    val deviceId get() = params["device_id"].orEmpty()
    val installId get() = params["iid"].orEmpty()
    /** 档案声明的客户端版本；查询串与 X-Ladon 都按它签名。 */
    val versionName get() = params["version_name"].orEmpty()
    val available get() = deviceId.isNotBlank() && installId.isNotBlank()
}

/**
 * 红果签名服务客户端。
 *
 * 电视端不再内置任何签名算法：访问红果官方接口前，先向局域网签名服务
 * （默认 `http://192.168.3.77:8901`，葫芦娃项目同款）取设备档案与签名头。
 *
 * - `GET /info`：已注册的匿名设备档案（`deviceProfile.params` + `user_agent`）。
 *   签名服务会拒绝与档案不符的 `device_id` / `iid`，所以请求参数必须用档案里的身份。
 * - `POST /sign`：传入完整 URL，返回六个签名头（Gorgon/Khronos/Argus/Ladon/Medusa/Helios）。
 * - `POST /recover`：设备身份被平台拒绝时，请服务更换一台匿名设备。
 *
 * 平台按「注册过的 device_id + iid」放行，未注册的随机身份只会拿到 HTTP 200 空响应。
 */
open class RemoteSigner(
    /** 签名服务地址；留空表示未配置。 */
    val baseUrl: String = DEFAULT_BASE_URL,
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build(),
) {
    companion object {
        /** 与葫芦娃后端使用同一台签名服务。 */
        const val DEFAULT_BASE_URL = "http://192.168.3.77:8901"
        private const val MAX_BODY = 1 shl 20
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private val HEADER_NAMES =
            setOf("X-Gorgon", "X-Khronos", "X-Argus", "X-Ladon", "X-Medusa", "X-Helios")
        /** 这些字段必须与设备档案一致，本机传了也不采纳。 */
        val IDENTITY_KEYS =
            setOf(
                "device_id", "iid", "cdid", "openudid", "ac", "channel", "aid", "app_name",
                "version_code", "version_name", "manifest_version_code", "update_version_code",
                "pv_player", "device_platform", "os", "os_api", "os_version", "ssmix", "device_type",
                "device_brand", "language", "resolution", "dpi", "host_abi", "dragon_device_type",
                "user_is_login", "compliance_status", "need_personal_recommend", "player_so_load",
                "is_android_pad_screen",
            )
        /** 这些是本机业务参数，档案里有同名值时以本机为准。 */
        private val BUSINESS_KEYS =
            setOf(
                "_rticket", "query", "count", "offset", "tab_type", "bookshelf_search_plan",
                "use_correct", "user_is_login", "search_id", "passback", "video_id", "video_id_type",
                "device_level", "need_all_video_definition", "need_mp4_align", "use_os_player",
                "video_platform", "source", "from_video_id", "video_series_id", "screen_width_px",
                "is_horizontal_screen", "cursor", "start_time", "end_time", "item_id",
            )
    }

    /** 最近的 `/info` 结果；`/recover` 后会失效重取。 */
    @Volatile private var profileCache: SignerProfile? = null

    val configured get() = baseUrl.isNotBlank()

    @Synchronized
    open fun profile(): SignerProfile {
        profileCache?.let { return it }
        val parsed = parseProfile(parse(get("/info")))
        profileCache = parsed
        return parsed
    }

    /** 丢掉缓存的设备档案，下次请求重新向签名服务取。 */
    fun invalidate() {
        profileCache = null
    }

    /**
     * 请签名服务更换匿名设备。成功与否都清掉本地档案缓存，
     * 失败本身不外抛——由调用方决定是否带着旧档案再试一次。
     */
    open fun recover() {
        if (!configured) return
        try {
            execute(Request.Builder().url(join("/recover")).post(body("{}")).build())
        } catch (_: IOException) {
            // 恢复失败保留原档案，播放链路会走自己的兜底。
        } finally {
            invalidate()
        }
    }

    /**
     * 为 [url] 生成签名。
     *
     * [values] 里的业务参数保留本机取值；身份字段一律用设备档案覆盖，否则签名服务会拒绝。
     * [SignedRequest.url] 才是实际要请求的地址（含补全后的查询串）。
     */
    open fun sign(url: String, values: Map<String, String>, body: ByteArray? = null): SignedRequest {
        if (!configured) throw SignatureException("未配置签名服务地址")
        val profile = profile()
        val signedUrl = buildUrl(url, values, profile)
        val json = parse(post("/sign", JSONObject().put("url", signedUrl).toString()))
        val headers = linkedMapOf<String, String>()
        for (name in HEADER_NAMES) {
            val value = json.optString(name, "")
            if (value.isNotBlank()) headers[name] = value
        }
        if (headers.isEmpty()) throw SignatureException("签名服务未返回签名头")
        return SignedRequest(signedUrl, headers, body)
    }

    /** 用设备档案拼查询串：业务参数本机优先，身份参数一律以档案为准。 */
    private fun buildUrl(url: String, values: Map<String, String>, profile: SignerProfile): String {
        val merged = linkedMapOf<String, String>()
        for ((key, value) in values) if (key !in IDENTITY_KEYS) merged[key] = value
        for ((key, value) in profile.params) {
            if (value.isBlank()) continue
            if (key in BUSINESS_KEYS && merged.containsKey(key)) continue
            merged[key] = value
        }
        merged["_rticket"] = System.currentTimeMillis().toString()
        return url.substringBefore('?') + "?" + encodeQuery(merged)
    }

    private fun parseProfile(json: JSONObject): SignerProfile {
        val device =
            json.optJSONObject("deviceProfile")
                ?: json.optJSONObject("device")
                ?: throw SignatureException("签名服务未返回设备档案")
        val params = device.optJSONObject("params") ?: throw SignatureException("设备档案缺少 params")
        val values = linkedMapOf<String, String>()
        for (key in params.keys()) values[key] = params.optString(key, "")
        val profile = SignerProfile(values, device.optString("user_agent", ""))
        if (!profile.available) throw SignatureException("签名服务设备档案不完整")
        return profile
    }

    private fun get(path: String): String =
        execute(Request.Builder().url(join(path)).get().build())

    private fun post(path: String, payload: String): String =
        execute(Request.Builder().url(join(path)).post(body(payload)).build())

    private fun body(value: String) = value.toRequestBody(JSON_TYPE.toMediaType())

    private fun execute(request: Request): String =
        RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw SignatureException("签名服务返回 HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw SignatureException("签名服务返回空响应")
            if (bytes.size > MAX_BODY) throw SignatureException("签名服务响应过大")
            String(bytes, Charsets.UTF_8)
        }

    private fun parse(raw: String): JSONObject =
        runCatching { JSONObject(raw) }
            .getOrElse { throw SignatureException("签名服务返回的数据格式不正确", it) }

    private fun join(path: String) = baseUrl.trimEnd('/') + path
}

/** 表单式查询串编码；与签名服务、红果接口对空格的约定一致（`+` 改写为 `%20`）。 */
internal fun encodeQuery(values: Map<String, String>): String =
    values.entries.joinToString("&") { (key, value) -> "${encodePart(key)}=${encodePart(value)}" }

internal fun encodePart(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")
