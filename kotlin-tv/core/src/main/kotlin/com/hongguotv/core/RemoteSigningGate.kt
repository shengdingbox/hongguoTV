// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** 同一个远程服务共用公平队列，避免播放、下载和检测一起把串行签名服务压满。 */
internal object RemoteSigningGate {
    private val gates = ConcurrentHashMap<String, Semaphore>()

    fun <T> run(endpoint: String, action: () -> T): T {
        val gate = gates.getOrPut(endpoint) { Semaphore(1, true) }
        // 排队和网络请求合计限时；外层播放或预取预算更短时继续服从外层。
        return RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(4)) {
            var acquired = false
            try {
                while (!acquired) {
                    RequestScope.ensureActive()
                    RequestScope.current()?.ensureActive()
                    val remaining = RequestScope.remainingMillis() ?: 4_000L
                    if (remaining <= 0) throw InterruptedIOException("远程签名排队超时")
                    acquired = gate.tryAcquire(minOf(remaining, 50L), TimeUnit.MILLISECONDS)
                }
                action()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("远程签名已取消")
            } finally {
                if (acquired) gate.release()
            }
        }
    }
}
