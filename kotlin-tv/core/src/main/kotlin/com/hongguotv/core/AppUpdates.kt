// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 局域网打包服务发布的一个新版 APK。
 *
 * 版本信息来自打包服务的公开更新接口；安装包按任务 ID 换取短时下载票据后拉取，
 * 不再依赖 GitHub Releases 及其镜像。
 */
data class AppUpdate(
    val taskId: Long,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val packageName: String,
    val size: Long,
    val sha256: String,
    val notes: String,
) {
    /** 只有版本更高、且当前电视满足新版 minSdk 才算可安装的更新。 */
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk

    companion object {
        /** 打包服务默认地址，与签名服务在同一台飞牛 NAS。 */
        const val DEFAULT_SERVICE = "http://192.168.3.77:8000"

        /** 免登录更新接口前缀，对应后端 HongguoTvPublicBuildController。 */
        const val PUBLIC_PATH = "/api/public/hongguotv/android"

        const val MAX_APK_BYTES = 80L * 1024 * 1024

        /**
         * 解析 `/releases/latest` 的响应信封。
         *
         * @param expectedPackage 当前安装包的包名；服务端发布的是别的包时直接拒绝，
         *   否则会下载一个根本装不上的 APK。
         * @return 服务端没有可用发布时返回 null
         */
        fun parse(body: String, expectedPackage: String): AppUpdate? {
            val envelope = JSONObject(body)
            if (envelope.optInt("code") != 200) {
                throw IOException(envelope.optString("message").ifBlank { "更新检查失败" })
            }
            val data = envelope.optJSONObject("data") ?: throw IOException("更新信息为空")
            if (!data.optBoolean("available")) return null
            val release = data.optJSONObject("release") ?: throw IOException("更新信息格式不正确")
            val update = AppUpdate(
                taskId = release.optLong("taskId"),
                versionCode = release.optLong("versionCode"),
                versionName = release.optString("versionName"),
                minSdk = release.optInt("minSdk"),
                packageName = release.optString("packageName"),
                size = release.optLong("apkSize"),
                sha256 = release.optString("sha256").lowercase(),
                notes = release.optString("commitSubject").take(200),
            )
            require(update.taskId > 0 && update.versionCode in 1..Int.MAX_VALUE.toLong() && update.minSdk in 21..100)
            require(update.versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")))
            require(update.size in 1..MAX_APK_BYTES && update.sha256.matches(Regex("[0-9a-f]{64}")))
            require(update.packageName == expectedPackage) { "更新服务发布的包名与本机不一致" }
            return update
        }
    }
}

/** 流式校验同时挡住不完整的下载与过期的旧缓存。 */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(32768); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }
    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L; var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            if (!partial.renameTo(destination)) {
                // File.renameTo refuses to replace an existing target on Windows.
                try { java.nio.file.Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                catch(e: Exception) { throw IOException("无法保存安装包", e) }
            }
        } finally { partial.delete() }
    }
}

/**
 * 局域网打包服务的更新客户端。
 *
 * 只访问 [service] 这一个 origin：清单、下载票据和 APK 全部来自同一台主机，
 * 避免服务端错误或中间人把请求重定向到任意地址。
 */
class AppUpdateRepository(
    /** 打包服务地址，例如 `http://192.168.3.77:8000`。 */
    val service: String = AppUpdate.DEFAULT_SERVICE,
    /** 当前安装包名，用来拒绝服务端其他包的发布。 */
    private val packageName: String,
) : Closeable {
    private val base = service.trimEnd('/')
    private val origin = base.toHttpUrlOrNull() ?: throw IllegalArgumentException("更新服务地址无效")
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // 同源校验放在拦截器里，重定向后的地址同样会被拦下。
            if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port)
                throw IOException("更新请求只能访问打包服务")
            chain.proceed(chain.request())
        }.build()

    private fun serviceUrl(path: String) = base + AppUpdate.PUBLIC_PATH + path

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Cache-Control", "no-cache").build()

    /** @return 服务端最新发布；没有可更新版本时返回 null。 */
    fun latest(): AppUpdate? {
        val call = http.newCall(request(serviceUrl("/releases/latest")))
        call.timeout().timeout(20, TimeUnit.SECONDS)
        val body = try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val input = response.body?.byteStream() ?: throw IOException("响应为空")
                // 清单只有几百字节，超过上限直接判为异常响应，避免被塞进一个超大文件。
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                input.use {
                    while (true) {
                        val n = it.read(buffer); if (n < 0) break
                        if (out.size() + n > 65536) throw IOException("更新信息过大")
                        out.write(buffer, 0, n)
                    }
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: IOException) {
            throw IOException("更新服务不可用（${origin.host}）：${e.message ?: "连接失败"}", e)
        }
        return try { AppUpdate.parse(body, packageName) }
        catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException(e.message ?: "更新信息格式不正确", e) }
    }

    /**
     * 下载并校验安装包。
     *
     * 票据只有 10 分钟有效期，下载失败时重新申请一次票据再试，
     * 这样过期票据和偶发断流都能自愈，不用等用户手动点第二次。
     */
    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        var failure: IOException? = null
        repeat(2) {
            try {
                fetch(ticket(update.taskId), update, file, progress)
                return
            } catch (e: IOException) {
                failure = e
                file.delete()
            }
        }
        throw failure ?: IOException("安装包下载失败")
    }

    /** 申请一张短时下载票据，返回票据对应的相对路径。 */
    private fun ticket(taskId: Long): String {
        val body = JSONObject().put("logFile", false).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val call = http.newCall(Request.Builder().url(serviceUrl("/tasks/$taskId/download-ticket")).post(body)
            .header("User-Agent", "HongguoTV-Updater").build())
        call.timeout().timeout(20, TimeUnit.SECONDS)
        val text = call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载票据申请失败（HTTP ${response.code}）")
            response.body?.string().orEmpty()
        }
        val envelope = try { JSONObject(text) } catch (e: Exception) { throw IOException("下载票据格式不正确", e) }
        if (envelope.optInt("code") != 200) {
            throw IOException(envelope.optString("message").ifBlank { "下载票据申请失败" })
        }
        val path = envelope.optJSONObject("data")?.optString("path").orEmpty()
        // 只接受本服务的下载路径，防止服务端把请求带到其他主机或路径。
        if (!path.matches(Regex("^" + Regex.escape(AppUpdate.PUBLIC_PATH) + "/downloads/[A-Za-z0-9_-]+$")))
            throw IOException("下载地址无效")
        return path
    }

    private fun fetch(path: String, update: AppUpdate, file: File, progress: (Int) -> Unit) {
        val call = http.newCall(request(serviceUrl(path.removePrefix(AppUpdate.PUBLIC_PATH))))
        call.timeout().timeout(10, TimeUnit.MINUTES)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            if (body.contentLength() >= 0 && body.contentLength() != update.size)
                throw IOException("安装包长度不匹配")
            body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
        }
    }

    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
