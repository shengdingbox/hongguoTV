// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.AppUpdateInfo

/** 冷启动检查和设置页手动检查共用的升级弹框。 */
object AppUpdateDialog {

    fun show(activity: Activity, update: AppUpdateInfo, anchor: View? = null) {
        if (PhoneDialog.shouldUse(activity)) {
            showPhone(activity, update)
            return
        }
        showTv(activity, update, anchor)
    }

    private fun message(update: AppUpdateInfo): String = buildString {
        appendLine("发现新版本 ${update.latestVersionName}（${update.latestVersionCode}）")
        appendLine("当前版本 ${update.currentVersionName}（${update.currentVersionCode}）")
        appendLine()
        append("点击“升级”将在应用内下载 APK，校验完成后自动打开系统安装器。")
    }

    private fun showPhone(activity: Activity, update: AppUpdateInfo) {
        val content = PhoneUi.column(activity)
        content.addView(PhoneUi.text(activity, "红果 ${update.latestVersionName}", 24f, PortraitStyle.text).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        content.addView(PhoneUi.text(activity, "versionCode ${update.latestVersionCode}", 13f,
            PortraitStyle.muted).apply { setPadding(0, PhoneUi.dp(activity, 6), 0, 0) })
        content.addView(PhoneUi.text(activity, "当前版本 ${update.currentVersionName}（${update.currentVersionCode}）",
            14f, PortraitStyle.secondaryText).apply {
            setPadding(0, PhoneUi.dp(activity, 16), 0, 0)
        })
        content.addView(PhoneUi.text(activity,
            "升级会打开 APK 下载地址，下载完成后由系统安装器完成覆盖安装。",
            14f, PortraitStyle.secondaryText).apply {
            setLineSpacing(PhoneUi.dp(activity, 4).toFloat(), 1f)
            setPadding(0, PhoneUi.dp(activity, 16), 0, 0)
        })
        val progress=ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max=100; visibility=View.GONE
        }
        val status=PhoneUi.text(activity,"",13f,PortraitStyle.muted).apply { visibility=View.GONE }
        content.addView(progress, PhoneUi.lp().apply { topMargin=PhoneUi.dp(activity,16) })
        content.addView(status, PhoneUi.lp().apply { topMargin=PhoneUi.dp(activity,8) })
        lateinit var dialog: AlertDialog
        lateinit var upgrade: android.widget.Button
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
        dialog = PhoneDialog.sheet(activity, "发现新版本", content = content)
        dialog.show()
    }

    private fun showTv(activity: Activity, update: AppUpdateInfo, anchor: View?) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TvDialog.title(activity, "发现新版本"))
        content.addView(TvDialog.body(activity, message(update)))
        val progress=ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max=100; visibility=View.GONE
        }
        val status=TvDialog.body(activity,"").apply { visibility=View.GONE }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin=dp(10) })
        content.addView(status)
        val upgrade = TvDialog.capsuleButton(activity, "升级") { }
        val later = TvDialog.capsuleButton(activity, "稍后") { }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
            addView(later, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            addView(upgrade, LinearLayout.LayoutParams(-2, -2))
        }
        content.addView(buttons)
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
        TvDialog.styleWindow(activity, dialog, widthDp = 420) { upgrade.requestFocus() }
        dialog.setOnDismissListener { anchor?.takeIf { it.isAttachedToWindow }?.requestFocus() }
        dialog.show()
    }
}
