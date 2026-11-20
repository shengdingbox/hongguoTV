// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.hongguotv.core.AppUpdateInfo

/** 冷启动检查和设置页手动检查共用的升级弹框。 */
object AppUpdateDialog {

    /** 当前展示的弹框；授权设置页 / 系统安装器返回时恢复按钮状态用。 */
    private var active: Handle?=null

    /** TV 弹框里可滚动的更新说明区；有内容且超高时上下键优先滚说明。 */
    private var notesScroll: android.widget.ScrollView?=null

    /** 授权回来已拉起系统安装器时把「下载中/正在安装…」恢复成「升级」。 */
    fun notifyInstallResumed() { active?.resetInstallState() }

    private class Handle(
        val button: TextView,
        val progress: ProgressBar,
        val status: TextView,
    ) {
        fun resetInstallState() {
            button.isEnabled=true
            button.text="升级"
            progress.visibility=View.GONE
            status.visibility=View.GONE
        }
    }

    fun show(activity: Activity, update: AppUpdateInfo, anchor: View? = null) {
        if (PhoneDialog.shouldUse(activity)) {
            showPhone(activity, update)
            return
        }
        showTv(activity, update, anchor)
    }

    /**
     * 排版原则：最新版本号是第一视觉（大字号 + accent 主题色），
     * 更新说明是内容主体（正常文字色 + 独立底衬卡片），
     * 当前版本与下载提示降级为底部辅助小字。
     */

    private fun showPhone(activity: Activity, update: AppUpdateInfo) {
        val content = PhoneUi.column(activity)
        // 顶部直接大版本号（accent 色突出），「发现新版本」由 sheet 标题承担，不重复。
        content.addView(PhoneUi.text(activity, update.latestVersionName, 34f, PortraitStyle.accent).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        content.addView(PhoneUi.text(activity, "versionCode ${update.latestVersionCode}", 12f,
            PortraitStyle.muted).apply { setPadding(0, PhoneUi.dp(activity, 2), 0, 0) })
        // 主体：更新说明，纯文字排版，accent 标题引导
        if(update.notes.isNotBlank()) {
            content.addView(PhoneUi.text(activity,"更新说明",15f,PortraitStyle.accent).apply {
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, PhoneUi.dp(activity, 20), 0, 0)
            })
            content.addView(PhoneUi.text(activity,update.notes,15f,PortraitStyle.text).apply {
                setLineSpacing(PhoneUi.dp(activity,7).toFloat(),1f)
                setPadding(0,PhoneUi.dp(activity,8),0,0)
            })
        }
        val progress=ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max=100; visibility=View.GONE
        }
        val status=PhoneUi.text(activity,"",13f,PortraitStyle.muted).apply { visibility=View.GONE }
        content.addView(progress, PhoneUi.lp().apply { topMargin=PhoneUi.dp(activity,16) })
        content.addView(status, PhoneUi.lp().apply { topMargin=PhoneUi.dp(activity,8) })
        lateinit var dialog: AlertDialog
        lateinit var upgrade: TextView
        upgrade=PhoneDialog.button(activity, "升级", primary = true) {
            upgrade.isEnabled=false
            progress.visibility=View.VISIBLE
            status.visibility=View.VISIBLE
            status.text="正在下载 0%"
            AppUpdateInstaller.download(activity,update,
                onProgress={ percent,_,_-> upgrade.text="下载中 $percent%"; progress.progress=percent; status.text="正在下载 $percent%" },
                onDone={ file -> upgrade.text="正在安装…"; status.text="下载并校验完成"; AppUpdateInstaller.install(activity,file) },
                onError={ message -> upgrade.isEnabled=true; upgrade.text="重试下载"; progress.visibility=View.GONE; status.visibility=View.VISIBLE; status.text=message })
        }
        content.addView(upgrade, PhoneUi.lp().apply { topMargin = PhoneUi.dp(activity, 20) })
        content.addView(PhoneDialog.button(activity, "稍后") { dialog.dismiss() },
            PhoneUi.lp().apply { topMargin = PhoneUi.dp(activity, 10) })
        // 底部辅助信息：当前版本 + 下载提示，全部小字弱化
        content.addView(PhoneUi.text(activity,
            "当前版本 ${update.currentVersionName}（${update.currentVersionCode}）",
            12f, PortraitStyle.muted).apply { setPadding(0, PhoneUi.dp(activity, 16), 0, 0) })
        content.addView(PhoneUi.text(activity,
            "点击升级将在应用内下载 APK，校验完成后自动打开系统安装器。",
            12f, PortraitStyle.muted).apply {
            setLineSpacing(PhoneUi.dp(activity, 3).toFloat(), 1f)
            setPadding(0, PhoneUi.dp(activity, 4), 0, 0)
        })
        // sheet 标题保留「发现新版本」，版本号展示在内容区，标题与内容不重复。
        dialog = PhoneDialog.sheet(activity, "发现新版本", content = content)
        val handle=Handle(upgrade,progress,status)
        active=handle
        dialog.setOnDismissListener { if(active===handle) active=null }
        dialog.show()
    }

    private fun showTv(activity: Activity, update: AppUpdateInfo, anchor: View?) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        // 顶部：大版本号（accent 色）独占一行，标签与 versionCode 收进辅助行
        content.addView(TextView(activity).apply {
            text="发现新版本"; textSize=15f; setTextColor(TvStyle.muted)
        })
        content.addView(TextView(activity).apply {
            text=update.latestVersionName; textSize=40f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(TvStyle.accent)
            setPadding(0,dp(2),0,0)
        })
        content.addView(TextView(activity).apply {
            text="versionCode ${update.latestVersionCode}"
            textSize=12f; setTextColor(TvStyle.muted)
            setPadding(0,dp(4),0,dp(12))
        })
        // 主体：更新说明，纯文字排版，accent 标题引导，正常文字色 + 行距
        if(update.notes.isNotBlank()) {
            content.addView(TextView(activity).apply {
                text="更新说明"; textSize=15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(TvStyle.accent)
                setPadding(0,0,0,dp(8))
            })
            val notesView=TextView(activity).apply {
                text=update.notes; textSize=16f
                setTextColor(TvStyle.text)
                setLineSpacing(dp(6).toFloat(),1.2f)
            }
            val scroll=android.widget.ScrollView(activity).apply {
                isVerticalScrollBarEnabled=true
                isScrollbarFadingEnabled=false
                scrollBarStyle=View.SCROLLBARS_INSIDE_INSET
                addView(notesView)
            }
            content.addView(scroll,LinearLayout.LayoutParams(-1,-2))
            // 说明超过上限时限高滚动，不把升级按钮顶出屏幕
            val maxHeight=dp(180)
            notesView.post {
                if(notesView.height>maxHeight) {
                    scroll.layoutParams=scroll.layoutParams.apply { height=maxHeight }
                }
            }
            notesScroll=scroll
        }
        val progress=ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max=100; visibility=View.GONE
        }
        val status=TvDialog.body(activity,"").apply { visibility=View.GONE }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin=dp(12) })
        content.addView(status)
        val upgrade = TvDialog.capsuleButton(activity, "升级") { }
        val later = TvDialog.capsuleButton(activity, "稍后") { }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
            addView(later, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            addView(upgrade, LinearLayout.LayoutParams(-2, -2))
        }
        content.addView(buttons)
        // 底部辅助信息：下载提示，小字弱化
        content.addView(TextView(activity).apply {
            text="点击升级将在应用内下载 APK，校验完成后自动打开系统安装器。"
            textSize=12f; setTextColor(TvStyle.muted)
            setLineSpacing(0f,1.2f)
            setPadding(0,dp(10),0,0)
        })
        val dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        upgrade.setOnClickListener {
            upgrade.isEnabled=false
            progress.visibility=View.VISIBLE
            status.visibility=View.VISIBLE
            status.text="正在下载 0%"
            AppUpdateInstaller.download(activity,update,
                onProgress={ percent,_,_-> upgrade.text="下载中 $percent%"; progress.progress=percent; status.text="正在下载 $percent%" },
                onDone={ file -> upgrade.text="正在安装…"; status.text="下载并校验完成"; AppUpdateInstaller.install(activity,file) },
                onError={ message -> upgrade.isEnabled=true; upgrade.text="重试下载"; progress.visibility=View.GONE; status.visibility=View.VISIBLE; status.text=message })
        }
        later.setOnClickListener { dialog.dismiss() }
        TvDialog.styleWindow(activity, dialog, widthDp = 460) { upgrade.requestFocus() }
        val handle=Handle(upgrade,progress,status)
        active=handle
        // 更新说明超高时，遥控器上下键先滚说明；焦点停在升级/稍后按钮上也能翻页。
        // 只有滚到顶/底之后才把按键还给焦点导航，左右键始终正常切换按钮。
        dialog.setOnKeyListener { _, keyCode, event ->
            val scroll=notesScroll
            if(scroll==null || event.action!=android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val page=(scroll.height*2/3).coerceAtLeast(1)
            when(keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    if(scroll.canScrollVertically(-1)) { scroll.scrollBy(0,-page); true } else false
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if(scroll.canScrollVertically(1)) { scroll.scrollBy(0,page); true } else false
                }
                else -> false
            }
        }
        dialog.setOnDismissListener {
            if(active===handle) active=null
            notesScroll=null
            anchor?.takeIf { it.isAttachedToWindow }?.requestFocus()
        }
        dialog.show()
    }
}
