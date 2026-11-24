// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 播放模型的域名健康度。
 *
 * 参考项目把“路径健康”和“设备身份”分开管理：单个域名返回空体时先冷却该域名并换下一条，
 * 只有所有域名都被判为空体时才轮换匿名身份。这里保留同样语义，但不保存媒体地址或凭据。
 */
class PlaybackRoutes {
    data class Route(val host: String)

    private data class Health(
        var success: Long = Long.MIN_VALUE,
        var blockedUntil: Long = 0,
        var empty: Boolean = false,
    )

    // V5 已统一使用多集接口，每个域名只保留一条路由及一份冷却状态。
    private val routes = PlayRetryPlan.hosts.distinct().map { Route(it) }
    private val health = routes.associateWith { Health() }

    @Synchronized
    fun plan(now: Long, speculative: Boolean): List<Route> {
        val available = routes.filter { health.getValue(it).blockedUntil <= now }
            .sortedByDescending { health.getValue(it).success }
        if (available.isNotEmpty()) {
            if (speculative) return available.take(1)
            return available
        }
        return if (speculative) emptyList() else {
            listOf(routes.minBy { health.getValue(it).blockedUntil })
        }
    }

    @Synchronized
    fun exhausted(now: Long): Boolean =
        routes.all { health.getValue(it).let { h -> h.empty && h.blockedUntil > now } }

    @Synchronized
    fun success(route: Route, now: Long) {
        health.getValue(route).apply {
            success = now
            blockedUntil = 0
            empty = false
        }
    }

    @Synchronized
    fun failed(route: Route, now: Long, empty: Boolean) {
        health.getValue(route).apply {
            blockedUntil = now + if (empty) EMPTY_COOLDOWN_MS else ERROR_COOLDOWN_MS
            this.empty = empty
        }
    }

    /** 匿名身份替换后，旧身份造成的空响应冷却不能沿用到新会话。 */
    @Synchronized
    fun reset() {
        health.values.forEach {
            it.success = Long.MIN_VALUE
            it.blockedUntil = 0
            it.empty = false
        }
    }

    private companion object {
        const val EMPTY_COOLDOWN_MS = 30_000L
        const val ERROR_COOLDOWN_MS = 5_000L
    }
}
