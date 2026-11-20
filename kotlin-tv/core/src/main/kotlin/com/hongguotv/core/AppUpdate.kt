// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 在线更新服务的默认地址，与 hongguoTvServer 的升级检查接口对应。 */
object AppUpdate {
    const val ENDPOINT = "http://taihe.678120.cn:8008"

    fun checkUrl(versionCode: Int, deviceId: String? = null, versionName: String = ""): String = ENDPOINT.toHttpUrl().newBuilder()
        .addPathSegments("api/v1/update")
        .addQueryParameter("versionCode", versionCode.toString())
        .addQueryParameter("platform", "android")
        .addQueryParameter("source", if (deviceId == null) "manual" else "cold_start")
        .apply { deviceId?.let { addQueryParameter("install_id", it) } }
        .addQueryParameter("version_name", versionName)
        .build()
        .toString()
}

/** 服务端返回的升级信息；比较只使用 versionCode。 */
data class AppUpdateInfo(
    val currentVersionName: String,
    val currentVersionCode: Long,
    val latestVersionName: String,
    val latestVersionCode: Long,
    val apkUrl: String,
)

/** 可注入的更新传输，便于测试而不依赖真实网络。 */
fun interface AppUpdateTransport {
    fun get(url: String): String
}

class OkHttpAppUpdateTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
) : AppUpdateTransport {
    override fun get(url: String): String =
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
}

/**
 * 在线更新检查器。服务端负责判断 versionCode，客户端仍再做一次版本比较，
 * 防止错误响应把旧版本误判为新版本。网络失败抛出异常，由调用方决定静默或提示。
 */
class AppUpdateChecker(
    private val endpoint: String = AppUpdate.ENDPOINT,
    private val transport: AppUpdateTransport = OkHttpAppUpdateTransport(),
) {
    fun check(versionCode: Int, deviceId: String? = null, versionName: String = ""): AppUpdateInfo? {
        if (versionCode <= 0) return null
        val url = if (endpoint == AppUpdate.ENDPOINT) {
            AppUpdate.checkUrl(versionCode, deviceId, versionName)
        } else {
            endpoint.toHttpUrl().newBuilder()
                .addPathSegments("api/v1/update")
                .addQueryParameter("versionCode", versionCode.toString())
                .addQueryParameter("platform", "android")
                .addQueryParameter("source", if (deviceId == null) "manual" else "cold_start")
                .apply { deviceId?.let { addQueryParameter("install_id", it) } }
                .addQueryParameter("version_name", versionName)
                .build()
                .toString()
        }
        val root = JSONObject(transport.get(url))
        if (!root.optBoolean("available", false)) return null
        val current = root.optJSONObject("current") ?: return null
        val latest = root.optJSONObject("latest") ?: return null
        val latestCode = latest.optLong("version_code", 0)
        val apkUrl = latest.optString("apk_url").trim()
        if (latestCode <= versionCode || !apkUrl.startsWith("https://") && !apkUrl.startsWith("http://")) {
            return null
        }
        return AppUpdateInfo(
            currentVersionName = current.optString("version_name"),
            currentVersionCode = current.optLong("version_code", 0),
            latestVersionName = latest.optString("version_name"),
            latestVersionCode = latestCode,
            apkUrl = apkUrl,
        )
    }
}
