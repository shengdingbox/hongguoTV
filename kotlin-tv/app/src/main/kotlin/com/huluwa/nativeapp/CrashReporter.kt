// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志收集：进程内任何线程抛出未捕获异常时，先把完整堆栈落到应用私有目录，
 * 再交回系统原有的崩溃处理器，「应用已停止」的系统流程不受影响。
 * 下次冷启动由 MainActivity 检出未提示过的记录并弹窗（CrashDialog 展示全文），
 * 「设置 → 关于红果 → 异常日志」可随时查看与一键复制历史。
 *
 * 磁盘上只保留最近 [MAX_REPORTS] 份，超出删最旧；收集过程的任何失败都静默吞掉——
 * 崩溃记录绝不能反过来干扰崩溃本身。
 */
object CrashReporter {
    private const val PREFS="crash_reports"
    private const val KEY_PROMPTED="prompted_at"
    private const val DIR="crash"
    /** 最多保留的份数：够回溯最近几次异常，又不至于让堆栈文件无限堆积。 */
    private const val MAX_REPORTS=5
    @Volatile private var installed=false
    /** 崩溃落盘用的进程级 Context。attachBaseContext 阶段 applicationContext 可能还没就绪，取不到就先存 base。 */
    @Volatile private var app: Context?=null

    /** 一份崩溃记录：[at] 为崩溃发生时刻（毫秒），[body] 为落盘全文。 */
    class Report(val at: Long,val body: String) {
        /** 一行摘要：MM-dd HH:mm + 堆栈首行（异常类名与信息）。 */
        val summary: String by lazy {
            val time=SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(at))
            // 头部是 time/version/… 信息行，空行之后的第一行即堆栈首行
            val first=body.lineSequence().dropWhile { it.isNotBlank() }.firstOrNull { it.isNotBlank() }?.trim()
            "$time · ${first?.take(80) ?: "未知异常"}"
        }
    }

    /**
     * 安装全局未捕获异常处理器。在 Application.attachBaseContext 里最早调用，
     * 连 Activity 构造期的启动闪退也能留下一份日志；幂等，重复调用无副作用。
     */
    fun install(context: Context) {
        if(installed) return
        installed=true
        // Application 尚未注册完成时 applicationContext 会返回 null，此时的 base
        // 本身就是进程级 ContextImpl（应用进程整个生命周期都有效），直接持有
        app=context.applicationContext ?: context
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,problem ->
            // 整段兜住：收集日志的任何失败都不能挡住系统崩溃流程
            runCatching { writeReport(thread,problem) }
            previous?.uncaughtException(thread,problem)
        }
    }

    private fun writeReport(thread: Thread,problem: Throwable) {
        val context=app ?: return
        val dir=File(context.filesDir,DIR)
        if(!dir.isDirectory) dir.mkdirs()
        val now=System.currentTimeMillis()
        File(dir,"crash-$now.txt").writeText(buildString {
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date(now))}")
            appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("thread: ${thread.name}")
            appendLine()
            append(Log.getStackTraceString(problem))
        })
        // 超出上限删最旧的：文件名带毫秒时间戳，字典序即时间序
        dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.dropLast(MAX_REPORTS)?.forEach { it.delete() }
    }

    /** 读取全部崩溃记录，新的在前。会做磁盘 IO，调用方放后台线程。 */
    fun reports(context: Context): List<Report> {
        val files=File(context.applicationContext.filesDir,DIR).listFiles { f -> f.isFile } ?: return emptyList()
        return files.map { f ->
            val at=f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: f.lastModified()
            Report(at,runCatching { f.readText() }.getOrDefault(""))
        }.filter { it.body.isNotBlank() }.sortedByDescending { it.at }
    }

    /** 记录份数（设置页回填文案用，只数文件不读内容）。 */
    fun count(context: Context): Int=
        File(context.applicationContext.filesDir,DIR).listFiles { f -> f.isFile }?.size ?: 0

    /** 上次已提示到的时刻：仅比它新的崩溃才会在下次启动弹窗。 */
    fun promptedAt(context: Context): Long=
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong(KEY_PROMPTED,0L)

    /** 标记已提示到最新一份：用户看过列表后，同一批不再当启动弹窗重复出现，新崩溃仍会提示。 */
    fun markPrompted(context: Context,reports: List<Report>) {
        val newest=reports.maxOfOrNull { it.at } ?: return
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putLong(KEY_PROMPTED,maxOf(promptedAt(context),newest)).apply()
    }

    /** 一键复制的全文：全部历史按新到旧拼接，最新的在最上面。 */
    fun combinedText(reports: List<Report>): String=
        reports.joinToString("\n\n--------------------------------\n\n") { it.body }
}
