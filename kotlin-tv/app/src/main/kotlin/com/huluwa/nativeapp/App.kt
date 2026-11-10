// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Application
import android.content.Context

/**
 * 进程入口：在 attachBaseContext 里安装崩溃收集，早于任何 Activity 创建，
 * 连 Activity 构造期的启动闪退（历史上出过一次）也能留下一份日志。
 * 崩溃的展示不走这里——MainActivity 冷启动时检测未提示过的记录再弹（见 onCreate）。
 */
class App: Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashReporter.install(base)
        // 运行日志第一行就有版本与机型：用户反馈「日志是空的」时能立刻判断 App 是否重启过
        RuntimeLog.appStart(base)
    }
}
