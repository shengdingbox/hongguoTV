// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 在线更新服务地址，以及标准版和 Android 5 兼容版的更新渠道。 */
object AppUpdate {
    const val ENDPOINT = "http://taihe.678120.cn:8008"
    const val STANDARD = "standard"
    const val ANDROID5 = "android5"

    fun variantFor(sdkInt: Int? = null, compatibilityBuild: Boolean = false): String =
        if (compatibilityBuild || sdkInt != null && sdkInt in 1..23) ANDROID5 else STANDARD

    fun checkUrl(
        versionCode: Int,
        deviceId: String? = null,
        versionName: String = "",
        sdkInt: Int? = null,
        variant: String? = null,
        endpoint: String = ENDPOINT,
    ): String {
        require(variant == null || variant == STANDARD || variant == ANDROID5) { "未知更新包类型" }
        val selected = variantFor(sdkInt, variant == ANDROID5)
        return endpoint.toHttpUrl().newBuilder()
            .addPathSegments("api/v1/update")
            .addQueryParameter("versionCode", versionCode.toString())
            .addQueryParameter("platform", "android")
            .addQueryParameter("source", if (deviceId == null) "manual" else "cold_start")
            .apply { deviceId?.let { addQueryParameter("install_id", it) } }
            .addQueryParameter("version_name", versionName)
            .apply {
                sdkInt?.let { addQueryParameter("sdk_int", it.toString()) }
                if (variant != null || sdkInt != null) addQueryParameter("variant", selected)
            }
            .build().toString()
    }
}

/** 服务端返回的升级信息；比较只使用 versionCode。 */
data class AppUpdateInfo(
    val currentVersionName: String,
    val currentVersionCode: Long,
    val latestVersionName: String,
    val latestVersionCode: Long,
    val apkUrl: String,
    val apkSize: Long = 0,
    val apkSha256: String = "",
    /** 管理后台配置的更新说明，空串表示不展示。 */
    val notes: String = "",
    val variant: String = AppUpdate.STANDARD,
    val minSdk: Int = 0,
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
    fun check(
        versionCode: Int,
        deviceId: String? = null,
        versionName: String = "",
        sdkInt: Int? = null,
        variant: String? = null,
    ): AppUpdateInfo? {
        if (versionCode <= 0) return null
        val requested = AppUpdate.variantFor(sdkInt, variant == AppUpdate.ANDROID5)
        val url = AppUpdate.checkUrl(versionCode, deviceId, versionName, sdkInt, variant, endpoint)
        val root = JSONObject(transport.get(url))
        if (!root.optBoolean("available", false)) {
            val reason = root.optString("unavailable_reason", "").trim()
            kotlin.check(reason.isBlank()) { reason }
            return null
        }
        val current = root.optJSONObject("current") ?: return null
        val latest = root.optJSONObject("latest") ?: return null
        val latestCode = latest.optLong("version_code", 0)
        val apkUrl = latest.optString("apk_url").trim()
        if (latestCode <= versionCode || !apkUrl.startsWith("https://") && !apkUrl.startsWith("http://")) {
            return null
        }
        val actualVariant = latest.optString("variant", AppUpdate.STANDARD)
        val minSdk = latest.optInt("min_sdk", 0)
        val checksum = latest.optString("sha256", "")
        if (variant != null || sdkInt != null) {
            kotlin.check(latest.has("variant") && minSdk >= 21) { "更新服务尚未提供匹配的安装包信息，请稍后再试" }
        }
        kotlin.check(actualVariant == requested) { "更新包类型与当前安装版本不匹配，已阻止下载" }
        kotlin.check(sdkInt == null || minSdk <= sdkInt) { "更新包不支持当前安卓系统，已阻止下载" }
        if (requested == AppUpdate.ANDROID5) {
            kotlin.check(minSdk in 21..22 && Regex("[0-9a-fA-F]{64}").matches(checksum)) {
                "更新服务没有提供可校验的 Android 5 兼容包，请稍后再试"
            }
        }
        // 下载地址同样携带渠道，避免中间服务复用同版本的另一种安装包链接。
        val downloadUrl = if (variant != null || sdkInt != null) {
            apkUrl.toHttpUrl().newBuilder().setQueryParameter("variant", requested).build().toString()
        } else apkUrl
        return AppUpdateInfo(
            currentVersionName = current.optString("version_name"),
            currentVersionCode = current.optLong("version_code", 0),
            latestVersionName = latest.optString("version_name"),
            latestVersionCode = latestCode,
            apkUrl = downloadUrl,
            apkSize = latest.optLong("apk_size", 0),
            apkSha256 = checksum,
            notes = current.optString("notes", "").trim(),
            variant = actualVariant,
            minSdk = minSdk,
        )
    }
}
