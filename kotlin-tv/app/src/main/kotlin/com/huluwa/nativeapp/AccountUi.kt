// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 红果账号的竖屏视觉层。
 *
 * 账号状态、二维码轮询和云端读写在 [AccountManager]；这里只负责把同一份状态画成
 * 一张有明确层级的账号卡、账号面板和扫码面板。所有视觉都用原生 View + 圆角形状，
 * 不引入图片资源，夜间/暖玉白两套主题共用一块珊瑚红品牌底。
 */
object AccountUi {
    private fun dp(activity: Activity, value: Int) = PhoneUi.dp(activity, value)
    private fun text(activity: Activity, value: String, size: Float, color: Int = TvStyle.text) =
        PhoneUi.text(activity, value, size, color)

    private fun white(alpha: Int) = Color.argb(alpha, 255, 255, 255)
    private fun medium(size: Float = 16f) = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun brandStart() = if (TvStyle.theme == TvStyle.Theme.NIGHT) Color.rgb(255, 126, 92) else Color.rgb(211, 91, 62)
    private fun brandEnd() = if (TvStyle.theme == TvStyle.Theme.NIGHT) Color.rgb(201, 58, 43) else Color.rgb(157, 52, 39)

    private fun gradient(activity: Activity, focused: Boolean = false) = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.LEFT_RIGHT
        colors = intArrayOf(brandStart(), brandEnd())
        cornerRadius = dp(activity, 22).toFloat()
        setStroke(dp(activity, 1), white(if (focused) 170 else 44))
    }

    private fun entryShape(activity: Activity, focused: Boolean, danger: Boolean = false) =
        TvStyle.shape(activity, if (focused) TvStyle.raised else TvStyle.surface,
            if (focused || danger) TvStyle.accent else TvStyle.outline, 15)

    private fun ring(activity: Activity, size: Int, alpha: Int) = View(activity).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(activity, 1), white(alpha))
        }
        layoutParams = FrameLayout.LayoutParams(dp(activity, size), dp(activity, size))
    }

    /** 品牌渐变底板：右上与左下各放一圈同色系细线，制造轻微的空间层次。 */
    private fun premiumFrame(activity: Activity): FrameLayout = FrameLayout(activity).apply {
        background = gradient(activity)
        clipToOutline = true
        clipChildren = true
        elevation = dp(activity, 2).toFloat()
        addView(ring(activity, 154, 34), FrameLayout.LayoutParams(dp(activity, 154), dp(activity, 154), Gravity.TOP or Gravity.END).apply {
            topMargin = -dp(activity, 54)
            rightMargin = -dp(activity, 42)
        })
        addView(ring(activity, 92, 26), FrameLayout.LayoutParams(dp(activity, 92), dp(activity, 92), Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = -dp(activity, 42)
            leftMargin = -dp(activity, 32)
        })
    }

    private fun avatar(activity: Activity, initial: String, size: Int = 54): TextView = text(activity, initial, 20f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(white(34))
            setStroke(dp(activity, 1), white(118))
        }
        layoutParams = LinearLayout.LayoutParams(dp(activity, size), dp(activity, size))
    }

    private fun pill(activity: Activity, label: String): TextView = text(activity, label, 11f, white(226)).apply {
        gravity = Gravity.CENTER
        setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4))
        background = TvStyle.shape(activity, white(28), white(72), 20)
    }

    private fun footer(activity: Activity, label: String): LinearLayout = PhoneUi.row(activity).apply {
        addView(text(activity, "●", 9f, white(155)), PhoneUi.lp(-2, -2).apply { rightMargin = dp(activity, 6) })
        addView(text(activity, label, 11.5f, white(198)), PhoneUi.lp(0, -2).apply { weight = 1f })
        addView(text(activity, "›", 17f, white(188)).apply { gravity = Gravity.CENTER_VERTICAL })
    }

    /** 「我的」页账号主卡：登录前后共用同一骨架，只替换标题、状态和行动按钮。 */
    fun profileCard(activity: Activity, account: AccountManager): View {
        val signedIn = account.signedIn
        val title = if (signedIn) account.displayName else "登录红果账号"
        val initial = if (signedIn) title.take(1).ifBlank { "果" } else "果"
        val status = account.cardStatus
        val subtitle = when {
            signedIn -> "云端 ${account.cloudCount} 部观看记录"
            account.cardStatus == "连接中" -> "正在验证账号状态…"
            else -> "登录后，观看进度可跨设备接着看"
        }
        val hint = when {
            signedIn -> "红果账号已连接 · 点击管理云端记录与同步"
            account.cardStatus == "连接中" -> "正在连接红果账号，请稍候"
            else -> "手机扫码登录 · 登录凭证加密保存在本机"
        }
        val action = if (signedIn) "管理" else "扫码登录"

        val frame = premiumFrame(activity)
        val content = PhoneUi.column(activity).apply { setPadding(dp(activity, 18), dp(activity, 15), dp(activity, 16), dp(activity, 14)) }

        val eyebrow = PhoneUi.row(activity)
        eyebrow.addView(text(activity, "红果账号", 12f, white(214)).apply { typeface = medium(12f) })
        eyebrow.addView(View(activity), PhoneUi.lp(0, -2).apply { weight = 1f })
        eyebrow.addView(pill(activity, status))
        content.addView(eyebrow, PhoneUi.lp(-1, -2))

        val identity = PhoneUi.row(activity).apply { setPadding(0, dp(activity, 12), 0, 0) }
        identity.addView(avatar(activity, initial), PhoneUi.lp(dp(activity, 54), dp(activity, 54)).apply { rightMargin = dp(activity, 12) })

        val info = PhoneUi.column(activity)
        info.addView(text(activity, title, 19f, Color.WHITE).apply {
            typeface = medium(19f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(text(activity, subtitle, 12f, white(208)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(activity, 4), 0, 0)
        })
        identity.addView(info, PhoneUi.lp(0, -2).apply { weight = 1f })

        val actionView = text(activity, action, 12f, if (signedIn) white(238) else brandEnd()).apply {
            gravity = Gravity.CENTER
            typeface = medium(12f)
            setPadding(dp(activity, 13), dp(activity, 8), dp(activity, 13), dp(activity, 8))
            background = if (signedIn) TvStyle.shape(activity, white(28), white(78), 18)
            else TvStyle.shape(activity, Color.WHITE, Color.WHITE, 18)
        }
        identity.addView(actionView, PhoneUi.lp(-2, -2).apply { leftMargin = dp(activity, 9) })
        content.addView(identity, PhoneUi.lp(-1, -2))

        content.addView(View(activity).apply { setBackgroundColor(white(38)) },
            PhoneUi.lp(-1, dp(activity, 1)).apply { topMargin = dp(activity, 13) })
        content.addView(footer(activity, hint), PhoneUi.lp(-1, -2).apply { topMargin = dp(activity, 11) })

        frame.addView(content, FrameLayout.LayoutParams(-1, -2))
        frame.id = View.generateViewId()
        frame.isFocusable = true
        frame.isFocusableInTouchMode = true
        frame.isClickable = true
        frame.contentDescription = "$title，$status，$hint"
        TouchControls.focusOnTouch(frame)
        frame.setOnFocusChangeListener { _, focused -> frame.background = gradient(activity, focused) }
        frame.setOnClickListener { account.showPhoneAccountDialog(activity) }
        return frame
    }

    private fun primaryButton(activity: Activity, label: String, onClick: () -> Unit): TextView = text(activity, label, 15f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        typeface = medium(15f)
        minHeight = dp(activity, 48)
        setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 10))
        background = TvStyle.shape(activity, TvStyle.accent, TvStyle.accent, 16)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        TouchControls.focusOnTouch(this)
        setOnFocusChangeListener { _, focused ->
            background = TvStyle.shape(activity, if (focused) TvStyle.raised else TvStyle.accent,
                TvStyle.accent, 16)
            setTextColor(if (focused) TvStyle.text else Color.WHITE)
        }
        setOnClickListener { onClick() }
    }

    /** 账号面板里的功能入口，保留整行点击和清晰的两级文字。 */
    private fun actionRow(
        activity: Activity,
        title: String,
        subtitle: String,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout = PhoneUi.row(activity).apply {
        setPadding(dp(activity, 15), dp(activity, 11), dp(activity, 13), dp(activity, 11))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = entryShape(activity, false, danger)
        TouchControls.focusOnTouch(this)

        val labels = PhoneUi.column(activity)
        labels.addView(text(activity, title, 15f, if (danger) TvStyle.accent else TvStyle.text).apply {
            typeface = medium(15f)
        })
        labels.addView(text(activity, subtitle, 11.5f, TvStyle.muted).apply {
            setPadding(0, dp(activity, 3), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(labels, PhoneUi.lp(0, -2).apply { weight = 1f })
        addView(text(activity, "›", 18f, if (danger) TvStyle.accent else TvStyle.muted))

        setOnFocusChangeListener { _, focused ->
            background = entryShape(activity, focused, danger)
        }
        setOnClickListener { onClick() }
    }

    private fun sectionLabel(activity: Activity, label: String): TextView = text(activity, label, 12f, TvStyle.muted).apply {
        typeface = medium(12f)
        setPadding(dp(activity, 2), dp(activity, 17), 0, dp(activity, 7))
    }

    private fun loginHero(activity: Activity, status: String): FrameLayout {
        val frame = premiumFrame(activity)
        val content = PhoneUi.column(activity).apply { setPadding(dp(activity, 18), dp(activity, 17), dp(activity, 18), dp(activity, 17)) }
        content.addView(text(activity, "扫码登录，接着上次看", 20f, Color.WHITE).apply { typeface = medium(20f) })
        content.addView(text(activity, "用手机红果 App 扫码，登录后可查看账号里的云端观看记录。", 12f, white(216)).apply {
            setPadding(0, dp(activity, 7), 0, 0)
            setLineSpacing(0f, 1.25f)
        })
        if (status.contains("过期")) {
            content.addView(text(activity, "登录状态已过期，请重新扫码", 11.5f, white(238)).apply {
                setPadding(0, dp(activity, 9), 0, 0)
            })
        }
        frame.addView(content, FrameLayout.LayoutParams(-1, -2))
        return frame
    }

    private fun signedInHero(activity: Activity, name: String, cloudCount: Int): FrameLayout {
        val frame = premiumFrame(activity)
        val content = PhoneUi.row(activity).apply { setPadding(dp(activity, 17), dp(activity, 16), dp(activity, 15), dp(activity, 16)) }
        content.addView(avatar(activity, name.take(1).ifBlank { "果" }, 50), PhoneUi.lp(dp(activity, 50), dp(activity, 50)).apply { rightMargin = dp(activity, 12) })
        val info = PhoneUi.column(activity)
        info.addView(text(activity, name, 19f, Color.WHITE).apply {
            typeface = medium(19f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(text(activity, "已登录 · 云端 $cloudCount 部观看记录", 12f, white(214)).apply {
            setPadding(0, dp(activity, 4), 0, 0)
        })
        content.addView(info, PhoneUi.lp(0, -2).apply { weight = 1f })
        content.addView(pill(activity, "已连接"))
        frame.addView(content, FrameLayout.LayoutParams(-1, -2))
        return frame
    }

    /**
     * 账号中心：未登录时只保留一个明确的主行动，避免旧版「点进列表再点扫码」的双层选择；
     * 已登录时把刷新、同步、退出分成独立行，主次关系比纯文字选项更清楚。
     */
    fun accountDialog(
        activity: Activity,
        name: String,
        signedIn: Boolean,
        cloudCount: Int,
        status: String,
        onLogin: () -> Unit,
        onRefresh: () -> Unit,
        onSync: () -> Unit,
        onLogout: () -> Unit,
    ): AlertDialog {
        if (PhoneDialog.shouldUse(activity)) {
            return PhoneAccountDialogs.account(activity, name, signedIn, cloudCount, status, onLogin, onRefresh, onSync, onLogout)
        }
        val content = PhoneUi.column(activity)
        lateinit var dialog: AlertDialog
        var firstFocus: View? = null

        val header = PhoneUi.row(activity)
        header.addView(text(activity, if (signedIn) name.take(1).ifBlank { "果" } else "果", 17f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = medium(17f)
            background = TvStyle.shape(activity, TvStyle.accent, radius = 22)
        }, PhoneUi.lp(dp(activity, 44), dp(activity, 44)).apply { rightMargin = dp(activity, 11) })
        val heading = PhoneUi.column(activity)
        heading.addView(text(activity, "红果账号", 21f).apply { typeface = medium(21f) })
        heading.addView(text(activity, if (signedIn) "云端观看记录与账号管理" else "登录后可查看云端观看记录", 12f, TvStyle.muted).apply {
            setPadding(0, dp(activity, 3), 0, 0)
        })
        header.addView(heading, PhoneUi.lp(0, -2).apply { weight = 1f })
        content.addView(header, PhoneUi.lp(-1, -2))

        if (signedIn) {
            content.addView(signedInHero(activity, name, cloudCount), PhoneUi.lp(-1, -2).apply { topMargin = dp(activity, 16) })
            content.addView(sectionLabel(activity, "账号操作"))
            val refresh = actionRow(activity, "刷新云端记录", "重新拉取云端观看进度") {
                dialog.dismiss(); onRefresh()
            }.also { firstFocus = it }
            content.addView(refresh, PhoneUi.lp(-1, -2).apply { bottomMargin = dp(activity, 8) })
            content.addView(actionRow(activity, "同步本机到云端", "把本机观看进度上传到红果账号") {
                dialog.dismiss(); onSync()
            }, PhoneUi.lp(-1, -2).apply { bottomMargin = dp(activity, 8) })
            content.addView(actionRow(activity, "退出红果账号", "清除本机登录凭证", danger = true) {
                dialog.dismiss(); onLogout()
            }, PhoneUi.lp(-1, -2))
            content.addView(text(activity, "登录凭证由系统加密后仅保存在本机，不写入本地备份。", 11.5f, TvStyle.muted).apply {
                setPadding(dp(activity, 2), dp(activity, 14), dp(activity, 2), 0)
                setLineSpacing(0f, 1.25f)
            })
        } else {
            content.addView(loginHero(activity, status), PhoneUi.lp(-1, -2).apply { topMargin = dp(activity, 16) })
            val login = primaryButton(activity, "扫码登录红果账号") {
                dialog.dismiss(); onLogin()
            }.also { firstFocus = it }
            content.addView(login, PhoneUi.lp(-1, dp(activity, 48)).apply { topMargin = dp(activity, 13) })
            content.addView(text(activity, "在手机红果 App 中打开「扫一扫」，确认后即可完成登录。", 11.5f, TvStyle.muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(activity, 6), dp(activity, 12), dp(activity, 6), 0)
            }, PhoneUi.lp(-1, -2))
        }

        dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        // 账号操作都先关面板再执行，避免旧面板压住二维码或确认弹窗。
        TvDialog.styleWindow(activity, dialog, widthDp = 352, capPx = (activity.resources.displayMetrics.heightPixels * 0.86f).toInt()) {
            firstFocus?.requestFocus()
        }
        return dialog
    }

    /** 二维码本体和扫码步骤；取消按钮由调用方添加，便于在轮询结束后统一收窗口。 */
    fun qrDialogBody(activity: Activity, bitmap: Bitmap): LinearLayout {
        if (PhoneDialog.shouldUse(activity)) return PhoneAccountDialogs.qrBody(activity, bitmap)
        val content = PhoneUi.column(activity)
        content.addView(text(activity, "扫码登录红果账号", 21f).apply { typeface = medium(21f) })
        content.addView(text(activity, "打开手机红果 App，扫描下方二维码并确认登录", 12f, TvStyle.muted).apply {
            setPadding(0, dp(activity, 5), 0, dp(activity, 14))
        })

        val qrBox = FrameLayout(activity).apply {
            background = TvStyle.shape(activity, Color.WHITE, radius = 20)
            setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14))
        }
        qrBox.addView(ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "红果账号登录二维码"
        }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        content.addView(qrBox, PhoneUi.lp(dp(activity, 218), dp(activity, 218)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        content.addView(stepRow(activity, "1", "打开手机红果 App，点击右上角「扫一扫」"), PhoneUi.lp(-1, -2).apply { topMargin = dp(activity, 14) })
        content.addView(stepRow(activity, "2", "扫描二维码，并在手机上确认登录"), PhoneUi.lp(-1, -2).apply { topMargin = dp(activity, 7) })

        val waiting = PhoneUi.row(activity).apply {
            setPadding(0, dp(activity, 14), 0, 0)
            addView(View(activity).apply {
                background = TvStyle.shape(activity, Color.rgb(87, 196, 127), radius = 4)
            }, PhoneUi.lp(dp(activity, 8), dp(activity, 8)).apply { rightMargin = dp(activity, 7) })
            addView(text(activity, "等待手机确认登录…", 12f, TvStyle.muted))
        }
        content.addView(waiting, PhoneUi.lp(-1, -2))
        return content
    }

    private fun stepRow(activity: Activity, index: String, label: String): LinearLayout = PhoneUi.row(activity).apply {
        addView(text(activity, index, 11f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = medium(11f)
            background = TvStyle.shape(activity, TvStyle.accent, radius = 18)
        }, PhoneUi.lp(dp(activity, 22), dp(activity, 22)).apply { rightMargin = dp(activity, 9) })
        addView(text(activity, label, 12f, TvStyle.text).apply {
            maxLines = 2
            setLineSpacing(0f, 1.18f)
        }, PhoneUi.lp(0, -2).apply { weight = 1f })
    }
}
