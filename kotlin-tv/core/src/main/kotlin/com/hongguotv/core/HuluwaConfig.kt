// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.net.URI

/**
 * 葫芦娃服务的内置连接配置。
 *
 * 这是私人自用版本：服务地址与访问密钥编译进 APK，不提供界面修改。
 * 需要换服务器或换密钥时改这里重新打包，并与葫芦娃后端的 `huluwa.tv.access-key` 保持一致。
 */
object HuluwaConfig {
    /** 葫芦娃主服务地址（局域网）。 */
    const val SERVER_URL = "http://192.168.3.77:8000"

    /** 电视端访问密钥，必须与后端 `huluwa.tv.access-key` 相同。 */
    const val ACCESS_KEY = "hongguotv-private-2026"

    /** 返回规范化后的服务地址；格式不合法时抛出 [IllegalArgumentException]。 */
    fun normalizeUrl(value: String?): String {
        val raw = value?.trim().orEmpty()
        require(raw.isNotEmpty()) { "服务地址为空" }
        require(raw.length <= 300) { "服务地址过长" }
        require(raw.none(Char::isISOControl)) { "服务地址包含非法字符" }
        val uri = try { URI(raw) } catch (error: Exception) { throw IllegalArgumentException("服务地址无效", error) }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "服务地址需以 http:// 或 https:// 开头" }
        require(!uri.host.isNullOrBlank()) { "服务地址缺少主机名" }
        require(uri.userInfo == null) { "服务地址不能包含账号信息" }
        require(uri.query == null && uri.fragment == null) { "服务地址不能包含查询参数" }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://${uri.host}$port${uri.path.orEmpty().trimEnd('/')}"
    }
}
