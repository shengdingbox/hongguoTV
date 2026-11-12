// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 设置页的诊断内容用竖屏表单承载，日志仍支持选择、复制和实时刷新。 */
internal object PhoneDiagnosticDialogs {
    private fun copy(activity: Activity, label: String, value: String) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("hongguotv-log", value))
        PhoneUi.toast(activity, label)
    }

    private fun log(activity: Activity, value: String) = PhoneUi.text(activity, value, 12f, PortraitStyle.secondaryText).apply {
        typeface = Typeface.MONOSPACE
        setLineSpacing(0f, 1.2f)
        setTextIsSelectable(true)
        setPadding(PhoneUi.dp(activity, 12), PhoneUi.dp(activity, 12), PhoneUi.dp(activity, 12), PhoneUi.dp(activity, 12))
        background = PortraitStyle.shape(activity, PortraitStyle.surface, radius = 12)
    }

    private fun scroll(activity: Activity, text: TextView) = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = true
        isFillViewport = false
        addView(text, LinearLayout.LayoutParams(-1, -2))
        layoutParams = LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * .34f).toInt())
    }

    private fun addButton(activity: Activity, content: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) {
        content.addView(PhoneDialog.button(activity, label, primary = primary, onClick = action),
            PhoneUi.lp().apply { topMargin = PhoneUi.dp(activity, 10) })
    }

    fun runtime(activity: Activity, summary: () -> String) {
        val content = PhoneUi.column(activity)
        val log = log(activity, RuntimeLog.snapshot(summary()))
        val scroll = scroll(activity, log)
        content.addView(scroll)
        lateinit var dialog: AlertDialog
        addButton(activity, content, "复制全部", primary = true) {
            val value = RuntimeLog.snapshot(summary())
            log.text = value
            copy(activity, "运行日志已复制，可直接粘贴反馈", value)
        }
        addButton(activity, content, "完成") { dialog.dismiss() }
        dialog = PhoneDialog.sheet(activity, "运行日志", "复现推荐或播放问题后再复制日志。仅保留本次运行，不含视频地址。", content)
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || activity.isDestroyed) return
                val follow = !scroll.canScrollVertically(1)
                log.text = RuntimeLog.snapshot(summary())
                if (follow) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                handler.postDelayed(this, 2_000L)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
        handler.postDelayed(tick, 2_000L)
    }

    fun crash(activity: Activity, report: CrashReporter.Report, history: List<CrashReporter.Report>) {
        val content = PhoneUi.column(activity)
        content.addView(scroll(activity, log(activity, report.body)))
        lateinit var dialog: AlertDialog
        addButton(activity, content, "复制本份", primary = true) { copy(activity, "本份崩溃日志已复制", report.body) }
        addButton(activity, content, "复制全部") {
            copy(activity, "已复制 ${history.size} 份崩溃日志", CrashReporter.combinedText(history))
        }
        addButton(activity, content, "完成") { dialog.dismiss() }
        dialog = PhoneDialog.sheet(activity, "崩溃详情", report.summary, content)
        dialog.show()
    }
}
