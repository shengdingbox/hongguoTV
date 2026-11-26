// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.RequestScope
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 串行检查收藏和预约更新，取消只影响本监视器自己的请求。 */
class FavoriteMonitor(private val library: Library, source: ContentRepository, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var epoch = 0
    private var job: Future<*>? = null
    private var scope: RequestScope? = null
    private var destroyed = false
    private val checkedAt = mutableMapOf<String, Long>()
    private val http = source.http.newBuilder().callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val repository = source.childTransport(http)
    var running = false; private set
    var status = "进入收藏可手动检查更新"; private set
    var lastChangedId: String? = null; private set
    var lastOriginalId: String? = null; private set

    fun check(force: Boolean = false) {
        if (destroyed || !library.isLoaded) return
        if (running) {
            if (!force) return
            stop()
        }
        val now = System.currentTimeMillis()
        val favorites = library.favorites()
        // 根据片单大小限制自动检查频率，避免集中请求。
        val windowHours = favorites.size / 40 + 1
        val items = favorites.filter { force || checkedAt[it.id]?.let { now - it !in 0 until windowHours * 3_600_000L } != false }
            .sortedByDescending { library.reserved(it.id) }
        lastChangedId = null; lastOriginalId = null
        if (items.isEmpty()) {
            status = if (favorites.isEmpty()) "收藏后可检查新集" else "最近 $windowHours 小时内已检查，可手动刷新"
            changed(); return
        }
        val ticket = ++epoch
        val owned = RequestScope()
        scope = owned
        running = true
        var completed = 0; var failures = 0; var consecutiveFailures = 0
        fun next() {
            if (ticket != epoch) return
            if (completed == items.size || consecutiveFailures >= 3) {
                running = false
                status = "已检查 $completed/${items.size} 部" + (if (failures > 0) " · $failures 部失败，稍后可重试" else " · 检查完成")
                changed(); return
            }
            val item = items[completed]
            val started = System.currentTimeMillis()
            status = "检查更新 ${completed + 1}/${items.size}…"
            changed()
            lastChangedId = null; lastOriginalId = null
            val reservation = library.reserved(item.id)
            job = worker.submit {
                if (ticket != epoch) return@submit
                val result = runCatching { owned.run {
                    if (reservation) repository.reservationDetail(item) else repository.detail(item.id, refreshMain = true)
                } }
                main.post {
                    if (ticket != epoch) return@post
                    job = null
                    result.onSuccess { detail ->
                        library.observeFavoriteDetail(detail, started, originalId = item.id)
                        checkedAt[item.id] = started
                        checkedAt[detail.series.id] = started
                        lastOriginalId = item.id
                        lastChangedId = detail.series.id
                        consecutiveFailures = 0
                    }.onFailure { failures++; consecutiveFailures++ }
                    completed++
                    changed()
                    // 串行请求间留出间隔，避免大片单对接口造成突发压力。
                    main.postDelayed({ next() }, 1_500L)
                }
            }
        }
        next()
    }
    fun stop() {
        epoch++
        if (running) status = "更新检查已暂停，返回目录后继续"
        running = false
        scope?.cancel(); scope = null
        job?.cancel(true); job = null
    }
    fun destroy() { destroyed = true; stop(); worker.shutdownNow() }
}
