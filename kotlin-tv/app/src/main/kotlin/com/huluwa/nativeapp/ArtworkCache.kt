// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

/** 公共封面磁盘缓存，最多 64 MiB / 400 张，7 天未访问过期；读写都在图片工作线程执行。 */
class ArtworkCache(private val directory: File) {
    companion object {
        private val publicImageHosts = listOf("byteimg.com", "douyinpic.com", "pstatp.com", "toutiaoimg.com")
        private val signatureParameters = setOf("x-expires", "x-signature", "x-signature-v2", "x-signature-v3")

        /** CDN 签名更新仍指向同一张公共封面；保留路径、裁剪、格式等内容参数。下载使用原始地址。 */
        fun identity(url: String): String {
            val parsed = url.toHttpUrlOrNull() ?: return url
            val builder = parsed.newBuilder().fragment(null)
            if (publicImageHosts.any { parsed.host == it || parsed.host.endsWith(".$it") }) {
                parsed.queryParameterNames.filter { it.lowercase(java.util.Locale.ROOT) in signatureParameters }
                    .forEach(builder::removeAllQueryParameters)
            }
            return builder.build().toString()
        }
    }

    // 同一封面的不同尺寸和遥控代取共用下载锁；下载期间不占用整个磁盘缓存的读写锁。
    private val downloads = Array(32) { Any() }
    private fun file(key: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })

    @Synchronized fun get(url: String): ByteArray? = runCatching {
        val key = identity(url)
        val target = file(key)
        // 兼容已有按完整 URL 保存的缓存，首次读取后迁移，避免升级后重新下载。
        val source = if (target.isFile) target else file(url)
        val now = System.currentTimeMillis()
        val age = now - source.lastModified()
        if (!source.isFile) null
        else if (source.length() !in 1..4*1024*1024 || age !in 0..7*24*60*60*1000L) {
            source.delete()
            null
        } else source.readBytes().also {
            val retained = if (source != target && source.renameTo(target)) target else source
            if (age >= 60_000L) retained.setLastModified(now)
        }
    }.getOrNull()

    fun getOrLoad(url: String, load: () -> ByteArray?): ByteArray? {
        get(url)?.let { return it }
        val key = identity(url)
        return synchronized(downloads[(key.hashCode() and Int.MAX_VALUE) % downloads.size]) {
            get(url) ?: load()?.takeIf { it.isNotEmpty() && it.size <= 4*1024*1024 }?.also { put(url, it) }
        }
    }

    @Synchronized fun remove(url: String) {
        file(identity(url)).delete()
        file(url).delete()
    }

    @Synchronized fun put(url: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > 4*1024*1024) return
        runCatching {
            directory.mkdirs(); val dest = file(identity(url)); val temp = File(directory, "${dest.name}.tmp")
            temp.writeBytes(bytes); if (!temp.renameTo(dest)) temp.delete()
            val files = directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
            var size = 0L
            files.forEachIndexed { index, f -> size += f.length(); if (size > 64*1024*1024 || index >= 400) f.delete() }
        }
    }
}
