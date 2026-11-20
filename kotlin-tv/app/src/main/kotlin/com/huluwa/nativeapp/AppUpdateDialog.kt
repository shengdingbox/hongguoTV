// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
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

    private fun open(activity: Activity, update: AppUpdateInfo) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "设备上没有可用的浏览器，请手动访问：${update.apkUrl}",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun message(update: AppUpdateInfo): String = buildString {
        appendLine("发现新版本 ${update.latestVersionName}（${update.latestVersionCode}）")
        appendLine("当前版本 ${update.currentVersionName}（${update.currentVersionCode}）")
        appendLine()
        append("点击“升级”将打开 APK 下载地址；下载完成后由系统安装器完成覆盖安装。电视设备请确保浏览器和安装器可以正常使用。")
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
        lateinit var dialog: AlertDialog
        content.addView(PhoneDialog.button(activity, "升级", primary = true) {
            dialog.dismiss()
            open(activity, update)
        }, PhoneUi.lp().apply { topMargin = PhoneUi.dp(activity, 20) })
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
        upgrade.setOnClickListener { dialog.dismiss(); open(activity, update) }
        later.setOnClickListener { dialog.dismiss() }
        TvDialog.styleWindow(activity, dialog, widthDp = 420) { upgrade.requestFocus() }
        dialog.setOnDismissListener { anchor?.takeIf { it.isAttachedToWindow }?.requestFocus() }
        dialog.show()
    }
}
