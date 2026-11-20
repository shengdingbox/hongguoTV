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
            runCatching { AppUpdateChecker().check(BuildConfig.VERSION_CODE) }
                .onSuccess { PendingAppUpdate.publish(it) }
                .onFailure { PendingAppUpdate.publish(null) }
        }
    }
}

/** 进程内的更新检查结果中转站；消费一次后清空，避免 Activity 重建重复弹框。 */
object PendingAppUpdate {
    @Volatile
    private var value: Pending?=null

    private class Pending(val update: AppUpdateInfo?, val manual: Boolean, val error: String?=null)

    fun publish(update: AppUpdateInfo?) { value=Pending(update, false) }
    fun publishManual(update: AppUpdateInfo?) { value=Pending(update, true) }
    fun publishManualError(message: String) { value=Pending(null, true, message) }

    fun consume(manual: Boolean): PendingResult? {
        val current=value ?: return null
        if(current.manual!=manual) return null
        value=null
        return PendingResult(current.update, current.manual, current.error)
    }

    fun peekManual(): Boolean = value?.manual==true
}

data class PendingResult(
    val update: AppUpdateInfo?,
    val manual: Boolean,
    val error: String?,
)
