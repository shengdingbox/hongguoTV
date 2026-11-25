// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.WeakHashMap

/** 不同签名口径与接口族分别标记，避免把同路径的 V5 请求和远程签名请求混用。 */
internal enum class PlaybackRouteKind {
    VIDEO_MODEL_V5, VIDEO_TAB_MULTI, VIDEO_TAB_SINGLE, MOBILE, VIDEO_TAB_V5, PLAYER, LEGACY,
}

/** 短期记住成功的接口和主机，按签名器与分集分组隔离，预取和正式播放共享。 */
internal class PlaybackRouteMemory(private val ttlMillis: Long = 10 * 60 * 1000L) {
    data class Success(val kind: PlaybackRouteKind, val endpoint: String, val at: Long)

    // 签名器被替换后允许回收对应记录，每个签名器最多保留普通短剧与合集两条记录。
    private val entries = WeakHashMap<RemoteSigner, MutableMap<String, Success>>()

    @Synchronized
    fun last(signer: RemoteSigner, group: String, now: Long): Success? {
        val groups = entries[signer] ?: return null
        val success = groups[group] ?: return null
        if (now < success.at || now - success.at >= ttlMillis) {
            groups.remove(group)
            return null
        }
        return success
    }

    @Synchronized
    fun remember(signer: RemoteSigner, group: String, kind: PlaybackRouteKind, endpoint: String, now: Long) {
        entries.getOrPut(signer) { mutableMapOf() }[group] = Success(kind, endpoint, now)
    }

    @Synchronized
    fun failed(signer: RemoteSigner, group: String, expected: Success) {
        val groups = entries[signer] ?: return
        // 旧请求的失败不能清掉另一并发请求刚刚写入的新成功记录。
        if (groups[group] === expected) groups.remove(group)
    }

    @Synchronized
    fun clear() { entries.clear() }
}
