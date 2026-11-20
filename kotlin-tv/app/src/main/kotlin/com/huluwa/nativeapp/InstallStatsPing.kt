// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import com.hongguotv.core.DeviceHeartbeatClient
import java.util.concurrent.Executors

/**
 * 匿名安装/在线统计：进程启动上报一次，App 在前台时定时发送心跳。
 * 仅携带随机设备 ID 与应用版本号；失败静默丢弃，不阻塞界面。
 */
object InstallStatsPing {
    private val worker = Executors.newSingleThreadExecutor()
    private val heartbeat = DeviceHeartbeatClient()

    @Volatile
    private var started = false

    fun startOnce(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        worker.execute {
            try {
                val id = DeviceId.get(app)
                heartbeat.start(id, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            } catch (_: Throwable) {
            }
        }
    }

    /** 由 MainActivity 在前台调用；进入后台后由定时任务停止。 */
    fun heartbeat(context: Context) {
        val app = context.applicationContext
        worker.execute {
            try {
                heartbeat.heartbeat(DeviceId.get(app), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            } catch (_: Throwable) {
            }
        }
    }
}
