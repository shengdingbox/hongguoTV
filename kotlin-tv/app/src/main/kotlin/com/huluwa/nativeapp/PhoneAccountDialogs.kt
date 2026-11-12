// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout

/** 手机账号操作与扫码表单采用和设置一致的底部面板及深浅配色。 */
internal object PhoneAccountDialogs {
    fun account(activity: Activity, name: String, signedIn: Boolean, cloudCount: Int, status: String,
                onLogin: () -> Unit, onRefresh: () -> Unit, onSync: () -> Unit, onLogout: () -> Unit): AlertDialog {
        val content = PhoneUi.column(activity)
        val identity = PhoneUi.row(activity).apply {
            setPadding(PhoneUi.dp(activity, 16), PhoneUi.dp(activity, 16), PhoneUi.dp(activity, 16), PhoneUi.dp(activity, 16))
            background = PortraitStyle.shape(activity, PortraitStyle.surface, radius = 14)
        }
        identity.addView(PhoneSettingsAvatar(activity), PhoneUi.lp(PhoneUi.dp(activity, 52), PhoneUi.dp(activity, 52)).apply {
            rightMargin = PhoneUi.dp(activity, 14)
        })
        val labels = PhoneUi.column(activity)
        labels.addView(PhoneUi.text(activity, if (signedIn) name else "红果账号", 20f, PortraitStyle.text).apply {
            setTypeface(null, Typeface.BOLD)
        }, PhoneUi.lp())
        labels.addView(PhoneUi.text(activity, if (signedIn) "云端观看记录 · $cloudCount 部" else status,
            13f, PortraitStyle.muted).apply { setPadding(0, PhoneUi.dp(activity, 5), 0, 0) }, PhoneUi.lp())
        identity.addView(labels, PhoneUi.lp(0, -2).apply { weight = 1f })
        content.addView(identity, PhoneUi.lp().apply { bottomMargin = PhoneUi.dp(activity, 16) })
        lateinit var dialog: AlertDialog
        fun action(label: String, primary: Boolean = false, destructive: Boolean = false, callback: () -> Unit) {
            content.addView(PhoneDialog.button(activity, label, primary, destructive) {
                dialog.dismiss(); callback()
            }, PhoneUi.lp().apply { topMargin = PhoneUi.dp(activity, 10) })
        }
        if (signedIn) {
            action("刷新云端记录", primary = true, callback = onRefresh)
            action("同步本机到云端", callback = onSync)
            action("退出红果账号", destructive = true, callback = onLogout)
        } else {
            action("扫码登录红果账号", primary = true, callback = onLogin)
        }
        content.addView(PhoneUi.text(activity,
            if (signedIn) "登录凭证加密保存在本机，不写入本地备份。" else "在红果 App 中打开「扫一扫」，扫描二维码并确认登录。",
            13f, PortraitStyle.muted).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(0, PhoneUi.dp(activity, 14), 0, 0)
        }, PhoneUi.lp())
        dialog = PhoneDialog.sheet(activity, "红果账号", if (signedIn) "账号与云端观看记录" else "登录后同步观看进度", content)
        return dialog
    }

    fun qrBody(activity: Activity, bitmap: Bitmap): LinearLayout = PhoneUi.column(activity).apply {
        addView(ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "红果账号登录二维码"
            setBackgroundColor(Color.WHITE)
            setPadding(PhoneUi.dp(activity, 12), PhoneUi.dp(activity, 12), PhoneUi.dp(activity, 12), PhoneUi.dp(activity, 12))
        }, PhoneUi.lp(PhoneUi.dp(activity, 208), PhoneUi.dp(activity, 208)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(PhoneUi.text(activity, "打开红果 App 的「扫一扫」\n扫描二维码后，在红果 App 内确认登录。", 15f, PortraitStyle.secondaryText).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setPadding(0, PhoneUi.dp(activity, 18), 0, 0)
        }, PhoneUi.lp())
        addView(PhoneUi.text(activity, "等待扫码确认…", 13f, PortraitStyle.navigationSelected).apply {
            gravity = Gravity.CENTER
            setPadding(0, PhoneUi.dp(activity, 12), 0, 0)
        }, PhoneUi.lp())
    }
}
