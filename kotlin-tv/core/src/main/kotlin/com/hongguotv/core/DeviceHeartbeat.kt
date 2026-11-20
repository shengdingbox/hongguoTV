// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 红果统计服务：启动上报和前台心跳。 */
object DeviceHeartbeat {
    const val ENDPOINT = "http://taihe.678120.cn:8008"
}

/** 可注入的 POST 传输，便于测试。返回 HTTP 状态码。 */
fun interface HeartbeatTransport {
    fun post(url: String, body: String): Int
}

class OkHttpHeartbeatTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build(),
) : HeartbeatTransport {
    override fun post(url: String, body: String): Int {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(request).execute().use { it.code }
    }
}

/**
 * 设备在线心跳客户端。start 只计启动；heartbeat 更新在线时间。
 * 调用方负责在前台定时发送，进入后台后停止。
 */
class DeviceHeartbeatClient(
    private val endpoint: String = DeviceHeartbeat.ENDPOINT,
    private val transport: HeartbeatTransport = OkHttpHeartbeatTransport(),
) {
    fun start(deviceId: String, versionName: String, versionCode: Int): Boolean =
        send("start", deviceId, versionName, versionCode)

    fun heartbeat(deviceId: String, versionName: String, versionCode: Int): Boolean =
        send("heartbeat", deviceId, versionName, versionCode)

    private fun send(
        action: String,
        deviceId: String,
        versionName: String,
        versionCode: Int,
    ): Boolean {
        if (!DEVICE_ID.matches(deviceId) || versionCode < 0) return false
        val body = JSONObject()
            .put("install_id", deviceId)
            .put("version_name", versionName)
            .put("version_code", versionCode)
            .put("platform", "android")
            .toString()
        return try {
            val code = transport.post("${endpoint.trimEnd('/')}/api/v1/$action", body)
            code in setOf(200, 201, 204)
        } catch (_: Throwable) {
            false
        }
    }

    private companion object {
        val DEVICE_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
    }
}
