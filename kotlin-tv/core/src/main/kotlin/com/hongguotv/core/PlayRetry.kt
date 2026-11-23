// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException

/** 播放模型同族域名与临时故障分类。 */
internal object PlayRetryPlan {
    val hosts = listOf(
        "api5-normal-sinfonlineb.fqnovel.com",
        "api5-normal-hl.fqnovel.com",
        "api5-normal-lf.fqnovel.com",
    )

    fun host(attempt: Int): String = hosts[attempt % hosts.size]

    fun tag(host: String): String = when (host) {
        "api5-normal-sinfonlineb.fqnovel.com" -> "b"
        "api5-normal-hl.fqnovel.com" -> "hl"
        "api5-normal-lf.fqnovel.com" -> "lf"
        else -> host
    }

    fun temporary(problem: IOException): Boolean =
        problem is EmptyPlaybackResponseException ||
            problem is java.net.SocketTimeoutException ||
            problem is java.net.SocketException ||
            problem is java.net.UnknownHostException ||
            (problem is ContentHttpException && problem.statusCode in setOf(500, 502, 503, 504))
}

/** 播放接口非 2xx；与普通内容请求区分，供域名路由判断临时故障。 */
class ContentHttpException(val statusCode: Int) : IOException("播放接口请求失败（HTTP $statusCode）")
