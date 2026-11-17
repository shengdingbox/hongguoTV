// SPDX-License-Identifier: GPL-3.0-only
// GPL-3.0-only derived; see the README 来源与声明 section.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
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
    const val MODEL_PATH = "/novel/player/multi_video_model/player/v1"
    const val GROUP_SHORT = "1004"
    const val GROUP_PUGC = "1001"
    // 沿用新协议的原始 UA；查询串中的版本按手机端模板使用 74332。
    private const val PLAYBACK_UA =
        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"

    /** 取址分组：普通短剧在 1004，合集分集属于 PUGC 的 1001，分组错误会回 101000。 */
    fun model(id: String, profile: SignerProfile, http: OkHttpClient, now: Long, group: String = GROUP_SHORT): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
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
            """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"$group":["$id"]},"dr_scene":"player"}"""
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
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
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
                val entry = entries.optJSONObject(id) ?: throw IOException("播放信息未包含指定剧集")
                val model = when (val raw = entry.opt("video_model")) {
                    is JSONObject -> raw
                    is String -> JSONObject(raw)
                    else -> throw IOException("播放信息格式异常")
                }
                val list = model.opt("video_list")
                val count = when (list) {
                    is JSONObject -> list.length()
                    is JSONArray -> list.length()
                    else -> 0
                }
                if (count == 0 && !model.has("fallback_api")) throw IOException("播放信息没有可用资源")
                for (field in listOf("expire_time", "url_expire")) {
                    if (!model.has(field)) {
                        val expiry = entry.optLong(field, data.optLong(field, 0L))
                        if (expiry > 0L) model.put(field, expiry)
                    }
                }
                model
            } catch (problem: JSONException) {
                throw IOException("播放信息格式异常", problem)
            }
        }
    }
}
