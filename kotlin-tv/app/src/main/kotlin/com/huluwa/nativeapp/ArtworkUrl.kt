// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import com.bumptech.glide.load.model.GlideUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/**
 * 公共 CDN 的签名轮换不改变封面内容；网络仍请求新签名地址，Glide 缓存沿用稳定的资源地址。
 * 保留路径、裁剪和格式参数，仅移除已知公共图床的临时签名，避免把真正的新封面当成旧图。
 */
class ArtworkUrl(url: String) : GlideUrl(url) {
    private val identity = identity(url)

    override fun getCacheKey(): String = identity

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(identity.toByteArray(CHARSET))
    }

    override fun equals(other: Any?): Boolean = other is ArtworkUrl && identity == other.identity
    override fun hashCode(): Int = identity.hashCode()

    companion object {
        private val publicImageHosts = listOf("byteimg.com", "douyinpic.com", "pstatp.com", "toutiaoimg.com")
        private val signatureParameters = setOf("x-expires", "x-signature", "x-signature-v2", "x-signature-v3")

        /** 只处理绝对 HTTP(S) 地址；非公共图床和无法解析的地址原样保留。 */
        fun identity(url: String): String {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return url
            val parsed = url.toHttpUrlOrNull() ?: return url
            if (publicImageHosts.none { parsed.host == it || parsed.host.endsWith(".$it") }) return url
            val builder = parsed.newBuilder().fragment(null)
            parsed.queryParameterNames.filter { it.lowercase(java.util.Locale.ROOT) in signatureParameters }
                .forEach(builder::removeAllQueryParameters)
            return builder.build().toString()
        }
    }
}
