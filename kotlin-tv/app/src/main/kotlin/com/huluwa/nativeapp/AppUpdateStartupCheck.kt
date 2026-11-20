// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import com.hongguotv.core.AppUpdateChecker
import com.hongguotv.core.AppUpdateInfo
import java.util.concurrent.Executors

/** 冷启动静默更新检查：只请求后台，不阻塞启动，也不直接展示 UI。 */
object AppUpdateStartupCheck {
    private val worker=Executors.newSingleThreadExecutor()

    @Volatile
    private var started=false

    fun startOnce(context: Context) {
        if(started) return
        started=true
        worker.execute {
            runCatching {
                AppUpdateChecker().check(
                    BuildConfig.VERSION_CODE,
                    DeviceId.get(context),
                    BuildConfig.VERSION_NAME,
                )
            }
                .onSuccess { PendingAppUpdate.publish(it) }
                .onFailure { PendingAppUpdate.publish(null) }
        }
    }
}

/**
 * 进程内的更新检查结果中转站；消费一次后清空，避免 Activity 重建重复弹框。
 *
 * 发布即弹：结果一落地就回调 listener（在哪个线程发布就在哪个线程回调，
 * 由 Activity 负责切主线程）。Activity 判断页面未就绪时不消费，结果保留在
 * 这里，等页面就绪后由 Activity 主动 drain。
 */
object PendingAppUpdate {
    @Volatile
    private var value: Pending?=null

    @Volatile
    private var listener: (()->Unit)?=null

    private class Pending(val update: AppUpdateInfo?, val manual: Boolean, val error: String?=null)

    /** Activity 在 onCreate 注册、onDestroy 注销，回调在发布线程上执行。 */
    fun setListener(callback: (()->Unit)?) { listener=callback }

    fun publish(update: AppUpdateInfo?) { value=Pending(update, false); listener?.invoke() }
    fun publishManual(update: AppUpdateInfo?) { value=Pending(update, true); listener?.invoke() }
    fun publishManualError(message: String) { value=Pending(null, true, message); listener?.invoke() }

    fun consume(manual: Boolean): PendingResult? {
        val current=value ?: return null
        if(current.manual!=manual) return null
        value=null
        return PendingResult(current.update, current.manual, current.error)
    }

    fun peekManual(): Boolean = value?.manual==true
    fun hasPending(): Boolean = value!=null
}

data class PendingResult(
    val update: AppUpdateInfo?,
    val manual: Boolean,
    val error: String?,
)
