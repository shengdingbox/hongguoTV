// SPDX-License-Identifier: GPL-3.0-only
// GPL-3.0-only derived; see the README 来源与声明 section.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 原生算法的 V5 取址通道；使用共用匿名身份，签名覆盖实际发送的请求体字节。 */
internal object MobilePlayback {
    const val MODEL_BASE = "https://api5-normal-sinfonlinea.fqnovel.com"
    const val PRELOAD_BASE = "https://reading.snssdk.com"
    const val GET_TAB_BASE = "https://api5-normal-sinfonlineb.fqnovel.com"
    // 旧版播放协议使用无场景后缀的路径，保留为兜底；当前官方客户端使用 VIDEO_MODEL_PATH。
    const val MODEL_PATH = "/novel/player/multi_video_model/v1/"
    const val PRELOAD_PATH = "/novel/player/multi_video_model/preload/v1/"
    const val PLAYER_PATH = "/novel/player/multi_video_model/player/v1/"
    const val VIDEO_MODEL_PATH = "/novel/player/video_model/v1/"
    const val GET_TAB_PATH = "/reading/bookapi/video_tab/multi_video_model/v1/"
    const val GROUP_SHORT = "1004"
    const val GROUP_PUGC = "1001"

    /**
     * 官方 7.4.5.32 App 画像。平台按「请求画像 + 签名」双重校验：
     * 旧的 `vivo_8662_64 / PGT-AN10 / Android 12` 与 `field21=377` 会让播放接口
     * 返回 HTTP 200 + 0 字节。该画像必须与 [PlaybackV5Signer.MEDUSA_FIELD21_74532]
     * 成对使用；设备相关的 iid / device_id / cdid 仍在运行时注入。
     */
    private val APP_QUERY = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread",
        "version_code" to "74532", "version_name" to "7.4.5.32",
        "update_version_code" to "74532", "manifest_version_code" to "74532",
        "os" to "android", "ssmix" to "a", "channel" to "update_64",
        "device_platform" to "android", "device_type" to "25053RT47C",
        "device_brand" to "Redmi", "os_version" to "16", "os_api" to "36",
        "ac" to "wifi", "language" to "zh", "host_abi" to "arm64-v8a",
    )
    private const val PLAYBACK_UA =
        "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; " +
            "Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    /** 当前官方画像的查询参数；设备身份由注册档案补充。 */
    private fun appQuery(profile: SignerProfile): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        putAll(APP_QUERY)
        put("iid", profile.installId)
        put("device_id", profile.deviceId)
        put("cdid", profile.params["cdid"].orEmpty())
    }

    /** 取址分组：普通短剧在 1004，合集分集属于 PUGC 的 1001，分组错误会回 101000。
     *
     *  [wanted] 首项是当前要播放的集，其余是顺带预取的相邻集（调用方最多再带 2 集）：
     *  mixed_video_id_map 原生支持多 ID，一次 POST 的响应里每集各有一份模型，
     *  调用方把顺带集写入模型缓存后，切集即可零请求命中。返回映射至少包含首项；
     *  顺带集缺失或结构不合格时直接略过，不影响当前集。 */
    fun models(
        wanted: List<String>,
        profile: SignerProfile,
        http: OkHttpClient,
        now: Long,
        group: String = GROUP_SHORT,
        host: String = MODEL_BASE.removePrefix("https://"),
    ): Map<String, JSONObject> {
        // 首项是当前集；上限 3 集与参考实现的顺带窗口一致，防止调用方传入整窗拖大请求体。
        val ids = wanted.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct().take(3)
        require(ids.isNotEmpty()) { "没有可请求的剧集 ID" }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        if (!profile.available || profile.params["cdid"].isNullOrBlank()) {
            throw SignatureException("本机匿名设备尚未就绪")
        }
        val body = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"$group":${JSONArray(ids)}}}"""
        ).toString().toByteArray(Charsets.UTF_8)
        // 旧 POST 保留 gzip 口径：签名绑定压缩后的实际发送字节，x-ss-stub 也取自压缩体。
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(body) } }.toByteArray()
        // 参考项目会按会话健康度在同族播放域名之间轮换；签名覆盖最终请求地址，不能先签名再改 host。
        val signed = PlaybackV5Signer.shared.sign(
            "https://$host$MODEL_PATH", appQuery(profile), gzip, now,
            PlaybackV5Signer.MEDUSA_FIELD21_74532,
        )
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json; charset=utf-8")
            .header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(gzip)
                .joinToString("") { "%02X".format(it) })
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post(gzip.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val remaining = RequestScope.remainingMillis()
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).apply {
            if (remaining != null) {
                val budget = remaining.coerceAtLeast(1L)
                connectTimeout(budget.coerceAtMost(4_000L), TimeUnit.MILLISECONDS)
                readTimeout(budget.coerceAtMost(4_000L), TimeUnit.MILLISECONDS)
                callTimeout(budget, TimeUnit.MILLISECONDS)
            }
        }.build()
        return RequestScope.execute(transport.newCall(request)) { response ->
            if (!response.isSuccessful) throw ContentHttpException(response.code)
            val responseBody = response.body ?: throw IOException("播放信息格式异常")
            // 分块响应/压缩响应的 contentLength 可能是 -1 或压缩长度，不能据此拒绝；
            // 只在实际读取时限制体积，避免正常模型被误判为“过大”。
            val limit = 2 * 1024 * 1024L
            val source = responseBody.source()
            source.request(limit + 1)
            if (source.buffer.size > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) throw EmptyPlaybackResponseException()
            try {
                val root = JSONObject(text)
                val code = root.optInt("Code", root.optInt("code", 0))
                if (code != 0) throw ServiceException(code)
                val data = root.optJSONObject("data") ?: throw IOException("播放信息为空")
                val entries = data.optJSONObject(group) ?: data
                // 当前集必须存在且结构可用；顺带集宽松处理，缺一条不影响本次播放。
                val current = entries.optJSONObject(ids.first()) ?: throw IOException("播放信息未包含指定剧集")
                val out = linkedMapOf<String, JSONObject>()
                parseEntry(current, data, strict = true)?.let { out[ids.first()] = it }
                for (extra in ids.drop(1)) {
                    val entry = entries.optJSONObject(extra) ?: continue
                    runCatching { parseEntry(entry, data, strict = false) }.getOrNull()?.let { out[extra] = it }
                }
                if (out.isEmpty()) throw IOException("播放信息没有可用资源")
                out
            } catch (problem: JSONException) {
                throw IOException("播放信息格式异常", problem)
            }
        }
    }

    /**
     * 构造旧 `multi_video_model` 系列的 POST 请求。
     *
     * 与 [videoModelRequest] 的差异是请求体仍使用 `biz_param + mixed_video_id_map`；
     * 该形态保留给 `preload` / `player` / 平铺 `v1` 兜底，不能用于新端点。
     */
    fun modelRequest(
        id: String,
        profile: SignerProfile,
        now: Long,
        path: String,
        host: String,
        group: String = GROUP_SHORT,
        deviceLevel: Int = 1,
    ): Request {
        require(id.matches(Regex("[0-9]{1,30}")))
        if (!profile.available || profile.params["cdid"].isNullOrBlank()) {
            throw SignatureException("本机匿名设备尚未就绪")
        }
        val body = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":$deviceLevel,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"$group":${JSONArray(listOf(id))}}}"""
        ).toString().toByteArray(Charsets.UTF_8)
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(body) } }.toByteArray()
        val values = appQuery(profile).apply { if (path.contains("preload")) put("dr_scene", "preload") }
        val signed = PlaybackV5Signer.shared.sign(
            "https://$host$path", values, gzip, now,
            PlaybackV5Signer.MEDUSA_FIELD21_74532,
        )
        return Request.Builder().url(signed.url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(gzip)
                .joinToString("") { "%02X".format(it) })
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post(gzip.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /**
     * 官方现行 `POST /novel/player/video_model/v1/` 取址入口。
     *
     * 这条端点只接受**扁平请求体**：`video_id` / `device_level` /
     * `need_all_video_definition` 等字段直接放在顶层。继续使用旧端点的
     * `biz_param + mixed_video_id_map` 会被回 `code=100001 video_id invalid`。
     *
     * 请求体先 gzip 再签名，`x-ss-stub` 也取压缩后字节的 MD5；这是 v5
     * 协议与旧明文 POST 的关键差异。
     */
    fun videoModelRequest(
        id: String,
        profile: SignerProfile,
        now: Long,
        host: String = MODEL_BASE.removePrefix("https://"),
        deviceLevel: Int = 1,
    ): Request {
        require(id.matches(Regex("[0-9]{1,30}")))
        if (!profile.available || profile.params["cdid"].isNullOrBlank()) {
            throw SignatureException("本机匿名设备尚未就绪")
        }
        val payload = JSONObject()
            .put("video_id", id)
            .put("video_id_type", 0)
            .put("device_level", deviceLevel)
            .put("need_all_video_definition", true)
            .put("need_mp4_align", false)
            .put("use_os_player", false)
            .put("video_platform", 1024)
        val raw = payload.toString().toByteArray(Charsets.UTF_8)
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(raw) } }.toByteArray()
        val signed = PlaybackV5Signer.shared.sign(
            "https://$host$VIDEO_MODEL_PATH", appQuery(profile), gzip, now,
            PlaybackV5Signer.MEDUSA_FIELD21_74532,
        )
        return Request.Builder().url(signed.url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(gzip)
                .joinToString("") { "%02X".format(it) })
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post(gzip.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /** 官方现行 video_model/v1 的扁平响应：兼容 data.video_model 与 data[id].video_model。 */
    fun videoModel(
        id: String,
        profile: SignerProfile,
        http: OkHttpClient,
        now: Long,
        host: String = MODEL_BASE.removePrefix("https://"),
    ): JSONObject {
        val request = videoModelRequest(id, profile, now, host)
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request)) { response ->
            if (!response.isSuccessful) throw ContentHttpException(response.code)
            val responseBody = response.body ?: throw IOException("播放信息格式异常")
            // 分块响应/压缩响应的 contentLength 可能是 -1 或压缩长度，不能据此拒绝；
            // 只在实际读取时限制体积，避免正常模型被误判为“过大”。
            val limit = 2 * 1024 * 1024L
            val source = responseBody.source()
            source.request(limit + 1)
            if (source.buffer.size > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) throw EmptyPlaybackResponseException()
            val root = try { JSONObject(text) } catch (problem: JSONException) {
                throw IOException("播放信息格式异常", problem)
            }
            val code = root.optInt("Code", root.optInt("code", 0))
            if (code != 0) throw ServiceException(code)
            val data = root.optJSONObject("data") ?: throw IOException("播放信息为空")
            val model = modelOfRow(data.opt("video_model"))
                ?: modelOfRow(data.optJSONObject(id)?.opt("video_model"))
                ?: throw IOException("播放信息未包含指定剧集")
            val count = when (val list = model.opt("video_list")) {
                is JSONObject -> list.length()
                is JSONArray -> list.length()
                else -> 0
            }
            if (count == 0 && !model.has("fallback_api")) throw IOException("播放信息没有可用资源")
            model
        }
    }

    private fun modelOfRow(raw: Any?): JSONObject? = when (raw) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrNull()
        else -> null
    }

    /** V5 GET video_tab 取址请求（对齐参考项目 0.16.13 的 videoTabRequest）：
     *  本地算法的首选路由。签名输入是 MD5(空字节)——空字节只进签名，
     *  GET 本身无 body；x-ss-stub / Content-Type / Content-Encoding 必须移除，
     *  否则服务端会按 POST 形态校验并直接拒绝。只构造请求，网络发送由调用方负责。 */
    fun videoTabRequest(
        id: String,
        profile: SignerProfile,
        now: Long,
        host: String = "api5-normal-sinfonlineb.fqnovel.com",
    ): Request {
        require(id.matches(Regex("[0-9]{1,30}")))
        if (!profile.available || profile.params["cdid"].isNullOrBlank()) {
            throw SignatureException("本机匿名设备尚未就绪")
        }
        // 设备身份与 74532 画像一起进入签名；字段缺失或画像不匹配都会回 200 + 空体。
        val values = appQuery(profile).apply {
            put("video_id", id)
            put("video_id_type", "0")
            put("device_level", "1")
            put("need_all_video_definition", "1")
            put("need_mp4_align", "0")
            put("use_os_player", "0")
            put("video_platform", "1024")
            put("source", "0")
            put("from_video_id", "")
        }
        val signed = PlaybackV5Signer.shared.sign(
            "https://$host$GET_TAB_PATH", values, ByteArray(0), now,
            PlaybackV5Signer.MEDUSA_FIELD21_74532,
        )
        return Request.Builder().url(signed.url).get()
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                signed.headers.forEach { (key, value) -> header(key, value) }
                // 空字节只是签名输入：GET 没有 body，也不带 POST 的完整性校验头。
                removeHeader("x-ss-stub")
                removeHeader("Content-Type")
                removeHeader("Content-Encoding")
            }
            .build()
    }

    /** 解析单集条目：模型提取、可用性检查与过期时间补齐。
     *  [strict] 为 true（当前集）时结构不合格直接抛；false（顺带集）返回 null 由调用方跳过。 */
    private fun parseEntry(entry: JSONObject, data: JSONObject, strict: Boolean): JSONObject? {
        val model = when (val raw = entry.opt("video_model")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
        if (model == null) {
            if (strict) throw IOException("播放信息格式异常")
            return null
        }
        val count = when (val list = model.opt("video_list")) {
            is JSONObject -> list.length()
            is JSONArray -> list.length()
            else -> 0
        }
        if (count == 0 && !model.has("fallback_api")) {
            if (strict) throw IOException("播放信息没有可用资源")
            return null
        }
        for (field in listOf("expire_time", "url_expire")) {
            if (!model.has(field)) {
                val expiry = entry.optLong(field, data.optLong(field, 0L))
                if (expiry > 0L) model.put(field, expiry)
            }
        }
        return model
    }
}
