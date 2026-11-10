// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本次运行日志：记录整集预载缓存的调度、跳过与失败原因，供「我的」页直接复制。
 *  内存只保留最近 [MAX_LINES] 行；日志同时写入 logcat 的 HongguoTV 标签。
 *
 *  为什么弹窗里还要单独拼状态摘要：用户复现「三个进度条一直是灰的」时，日志可能一条都没有——
 *  因为调度根本没跑起来。摘要把开关、网络、当前集号、每段比例都摊开，没有日志也能直接判断卡在哪。 */
object RuntimeLog {
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized fun add(message: String) {
        val line = "${time.format(Date())} $message"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        Log.i("HongguoTV", message)
    }

    /** 复制到剪贴板的完整文本：设备信息 + [summary] 现场摘要 + 最近日志。 */
    @Synchronized fun snapshot(summary: String = ""): String = buildString {
        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("说明: 日志只保留本次运行最近 $MAX_LINES 行，请复现问题后再复制。")
        if (summary.isNotBlank()) {
            appendLine()
            appendLine("【当前状态】")
            appendLine(summary.trimEnd())
        }
        appendLine()
        appendLine("【运行日志】")
        if (lines.isEmpty()) append("暂无运行日志") else lines.forEach(::appendLine)
    }

    /** 一次性记录 App 启动信息：排查时先确认是不是换了版本、换了设备。 */
    fun appStart(context: Context) {
        add("app start ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
            "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
    }
}
