// SPDX-License-Identifier: GPL-3.0-only
// GPL-3.0-only derived; see the README 来源与声明 section.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
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

/** 原生算法的 V5 取址通道；使用共用匿名身份，签名覆盖实际发送的 gzip 字节。 */
internal object MobilePlayback {
    const val MODEL_BASE = "https://api5-normal-sinfonlinea.fqnovel.com"
    // 2026-11-20 起服务端把 dr_scene 从路径段移进请求体，模板为
    // POST /novel/player/multi_video_model/:dr_scene/v1/。旧路径仍返回 HTTP 200 空体，
    // 不能据此判断路由不可用，所以本地算法直接使用新路径。
    const val MODEL_PATH = "/novel/player/multi_video_model/v1/"
    const val GROUP_SHORT = "1004"
    const val GROUP_PUGC = "1001"
    // 沿用新协议的原始 UA；查询串中的版本按手机端模板使用 74332。
    private const val PLAYBACK_UA =
        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"

    /** 取址分组：普通短剧在 1004，合集分集属于 PUGC 的 1001，分组错误会回 101000。
     *
     *  [wanted] 首项是当前要播放的集，其余是顺带预取的相邻集（调用方最多再带 2 集）：
     *  mixed_video_id_map 原生支持多 ID，一次 POST 的响应里每集各有一份模型，
     *  调用方把顺带集写入模型缓存后，切集即可零请求命中。返回映射至少包含首项；
     *  顺带集缺失或结构不合格时直接略过，不影响当前集。 */
    fun models(wanted: List<String>, profile: SignerProfile, http: OkHttpClient, now: Long, group: String = GROUP_SHORT): Map<String, JSONObject> {
        // 首项是当前集；上限 3 集与参考实现的顺带窗口一致，防止调用方传入整窗拖大请求体。
        val ids = wanted.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct().take(3)
        require(ids.isNotEmpty()) { "没有可请求的剧集 ID" }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        if (!profile.available || profile.params["cdid"].isNullOrBlank()) {
            throw SignatureException("本机匿名设备尚未就绪")
        }
        val query = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
            "iid" to profile.installId, "cdid" to profile.params.getValue("cdid"),
            "device_id" to profile.deviceId, "_rticket" to now.toString(),
        )
        val payload = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"$group":${JSONArray(ids)}},"dr_scene":"player"}"""
        )
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val body = output.toByteArray()
        val signed = PlaybackV5Signer.shared.sign("$MODEL_BASE$MODEL_PATH", query, body, now)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
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
            if (!response.isSuccessful) throw IOException("播放接口请求失败（HTTP ${response.code}）")
            val responseBody = response.body ?: throw IOException("播放信息格式异常")
            val limit = 2 * 1024 * 1024L
            if (responseBody.contentLength() > limit) throw IOException("播放信息过大")
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
