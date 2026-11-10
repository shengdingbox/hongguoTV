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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 运行日志弹窗：现场状态摘要 + 本次运行日志，一键复制后可直接反馈。
 *
 *  打开期间每 2 秒刷新一次正文：用户按「播放 → 看到三条灰线 → 回来复制」这条路径走时，
 *  弹窗早开晚开都拿得到同一份结果，不用为了看最新进度反复开关弹窗。 */
object RuntimeLogDialog {
    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun show(activity: Activity, summary: () -> String) {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TvDialog.title(activity, "运行日志"))
        content.addView(TvDialog.body(activity, "先播放并复现问题，再复制日志。仅保留本次运行，不含视频地址。"))
        val log = TextView(activity).apply {
            text = RuntimeLog.snapshot(summary())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(TvStyle.muted)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            addView(log, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(scroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        // 刷新期间用户可能正在翻看：只有本来停在末尾才自动跟到最新一行
        fun refresh() {
            val follow = !scroll.canScrollVertically(1)
            log.text = RuntimeLog.snapshot(summary())
            if (follow) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val copy = TvDialog.capsuleButton(activity, "复制全部") {
            val text = RuntimeLog.snapshot(summary())
            val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("hongguotv-runtime-log", text))
            log.text = text
            Toast.makeText(activity, "运行日志已复制，可直接粘贴反馈", Toast.LENGTH_SHORT).show()
        }
        val close = TvDialog.capsuleButton(activity, "关闭") {}
        buttons.addView(copy, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(activity, 12) })
        buttons.addView(close)
        content.addView(buttons)
        val dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        // 与异常日志一致：正文封在八成屏，标题与按钮始终在屏内
        val cap = (activity.resources.displayMetrics.heightPixels * 0.75f).toInt()
        TvDialog.styleWindow(activity, dialog, capPx = cap) {
            copy.requestFocus()
            // 复用 TvDialog 的 TAG_BODY 通道让遥控上下键翻正文（常量 private，字面量保持一致）
            scroll.tag = "tvdialog:body"
        }
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()
        // 实体遥控上下键翻正文：与 TvDialog.bindScrollKeys 同一套拦截（那边是 private）
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val page = (scroll.height * 2 / 3).coerceAtLeast(1)
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { scroll.scrollBy(0, -page); true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.scrollBy(0, page); true }
                else -> false
            }
        }
        // 2 秒一轮，弹窗关掉（Activity 已销毁或对话框 dismiss）就停
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || activity.isDestroyed) return
                refresh()
                handler.postDelayed(this, 2_000L)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        handler.postDelayed(tick, 2_000L)
    }
}
