// SPDX-License-Identifier: GPL-3.0-only.
package com.hongguotv.core

import java.io.InterruptedIOException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 签名前置条件不满足（设备注册失败、算法分支不可用等）。 */
class SignatureException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 一次签名结果：完整请求地址（参数已按设备档案补全）与需要回写的请求头。 */
data class SignedRequest(val url: String, val headers: Map<String, String>, val body: ByteArray? = null)

/** 本机持有的匿名设备档案。 */
data class SignerProfile(
    val params: Map<String, String>,
    val userAgent: String,
    /** 匿名注册响应的游客 Cookie，单独保存，绝不并入签名查询串。 */
    val cookies: Map<String, String> = emptyMap(),
) {
    val deviceId get() = params["device_id"].orEmpty()
    val installId get() = params["iid"].orEmpty()
    /** 档案声明的客户端版本；查询串与 X-Ladon 都按它签名。 */
    val versionName get() = params["version_name"].orEmpty()
    val available get() = deviceId.isNotBlank() && installId.isNotBlank()
}

/**
 * 红果请求签名器：**全部在本地完成**，不依赖任何签名服务。
 *
 * 两件事都在本机做：
 * 1. 匿名设备注册——用 [DeviceRegister] 的 ttEncrypt 协议直接向 log5 注册，
 *    拿到平台认可的 `device_id` / `iid`。平台按「注册过的身份」放行，
 *    随机生成的身份只会拿到 HTTP 200 + 0 字节空响应。
 * 2. 签名——[SignAlgorithms] 里的纯本地算法算出六个签名头。
 *
 * 设备档案持久化由调用方注入的 [store] 负责（电视端落在应用私有目录），
 * 冷启动复用同一身份，避免每次开机都重新注册。
 *
 * 身份失效（持续空响应）时调用 [recover]，重新注册一台匿名设备。
 *
 * 保留 `RemoteSigner` 这个类名是为了让上层调用点无需改动。
 */
open class RemoteSigner(
    /** 设备注册使用的传输；留空时按默认超时新建。 */
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build(),
    /** 设备档案持久化；为空时只在内存里保存（进程退出即失效）。 */
    private val store: SignerProfileStore = InMemorySignerProfileStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: java.util.Random = java.util.Random(),
) {
    companion object {
        /** 与设备档案里的取值保持一致；改动会同时影响查询串与签名指纹。 */
        const val APP_VERSION_NAME = "7.3.9.32"
        const val APP_VERSION_CODE = "73932"
        const val DEFAULT_USER_AGENT =
            "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; " +
                "Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"

        /**
         * 这些字段必须与设备档案一致，本机传了也不采纳。
         * 保留给上层：业务参数与本机身份冲突时，以档案为准。
         */
        val IDENTITY_KEYS =
            setOf(
                "device_id", "iid", "cdid", "openudid", "ac", "channel", "aid", "app_name",
                "version_code", "version_name", "manifest_version_code", "update_version_code",
                "pv_player", "device_platform", "os", "os_api", "os_version", "ssmix", "device_type",
                "device_brand", "language", "resolution", "dpi", "host_abi", "dragon_device_type",
                "user_is_login", "compliance_status", "need_personal_recommend", "player_so_load",
                "is_android_pad_screen",
            )

        /** 注册请求不带签名头，且要做 GZIP + ttEncrypt；这里给出完整的注册地址。 */
        const val REGISTER_URL =
            "https://log5-applog-lf.fqnovel.com/service/2/device_register/" +
                "?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin" +
                "&use_store_region_cookie=1"
    }

    /** 当前设备档案；首次访问时从 [store] 读取，缺失则注册一台新设备。 */
    @Volatile private var profileCache: SignerProfile? = null

    /** 本机生成的安装标识；注册时上报，平台不校验其与真实安装一致。 */
    private val cdid = UUID.randomUUID().toString()

    @Synchronized
    open fun profile(): SignerProfile {
        profileCache?.let { return it }
        store.load()?.let { saved ->
            if (saved.available) {
                profileCache = saved
                return saved
            }
        }
        return register()
    }

    /** 丢弃本地身份并重新注册一台匿名设备。 */
    @Synchronized
    open fun recover() {
        profileCache = null
        store.clear()
        runCatching { register() }
    }

    /** [recover] 的显式版本：注册失败会抛出，便于调用方决定是否继续。 */
    @Synchronized
    fun renew(): SignerProfile {
        profileCache = null
        store.clear()
        return register()
    }

    /**
     * 为 [url] 生成签名。
     *
     * [values] 里的业务参数保留本机取值；身份字段一律用设备档案覆盖，
     * 与平台校验的口径一致（签名服务版本也是这么做的）。
     * [SignedRequest.url] 才是实际要请求的地址（含补全后的查询串）。
     */
    open fun sign(url: String, values: Map<String, String>, body: ByteArray? = null): SignedRequest {
        val profile = profile()
        if (!profile.available) throw SignatureException("本机匿名设备尚未就绪")
        // 业务参数本机优先，身份字段一律以设备档案为准；档案特有的参数补齐其余。
        val merged = linkedMapOf<String, String>()
        for ((key, value) in values) if (key !in IDENTITY_KEYS) merged[key] = value
        for ((key, value) in profile.params) {
            if (value.isBlank()) continue
            if (key in merged) continue
            merged[key] = value
        }
        val (signedUrl, headers) = signQuery(url, merged, body, profile)
        return SignedRequest(signedUrl, headers, body)
    }

    /** 书城推荐复用本机签名，并按 iOS SDK 带上同一设备的游客票据。 */
    @Synchronized
    open fun signCatalog(url: String, values: Map<String, String>, body: ByteArray? = null): SignedRequest {
        val saved = profile()
        // 老版本只保存了设备 ID，没有保存 ttreq；首次进入推荐时重新注册补齐。
        val profile = if (saved.cookies["ttreq"].isNullOrBlank()) register() else saved
        if (profile.cookies["ttreq"].isNullOrBlank()) {
            throw SignatureException("匿名设备缺少游客票据，请稍后重试")
        }
        val signed = sign(url, values, body)
        val headers = signed.headers.toMutableMap()
        headers["lc"] = "101"
        headers["x-ss-dp"] = profile.params["aid"].orEmpty().ifBlank { "8662" }
        headers["x-vc-bdturing-sdk-version"] = "4.0.3.cn"
        val ticket = signed.headers["x-ss-req-ticket"].orEmpty().ifBlank { clock().toString() }
        headers["x-reading-request"] = "$ticket-${random.nextInt().toLong().let { if (it < 0) -it else it }}"
        if (!profile.cookies["ttreq"].isNullOrBlank()) {
            headers["Cookie"] = "passport_csrf_token=; passport_csrf_token_default=; " +
                "store-region=${profile.cookies["store-region"].orEmpty()}; " +
                "store-region-src=${profile.cookies["store-region-src"].orEmpty()}; " +
                "install_id=${profile.installId}; ttreq=${profile.cookies["ttreq"]}"
        }
        return signed.copy(headers = headers)
    }

    /**
     * 组装查询串与六个签名头。
     *
     * `_rticket` 从当前毫秒时间开始逐个递增，直到 hash 分支落在可直接计算的分支上。
     * 分支为 1 时 [hashF13] 走最简单的拼接路径，旧实现同样只支持这一支。
     */
    private fun signQuery(
        url: String,
        values: Map<String, String>,
        body: ByteArray?,
        profile: SignerProfile,
    ): Pair<String, Map<String, String>> {
        val now = clock()
        val hasBody = body != null
        val bodyMd5 = if (hasBody) digest("MD5", body!!) else ByteArray(16)
        var ticket = now
        var khronos = 0
        var query = ""
        // 参考实现遇到「非 1」分支就停止调整；分支 1 由 hashF13 内部另行处理。
        // 这里保持一致：绝大多数请求第一次就跳出循环，最坏情况退到 32 次后的取值。
        for (offset in 0L until 32L) {
            ticket = now + offset
            khronos = (ticket / 1000).toInt()
            val merged = linkedMapOf<String, String>()
            merged.putAll(values)
            merged["ts"] = khronos.toString()
            merged["_rticket"] = ticket.toString()
            query = encodeQuery(merged)
            val branch = hashBranch(sm3(query.toByteArray(Charsets.UTF_8)), bodyMd5, le32(khronos))
            if (branch != 1) break
        }
        val entropy = drawEntropy(now, random)
        // x-medusa 哈希的是这个固定主机的地址，不随实际请求主机变化（平台实测如此）。
        val path = url.substringAfter("://").let { it.substring(it.indexOf('/')) }
        val medusaSource = "https://reading.snssdk.com" + path + "?" + query
        val headers = linkedMapOf(
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-xs-from-web" to "0",
            "x-ss-req-ticket" to ticket.toString(),
            "x-tt-request-tag" to "t=0;n=0",
            "sdk-version" to "2",
            "passport-sdk-version" to "50561",
            "x-vc-bdturing-sdk-version" to "3.7.2.cn",
            "x-khronos" to khronos.toString(),
            "x-ladon" to be32(khronos).base64(),
            "x-argus" to le32(khronos).base64(),
            "x-gorgon" to xGorgon(query, body, hasBody, khronos, entropy.gorgonRandom),
            "x-helios" to assembleHelios(khronos, entropy.heliosRandom),
            "x-medusa" to assembleMedusa(
                medusaSource,
                body,
                hasBody,
                khronos,
                profile.deviceId,
                profile.versionName.ifBlank { APP_VERSION_NAME },
                entropy,
            ),
            "x-tt-dt" to "",
        )
        if (PlaybackSigning.isPlayerPath(path.substringBefore('?'))) {
            // Bind the envelope to the final query and the same profile used
            // above; reading profile() again could race with device recovery.
            headers.remove("x-medusa")
            headers["lc"] = "101"
            headers["x-ss-dp"] = profile.params["aid"].orEmpty().ifBlank { "8662" }
            headers.putAll(PlaybackSigning.headers(
                query, body, khronos, profile.deviceId, profile.installId,
                versionName = profile.versionName.ifBlank { APP_VERSION_NAME },
            ))
        }
        if (hasBody) {
            headers["Content-Type"] = "application/json; charset=UTF-8"
            headers["x-ss-stub"] = digest("MD5", body!!).hex().uppercase()
        }
        val base = url.substringBefore('?')
        return "$base?$query" to headers
    }

    /** 注册一台匿名设备并把档案写入 [store]。 */
    @Synchronized
    private fun register(): SignerProfile {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val device = VendorConstants.device + mapOf("openudid" to UUID.randomUUID().toString().replace("-", "").take(16))
        val body = DeviceRegister.payload(device + mapOf("cdid" to cdid), clock())
        val request = Request.Builder().url(REGISTER_URL).apply {
            DeviceRegister.headers(body, device, clock()).forEach { (key, value) -> header(key, value) }
        }.post(body.toRequestBody("application/octet-stream".toMediaType())).build()
        // 注册请求不带账号 Cookie，避免把网页会话混进来。
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        val registered: DeviceRegister.Registered = try {
            RequestScope.execute(transport.newCall(request)) { response ->
                if (!response.isSuccessful) throw SignatureException("设备注册失败（HTTP ${response.code}）")
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) throw SignatureException("设备注册返回空响应")
                DeviceRegister.parse(text, response.headers("Set-Cookie"))
            }
        } catch (problem: InterruptedIOException) {
            throw problem
        } catch (problem: SignatureException) {
            throw problem
        } catch (problem: IOException) {
            throw SignatureException("设备注册失败", problem)
        } ?: throw SignatureException("设备注册被平台拒绝，请稍后重试")

        val params = linkedMapOf<String, String>()
        params.putAll(device)
        params["device_id"] = registered.deviceId
        params["iid"] = registered.installId
        params["cdid"] = cdid
        params["version_code"] = APP_VERSION_CODE
        params["manifest_version_code"] = APP_VERSION_CODE
        params["update_version_code"] = APP_VERSION_CODE
        params["pv_player"] = APP_VERSION_CODE
        params["version_name"] = APP_VERSION_NAME
        val guestCookies = registered.cookies.filterKeys { it in setOf("ttreq", "store-region", "store-region-src", "install_id") }
        val profile = SignerProfile(params, DEFAULT_USER_AGENT, guestCookies)
        profileCache = profile
        store.save(profile)
        return profile
    }

    val configured get() = true
}

/** 本机持久化匿名设备档案。 */
interface SignerProfileStore {
    fun load(): SignerProfile?
    fun save(profile: SignerProfile)
    fun clear()
}

/** 进程内档案：进程退出即丢失，下次启动重新注册。 */
class InMemorySignerProfileStore : SignerProfileStore {
    @Volatile private var profile: SignerProfile? = null
    override fun load(): SignerProfile? = profile
    override fun save(profile: SignerProfile) { this.profile = profile }
    override fun clear() { profile = null }
}

/** 查询串编码：与平台约定一致（空格用 %20，`~` 不转义）。 */
internal fun encodeQuery(values: Map<String, String>): String =
    values.entries.joinToString("&") { (key, value) -> "${encodePart(key)}=${encodePart(value)}" }

internal fun encodePart(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("%7E", "~")
