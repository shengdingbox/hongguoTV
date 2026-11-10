// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 崩溃日志弹窗：摘要列表挑一份，或直接看完整全文。
 * 视觉沿用 [TvDialog] 卡片与胶囊按钮；日志正文等宽小字 + 常显滚动条，
 * 遥控上下键翻正文（复用 TvDialog 的 TAG_BODY 通道，见 [showDetail]）。
 *
 * 「一键复制」把全部历史日志（不只当前这份）复制进剪贴板，
 * TV 上可粘进手机遥控输入页，手机也可通过遥控会话直接粘贴。
 */
object CrashDialog {
    private fun dp(activity: Activity,value: Int)=(value*activity.resources.displayMetrics.density).toInt()

    /** 单份崩溃全文弹窗：等宽日志 + 复制本份 / 复制全部 + 关闭。
     *  records 传全部历史（默认现读磁盘），「复制全部」用。 */
    fun showDetail(activity: Activity,report: CrashReporter.Report,
                   records: List<CrashReporter.Report>?=null) {
        val history=records ?: CrashReporter.reports(activity)
        val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        content.addView(TvDialog.title(activity,"崩溃详情"))
        content.addView(TvDialog.body(activity,report.summary))
        // 等宽小字：堆栈缩进对齐才好逐行读；muted 色与弹窗风格一致
        val log=TextView(activity).apply {
            text=report.body
            textSize=11f
            typeface=Typeface.MONOSPACE
            setTextColor(TvStyle.muted)
            setLineSpacing(0f,1.15f)
        }
        val scroll=ScrollView(activity).apply {
            isVerticalScrollBarEnabled=true
            // 滚动条常显：电视遥控器没有滚轮，看不见就等于没有（与 TvDialog.scrollBody 同理）
            isScrollbarFadingEnabled=false
            scrollBarStyle=View.SCROLLBARS_INSIDE_INSET
            addView(log,ViewGroup.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(scroll,LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT))
        val buttons=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.END
            setPadding(0,dp(activity,14),0,0)
        }
        fun copyToClipboard(label: String,text: String) {
            val cm=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hongguotv-crash",text))
            Toast.makeText(activity,label,Toast.LENGTH_SHORT).show()
        }
        val close=TvDialog.capsuleButton(activity,"关闭") {}
        val copyAll=TvDialog.capsuleButton(activity,"复制全部") { copyToClipboard("已复制 ${history.size} 份崩溃日志",CrashReporter.combinedText(history)) }
        val copyOne=TvDialog.capsuleButton(activity,"复制本份") { copyToClipboard("本份崩溃日志已复制",report.body) }
        buttons.addView(copyAll,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(activity,12) })
        buttons.addView(copyOne,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(activity,12) })
        buttons.addView(close,LinearLayout.LayoutParams(-2,-2))
        content.addView(buttons)
        val card=TvDialog.card(activity,content)
        val dialog=AlertDialog.Builder(activity).setView(card).create()
        // 长堆栈把卡片高度封在六成屏：标题/按钮常在屏内，正文自己滚。
        // 弹窗收窄走 TvDialog 自然测宽：纯文字没有水平 weight，UNSPECIFIED 测量安全（见 styleWindow 注释）
        val cap=(activity.resources.displayMetrics.heightPixels*0.65f).toInt()
        TvDialog.styleWindow(activity,dialog,capPx=cap) {
            close.requestFocus()
            // 复用 TvDialog 的 TAG_BODY 通道让手机遥控上下键翻正文（实体遥控由下方
            // OnKeyListener 处理）；常量是 private，字面量与 TvDialog 保持同步
            scroll.tag="tvdialog:body"
        }
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()
        // 实体遥控上下键翻正文：与 TvDialog.bindScrollKeys 同一套拦截（那边是 private），
        // 焦点停在按钮上也能直接滚
        dialog.setOnKeyListener { _,keyCode,event ->
            if(event.action!=android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val page=(scroll.height*2/3).coerceAtLeast(1)
            when(keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { scroll.scrollBy(0,-page); true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.scrollBy(0,page); true }
                else -> false
            }
        }
    }

    /**
     * 崩溃摘要列表弹窗：选一份看全文，extra 位放「一键复制」全部历史。
     * fromStartup=true 为启动提示场景，用户点开任一份前先标记「已提示」，
     * 这批崩溃下次启动不再弹（新崩溃仍会）。
     */
    fun showList(activity: Activity,records: List<CrashReporter.Report>,fromStartup: Boolean=false) {
        if(records.isEmpty()) {
            Toast.makeText(activity,"当前没有崩溃日志",Toast.LENGTH_SHORT).show()
            return
        }
        // 启动提示先记账：用户只看摘要不点详情、直接关掉也不再重复弹
        if(fromStartup) CrashReporter.markPrompted(activity,records)
        TvDialog.choose(activity,"异常日志",records.map { it.summary },{ index ->
            showDetail(activity,records[index],records)
        },"一键复制全部",onExtra={
            val cm=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hongguotv-crash",CrashReporter.combinedText(records)))
            Toast.makeText(activity,"已复制 ${records.size} 份崩溃日志",Toast.LENGTH_SHORT).show()
        }).show()
    }
}
