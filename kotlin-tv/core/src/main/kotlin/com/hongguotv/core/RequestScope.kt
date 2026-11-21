// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Cancels only the work owned by one navigation/playback intent, including body reads. */
class RequestScope(timeoutMillis: Long = 0) {
    private var deadlineNanos = if(timeoutMillis>0) System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis) else Long.MAX_VALUE
    private var cancelled = false
    private val calls = mutableSetOf<Call>()

    /** 调用方据此丢弃「已被新意图取消」的结果与异常，避免把取消当成真实失败上报。 */
    val isCancelled: Boolean @Synchronized get() = cancelled

    /** 返回一个独立取消、但随父页面一起取消的子作用域。 */
    fun child(timeoutMillis: Long = 0): RequestScope {
        val next=RequestScope(timeoutMillis)
        synchronized(this) {
            next.deadlineNanos=minOf(deadlineNanos,next.deadlineNanos)
            if(cancelled) next.cancel() else children+=next
        }
        return next
    }

    private val children=mutableSetOf<RequestScope>()

    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
        children.forEach { it.cancel() }
    }

    @Synchronized fun ensureActive() {
        if(cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("请求已取消")
        if(deadlineNanos!=Long.MAX_VALUE && System.nanoTime()>=deadlineNanos) throw InterruptedIOException("请求超时，请稍后重试")
    }

    @Synchronized private fun attach(call: Call) {
        try { ensureActive() } catch(problem: IOException) { call.cancel(); throw problem }
        if(deadlineNanos!=Long.MAX_VALUE) {
            val timeout=call.timeout()
            if(!timeout.hasDeadline() || deadlineNanos<timeout.deadlineNanoTime()) timeout.deadlineNanoTime(deadlineNanos)
        }
        calls += call
    }

    @Synchronized private fun detach(call: Call) { calls -= call }

    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            ensureActive()
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<RequestScope>()
        /** 当前取址轮次还剩多少毫秒；没有外层预算时返回 null，普通内容请求保持原超时。 */
        fun remainingMillis(): Long? = current.get()?.let { scope ->
            synchronized(scope) { scope.deadlineNanos }
        }?.takeIf { it != Long.MAX_VALUE }?.let {
            ((it - System.nanoTime()) / 1_000_000L).coerceAtMost(Long.MAX_VALUE)
        }

        /** 子线程需要显式继承取消作用域，页面离开后才能停掉辅助请求。 */
        fun current(): RequestScope? = current.get()
        fun <T> execute(call: Call, consume: (Response) -> T): T {
            val owner = current.get()
            owner?.attach(call)
            try { return call.execute().use(consume) }
            finally { owner?.detach(call) }
        }
    }
}
